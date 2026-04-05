package com.phantomz3.event;

import com.phantomz3.LifestealMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;


import com.phantomz3.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;

public class DisablingFeatureManager {

	public void register() {
		ModConfig config = getConfig();

		if (config.disableTotem) {
			registerTotemDisable();
		}

		if (config.disableCPVP) {
			registerCrystalPvPDisable();
		}

		if (config.disableNetherite) {
			registerNetheriteDisable();
		}

		if (config.noDragonEggEnderChest) {
			registerDragonEggEnderChestDisable();
		}

		if (config.disableEnderPearl) {
			registerDisableEnderPearl();
		}
	}

	private void registerTotemDisable() {
		LifestealMod.LOGGER.info("Totem of Undying is now disabled on the server!");
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			server.getPlayerManager().getPlayerList().forEach(player -> {
				ItemStack main = player.getMainHandStack();
				ItemStack off = player.getOffHandStack();

				if (main.getItem() == Items.TOTEM_OF_UNDYING || off.getItem() == Items.TOTEM_OF_UNDYING) {
					int count = player.getInventory().count(Items.TOTEM_OF_UNDYING);
					main.decrement(count);
					off.decrement(count);
				}
			});
		});
	}

	private void registerCrystalPvPDisable() {
		LifestealMod.LOGGER.info("Crystal PvP is now disabled on the server!");
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (entity.getType() == EntityType.END_CRYSTAL) {
				player.sendMessage(
						Text.literal("Crystals are disabled on this server!").formatted(Formatting.RED), true);
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof RespawnAnchorBlock) {
				boolean inNether = world.getRegistryKey() == World.NETHER;
				if (!inNether) {
					player.sendMessage(
							Text.literal("Respawn anchors are disabled on this server.").formatted(Formatting.RED),
							true);
					return ActionResult.FAIL;
				}
			}
			return ActionResult.PASS;
		});
	}

	private void registerNetheriteDisable() {
		LifestealMod.LOGGER.info("Netherite is now disabled on the server!");
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			server.getPlayerManager().getPlayerList().forEach(this::removeNetheriteItems);
		});
	}

	private void removeNetheriteItems(ServerPlayerEntity player) {
		ItemStack main = player.getMainHandStack();
		ItemStack off = player.getOffHandStack();

		if (isNetheriteTool(main) || isNetheriteTool(off)) {
			main.decrement(1);
			off.decrement(1);
			player.sendMessage(
					Text.literal("Netherite tools are disabled on this server!").formatted(Formatting.RED), true);
		}

		removeNetheriteArmor(player, EquipmentSlot.HEAD, Items.NETHERITE_HELMET);
		removeNetheriteArmor(player, EquipmentSlot.CHEST, Items.NETHERITE_CHESTPLATE);
		removeNetheriteArmor(player, EquipmentSlot.LEGS, Items.NETHERITE_LEGGINGS);
		removeNetheriteArmor(player, EquipmentSlot.FEET, Items.NETHERITE_BOOTS);
	}

	private boolean isNetheriteTool(ItemStack stack) {
		return stack.getItem() == Items.NETHERITE_SWORD || stack.getItem() == Items.NETHERITE_AXE;
	}

	private void removeNetheriteArmor(ServerPlayerEntity player, EquipmentSlot slot, net.minecraft.item.Item item) {
		if (player.getEquippedStack(slot).getItem() == item) {
			player.equipStack(slot, ItemStack.EMPTY);
			player.sendMessage(
					Text.literal("Netherite armor is disabled on this server!").formatted(Formatting.RED), true);
		}
	}

	private void registerDragonEggEnderChestDisable() {
		LifestealMod.LOGGER.info("Storing dragon eggs in ender chests is now disabled on the server!");
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			server.getPlayerManager().getPlayerList().forEach(player -> {
				EnderChestInventory enderChest = player.getEnderChestInventory();

				for (int i = 0; i < enderChest.size(); i++) {
					if (enderChest.getStack(i).getItem() == Items.DRAGON_EGG) {
						enderChest.setStack(i, ItemStack.EMPTY);
						player.giveItemStack(new ItemStack(Items.DRAGON_EGG));
						player.sendMessage(
								Text.literal("You cannot keep the dragon egg in your ender chest!").formatted(Formatting.RED), true);
					}
				}
			});
		});
	}

	private void registerDisableEnderPearl() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (player.getStackInHand(hand).getItem() == Items.ENDER_PEARL) {
				player.sendMessage(
						Text.literal("Ender pearls are disabled on this server!").formatted(Formatting.RED),
						true);
				return ActionResult.FAIL;
			}
			return ActionResult.PASS;
		});
	}

	private ModConfig getConfig() {
		return AutoConfig.getConfigHolder(ModConfig.class).getConfig();
	}
}

