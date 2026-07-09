package nl.jdries.merchantlink.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import nl.jdries.merchantlink.data.LinkedChest;
import nl.jdries.merchantlink.data.MerchantProfile;
import nl.jdries.merchantlink.manager.MerchantManager;

import java.util.List;

/**
 * Owner management GUI (opened by {@code /ml manage}): shows the 2 supply
 * slots + return slot (click to unlink), a remove-merchant button, and up to
 * 27 listings. Left-click a listing to open the price editor; the Paper
 * plugin's "drop key deletes a listing" control doesn't have a clean 1:1 in a
 * read-only Fabric container GUI, so this mod uses the vanilla "throw" action
 * instead (default keybind: Q, or Ctrl+Q for the stack variant) — same
 * physical key as the plugin's drop-key delete, so muscle memory carries
 * over.
 */
public final class ManageScreen {
    private static final int SIZE = 54;
    private static final int LISTING_START = 27;
    private static final int MAX_LISTINGS = 27;

    private ManageScreen() {
    }

    public static void open(ServerPlayerEntity player, MerchantManager manager, MerchantProfile profile) {
        manager.refreshListingsFromChests(profile);
        List<MerchantManager.ManageEntry> entries = manager.buildManageEntries(profile);
        SimpleInventory inventory = new SimpleInventory(SIZE);
        fill(inventory, manager, profile, entries);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, p) -> new Handler(syncId, playerInventory, inventory, manager, profile, entries),
            Text.literal("MerchantLink Manage")
        ));
    }

    private static void fill(SimpleInventory inventory, MerchantManager manager, MerchantProfile profile, List<MerchantManager.ManageEntry> entries) {
        inventory.clear();
        inventory.setStack(10, profile.supplyChests.size() > 0
            ? GuiIcons.linkedChestIcon(manager, profile.supplyChests.get(0), "Click to unlink")
            : GuiIcons.named(Items.CHEST, GuiIcons.gray("Supply Chest 1"), List.of(GuiIcons.gray("Not linked"))));
        inventory.setStack(12, profile.supplyChests.size() > 1
            ? GuiIcons.linkedChestIcon(manager, profile.supplyChests.get(1), "Click to unlink")
            : GuiIcons.named(Items.CHEST, GuiIcons.gray("Supply Chest 2"), List.of(GuiIcons.gray("Not linked"))));
        inventory.setStack(14, profile.returnChest != null
            ? GuiIcons.linkedChestIcon(manager, profile.returnChest, "Click to unlink")
            : GuiIcons.named(Items.ENDER_CHEST, GuiIcons.gray("Return Chest"), List.of(GuiIcons.gray("Not linked"))));
        inventory.setStack(16, GuiIcons.named(Items.VILLAGER_SPAWN_EGG, GuiIcons.red("Remove Merchant"), List.of(
            profile.hasMerchantHome() ? GuiIcons.gray(profile.merchantWorldKey + " " + (int) profile.merchantX + ", " + (int) profile.merchantY + ", " + (int) profile.merchantZ)
                : GuiIcons.gray("No merchant spawned")
        )));
        inventory.setStack(22, GuiIcons.named(Items.BOOK, GuiIcons.gold("Listing Controls"), List.of(
            GuiIcons.gray("Left click a listing: set barter price"),
            GuiIcons.gray("Throw (Q): delete listing"),
            GuiIcons.gray("Listings stay visible while out of stock")
        )));

        for (int i = 0; i < Math.min(entries.size(), MAX_LISTINGS); i++) {
            inventory.setStack(LISTING_START + i, manageListingItem(manager, entries.get(i)));
        }
    }

    private static ItemStack manageListingItem(MerchantManager manager, MerchantManager.ManageEntry entry) {
        ItemStack display = entry.listing().product.copyWithCount(Math.max(1, Math.min(64, entry.stock())));
        List<Text> lore = new java.util.ArrayList<>();
        lore.add(GuiIcons.gray("Stock: " + entry.stock()));
        if (entry.listing().cost == null) {
            lore.add(GuiIcons.red("UNPRICED"));
            lore.add(GuiIcons.gray("Hidden from public shop until priced"));
        } else {
            lore.add(GuiIcons.gray("Price: " + manager.describeStack(entry.listing().cost)));
        }
        lore.add(GuiIcons.yellow("Left click: set barter price"));
        lore.add(GuiIcons.red("Throw (Q): delete listing"));
        display.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
        return display;
    }

    private static final class Handler extends GenericContainerScreenHandler {
        private final MerchantManager manager;
        private final MerchantProfile profile;
        private final List<MerchantManager.ManageEntry> entries;

        private Handler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory,
                         MerchantManager manager, MerchantProfile profile, List<MerchantManager.ManageEntry> entries) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, SIZE / 9);
            this.manager = manager;
            this.profile = profile;
            this.entries = entries;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity owner) || actionType == SlotActionType.QUICK_CRAFT) {
                return;
            }

            if (slotIndex == 10 && !profile.supplyChests.isEmpty()) {
                manager.unlinkSupply(profile, 0);
                ManageScreen.open(owner, manager, profile);
                return;
            }
            if (slotIndex == 12 && profile.supplyChests.size() > 1) {
                manager.unlinkSupply(profile, 1);
                ManageScreen.open(owner, manager, profile);
                return;
            }
            if (slotIndex == 14 && profile.returnChest != null) {
                manager.unlinkReturn(profile);
                ManageScreen.open(owner, manager, profile);
                return;
            }
            if (slotIndex == 16) {
                owner.closeHandledScreen();
                manager.removeMerchant(owner);
                return;
            }
            if (slotIndex < LISTING_START || slotIndex >= SIZE) {
                return;
            }

            int index = slotIndex - LISTING_START;
            if (index >= entries.size()) {
                return;
            }
            var listing = entries.get(index).listing();
            if (actionType == SlotActionType.THROW) {
                manager.removeListing(profile, listing.itemKey);
                manager.message(owner, "Listing removed.");
                ManageScreen.open(owner, manager, profile);
                return;
            }
            manager.openPriceEditor(owner, profile, listing.itemKey);
        }
    }
}
