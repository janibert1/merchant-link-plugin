package nl.jdries.merchantlink.events;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import nl.jdries.merchantlink.MerchantLinkMod;
import nl.jdries.merchantlink.data.LinkKind;
import nl.jdries.merchantlink.manager.MerchantManager;

/**
 * Left-clicking a chest while in "pending link" state ({@code /ml link supply}/{@code return})
 * links it (Paper plugin: {@code Action.LEFT_CLICK_BLOCK}). Also protects
 * linked chests from being broken by non-owners.
 *
 * <p>Note on scope: unlike the Paper plugin, this mod does not hook explosion
 * or fire-spread events to protect linked chests — Fabric API does not
 * currently expose a clean equivalent to Bukkit's
 * {@code BlockExplodeEvent}/{@code EntityExplodeEvent}/{@code BlockIgniteEvent}/
 * {@code BlockBurnEvent}. Linked chests here are protected from being broken
 * by other players, but not from explosions or fire. See the mod README.
 */
public final class ChestLinkEvents {
    private ChestLinkEvents() {
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            MerchantManager manager = MerchantLinkMod.manager();
            if (manager == null) {
                return ActionResult.PASS;
            }
            LinkKind pending = manager.pendingLinkFor(serverPlayer.getUuid());
            if (pending == null) {
                return ActionResult.PASS;
            }
            manager.attemptCompleteLink(serverPlayer, world, pos);
            return ActionResult.SUCCESS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return true;
            }
            MerchantManager manager = MerchantLinkMod.manager();
            if (manager == null) {
                return true;
            }
            return manager.handleChestBreakAttempt(serverPlayer, world, pos);
        });
    }
}
