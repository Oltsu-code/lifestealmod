package com.phantomz3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.phantomz3.command.LifestealCommands;
import com.phantomz3.event.DisablingFeatureManager;
import com.phantomz3.event.ItemEventManager;
import com.phantomz3.event.PlayerEventManager;

public class LifestealMod implements ModInitializer {
	public static final String MOD_ID = "lifestealmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final Set<UUID> pendingRevives = new HashSet<>();
	private static final Path REVIVE_FILE = Path.of("lifesteal_revives.txt");

	public static final String REVIVE_BAN_REASON = "Losing all hearts ban";
	private static final String HEART_ITEM_NAME = "Heart";
	private static final String REVIVE_BEACON_NAME = "Revive Beacon";

	private final PlayerEventManager playerEventManager = new PlayerEventManager(this);
	private final ItemEventManager itemEventManager = new ItemEventManager(this);
	private final DisablingFeatureManager disablingFeatureManager = new DisablingFeatureManager();
	private final LifestealCommands lifeStealCommands = new LifestealCommands(this);

	@Override
	public void onInitialize() {
		LOGGER.info("Lifesteal mod has been initialized!");

		registerConfig();
		playerEventManager.register();
		itemEventManager.register();
		disablingFeatureManager.register();
		lifeStealCommands.register();

		loadRevives();
	}

	private void registerConfig() {
		AutoConfig.register(ModConfig.class, GsonConfigSerializer::new);
	}

	public ItemStack createCustomNetherStar(String name) {
		ItemStack heartStack = new ItemStack(Items.NETHER_STAR);
		heartStack.set(DataComponentTypes.ITEM_NAME, Text.literal(name));
		heartStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
		return heartStack;
	}

	public ItemStack createReviveBeacon(String name) {
		ItemStack reviveBeaconStack = new ItemStack(Items.BEACON);
		reviveBeaconStack.set(DataComponentTypes.ITEM_NAME, Text.literal(name));
		reviveBeaconStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
		return reviveBeaconStack;
	}

	public void increasePlayerHealth(PlayerEntity player) {
		double currentMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
		player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(currentMaxHealth + 2.0);
	}

	public void decreasePlayerHealth(PlayerEntity player) {
		double currentMaxHealth = player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
		double newMaxHealth = Math.max(2.0, currentMaxHealth - 2.0);
		player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(newMaxHealth);
	}

	public boolean isHeartItem(ItemStack stack) {
		return stack.getItem() == Items.NETHER_STAR
			&& stack.contains(DataComponentTypes.ITEM_NAME)
			&& stack.get(DataComponentTypes.ITEM_NAME).getString().equals(HEART_ITEM_NAME);
	}

	public boolean isReviveBeacon(ItemStack stack) {
		return stack.getItem() == Items.BEACON
			&& stack.hasGlint()
			&& stack.getName().getString().equals(REVIVE_BEACON_NAME);
	}

	public boolean isTotemEquipped(PlayerEntity player) {
		return player.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING
			|| player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING;
	}

	public int executeRevive(ServerCommandSource source, PlayerConfigEntry targetEntry) {
		ModConfig config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

		if (config.banPlayersOnElimination) {
			var bannedList = source.getServer().getPlayerManager().getUserBanList();

			if (!bannedList.contains(targetEntry)) {
				source.sendError(Text.literal("This player is not banned."));
				return 0;
			}

			bannedList.remove(targetEntry);
		}

		pendingRevives.add(targetEntry.id());
		LifestealMod.saveRevives();

		source.sendFeedback(() -> Text.literal(targetEntry.name() + " has been revived!").formatted(Formatting.GREEN), true);
		return 1;
	}

	public static void saveRevives() {
		try {
			Files.writeString(REVIVE_FILE,
					pendingRevives.stream().map(UUID::toString).collect(Collectors.joining("\n")));
		} catch (IOException e) {
			LOGGER.error("Failed to save pending revives", e);
		}
	}

	public static void loadRevives() {
		if (!Files.exists(REVIVE_FILE)) return;
		try {
			String content = Files.readString(REVIVE_FILE);
			if (content.isBlank()) return;
			for (String line : content.split("\n")) {
				pendingRevives.add(UUID.fromString(line.trim()));
			}
		} catch (IOException e) {
			LOGGER.error("Failed to load pending revives", e);
		}
	}

	public void openRecipeGUI(ServerPlayerEntity player) {
		SimpleInventory inventory = new SimpleInventory(45);

		ItemStack rawGoldBlock = new ItemStack(Items.RAW_GOLD_BLOCK);
		rawGoldBlock.set(DataComponentTypes.ITEM_NAME, Text.literal("Raw Gold Block"));

		ItemStack netheriteIngot = new ItemStack(Items.NETHERITE_INGOT);
		netheriteIngot.set(DataComponentTypes.ITEM_NAME, Text.literal("Netherite Ingot"));

		ItemStack netherStar = new ItemStack(Items.NETHER_STAR);
		netherStar.set(DataComponentTypes.ITEM_NAME, Text.literal("Nether Star"));

		ItemStack beacon = new ItemStack(Items.BEACON);
		beacon.set(DataComponentTypes.ITEM_NAME, Text.literal("Beacon"));

		ItemStack reviveBeacon = createReviveBeacon("Revive Beacon");
		ItemStack heart = createCustomNetherStar("Heart");

		String[] recipePattern1 = {"RNR", "NGN", "RNR"};
		String[] recipePattern2 = {"NGN", "GBG", "NGN"};

		placeRecipeItems(inventory, recipePattern1, 1, 0, rawGoldBlock, netheriteIngot, netherStar, null);
		placeRecipeItems(inventory, recipePattern2, 1, 5, null, netheriteIngot, netherStar, beacon);

		int resultSlot1 = (1 + 1) * 9 + (0 + 3);
		inventory.setStack(resultSlot1, heart);

		int resultSlot2 = (1 + 1) * 9 + (5 + 3);
		inventory.setStack(resultSlot2, reviveBeacon);

		for (int i = 0; i < inventory.size(); i++) {
			if (inventory.getStack(i).isEmpty()) {
				ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
				glassPane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
				inventory.setStack(i, glassPane);
			}
		}

		player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, playerInventory, playerEntity) -> new RecipeScreenHandler(syncId, playerInventory, inventory),
				Text.of("View Recipes")));
	}

	public static boolean validateLifestealBan(BannedPlayerEntry entry) {
		if (entry.getReason().equals(REVIVE_BAN_REASON) && entry.getSource().equals(MOD_ID)) return true;
		return false;
	}

	private void placeRecipeItems(SimpleInventory inventory, String[] pattern, int startRow, int startCol,
		ItemStack raw, ItemStack netherite, ItemStack star, ItemStack beacon) {
		for (int i = 0; i < pattern.length; i++) {
			String row = pattern[i];
			for (int j = 0; j < row.length(); j++) {
				char c = row.charAt(j);
				ItemStack itemStack = ItemStack.EMPTY;

				switch (c) {
					case 'R' -> itemStack = raw;
					case 'N' -> itemStack = netherite;
					case 'G' -> itemStack = star;
					case 'B' -> itemStack = beacon;
				}

				int slotIndex = (startRow + i) * 9 + (startCol + j);
				inventory.setStack(slotIndex, itemStack);
			}
		}
	}
}


