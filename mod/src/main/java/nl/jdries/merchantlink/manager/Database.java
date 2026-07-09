package nl.jdries.merchantlink.manager;

import net.minecraft.registry.RegistryWrapper;
import nl.jdries.merchantlink.MerchantLinkMod;
import nl.jdries.merchantlink.data.LinkKind;
import nl.jdries.merchantlink.data.LinkedChest;
import nl.jdries.merchantlink.data.Listing;
import nl.jdries.merchantlink.data.MerchantProfile;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite persistence layer. Same rough schema as the Paper plugin's
 * {@code merchantlink.db}, minus the chest {@code contents_blob} column —
 * this mod reads linked-chest contents live off the block entity instead of
 * caching a copy (see {@link LinkedChest}), so there is nothing to persist
 * there beyond the chest's location.
 *
 * <p>Unlike the Paper plugin, the sqlite-jdbc driver is bundled directly into
 * this mod's jar (see {@code build.gradle}'s {@code include implementation(...)}),
 * so no separate driver needs to be present on the server's classpath.
 */
public final class Database {
    private final File databaseFile;

    public Database(File databaseFile) {
        this.databaseFile = databaseFile;
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not found on classpath", e);
        }
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create MerchantLink data folder: " + parent);
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode=WAL");
            statement.executeUpdate("PRAGMA synchronous=NORMAL");
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS merchant_profiles (" +
                    "owner_uuid TEXT PRIMARY KEY," +
                    "merchant_uuid TEXT," +
                    "merchant_world TEXT," +
                    "merchant_x REAL," +
                    "merchant_y REAL," +
                    "merchant_z REAL" +
                ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS linked_chests (" +
                    "owner_uuid TEXT NOT NULL," +
                    "kind TEXT NOT NULL," +
                    "slot_index INTEGER NOT NULL," +
                    "world TEXT NOT NULL," +
                    "x INTEGER NOT NULL," +
                    "y INTEGER NOT NULL," +
                    "z INTEGER NOT NULL," +
                    "PRIMARY KEY(owner_uuid, kind, slot_index)" +
                ")"
            );
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS listings (" +
                    "owner_uuid TEXT NOT NULL," +
                    "item_key TEXT NOT NULL," +
                    "product_blob TEXT NOT NULL," +
                    "cost_blob TEXT," +
                    "PRIMARY KEY(owner_uuid, item_key)" +
                ")"
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize MerchantLink database", e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    public void loadAll(Map<UUID, MerchantProfile> target, MerchantManager manager) {
        target.clear();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid, merchant_uuid, merchant_world, merchant_x, merchant_y, merchant_z FROM merchant_profiles"
            ); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID ownerId = UUID.fromString(rs.getString("owner_uuid"));
                    MerchantProfile profile = manager.getOrCreateProfile(ownerId);
                    String merchantUuid = rs.getString("merchant_uuid");
                    profile.merchantUuid = merchantUuid == null ? null : UUID.fromString(merchantUuid);
                    profile.merchantWorldKey = rs.getString("merchant_world");
                    profile.merchantX = rs.getDouble("merchant_x");
                    profile.merchantY = rs.getDouble("merchant_y");
                    profile.merchantZ = rs.getDouble("merchant_z");
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid, kind, slot_index, world, x, y, z FROM linked_chests ORDER BY slot_index ASC"
            ); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    MerchantProfile profile = manager.getOrCreateProfile(UUID.fromString(rs.getString("owner_uuid")));
                    LinkedChest chest = new LinkedChest(
                        rs.getString("world"),
                        new net.minecraft.util.math.BlockPos(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                        LinkKind.valueOf(rs.getString("kind"))
                    );
                    if (chest.kind == LinkKind.SUPPLY) {
                        profile.supplyChests.add(chest);
                    } else {
                        profile.returnChest = chest;
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid, item_key, product_blob, cost_blob FROM listings"
            ); ResultSet rs = statement.executeQuery()) {
                RegistryWrapper.WrapperLookup registries = MerchantLinkMod.registries();
                while (rs.next()) {
                    MerchantProfile profile = manager.getOrCreateProfile(UUID.fromString(rs.getString("owner_uuid")));
                    var product = ItemCodecs.deserialize(registries, rs.getString("product_blob"));
                    if (product.isEmpty()) {
                        continue;
                    }
                    String costBlob = rs.getString("cost_blob");
                    var cost = costBlob == null ? null : ItemCodecs.deserialize(registries, costBlob);
                    String itemKey = rs.getString("item_key");
                    profile.listings.put(itemKey, new Listing(itemKey, product, cost != null && !cost.isEmpty() ? cost : null));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load MerchantLink data", e);
        }
    }

    public void saveProfile(MerchantProfile profile) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (
                PreparedStatement deleteProfile = connection.prepareStatement("DELETE FROM merchant_profiles WHERE owner_uuid = ?");
                PreparedStatement deleteChests = connection.prepareStatement("DELETE FROM linked_chests WHERE owner_uuid = ?");
                PreparedStatement deleteListings = connection.prepareStatement("DELETE FROM listings WHERE owner_uuid = ?")
            ) {
                deleteProfile.setString(1, profile.ownerId.toString());
                deleteProfile.executeUpdate();
                deleteChests.setString(1, profile.ownerId.toString());
                deleteChests.executeUpdate();
                deleteListings.setString(1, profile.ownerId.toString());
                deleteListings.executeUpdate();
            }

            try (PreparedStatement insertProfile = connection.prepareStatement(
                "INSERT INTO merchant_profiles(owner_uuid, merchant_uuid, merchant_world, merchant_x, merchant_y, merchant_z) VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                insertProfile.setString(1, profile.ownerId.toString());
                insertProfile.setString(2, profile.merchantUuid == null ? null : profile.merchantUuid.toString());
                insertProfile.setString(3, profile.merchantWorldKey);
                insertProfile.setDouble(4, profile.merchantX);
                insertProfile.setDouble(5, profile.merchantY);
                insertProfile.setDouble(6, profile.merchantZ);
                insertProfile.executeUpdate();
            }

            try (PreparedStatement insertChest = connection.prepareStatement(
                "INSERT INTO linked_chests(owner_uuid, kind, slot_index, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?, ?)"
            )) {
                for (int i = 0; i < profile.supplyChests.size(); i++) {
                    writeChestRow(insertChest, profile.ownerId, LinkKind.SUPPLY, i, profile.supplyChests.get(i));
                }
                if (profile.returnChest != null) {
                    writeChestRow(insertChest, profile.ownerId, LinkKind.RETURN, 0, profile.returnChest);
                }
                insertChest.executeBatch();
            }

            try (PreparedStatement insertListing = connection.prepareStatement(
                "INSERT INTO listings(owner_uuid, item_key, product_blob, cost_blob) VALUES (?, ?, ?, ?)"
            )) {
                RegistryWrapper.WrapperLookup registries = MerchantLinkMod.registries();
                for (Listing listing : profile.listings.values()) {
                    insertListing.setString(1, profile.ownerId.toString());
                    insertListing.setString(2, listing.itemKey);
                    insertListing.setString(3, ItemCodecs.serialize(registries, listing.product));
                    insertListing.setString(4, listing.cost == null ? null : ItemCodecs.serialize(registries, listing.cost));
                    insertListing.addBatch();
                }
                insertListing.executeBatch();
            }

            connection.commit();
        } catch (SQLException e) {
            MerchantLinkMod.LOGGER.warn("Failed to save MerchantLink data for {}: {}", profile.ownerId, e.getMessage());
        }
    }

    private void writeChestRow(PreparedStatement statement, UUID ownerId, LinkKind kind, int slotIndex, LinkedChest chest) throws SQLException {
        statement.setString(1, ownerId.toString());
        statement.setString(2, kind.name());
        statement.setInt(3, slotIndex);
        statement.setString(4, chest.worldKey);
        statement.setInt(5, chest.pos.getX());
        statement.setInt(6, chest.pos.getY());
        statement.setInt(7, chest.pos.getZ());
        statement.addBatch();
    }
}
