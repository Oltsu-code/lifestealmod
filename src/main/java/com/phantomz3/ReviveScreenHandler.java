package com.phantomz3;

import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

public class ReviveScreenHandler extends GenericContainerScreenHandler {

	private static final String REVIVE_BEACON_NAME = "Revive Beacon";

	public ReviveScreenHandler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory) {
		super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, inventory, 3);
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return true;
	}

	@Override
	public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
		return false;
	}

	@Override
	public void onSlotClick(int slotId, int button, SlotActionType actionType, PlayerEntity player) {
		if (isInvalidSlotId(slotId)) {
			super.onSlotClick(slotId, button, actionType, player);
			return;
		}

		ItemStack clickedStack = this.slots.get(slotId).getStack();

		if (isPlayerHead(clickedStack)) {
			handlePlayerHeadClick((ServerPlayerEntity) player, clickedStack, slotId);
		} else if (isGlassPane(clickedStack)) {
			((ServerPlayerEntity) player).closeHandledScreen();
		}

		super.onSlotClick(slotId, button, actionType, player);
	}

	private boolean isInvalidSlotId(int slotId) {
		return slotId < 0 || slotId >= this.slots.size();
	}

	private boolean isPlayerHead(ItemStack stack) {
		return stack.getItem() == Items.PLAYER_HEAD;
	}

	private boolean isGlassPane(ItemStack stack) {
		return stack.getItem() == Items.GRAY_STAINED_GLASS_PANE;
	}

	private void handlePlayerHeadClick(ServerPlayerEntity reviver, ItemStack playerHead, int slotId) {
		String playerName = playerHead.get(DataComponentTypes.ITEM_NAME).getString();
		PlayerConfigEntry targetEntry = findBannedPlayer(reviver, playerName);

		if (targetEntry == null) {
			reviver.sendMessage(Text.literal("Player not found in ban list.").formatted(Formatting.RED), true);
			return;
		}

		if (attemptRevive(reviver, targetEntry)) {
			this.slots.get(slotId).setStack(ItemStack.EMPTY);
			this.sendContentUpdates();
			reviver.closeHandledScreen();
		}
	}

	private PlayerConfigEntry findBannedPlayer(ServerPlayerEntity reviver, String playerName) {
		BannedPlayerList bannedList = reviver.getEntityWorld().getServer().getPlayerManager().getUserBanList();

		for (BannedPlayerEntry entry : bannedList.values()) {
			if (entry.getKey().name().equalsIgnoreCase(playerName)) {
				return entry.getKey();
			}
		}

		return null;
	}

	private boolean attemptRevive(ServerPlayerEntity reviver, PlayerConfigEntry targetEntry) {
		ModConfig config  = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
		BannedPlayerList bannedList = reviver.getEntityWorld().getServer().getPlayerManager().getUserBanList();

		if (config.banPlayersOnElimination && !bannedList.contains(targetEntry)) {
			reviver.sendMessage(Text.literal("This player is not banned.").formatted(Formatting.RED), true);
			return false;
		}

		if (!hasReviveBeacon(reviver)) {
			reviver.sendMessage(Text.literal("You need a Revive Beacon to revive players!").formatted(Formatting.RED), true);
			return false;
		}

		MinecraftServer server = reviver.getEntityWorld().getServer();
		ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetEntry.id());
		if (!config.banPlayersOnElimination && target != null) { // perform immediate revive
			target.changeGameMode(GameMode.SURVIVAL);
			target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(6.0);
			target.setHealth(6.0f);
			target.sendMessage(Text.literal("You have been revived! Welcome back.").formatted(Formatting.GREEN), false);
		} else { // perform revive
			if (config.banPlayersOnElimination) bannedList.remove(targetEntry);
			LifestealMod.pendingRevives.add(targetEntry.id());
			LifestealMod.saveRevives();
		}

		consumeReviveBeacon(reviver);

		reviver.sendMessage(Text.literal(targetEntry.name() + " has been revived!").formatted(Formatting.GREEN), true);
		return true;
	}

	private boolean hasReviveBeacon(ServerPlayerEntity player) {
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (isReviveBeacon(stack)) {
				return true;
			}
		}
		return false;
	}

	private void consumeReviveBeacon(ServerPlayerEntity player) {
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (isReviveBeacon(stack)) {
				stack.decrement(1);
				break;
			}
		}
	}

	private boolean isReviveBeacon(ItemStack stack) {
		return stack.getItem() == Items.BEACON
			&& stack.hasGlint()
			&& stack.getName().getString().equals(REVIVE_BEACON_NAME);
	}
}

