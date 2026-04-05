package com.phantomz3.event;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import com.phantomz3.LifestealMod;
import com.phantomz3.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;

public class PlayerEventManager {

	private final LifestealMod mod;
	private static final double HEART_VALUE = 2.0;
	private static final double MIN_HEALTH = 2.0;

	public PlayerEventManager(LifestealMod mod) {
		this.mod = mod;
	}

	public void register() {
		registerPlayerJoinEvent();
		registerPlayerDeathEvent();
		registerPlayerHealthCapEvent();
	}

	private void registerPlayerJoinEvent() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity joined = handler.player;
			if (LifestealMod.pendingRevives.remove(joined.getUuid())) {
				setPlayerHealth(joined, 3.0);
				joined.changeGameMode(GameMode.SURVIVAL);
				joined.sendMessage(
						Text.literal("You have been revived! Welcome back.").formatted(Formatting.GREEN),
						false);
			}
		});
	}

	private void registerPlayerDeathEvent() {
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (!(entity instanceof PlayerEntity)) {
				return true;
			}

			PlayerEntity player = (PlayerEntity) entity;
			LivingEntity attacker = (LivingEntity) source.getAttacker();

			if (hasTotemEquipped(player)) {
				return true;
			}

			ModConfig config = getConfig();
			handlePlayerKill(player, attacker, config);
			handlePlayerDeath(player, config);

			return false;
		});
	}

	private void registerPlayerHealthCapEvent() {
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (!(entity instanceof PlayerEntity)) {
				return true;
			}

			PlayerEntity player = (PlayerEntity) entity;
			ModConfig config = getConfig();
			double maxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

			if (maxHealth > config.maxHeartCap) {
				player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(config.maxHeartCap);
				player.sendMessage(
						Text.literal("You have reached the maximum health limit!").formatted(Formatting.RED),
						true);
			}

			return true;
		});
	}

	private boolean hasTotemEquipped(PlayerEntity player) {
		return mod.isTotemEquipped(player);
	}

	private void handlePlayerKill(PlayerEntity player, LivingEntity attacker, ModConfig config) {
		if (!(attacker instanceof PlayerEntity)) {
			var heart = mod.createCustomNetherStar("Heart");
			player.dropItem(heart, true);
			return;
		}

		PlayerEntity playerAttacker = (PlayerEntity) attacker;
		double attackerMaxHealth = attacker.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

		if (attackerMaxHealth < config.maxHeartCap) {
			mod.increasePlayerHealth(playerAttacker);
			playerAttacker.sendMessage(
					Text.literal("You gained an additional heart!").formatted(Formatting.GRAY),
					true);
		} else {
			var heart = mod.createCustomNetherStar("Heart");
			player.dropItem(heart, true);
		}
	}

	private void handlePlayerDeath(PlayerEntity player, ModConfig config) {
		mod.decreasePlayerHealth(player);
		player.sendMessage(Text.literal("You lost a heart!").formatted(Formatting.RED), true);

		double maxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

		if (maxHealth <= MIN_HEALTH) {
			eliminatePlayer(player);
		}
	}

	private void eliminatePlayer(PlayerEntity player) {
		ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
		serverPlayer.changeGameMode(GameMode.SPECTATOR);
		player.setHealth(1.0f);
		player.getInventory().dropAll();

		// ban player
		BannedPlayerList bannedList = player.getEntityWorld().getServer().getPlayerManager().getUserBanList();
		BannedPlayerEntry banEntry = new BannedPlayerEntry(
				player.getPlayerConfigEntry(),
				null,
				LifestealMod.MOD_ID,
				null,
				LifestealMod.REVIVE_BAN_REASON
		);
		bannedList.add(banEntry);

		player.sendMessage(
				Text.literal("You lost all your hearts! You are now in spectator mode!").formatted(Formatting.GRAY),
				true);

		serverPlayer.networkHandler.disconnect(Text.literal("You lost all your hearts!"));

		player.getEntityWorld().getServer().getPlayerManager().broadcast(
				Text.literal("→ " + player.getDisplayName().getString() + " has lost all of his hearts and is eliminated!").formatted(Formatting.RED),
				false);
	}

	private void setPlayerHealth(PlayerEntity player, double hearts) {
		double maxHealth = Math.max(MIN_HEALTH, hearts * HEART_VALUE);
		player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(maxHealth);
		player.setHealth((float) maxHealth);
	}

	private ModConfig getConfig() {
		return AutoConfig.getConfigHolder(ModConfig.class).getConfig();
	}
}

