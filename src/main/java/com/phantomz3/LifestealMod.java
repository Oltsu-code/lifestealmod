package com.phantomz3;

import java.util.*;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.item.Item;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.world.World;
import org.joml.Math;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

public class LifestealMod implements ModInitializer {
	public static final String MOD_ID = "lifestealmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Set<UUID> pendingRevives = new HashSet<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Lifesteal mod has been initialized!");

		registerConfig();
		registerEvents();
		registerCommands();
		// registerReviveCommand();
		recipeViewRecipeCommand();
		registerOpReviveCommand();
	}

	private void registerConfig() {
		AutoConfig.register(ModConfig.class, GsonConfigSerializer::new);
	}

	private void registerEvents() {
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity joined = handler.player;
			if (pendingRevives.remove(joined.getUuid())) {
				joined.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(6.0);
				joined.setHealth(6.0f);
				joined.changeGameMode(GameMode.SURVIVAL);
				joined.sendMessage(Text.literal("You have been revived! Welcome back.").formatted(Formatting.GREEN), false);
			}
		});

		// player death and drop heart
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof PlayerEntity) {
				PlayerEntity player = (PlayerEntity) entity;
				LivingEntity attacker = (LivingEntity) source.getAttacker();
				ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

				// do not continue if player has a totem equipped
				if (player.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING 
				    || player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING) {
					return true;
				}

				// killed by player
				if (attacker instanceof PlayerEntity) {
					PlayerEntity playerAttacker = (PlayerEntity) attacker;

					// attacker has less than 'maxHeartCap' health
					if (attacker.getAttributeBaseValue(EntityAttributes.MAX_HEALTH) < config.maxHeartCap) {
						// give one heart to the attacker
						increasePlayerHealth(playerAttacker);
						playerAttacker.sendMessage(
								Text.literal("You gained an additional heart!").formatted(Formatting.GRAY),
								true);
					} else {
						ItemStack heartStack = createCustomNetherStar("Heart");
						player.dropItem(heartStack, true);
					}

				} else if (!(attacker instanceof PlayerEntity)) {
					ItemStack heartStack = createCustomNetherStar("Heart");
					player.dropItem(heartStack, true);
				}

				// decrease the player's max health
				double playerMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
				player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(playerMaxHealth - 2.0);
				player.sendMessage(Text.literal("You lost a heart!").formatted(Formatting.RED),
						true);

				// update the player max health after decreasing it
				playerMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

				if (playerMaxHealth <= 1.0) {
					ServerWorld serverWorld = (ServerWorld) player.getEntityWorld();
					((ServerPlayerEntity) player).changeGameMode(GameMode.SPECTATOR);
					player.sendMessage(
							Text.literal("You lost all your hearts! You are now in spectator mode!")
									.formatted(Formatting.GRAY),
							true);

					player.setHealth(1.0f);

					player.getInventory().dropAll();

					// ban the player
					BannedPlayerList bannedPlayerList = player.getEntityWorld().getServer().getPlayerManager().getUserBanList();
					BannedPlayerEntry banEntry = new BannedPlayerEntry(player.getPlayerConfigEntry(),
							(Date) null, "LifestealMod", (Date) null, "Losing all hearts ban");
					bannedPlayerList.add(banEntry);

					((ServerPlayerEntity) player).networkHandler.disconnect(Text.literal("You lost all your hearts!"));

					player.getEntityWorld().getServer().getPlayerManager().broadcast(
							Text.literal("→ " + player.getDisplayName().getString()
									+ " has lost all of his hearts and is eliminated!")
									.formatted(Formatting.RED),
							false);

					// return false to prevent the player from dying
					return false;
				}
			}

			return true;
		});

		ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

		if (config.disableTotem) {
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				server.getPlayerManager().getPlayerList().forEach(player -> {
					ItemStack mainHandStack = player.getMainHandStack();
					ItemStack offHandStack = player.getOffHandStack();
					Integer totemAmount = player.getInventory().count(Items.TOTEM_OF_UNDYING);

					if (mainHandStack.getItem() == Items.TOTEM_OF_UNDYING
							|| offHandStack.getItem() == Items.TOTEM_OF_UNDYING) {
						player.getMainHandStack().decrement(totemAmount);
						player.getOffHandStack().decrement(totemAmount);
					}
				});
			});
		}

		if (config.disableCPVP) {
			AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
				if (entity.getType() == EntityType.END_CRYSTAL) {
					// entity.kill();
					player.sendMessage(
							Text.literal("Crystals are disabled on this server!").formatted(Formatting.RED), true);
					return ActionResult.SUCCESS;
				}
				return ActionResult.PASS;
			});
			UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
				Boolean playerInNether = world.getRegistryKey() == World.NETHER;
				if (playerInNether
						&& world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof RespawnAnchorBlock) {
					return ActionResult.PASS;
				} else if (world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof RespawnAnchorBlock) {
					player.sendMessage(
							Text.literal("Respawns anchors are disabled on this server.").formatted(Formatting.RED),
							true);
					return ActionResult.FAIL;
				}
				return ActionResult.PASS;
			});
		}
		if (config.disableEnderPearl) {
			UseItemCallback.EVENT.register((player, world, hand) -> {
				if (player.getStackInHand(hand).getItem() == Items.ENDER_PEARL) {
					player.sendMessage(
							Text.literal("Ender pearls are disabled on this server!").formatted(Formatting.RED),
							true);
					return ActionResult.FAIL; // Prevent usage
				}
				return ActionResult.PASS; // Allow other items to proceed
			});

			// Handle custom Heart item usage
			UseItemCallback.EVENT.register((player, world, hand) -> {
				ItemStack itemStack = player.getStackInHand(hand);

				// Check if the item is a Nether Star named "Heart"
				if (itemStack.getItem() == Items.NETHER_STAR 
					&& itemStack.contains(DataComponentTypes.ITEM_NAME)
					&& itemStack.get(DataComponentTypes.ITEM_NAME).getString().equals("Heart")) {

					ModConfig modConfig = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
					double currentMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

					// Check if player is already at max heart cap
					if (currentMaxHealth < modConfig.maxHeartCap) {
						// Increase max health by 1 heart (2.0f)
						double newMaxHealth = currentMaxHealth + 2.0;
						player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(newMaxHealth);

						// Heal player by 1 heart (2.0f)
						player.heal(2.0f);

						// Consume the item if not in creative mode
						if (!player.getAbilities().creativeMode) {
							itemStack.decrement(1);
						}

						player.sendMessage(Text.literal("You consumed a heart!").formatted(Formatting.GREEN), true);
						return ActionResult.SUCCESS;
					} else {
						player.sendMessage(Text.literal("You have reached the maximum heart limit!").formatted(Formatting.RED), true);
						return ActionResult.FAIL;
					}
				}
				return ActionResult.PASS; // Pass for other items
			});
		}

		if (config.disableNetherite) {
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				server.getPlayerManager().getPlayerList().forEach(player -> {
					ItemStack mainHandStack = player.getMainHandStack();
					ItemStack offHandStack = player.getOffHandStack();

					if (mainHandStack.getItem() == Items.NETHERITE_SWORD
							|| offHandStack.getItem() == Items.NETHERITE_SWORD) {
						player.getMainHandStack().decrement(1);
						player.getOffHandStack().decrement(1);
						player.sendMessage(Text.literal("Netherite swords are disabled on this server!")
								.formatted(Formatting.RED), true);
					}

					if (mainHandStack.getItem() == Items.NETHERITE_AXE
							|| offHandStack.getItem() == Items.NETHERITE_AXE) {
						player.getMainHandStack().decrement(1);
						player.getOffHandStack().decrement(1);
						player.sendMessage(Text.literal("Netherite axes are disabled on this server!")
								.formatted(Formatting.RED), true);
					}

					if (player.getEquippedStack(EquipmentSlot.HEAD).getItem() == Items.NETHERITE_HELMET) {
						player.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
						player.sendMessage(Text.literal("Netherite armor is disabled on this server!")
								.formatted(Formatting.RED), true);
					}

					if (player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.NETHERITE_CHESTPLATE) {
						player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
						player.sendMessage(Text.literal("Netherite armor is disabled on this server!")
								.formatted(Formatting.RED), true);
					}

					if (player.getEquippedStack(EquipmentSlot.LEGS).getItem() == Items.NETHERITE_LEGGINGS) {
						player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
						player.sendMessage(Text.literal("Netherite armor is disabled on this server!")
								.formatted(Formatting.RED), true);
					}

					if (player.getEquippedStack(EquipmentSlot.FEET).getItem() == Items.NETHERITE_BOOTS) {
						player.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
						player.sendMessage(Text.literal("Netherite armor is disabled on this server!")
								.formatted(Formatting.RED), true);
					}
				});
			});
		}

		if (config.noDragonEggEnderChest) {
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				server.getPlayerManager().getPlayerList().forEach(player -> {
					EnderChestInventory enderChestInventory = player.getEnderChestInventory();

					for (int i = 0; i < enderChestInventory.size(); i++) {
						if (enderChestInventory.getStack(i).getItem() == Items.DRAGON_EGG) {
							enderChestInventory.setStack(i, ItemStack.EMPTY);
							player.giveItemStack(new ItemStack(Items.DRAGON_EGG));
							player.sendMessage(Text.literal("You cannot keep the dragon egg in your ender chest!")
									.formatted(Formatting.RED), true);
						}
					}
				});
			});
		}

		if (config.riptideCooldownEnabled) {
			UseItemCallback.EVENT.register((player, world, hand) -> {
				if (world.isClient()) {
					return ActionResult.PASS; // Client-side, pass through
				}

				ItemStack itemStack = player.getStackInHand(hand);

				if (itemStack.getItem() == Items.TRIDENT && player.isUsingRiptide()) {
					if (player.getItemCooldownManager().isCoolingDown(itemStack)) {
						return ActionResult.FAIL; // Cooldown active, prevent usage
					} else {
						player.getItemCooldownManager().set(itemStack, config.riptideCooldown);
						return ActionResult.SUCCESS; // Apply cooldown and allow usage
					}
				}

				return ActionResult.PASS; // Other items pass through
			});
		}

		// Right-click heart to gain health
		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack itemStack = player.getStackInHand(hand);

			if (itemStack.getItem() == Items.NETHER_STAR && !(itemStack.hasGlint())
					&& itemStack.getName().getString().equals("Heart")) {
				if (player instanceof ServerPlayerEntity) {
					ModConfig modConfig = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
					double playerMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
					if (playerMaxHealth >= modConfig.maxHeartCap) {
						player.sendMessage(
								Text.literal("You have reached the maximum health limit!").formatted(Formatting.RED),
								true);
						return ActionResult.FAIL; // Max health reached, prevent usage
					}

					player.getAttributeInstance(EntityAttributes.MAX_HEALTH)
							.setBaseValue(playerMaxHealth + 2.0);

					player.sendMessage(
							Text.literal("You gained an additional heart!").formatted(Formatting.GREEN), true);

					itemStack.decrement(1); // Consume the heart

					return ActionResult.SUCCESS; // Successfully used the heart
				}
			}

			return ActionResult.PASS; // Other items pass through

		});

		// Right-click revive beacon to open a revive GUI to revive a player
		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack itemStack = player.getStackInHand(hand);

			// Check for the specific "Revive Beacon"
			if (itemStack.getItem() == Items.BEACON && itemStack.hasGlint()
					&& itemStack.getName().getString().equals("Revive Beacon")) {

				if (player instanceof ServerPlayerEntity) {
					ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;

					// Create a simple 9-slot chest inventory
					SimpleInventory inventory = new SimpleInventory(27);

					BannedPlayerList deadPlayers = serverPlayer.getEntityWorld().getServer().getPlayerManager().getUserBanList();

					serverPlayer.getEntityWorld().getServer().getPlayerManager().getUserBanList().values().forEach(deadPlayer -> {
						if (deadPlayer.getReason() != null && deadPlayer.getReason().equals("Losing all hearts ban")) {
							ItemStack playerHead = new ItemStack(Items.PLAYER_HEAD);
							// not using nbt because it is not supported in 1.21 using component system
							playerHead.set(DataComponentTypes.ITEM_NAME, Text.literal(deadPlayer.getKey().name()));

							NbtCompound nbtCompound = new NbtCompound();
							nbtCompound.putString("SkullOwner", deadPlayer.getKey().name());
							NbtComponent nbtComponent = NbtComponent.of(nbtCompound);
							playerHead.set(DataComponentTypes.CUSTOM_DATA, nbtComponent);
							inventory.addStack(playerHead);
						}
					});

					// Filling the inventory with gray glass panes to fill the remaining slots
					for (int i = 0; i < inventory.size(); i++) {
						if (inventory.getStack(i).isEmpty()) {
							ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
							glassPane.set(DataComponentTypes.ITEM_NAME, Text.literal("Empty"));
							inventory.setStack(i, glassPane);
						}
					}

					// Open the chest GUI for the player
					serverPlayer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
							(syncId, playerInventory, playerEntity) -> new ReviveScreenHandler(syncId, playerInventory,
									inventory),
							Text.of("Revive Players")));

					return ActionResult.SUCCESS;
				}
			}

			return ActionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			ItemStack itemStack = player.getStackInHand(hand);

			if (itemStack.getItem() == Items.BEACON && itemStack.hasGlint()
					&& itemStack.getName().getString().equals("Revive Beacon")) {
				return ActionResult.FAIL;
			}

			return ActionResult.PASS;
		});

		// When a player gets killed respect health cap
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof PlayerEntity) {
				PlayerEntity player = (PlayerEntity) entity;
				double playerMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
				ModConfig modConfig = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

				if (playerMaxHealth > modConfig.maxHeartCap) {
					player.sendMessage(
							Text.literal("You have reached the maximum health limit!").formatted(Formatting.RED),
							true);
					player.getAttributeInstance(EntityAttributes.MAX_HEALTH)
							.setBaseValue(modConfig.maxHeartCap);
				}
			}

			return true;
		});
	}

	private ItemStack createCustomNetherStar(String name) {
		ItemStack heartStack = new ItemStack(Items.NETHER_STAR);
		heartStack.set(DataComponentTypes.ITEM_NAME, Text.literal(name));
		heartStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
//		LoreComponent loreComponent = new LoreComponent(List.of(Text.literal("Right click to consume")));
//		heartStack.set(DataComponentTypes.LORE, loreComponent);
		return heartStack;
	}

	private ItemStack createReviveBeacon(String name) {
		ItemStack reviveBeaconStack = new ItemStack(Items.BEACON);
		reviveBeaconStack.set(DataComponentTypes.ITEM_NAME, Text.literal(name));
		reviveBeaconStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
//		LoreComponent loreComponent = new LoreComponent(List.of(Text.literal("Right click to open revive GUI")));
//		reviveBeaconStack.set(DataComponentTypes.LORE, loreComponent);

		return reviveBeaconStack;
	}

	private void increasePlayerHealth(PlayerEntity player) {
		double playerMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
		player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(playerMaxHealth + 2.0);
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("lifesteal")
					.then(CommandManager.literal("withdraw")
							.then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
									.executes(context -> {
										ServerCommandSource source = context.getSource();
										ServerPlayerEntity player = source.getPlayer();
										int amount = IntegerArgumentType.getInteger(context, "amount");

										double playerMaxHealth = player
												.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

										// if he tries to withdraw all of his health, don't let him
										if (amount >= playerMaxHealth / 2.0) {
											player.sendMessage(Text.literal("Withdrawing heart failed!")
													.formatted(Formatting.RED), true);
											return 0;
										}

										if (playerMaxHealth >= amount * 2.0) {
											ItemStack heartStack = createCustomNetherStar("Heart");
											heartStack.setCount(amount);

											if (!player.getInventory().insertStack(heartStack)) {
												player.sendMessage(Text.literal("Your inventory is full! Cannot withdraw heart.")
														.formatted(Formatting.RED), true);
												return 0;
											}

											double newMaxHealth = playerMaxHealth - amount * 2.0;
											// store current health
											double playerCurrentHealth = player.getHealth();

											// sets the current health too
											player.getAttributeInstance(EntityAttributes.MAX_HEALTH)
													.setBaseValue(newMaxHealth);

											// restoring current health if it is less than the new max health and if enabled in config

											ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

											if (config.healPlayerOnWithdraw) {
											    if (playerCurrentHealth < newMaxHealth) {
											  		player.setHealth((float) newMaxHealth);
												}
											}

											player.sendMessage(
													Text.literal("You have successfully withdrawn the heart!")
															.formatted(Formatting.GREEN),
													true);
										} else {
											player.sendMessage(Text.literal("Withdrawing heart failed!")
													.formatted(Formatting.RED), true);
										}

										return amount;
									})))
						.then(CommandManager.literal("take")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("targets", EntityArgumentType.players())
										.then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
												.executes(context -> {
													Collection<ServerPlayerEntity> targets = EntityArgumentType
															.getPlayers(context, "targets");
													int amount = IntegerArgumentType.getInteger(context, "amount");
	
													for (ServerPlayerEntity target : targets) {
														double currentMaxHealth = target
																.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
														double newMaxHealth = Math.max(2.0,
																currentMaxHealth - amount * 2.0); // 1 heart = 2.0 health
	
														target.getAttributeInstance(EntityAttributes.MAX_HEALTH)
																.setBaseValue(newMaxHealth);
														target.setHealth((float) newMaxHealth); // Set player's health to
																								// the new max health
													}
	
													return targets.size();
												}))))
						.then(CommandManager.literal("set")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("targets", EntityArgumentType.players())
										.then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
												.executes(context -> {
													Collection<ServerPlayerEntity> targets = EntityArgumentType
															.getPlayers(context, "targets");
													int amount = IntegerArgumentType.getInteger(context, "amount");
	
													for (ServerPlayerEntity target : targets) {
														double currentMaxHealth = target
																.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
														double newMaxHealth = Math.max(2.0, amount * 2.0); // 1 heart = 2.0 health
	
														target.getAttributeInstance(EntityAttributes.MAX_HEALTH)
																.setBaseValue(newMaxHealth);
														target.setHealth((float) newMaxHealth); // Set player's health to
																								// the new max health
													}
	
													return targets.size();
												}))))
					.then(CommandManager.literal("give")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("targets", EntityArgumentType.players())
									.then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
											.executes(context -> {
												Collection<ServerPlayerEntity> targets = EntityArgumentType
														.getPlayers(context, "targets");
												int amount = IntegerArgumentType.getInteger(context, "amount");

												for (ServerPlayerEntity target : targets) {
													double currentMaxHealth = target
															.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
													double newMaxHealth = Math.max(2.0,
															currentMaxHealth + amount * 2.0); // 1 heart = 2.0 health

													target.getAttributeInstance(EntityAttributes.MAX_HEALTH)
															.setBaseValue(newMaxHealth);
													target.setHealth((float) newMaxHealth); // Set player's health to
																							// the new max health
												}

												return targets.size();
											})))));
		});
	}

	private void registerReviveCommand() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("revive")
					.then(CommandManager.argument("player", EntityArgumentType.player())
							.executes(context -> {
								return executeRevive(context.getSource(),
										EntityArgumentType.getPlayer(context, "player").getPlayerConfigEntry());
							})));

			// Registering /lifesteal revive [target] to do the same as /revive
			dispatcher.register(CommandManager.literal("lifesteal")
					.then(CommandManager.literal("revive")
							.then(CommandManager.argument("player", EntityArgumentType.player())
									.executes(context -> {
										return executeRevive(context.getSource(),
												EntityArgumentType.getPlayer(context, "player").getPlayerConfigEntry());
									}))));
		});
	}

	// Register the new op revive command
	private void registerOpReviveCommand() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("lifesteal")
					.then(CommandManager.literal("oprevive")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("player", StringArgumentType.string())
									.suggests((context, builder) -> {
										MinecraftServer server = context.getSource().getServer();
										for (BannedPlayerEntry entry : server.getPlayerManager().getUserBanList().values()) {
											if (entry.getReason() != null && entry.getReason().equals("Losing all hearts ban")) {
												builder.suggest(entry.getKey().name());
											}
										}
										return builder.buildFuture();
									})
									.executes(context -> {
										String playerName = StringArgumentType.getString(context, "player");
										MinecraftServer server = context.getSource().getServer();

										PlayerConfigEntry targetEntry = null;
										for (BannedPlayerEntry entry : server.getPlayerManager().getUserBanList().values()) {
											if (entry.getKey().name().equalsIgnoreCase(playerName)
													&& entry.getReason() != null
													&& entry.getReason().equals("Losing all hearts ban")) {
												targetEntry = entry.getKey();
												break;
											}
										}

										if (targetEntry == null) {
											context.getSource().sendError(Text.literal(playerName + " is not a dead player."));
											return 0;
										}

										return executeRevive(context.getSource(), targetEntry);
									}))
					));
		});
	}

	private int executeRevive(ServerCommandSource source, PlayerConfigEntry targetEntry) {
		BannedPlayerList bannedPlayerList = source.getServer().getPlayerManager().getUserBanList();

		if (!bannedPlayerList.contains(targetEntry)) {
			source.sendError(Text.literal("This player is not banned."));
			return 0;
		}

		bannedPlayerList.remove(targetEntry);
		pendingRevives.add(targetEntry.id());

		source.sendFeedback(() -> Text.literal(targetEntry.name() + " has been revived!").formatted(Formatting.GREEN), true);
		return 1;
	}

	public void recipeViewRecipeCommand() {
		// Registering /lifesteal viewRecipe to open the RecipeScreenHandler
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("lifesteal")
					.then(CommandManager.literal("viewRecipes")
							.executes(context -> {
								ServerPlayerEntity player = context.getSource().getPlayer();
								openRecipeGUI(player);
								return 1;
							})));
		});
	}

	public void openRecipeGUI(ServerPlayerEntity player) {
		if (player instanceof ServerPlayerEntity) {
			ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;

			// Create a 45-slot chest inventory
			SimpleInventory inventory = new SimpleInventory(45);

			// Define items for Recipe 1 and Recipe 2
			ItemStack rawGoldBlock = new ItemStack(Items.RAW_GOLD_BLOCK);
			rawGoldBlock.set(DataComponentTypes.ITEM_NAME, Text.literal("Raw Gold Block"));

			ItemStack netheriteIngot = new ItemStack(Items.NETHERITE_INGOT);
			netheriteIngot.set(DataComponentTypes.ITEM_NAME, Text.literal("Netherite Ingot"));

			ItemStack netherStar = new ItemStack(Items.NETHER_STAR);
			netherStar.set(DataComponentTypes.ITEM_NAME, Text.literal("Nether Star"));

			ItemStack beacon = new ItemStack(Items.BEACON);
			beacon.set(DataComponentTypes.ITEM_NAME, Text.literal("Beacon"));

			ItemStack reviveBeacon = createReviveBeacon("Revive Beacon");

			// Custom item for the result of each recipe
			ItemStack heart = createCustomNetherStar("Heart");

			// Recipe 1 pattern
			String[] recipePattern1 = new String[]{"RNR", "NGN", "RNR"};

			// Recipe 2 pattern
			String[] recipePattern2 = new String[]{"NGN", "GBG", "NGN"};

			// Define the starting row and column for Recipe 1 (left side)
			int startRow1 = 1;
			int startCol1 = 0;

			// Define the starting row and column for Recipe 2 (right side)
			int startRow2 = 1;
			int startCol2 = 5;

			// Place Recipe 1 items in the left 3x3 grid
			for (int i = 0; i < recipePattern1.length; i++) {
				String row = recipePattern1[i];
				for (int j = 0; j < row.length(); j++) {
					char c = row.charAt(j);
					ItemStack itemStack = ItemStack.EMPTY;

					// Assign items for Recipe 1
					switch (c) {
						case 'R':
							itemStack = rawGoldBlock;
							break;
						case 'N':
							itemStack = netheriteIngot;
							break;
						case 'G':
							itemStack = netherStar;
							break;
					}

					// Calculate the correct slot for Recipe 1 items
					int slotIndex = (startRow1 + i) * 9 + (startCol1 + j);
					inventory.setStack(slotIndex, itemStack);
				}
			}

			// Place Recipe 2 items in the right 3x3 grid
			for (int i = 0; i < recipePattern2.length; i++) {
				String row = recipePattern2[i];
				for (int j = 0; j < row.length(); j++) {
					char c = row.charAt(j);
					ItemStack itemStack = ItemStack.EMPTY;

					// Assign items for Recipe 2
					switch (c) {
						case 'B':
							itemStack = beacon;
							break;
						case 'N':
							itemStack = netheriteIngot;
							break;
						case 'G':
							itemStack = netherStar;
							break;
					}

					// Calculate the correct slot for Recipe 2 items
					int slotIndex = (startRow2 + i) * 9 + (startCol2 + j);
					inventory.setStack(slotIndex, itemStack);
				}
			}

			// Place the result of Recipe 1 (Heart) at a separate slot
			int resultSlot1 = (startRow1 + 1) * 9 + (startCol1 + 3); // Row 2, Column 4
			inventory.setStack(resultSlot1, heart);

			// Place the result of Recipe 2 (Super Heart) at a separate slot
			int resultSlot2 = (startRow2 + 1) * 9 + (startCol2 + 3); // Row 2, Column 8
			inventory.setStack(resultSlot2, reviveBeacon);

			// Fill the remaining slots with gray glass panes to indicate empty spaces
			for (int i = 0; i < inventory.size(); i++) {
				if (inventory.getStack(i).isEmpty()) {
					ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE); // White to gray (looks nicer)
					glassPane.set(DataComponentTypes.ITEM_NAME, Text.literal("Empty"));
					inventory.setStack(i, glassPane);
				}
			}

			// Open the chest GUI for the player
			serverPlayer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
					(syncId, playerInventory, playerEntity) -> new RecipeScreenHandler(syncId, playerInventory, inventory),
					Text.of("View Recipes")
			));
		}
	}
	
		
}
