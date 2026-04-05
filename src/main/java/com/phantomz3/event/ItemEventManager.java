package com.phantomz3.event;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Formatting;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.BannedPlayerList;

import com.phantomz3.LifestealMod;
import com.phantomz3.ModConfig;
import com.phantomz3.ReviveScreenHandler;
import me.shedaniel.autoconfig.AutoConfig;

public class ItemEventManager {

	private final LifestealMod mod;
	private static final double HEART_VALUE = 2.0;

	public ItemEventManager(LifestealMod mod) {
		this.mod = mod;
	}

	public void register() {
		registerHeartItemUsage();
		registerReviveBeaconUsage();
		registerEnderPearlDisable();
		registerRiptideCooldown();
	}

	private void registerHeartItemUsage() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack itemStack = player.getStackInHand(hand);

			if (!mod.isHeartItem(itemStack) || itemStack.hasGlint()) {
				return ActionResult.PASS;
			}

			ModConfig config = getConfig();
			double maxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);

			if (maxHealth >= config.maxHeartCap) {
				player.sendMessage(
						Text.literal("You have reached the maximum heart limit!").formatted(Formatting.RED),
						true);
				return ActionResult.FAIL;
			}

			player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(maxHealth + HEART_VALUE);
			player.heal((float) HEART_VALUE);

			if (!player.getAbilities().creativeMode) {
				itemStack.decrement(1);
			}

			player.sendMessage(Text.literal("You gained an additional heart!").formatted(Formatting.GREEN), true);
			return ActionResult.SUCCESS;
		});
	}

	private void registerReviveBeaconUsage() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack itemStack = player.getStackInHand(hand);

			if (!mod.isReviveBeacon(itemStack)) {
				return ActionResult.PASS;
			}

			if (!(player instanceof ServerPlayerEntity)) {
				return ActionResult.PASS;
			}

			ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
			openReviveGUI(serverPlayer);
			return ActionResult.SUCCESS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			ItemStack itemStack = player.getStackInHand(hand);
			return mod.isReviveBeacon(itemStack) ? ActionResult.FAIL : ActionResult.PASS;
		});
	}

	private void registerEnderPearlDisable() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack itemStack = player.getStackInHand(hand);

			if (itemStack.getItem() == Items.ENDER_PEARL) {
				player.sendMessage(
						Text.literal("Ender pearls are disabled on this server!").formatted(Formatting.RED),
						true);
				return ActionResult.FAIL;
			}

			return ActionResult.PASS;
		});
	}

	private void registerRiptideCooldown() {
		ModConfig config = getConfig();

		if (!config.riptideCooldownEnabled) {
			return;
		}

		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClient()) {
				return ActionResult.PASS;
			}

			ItemStack itemStack = player.getStackInHand(hand);

			if (itemStack.getItem() != Items.TRIDENT || !player.isUsingRiptide()) {
				return ActionResult.PASS;
			}

			if (player.getItemCooldownManager().isCoolingDown(itemStack)) {
				return ActionResult.FAIL;
			}

			player.getItemCooldownManager().set(itemStack, config.riptideCooldown);
			return ActionResult.SUCCESS;
		});
	}

	private void openReviveGUI(ServerPlayerEntity player) {
		SimpleInventory inventory = new SimpleInventory(27);
		BannedPlayerList bannedList = player.getEntityWorld().getServer().getPlayerManager().getUserBanList();

		bannedList.values().forEach(entry -> {
			if (LifestealMod.validateLifestealBan(entry)) {
				ItemStack playerHead = new ItemStack(Items.PLAYER_HEAD);
				playerHead.set(DataComponentTypes.ITEM_NAME, Text.literal(entry.getKey().name()));

				NbtCompound nbt = new NbtCompound();
				nbt.putString("SkullOwner", entry.getKey().name());
				playerHead.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
				inventory.addStack(playerHead);
			}
		});

		for (int i = 0; i < inventory.size(); i++) {
			if (inventory.getStack(i).isEmpty()) {
				ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
				glassPane.set(DataComponentTypes.ITEM_NAME, Text.literal("Empty"));
				inventory.setStack(i, glassPane);
			}
		}

		player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, playerInventory, playerEntity) -> new ReviveScreenHandler(syncId, playerInventory, inventory),
				Text.of("Revive Players")));
	}

	private ModConfig getConfig() {
		return AutoConfig.getConfigHolder(ModConfig.class).getConfig();
	}
}

