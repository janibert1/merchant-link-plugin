package nl.jdries.merchantlink;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import nl.jdries.merchantlink.commands.MerchantCommands;
import nl.jdries.merchantlink.events.ChestLinkEvents;
import nl.jdries.merchantlink.events.ShopEvents;
import nl.jdries.merchantlink.manager.Database;
import nl.jdries.merchantlink.manager.MerchantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.UUID;

public class MerchantLinkMod implements ModInitializer {

    public static final String MOD_ID = "merchant-link";
    public static final Logger LOGGER = LoggerFactory.getLogger("merchant-link");

    /** Owner UUID attached directly to the merchant villager entity, persisted across restarts. */
    public static final AttachmentType<UUID> MERCHANT_OWNER =
        AttachmentRegistry.createPersistent(Identifier.of(MOD_ID, "merchant_owner"), Uuids.CODEC);

    private static volatile MinecraftServer serverInstance;
    private static MerchantManager manager;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            File dataDir = new File("config/merchant-link");
            //noinspection ResultOfMethodCallIgnored
            dataDir.mkdirs();
            Database database = new Database(new File(dataDir, "merchantlink.db"));
            database.init();
            manager = new MerchantManager(server, database);
            manager.load();
            LOGGER.info("MerchantLink enabled.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (manager != null) {
                manager.saveAllNow();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (manager != null) {
                manager.tick();
            }
        });

        MerchantCommands.register();
        ShopEvents.register();
        ChestLinkEvents.register();
    }

    public static MinecraftServer server() {
        return serverInstance;
    }

    public static RegistryWrapper.WrapperLookup registries() {
        return serverInstance.getRegistryManager();
    }

    public static MerchantManager manager() {
        return manager;
    }
}
