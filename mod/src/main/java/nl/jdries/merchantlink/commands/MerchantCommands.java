package nl.jdries.merchantlink.commands;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import nl.jdries.merchantlink.MerchantLinkMod;
import nl.jdries.merchantlink.data.LinkKind;
import nl.jdries.merchantlink.manager.MerchantManager;

import static net.minecraft.server.command.CommandManager.literal;

/** Registers {@code /ml <spawn|remove|despawn|link|manage>}, mirroring the Paper plugin's {@code /ml} command. */
public final class MerchantCommands {
    private MerchantCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("ml")
                .executes(ctx -> {
                    ctx.getSource().getPlayerOrThrow().sendMessage(Text.literal(
                        MerchantManager.PREFIX_PLAIN + "/ml spawn, /ml remove, /ml link supply, /ml link return, /ml manage"));
                    return 1;
                })
                .then(literal("spawn").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    manager().spawnMerchant(player);
                    return 1;
                }))
                .then(literal("remove").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    manager().removeMerchant(player);
                    return 1;
                }))
                .then(literal("despawn").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    manager().removeMerchant(player);
                    return 1;
                }))
                .then(literal("manage").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    manager().openManage(player);
                    return 1;
                }))
                .then(literal("link")
                    .executes(ctx -> {
                        ctx.getSource().getPlayerOrThrow().sendMessage(Text.literal(
                            MerchantManager.PREFIX_PLAIN + "Usage: /ml link supply or /ml link return"));
                        return 1;
                    })
                    .then(literal("supply").executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        manager().startLink(player, LinkKind.SUPPLY);
                        return 1;
                    }))
                    .then(literal("return").executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        manager().startLink(player, LinkKind.RETURN);
                        return 1;
                    }))));
        });
    }

    private static MerchantManager manager() {
        return MerchantLinkMod.manager();
    }
}
