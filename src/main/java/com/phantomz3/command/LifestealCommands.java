package com.phantomz3.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import com.phantomz3.LifestealMod;
import com.phantomz3.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.util.UserCache;
import net.minecraft.world.GameMode;

import java.util.Collection;
import java.util.UUID;

import static com.phantomz3.LifestealMod.*;

public class LifestealCommands {

	private final LifestealMod mod;

	public LifestealCommands(LifestealMod mod) {
		this.mod = mod;
	}

	public void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("lifesteal")
					.then(CommandManager.literal("withdraw")
							.then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
									.executes(this::executeWithdraw)))

					.then(CommandManager.literal("give")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
									.then(CommandManager.argument("targets", EntityArgumentType.players())
											.executes(this::executeGive))))

					.then(CommandManager.literal("take")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("targets", EntityArgumentType.players())
									.then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
											.executes(this::executeTake))))

					.then(CommandManager.literal("set")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("targets", EntityArgumentType.players())
									.then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
											.executes(this::executeSet))))

					.then(CommandManager.literal("oprevive")
							.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
							.then(CommandManager.argument("player", StringArgumentType.string())
									.suggests((context, builder) -> {
										MinecraftServer server = context.getSource().getServer();
										for (UUID uuid : LifestealMod.eliminatedPlayers) {
											GameProfile profile = server.getApiServices().profileResolver().getProfileById(uuid).orElse(null);
											if (profile != null) {
												builder.suggest(profile.name());
											}
										}
										return builder.buildFuture();
									})
									.executes(this::executeOpRevive)))
					.then(CommandManager.literal("viewRecipes")
						.executes(this::executeViewRecipes)));
		});
	}

	private int executeWithdraw(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayer();
		int amount = IntegerArgumentType.getInteger(context, "amount");

		double playerMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

		// prevent from withdrawing all hearts
		if (amount >= playerMaxHealth / 2.0) {
			player.sendMessage(Text.literal("You cannot withdraw that many hearts! You must keep at least 1 heart.")
					.formatted(Formatting.RED), true);
			return 0;
		}

		if (playerMaxHealth >= amount * 2.0) {
			var heartStack = mod.createCustomNetherStar("Heart");
			heartStack.setCount(amount);

			if (!player.getInventory().insertStack(heartStack)) {
				player.sendMessage(Text.literal("Your inventory is full! Cannot withdraw heart.")
						.formatted(Formatting.RED), true);
				return 0;
			}

			double newMaxHealth = playerMaxHealth - amount * 2.0;
			double playerCurrentHealth = player.getHealth();

			player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(newMaxHealth);

			ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
			if (config.healPlayerOnWithdraw && playerCurrentHealth < newMaxHealth) {
				player.setHealth((float) newMaxHealth);
			}

			player.sendMessage(Text.literal("You have successfully withdrawn the heart!")
					.formatted(Formatting.GREEN), true);
			return amount;
		}

		player.sendMessage(Text.literal("Withdrawing heart failed!")
				.formatted(Formatting.RED), true);
		return 0;
	}

	private int executeGive(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, "targets");
		int amount = IntegerArgumentType.getInteger(context, "amount");

		for (ServerPlayerEntity target : targets) {
			double currentMaxHealth = target.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
			double newMaxHealth = Math.max(2.0, currentMaxHealth + amount * 2.0);

			target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(newMaxHealth);
			target.setHealth((float) newMaxHealth);
		}

		return targets.size();
	}

	private int executeTake(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, "targets");
		int amount = IntegerArgumentType.getInteger(context, "amount");

		for (ServerPlayerEntity target : targets) {
			double currentMaxHealth = target.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
			double newMaxHealth = Math.max(2.0, currentMaxHealth - amount * 2.0);

			target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(newMaxHealth);
			target.setHealth((float) newMaxHealth);
		}

		return targets.size();
	}

	private int executeSet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, "targets");
		int amount = IntegerArgumentType.getInteger(context, "amount");

		for (ServerPlayerEntity target : targets) {
			double newMaxHealth = Math.max(2.0, amount * 2.0);

			target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(newMaxHealth);
			target.setHealth((float) newMaxHealth);
		}

		return targets.size();
	}

	private int executeOpRevive(CommandContext<ServerCommandSource> context) {
		String playerName = StringArgumentType.getString(context, "player");
		ServerCommandSource source = context.getSource();
		MinecraftServer server = source.getServer();
		ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

		System.out.println("Eliminated Players: " + eliminatedPlayers);

		PlayerConfigEntry targetEntry = null;

		for (UUID uuid : LifestealMod.eliminatedPlayers) {
			GameProfile profile = server.getApiServices().profileResolver().getProfileByName(playerName).orElse(null);
			System.out.println("Profile: " + profile);
			System.out.println("PlayerName: " + playerName);
			if (profile != null && profile.name().equalsIgnoreCase(playerName)) {
				targetEntry = new PlayerConfigEntry(profile);
				System.out.println("Target Entry: " + targetEntry.name() + " " + targetEntry.id());
				break;
			}
		}

		if (targetEntry == null) {
			source.sendError(Text.literal(playerName + " is not a dead player."));
			return 0;
		}

		return executeRevive(source, targetEntry);
	}

	public int executeRevive(ServerCommandSource source, PlayerConfigEntry targetEntry) {
		ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

		if (!eliminatedPlayers.contains(targetEntry.id())) {
			source.sendError(Text.literal("This player is not eliminated."));
			return 0;
		}

		if (config.banPlayersOnElimination) {
			var bannedList = source.getServer().getPlayerManager().getUserBanList();
			bannedList.remove(targetEntry);

			pendingRevives.add(targetEntry.id());
		} else {
			ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(targetEntry.id());
			if (target != null) {
				target.changeGameMode(GameMode.SURVIVAL);
				target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(config.heartsAfterRevive * 2.0f);
				target.setHealth(config.heartsAfterRevive * 2.0f);
				target.sendMessage(Text.literal("You have been revived! Welcome back.").formatted(Formatting.GREEN), false);
			} else {
				pendingRevives.add(targetEntry.id());
			}
		}

		eliminatedPlayers.remove(targetEntry.id());
		saveEliminated();
		saveRevives();

		source.sendFeedback(() -> Text.literal(targetEntry.name() + " has been revived!").formatted(Formatting.GREEN), true);
		return 1;
	}

	private int executeViewRecipes(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		mod.openRecipeGUI(player);
		return 1;
	}
}



