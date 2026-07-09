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
import nl.jdries.merchantlink.data.Listing;
import nl.jdries.merchantlink.data.MerchantProfile;
import nl.jdries.merchantlink.manager.MerchantManager;

import java.util.List;

/**
 * Barter-price editor sub-GUI, opened from a listing in {@link ManageScreen}.
 * Slot 13 is the only interactive slot — place the desired cost stack there
 * and click "Save" (slot 15). Every other slot is a read-only display icon.
 */
public final class PriceEditorScreen {
    private static final int SIZE = 27;
    private static final int PRODUCT_SLOT = 11;
    private static final int COST_SLOT = 13;
    private static final int SAVE_SLOT = 15;
    private static final int CANCEL_SLOT = 22;

    private PriceEditorScreen() {
    }

    public static void open(ServerPlayerEntity player, MerchantManager manager, MerchantProfile profile, Listing listing) {
        SimpleInventory inventory = new SimpleInventory(SIZE);
        inventory.setStack(PRODUCT_SLOT, productPreview(manager, listing));
        inventory.setStack(SAVE_SLOT, GuiIcons.named(Items.LIME_WOOL, GuiIcons.gold("Save Listing"), List.of(
            GuiIcons.gray("Place the cost stack in the center slot."))));
        inventory.setStack(CANCEL_SLOT, GuiIcons.named(Items.BARRIER, GuiIcons.red("Cancel"), List.of(
            GuiIcons.gray("Close without changes."))));
        if (listing.cost != null) {
            inventory.setStack(COST_SLOT, listing.cost.copy());
        }

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, p) -> new Handler(syncId, playerInventory, inventory, manager, profile, listing.itemKey),
            Text.literal("Set Barter Price")
        ));
    }

    private static ItemStack productPreview(MerchantManager manager, Listing listing) {
        ItemStack display = listing.product.copy();
        List<Text> lore = new java.util.ArrayList<>();
        lore.add(GuiIcons.gray("Product: " + manager.describeStack(listing.product.copyWithCount(1))));
        lore.add(GuiIcons.gray("Current price: " + (listing.cost == null ? "Not set" : manager.describeStack(listing.cost))));
        lore.add(GuiIcons.gray("Place the new cost stack in the center slot."));
        display.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
        return display;
    }

    private static final class Handler extends GenericContainerScreenHandler {
        private final MerchantManager manager;
        private final MerchantProfile profile;
        private final String itemKey;

        private Handler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory,
                         MerchantManager manager, MerchantProfile profile, String itemKey) {
            super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, inventory, SIZE / 9);
            this.manager = manager;
            this.profile = profile;
            this.itemKey = itemKey;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity owner)) {
                return;
            }

            // The cost slot behaves like a normal container slot: freely place/take items.
            if (slotIndex == COST_SLOT && actionType == SlotActionType.PICKUP) {
                super.onSlotClick(slotIndex, button, actionType, player);
                return;
            }
            // Everything else (including shift-click / throw anywhere) is a no-op — never move real items.
            if (actionType != SlotActionType.PICKUP) {
                return;
            }

            if (slotIndex == SAVE_SLOT) {
                ItemStack cost = getInventory().getStack(COST_SLOT);
                if (cost == null || cost.isEmpty()) {
                    manager.message(owner, "Place the barter cost stack in the center slot first.");
                    return;
                }
                manager.setListingCost(profile, itemKey, cost.copy());
                manager.message(owner, "Listing price saved.");
                // Clear before closing so onClosed() doesn't hand the now-saved cost stack back to the player.
                getInventory().setStack(COST_SLOT, ItemStack.EMPTY);
                owner.closeHandledScreen();
                nl.jdries.merchantlink.gui.ManageScreen.open(owner, manager, profile);
                return;
            }
            if (slotIndex == CANCEL_SLOT) {
                owner.closeHandledScreen();
                nl.jdries.merchantlink.gui.ManageScreen.open(owner, manager, profile);
            }
        }

        @Override
        public void onClosed(PlayerEntity player) {
            ItemStack cost = getInventory().getStack(COST_SLOT);
            if (cost != null && !cost.isEmpty() && player instanceof ServerPlayerEntity serverPlayer) {
                if (!serverPlayer.getInventory().insertStack(cost.copy())) {
                    serverPlayer.dropItem(cost.copy(), false);
                }
                getInventory().setStack(COST_SLOT, ItemStack.EMPTY);
            }
            super.onClosed(player);
        }
    }
}
