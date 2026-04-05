package com.phantomz3;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

@Config(name = LifestealMod.MOD_ID)
public class ModConfig implements ConfigData {
    public int maxHeartCap = 40;
    public boolean banPlayersOnElimination = true;
    public int heartsAfterRevive = 3;
    public boolean disableEnderPearl = true;
    public boolean disableCPVP = true;
    public boolean disableTotem = true;
    public boolean disableNetherite = true;
    public boolean noDragonEggEnderChest = true;
    public int riptideCooldown = 200; // 200 ticks = 10 seconds
    public boolean riptideCooldownEnabled = true;
    public boolean healPlayerOnWithdraw = false;
    public int maxSharpnessLevel = 4;
    public int maxProtectionLevel = 3;
}
