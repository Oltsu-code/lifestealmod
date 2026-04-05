package com.phantomz3.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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

import java.util.Collection;

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
										for (BannedPlayerEntry entry : server.getPlayerManager().getUserBanList().values()) {
											if (LifestealMod.validateLifestealBan(entry)) {
												builder.suggest(entry.getKey().name());
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

		PlayerConfigEntry targetEntry = null;
		for (BannedPlayerEntry entry : server.getPlayerManager().getUserBanList().values()) {
			if (entry.getKey().name().equalsIgnoreCase(playerName) && LifestealMod.validateLifestealBan(entry)) {
				targetEntry = entry.getKey();
				break;
			}
		}

		if (targetEntry == null) {
			source.sendError(Text.literal(playerName + " is not a dead player."));
			return 0;
		}

		return mod.executeRevive(source, targetEntry);
	}

	private int executeViewRecipes(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		mod.openRecipeGUI(player);
		return 1;
	}
}



