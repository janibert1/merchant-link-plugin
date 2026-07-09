package nl.jdries.merchantlink.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import nl.jdries.merchantlink.data.MerchantProfile;
import nl.jdries.merchantlink.manager.MerchantManager;

/**
 * Shown when a player links a third supply chest — the plugin's
 * "openReplaceInventory" flow: pick which of the two existing supply chests
 * to replace with the newly clicked one.
 */
public final class ReplaceScreen {
    private static final int SIZE = 27;

    private ReplaceScreen() {
    }

    public static void open(ServerPlayerEntity player, MerchantManager manager, MerchantProfile profile) {
        SimpleInventory inventory = new SimpleInventory(SIZE);
        for (int i = 0; i < profile.supplyChests.size(); i++) {
            inventory.setStack(11 + (i * 4), GuiIcons.linkedChestIcon(manager, profile.supplyChests.get(i), "Click to replace"));
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, p) -> new Handler(syncId, playerInventory, inventory, manager, profile),
            Text.literal("Replace Supply Link")
        ));
    }

    private static final class Handler extends GenericContainerScreenHandler {
        private final MerchantManager manager;
        private final MerchantProfile profile;

        private Handler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory, MerchantManager manager, MerchantProfile profile) {
            super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, inventory, SIZE / 9);
            this.manager = manager;
            this.profile = profile;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity owner)) {
                return;
            }
            int index = switch (slotIndex) {
                case 11 -> 0;
                case 15 -> 1;
                default -> -1;
            };
            if (index < 0 || index >= profile.supplyChests.size()) {
                return;
            }
            manager.resolveReplace(owner, profile, index);
        }
    }
}
