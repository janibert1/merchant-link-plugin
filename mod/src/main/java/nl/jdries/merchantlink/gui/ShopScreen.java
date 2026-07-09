package nl.jdries.merchantlink.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import nl.jdries.merchantlink.data.MerchantProfile;
import nl.jdries.merchantlink.manager.MerchantManager;

import java.util.List;

/**
 * Buyer-facing shop GUI, opened by right-clicking someone else's merchant.
 * Read-only: every slot click is intercepted and never moves real items —
 * left-click buys 1, right-click buys 8, shift-click buys 64, mirroring the
 * Paper plugin's {@code ClickType} tiers via vanilla's {@link SlotActionType}
 * (PICKUP button 0/1, QUICK_MOVE).
 */
public final class ShopScreen {
    private static final int SIZE = 54;
    private static final int USABLE_SLOTS = 45;

    private ShopScreen() {
    }

    public static void open(ServerPlayerEntity customer, MerchantManager manager, MerchantProfile ownerProfile) {
        List<MerchantManager.ShopEntry> entries = manager.buildShopEntries(ownerProfile);
        SimpleInventory inventory = new SimpleInventory(SIZE);
        fill(inventory, manager, entries);

        Text title = Text.literal(manager.merchantTitle(ownerProfile));
        customer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, player) -> new Handler(syncId, playerInventory, inventory, manager, ownerProfile, entries),
            title
        ));
    }

    private static void fill(SimpleInventory inventory, MerchantManager manager, List<MerchantManager.ShopEntry> entries) {
        inventory.clear();
        if (entries.isEmpty()) {
            inventory.setStack(22, GuiIcons.named(net.minecraft.item.Items.GRAY_STAINED_GLASS_PANE,
                GuiIcons.gray("No Listings"),
                List.of(GuiIcons.gray("This merchant has nothing listed yet."))));
            return;
        }
        for (int i = 0; i < Math.min(entries.size(), USABLE_SLOTS); i++) {
            inventory.setStack(i, shopItem(manager, entries.get(i)));
        }
    }

    private static ItemStack shopItem(MerchantManager manager, MerchantManager.ShopEntry entry) {
        ItemStack display = entry.product().copyWithCount(1);
        java.util.List<Text> lore = new java.util.ArrayList<>();
        lore.add(GuiIcons.gray("Stock: " + entry.stock()));
        lore.add(GuiIcons.gray("Price: " + manager.describeStack(entry.cost())));
        if (!entry.returnAvailable()) {
            lore.add(GuiIcons.red("MERCHANT RETURN CHEST MISSING"));
        } else if (entry.stock() <= 0) {
            lore.add(GuiIcons.red("OUT OF STOCK"));
        }
        lore.add(GuiIcons.yellow("Left-Click to buy 1"));
        lore.add(GuiIcons.gold("Right-Click to buy 8"));
        lore.add(GuiIcons.red("Shift-Click to buy 64"));
        display.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
        return display;
    }

    private static final class Handler extends GenericContainerScreenHandler {
        private final MerchantManager manager;
        private final MerchantProfile ownerProfile;
        private final List<MerchantManager.ShopEntry> entries;

        private Handler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory,
                         MerchantManager manager, MerchantProfile ownerProfile, List<MerchantManager.ShopEntry> entries) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, SIZE / 9);
            this.manager = manager;
            this.ownerProfile = ownerProfile;
            this.entries = entries;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity buyer)) {
                return;
            }
            if (slotIndex < 0 || slotIndex >= USABLE_SLOTS || slotIndex >= entries.size()) {
                return;
            }
            int quantity = switch (actionType) {
                case PICKUP -> button == 0 ? 1 : 8;
                case QUICK_MOVE -> 64;
                default -> 0;
            };
            if (quantity == 0) {
                return;
            }

            MerchantManager.ShopEntry entry = entries.get(slotIndex);
            String failure = manager.performPurchase(buyer, ownerProfile, entry, quantity);
            if (failure != null) {
                manager.message(buyer, failure);
            } else {
                manager.message(buyer, "Purchased " + quantity + "x " + manager.niceName(entry.product()) + ".");
            }
            // Re-open with fresh stock/entries, matching the Paper plugin's re-open-after-purchase flow.
            ShopScreen.open(buyer, manager, ownerProfile);
        }
    }
}
