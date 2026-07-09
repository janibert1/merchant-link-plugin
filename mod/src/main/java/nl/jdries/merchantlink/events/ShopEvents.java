package nl.jdries.merchantlink.events;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import nl.jdries.merchantlink.MerchantLinkMod;
import nl.jdries.merchantlink.data.MerchantProfile;
import nl.jdries.merchantlink.manager.MerchantManager;

import java.util.UUID;

/** Right-clicking an owned merchant villager opens the shop GUI instead of the vanilla trade UI. */
public final class ShopEvents {
    private ShopEvents() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }
            if (!(entity instanceof VillagerEntity) || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            MerchantManager manager = MerchantLinkMod.manager();
            if (manager == null) {
                return ActionResult.PASS;
            }
            UUID ownerId = manager.getMerchantOwner(entity);
            if (ownerId == null) {
                return ActionResult.PASS;
            }

            MerchantProfile profile = manager.getProfile(ownerId);
            if (profile == null) {
                serverPlayer.sendMessage(net.minecraft.text.Text.literal(MerchantManager.PREFIX_PLAIN + "This merchant is not configured."));
                return ActionResult.SUCCESS;
            }
            manager.openShop(serverPlayer, profile);
            return ActionResult.SUCCESS;
        });
    }
}
