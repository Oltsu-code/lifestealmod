package com.phantomz3.mixin;

import com.phantomz3.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilScreenHandler.class)
public class AnvilScreenHandlerMixin {

    @Inject(method = "onTakeOutput", at = @At("HEAD"))
    private void onTakeOutput(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
        var enchantRegistry = player.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        // strip incompatible enchants from regular items
        var enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantments != null && !stack.isOf(net.minecraft.item.Items.ENCHANTED_BOOK)) {
            var builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
            enchantments.getEnchantmentEntries().forEach(entry -> {
                if (entry.getKey().value().isAcceptableItem(stack)) {
                    builder.set(entry.getKey(), enchantments.getLevel(entry.getKey()));
                }
            });
            stack.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        }

        // rebuild stored enchantments (books)
        var storedEnchantments = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (storedEnchantments != null) {
            var builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
            storedEnchantments.getEnchantmentEntries().forEach(entry -> {
                builder.set(entry.getKey(), storedEnchantments.getLevel(entry.getKey()));
            });
            stack.set(DataComponentTypes.STORED_ENCHANTMENTS, builder.build());
        }

        // clamp levels
        enchantRegistry.getOptional(Enchantments.SHARPNESS).ifPresent(e -> {
            clampComponent(stack, DataComponentTypes.ENCHANTMENTS, e, config.maxSharpnessLevel);
            clampComponent(stack, DataComponentTypes.STORED_ENCHANTMENTS, e, config.maxSharpnessLevel);
        });
        enchantRegistry.getOptional(Enchantments.PROTECTION).ifPresent(e -> {
            clampComponent(stack, DataComponentTypes.ENCHANTMENTS, e, config.maxProtectionLevel);
            clampComponent(stack, DataComponentTypes.STORED_ENCHANTMENTS, e, config.maxProtectionLevel);
        });

        // sync to client
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    @Unique
    private boolean clampComponent(ItemStack stack, net.minecraft.component.ComponentType<ItemEnchantmentsComponent> type, RegistryEntry<Enchantment> entry, int maxLevel) {
        var enchantments = stack.get(type);
        if (enchantments == null) return false;
        int level = enchantments.getLevel(entry);
        if (level > maxLevel && maxLevel > 0) {
            var builder = new ItemEnchantmentsComponent.Builder(enchantments);
            builder.set(entry, maxLevel);
            stack.set(type, builder.build());
            return true;
        }
        return false;
    }
}