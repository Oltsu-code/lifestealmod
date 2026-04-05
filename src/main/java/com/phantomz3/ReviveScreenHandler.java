package com.phantomz3;

import net.minecraft.component.DataComponentTypes;
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
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ReviveScreenHandler extends GenericContainerScreenHandler {

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
        if (slotId < 0 || slotId >= this.slots.size()) {
            super.onSlotClick(slotId, button, actionType, player);
            return;
        }

        ItemStack clickedStack = this.slots.get(slotId).getStack();

        if (clickedStack.getItem() == Items.PLAYER_HEAD) {
            String playerName = clickedStack.get(DataComponentTypes.ITEM_NAME).getString();

            BannedPlayerList bannedPlayerList = player.getEntityWorld().getServer()
                    .getPlayerManager().getUserBanList();

            PlayerConfigEntry targetEntry = null;
            for (BannedPlayerEntry entry : bannedPlayerList.values()) {
                if (entry.getKey().name().equalsIgnoreCase(playerName)) {
                    targetEntry = entry.getKey();
                    break;
                }
            }

            if (targetEntry != null) {
                int result = executeRevive_((ServerPlayerEntity) player, targetEntry);
                if (result == 1) {
                    this.slots.get(slotId).setStack(ItemStack.EMPTY);
                    this.sendContentUpdates();
                    ((ServerPlayerEntity) player).closeHandledScreen();
                }
            }
        }

        if (clickedStack.getItem() == Items.GRAY_STAINED_GLASS_PANE) {
            ((ServerPlayerEntity) player).closeHandledScreen();
        }

        super.onSlotClick(slotId, button, actionType, player);
    }

    private int executeRevive_(ServerPlayerEntity reviver, PlayerConfigEntry targetEntry) {
        BannedPlayerList bannedPlayerList = reviver.getEntityWorld().getServer()
                .getPlayerManager().getUserBanList();

        if (!bannedPlayerList.contains(targetEntry)) {
            reviver.sendMessage(Text.literal("This player is not banned.").formatted(Formatting.RED), true);
            return 0;
        }

        boolean hasBeacon = false;
        for (int i = 0; i < reviver.getInventory().size(); i++) {
            ItemStack stack = reviver.getInventory().getStack(i);
            if (stack.getItem() == Items.BEACON && stack.hasGlint()
                    && stack.getName().getString().equals("Revive Beacon")) {
                hasBeacon = true;
                break;
            }
        }
        if (!hasBeacon) {
            reviver.sendMessage(Text.literal("You need a Revive Beacon to revive players!").formatted(Formatting.RED), true);
            return 0;
        }

        bannedPlayerList.remove(targetEntry);
        LifestealMod.pendingRevives.add(targetEntry.id());

        reviver.sendMessage(Text.literal(targetEntry.name() + " has been revived!").formatted(Formatting.GREEN), true);

        for (int i = 0; i < reviver.getInventory().size(); i++) {
            ItemStack stack = reviver.getInventory().getStack(i);
            if (stack.getItem() == Items.BEACON && stack.hasGlint()
                    && stack.getName().getString().equals("Revive Beacon")) {
                stack.decrement(1);
                break;
            }
        }

        return 1;
    }
}