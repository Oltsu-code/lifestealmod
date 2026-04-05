package com.phantomz3.mixin;

import com.phantomz3.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.Stream;

@Mixin(EnchantmentHelper.class)
public class EnchantmentHelperMixin {

    @Inject(method = "generateEnchantments", at = @At("RETURN"), cancellable = true)
    private static void onGenerateEnchantments(Random random, ItemStack stack, int level, Stream<RegistryEntry<Enchantment>> possibleEnchantments, CallbackInfoReturnable<List<EnchantmentLevelEntry>> cir) {
        ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

        List<EnchantmentLevelEntry> result = new java.util.ArrayList<>(cir.getReturnValue());
        result.replaceAll(entry -> {
            if (entry.enchantment().matchesKey(Enchantments.SHARPNESS) && entry.level() > config.maxSharpnessLevel) {
                return new EnchantmentLevelEntry(entry.enchantment(), config.maxSharpnessLevel);
            }
            if (entry.enchantment().matchesKey(Enchantments.PROTECTION) && entry.level() > config.maxProtectionLevel) {
                return new EnchantmentLevelEntry(entry.enchantment(), config.maxProtectionLevel);
            }
            return entry;
        });
        cir.setReturnValue(result);
    }
}