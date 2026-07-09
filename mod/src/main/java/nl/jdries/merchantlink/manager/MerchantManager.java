package nl.jdries.merchantlink.manager;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import nl.jdries.merchantlink.MerchantLinkMod;
import nl.jdries.merchantlink.data.LinkKind;
import nl.jdries.merchantlink.data.LinkedChest;
import nl.jdries.merchantlink.data.Listing;
import nl.jdries.merchantlink.data.MerchantProfile;
import nl.jdries.merchantlink.gui.ManageScreen;
import nl.jdries.merchantlink.gui.PriceEditorScreen;
import nl.jdries.merchantlink.gui.ReplaceScreen;
import nl.jdries.merchantlink.gui.ShopScreen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Core business logic for MerchantLink, ported from the Paper plugin's single
 * {@code MerchantLinkPlugin} class. Holds all owner profiles in memory and
 * mirrors changes to SQLite via {@link Database}.
 */
public final class MerchantManager {
    public static final String PREFIX_PLAIN = "[MerchantLink] ";
    private static final int MAX_SUPPLY_CHESTS = 2;
    private static final int SNAP_INTERVAL_TICKS = 20;

    private final MinecraftServer server;
    private final Database database;

    private final Map<UUID, MerchantProfile> profiles = new HashMap<>();
    private final Map<UUID, LinkKind> pendingLinks = new HashMap<>();
    private final Map<UUID, LinkedChest> pendingReplacements = new HashMap<>();

    private final ScheduledExecutorService saveExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "merchant-link-save");
            t.setDaemon(true);
            return t;
        });
    private final Map<UUID, ScheduledFuture<?>> pendingSaves = new HashMap<>();

    private int tickCounter = 0;

    public MerchantManager(MinecraftServer server, Database database) {
        this.server = server;
        this.database = database;
    }

    // ---------------------------------------------------------------- lifecycle

    public void load() {
        database.loadAll(profiles, this);
    }

    public void tick() {
        tickCounter++;
        if (tickCounter % SNAP_INTERVAL_TICKS == 0) {
            snapMerchantsToHome();
        }
    }

    public void saveAllNow() {
        for (ScheduledFuture<?> future : pendingSaves.values()) {
            future.cancel(false);
        }
        pendingSaves.clear();
        for (MerchantProfile profile : profiles.values()) {
            database.saveProfile(profile.copy());
        }
    }

    private void scheduleSave(MerchantProfile profile) {
        if (profile == null) {
            return;
        }
        ScheduledFuture<?> existing = pendingSaves.remove(profile.ownerId);
        if (existing != null) {
            existing.cancel(false);
        }
        MerchantProfile snapshot = profile.copy();
        ScheduledFuture<?> future = saveExecutor.schedule(() -> {
            database.saveProfile(snapshot);
            pendingSaves.remove(profile.ownerId);
        }, 500, TimeUnit.MILLISECONDS);
        pendingSaves.put(profile.ownerId, future);
    }

    public MerchantProfile getOrCreateProfile(UUID ownerId) {
        return profiles.computeIfAbsent(ownerId, MerchantProfile::new);
    }

    public MerchantProfile getProfile(UUID ownerId) {
        return profiles.get(ownerId);
    }

    // ---------------------------------------------------------------- commands

    public void spawnMerchant(ServerPlayerEntity player) {
        MerchantProfile profile = getOrCreateProfile(player.getUuid());
        if (getLiveMerchant(profile) != null) {
            message(player, "You already have a merchant.");
            return;
        }

        ServerWorld world = (ServerWorld) player.getEntityWorld();
        VillagerEntity villager = EntityType.VILLAGER.create(world, net.minecraft.entity.SpawnReason.COMMAND);
        if (villager == null) {
            message(player, "Failed to spawn merchant.");
            return;
        }
        villager.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0);
        villager.setAiDisabled(true);
        villager.setInvulnerable(true);
        villager.setNoGravity(true);
        villager.noClip = true;
        villager.setSilent(true);
        villager.setPersistent();
        villager.setCanPickUpLoot(false);
        villager.setCustomName(Text.literal(player.getGameProfile().name() + "'s Merchant"));
        villager.setCustomNameVisible(true);
        villager.setAttached(MerchantLinkMod.MERCHANT_OWNER, player.getUuid());
        world.spawnEntity(villager);

        profile.merchantUuid = villager.getUuid();
        profile.merchantWorldKey = worldKey(world);
        profile.merchantX = villager.getX();
        profile.merchantY = villager.getY();
        profile.merchantZ = villager.getZ();
        scheduleSave(profile);
        message(player, "Merchant spawned.");
    }

    public void removeMerchant(ServerPlayerEntity player) {
        MerchantProfile profile = getOrCreateProfile(player.getUuid());
        VillagerEntity merchant = getLiveMerchant(profile);
        if (merchant != null) {
            merchant.discard();
        }
        profile.merchantUuid = null;
        profile.merchantWorldKey = null;
        scheduleSave(profile);
        message(player, "Merchant removed.");
    }

    public void startLink(ServerPlayerEntity player, LinkKind kind) {
        pendingLinks.put(player.getUuid(), kind);
        pendingReplacements.remove(player.getUuid());
        message(player, "Left-click the chest to link as your " + kind.display + " chest.");
    }

    public void openManage(ServerPlayerEntity player) {
        MerchantProfile profile = getOrCreateProfile(player.getUuid());
        ManageScreen.open(player, this, profile);
    }

    // ---------------------------------------------------------------- chest linking

    public LinkKind pendingLinkFor(UUID playerId) {
        return pendingLinks.get(playerId);
    }

    /** Called from the block-attack (left-click) callback when a player has a pending link. */
    public void attemptCompleteLink(ServerPlayerEntity player, World world, BlockPos pos) {
        LinkKind kind = pendingLinks.remove(player.getUuid());
        if (kind == null) {
            return;
        }
        MerchantProfile profile = getOrCreateProfile(player.getUuid());

        BlockState state = world.getBlockState(pos);
        if (!isChestBlock(state)) {
            message(player, "You must left-click a chest to link it.");
            return;
        }
        String clickedWorldKey = worldKey(world);
        if (profile.hasMerchantHome() && !profile.merchantWorldKey.equals(clickedWorldKey)) {
            message(player, "Linked chests must be in the same world as your merchant.");
            return;
        }

        LinkedChestRef existing = findLinkedChestRef(clickedWorldKey, pos);
        if (existing != null) {
            if (existing.ownerId().equals(profile.ownerId)) {
                message(player, "That chest is already linked to your merchant.");
            } else {
                message(player, "That chest is already linked to another merchant.");
            }
            return;
        }

        LinkedChest linkedChest = new LinkedChest(clickedWorldKey, pos, kind);

        if (kind == LinkKind.RETURN) {
            profile.returnChest = linkedChest;
            scheduleSave(profile);
            message(player, "Return chest linked.");
            return;
        }

        Inventory inventory = getChestInventory(world, pos);
        if (inventory != null) {
            absorbListings(profile, inventory);
        }
        if (profile.supplyChests.size() < MAX_SUPPLY_CHESTS) {
            profile.supplyChests.add(linkedChest);
            scheduleSave(profile);
            message(player, "Supply chest linked.");
            return;
        }

        pendingReplacements.put(player.getUuid(), linkedChest);
        ReplaceScreen.open(player, this, profile);
        message(player, "Choose which supply chest to replace.");
    }

    public LinkedChest pendingReplacementFor(UUID playerId) {
        return pendingReplacements.get(playerId);
    }

    public void resolveReplace(ServerPlayerEntity player, MerchantProfile profile, int index) {
        LinkedChest replacement = pendingReplacements.remove(player.getUuid());
        if (replacement == null || index < 0 || index >= profile.supplyChests.size()) {
            return;
        }
        profile.supplyChests.set(index, replacement);
        Inventory inv = findWorld(replacement.worldKey) == null ? null : getChestInventory(findWorld(replacement.worldKey), replacement.pos);
        if (inv != null) {
            absorbListings(profile, inv);
        }
        scheduleSave(profile);
        player.closeHandledScreen();
        message(player, "Supply chest replaced.");
    }

    /**
     * Called from the block-break-before callback. Returns {@code true} to allow the break.
     * If the breaking player is the chest's owner, the chest is unlinked as a side effect.
     */
    public boolean handleChestBreakAttempt(ServerPlayerEntity player, World world, BlockPos pos) {
        LinkedChestRef ref = findLinkedChestRef(worldKey(world), pos);
        if (ref == null) {
            return true;
        }
        if (!ref.ownerId().equals(player.getUuid())) {
            message(player, "No permission.");
            return false;
        }
        if (ref.kind() == LinkKind.SUPPLY) {
            ref.profile().supplyChests.remove(ref.chest());
        } else {
            ref.profile().returnChest = null;
        }
        scheduleSave(ref.profile());
        message(player, ref.kind().display + " chest unlinked.");
        return true;
    }

    public LinkedChestRef findLinkedChestRef(String worldKey, BlockPos pos) {
        for (MerchantProfile profile : profiles.values()) {
            for (LinkedChest chest : profile.supplyChests) {
                if (chest.sameBlock(worldKey, pos)) {
                    return new LinkedChestRef(profile.ownerId, profile, chest, LinkKind.SUPPLY);
                }
            }
            if (profile.returnChest != null && profile.returnChest.sameBlock(worldKey, pos)) {
                return new LinkedChestRef(profile.ownerId, profile, profile.returnChest, LinkKind.RETURN);
            }
        }
        return null;
    }

    public boolean isProtectedChest(String worldKey, BlockPos pos) {
        return findLinkedChestRef(worldKey, pos) != null;
    }

    public void unlinkSupply(MerchantProfile profile, int index) {
        if (index >= 0 && index < profile.supplyChests.size()) {
            profile.supplyChests.remove(index);
            scheduleSave(profile);
        }
    }

    public void unlinkReturn(MerchantProfile profile) {
        profile.returnChest = null;
        scheduleSave(profile);
    }

    // ---------------------------------------------------------------- listings

    private void absorbListings(MerchantProfile profile, Inventory inventory) {
        RegistryWrapper.WrapperLookup registries = MerchantLinkMod.registries();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack single = stack.copyWithCount(1);
            String key = ItemCodecs.itemKey(registries, single);
            profile.listings.computeIfAbsent(key, ignored -> new Listing(key, single, null));
        }
    }

    /** Re-scans linked supply chests for any new item types (called on shop-open / manage-open). */
    public void refreshListingsFromChests(MerchantProfile profile) {
        for (LinkedChest chest : profile.supplyChests) {
            World world = findWorld(chest.worldKey);
            if (world == null) {
                continue;
            }
            Inventory inv = getChestInventory(world, chest.pos);
            if (inv != null) {
                absorbListings(profile, inv);
            }
        }
    }

    public void removeListing(MerchantProfile profile, String itemKey) {
        profile.listings.remove(itemKey);
        scheduleSave(profile);
    }

    public void setListingCost(MerchantProfile profile, String itemKey, ItemStack cost) {
        Listing listing = profile.listings.get(itemKey);
        if (listing != null) {
            listing.cost = cost;
            scheduleSave(profile);
        }
    }

    public int countStock(MerchantProfile profile, ItemStack product) {
        int total = 0;
        for (LinkedChest chest : profile.supplyChests) {
            World world = findWorld(chest.worldKey);
            if (world == null) {
                continue;
            }
            Inventory inv = getChestInventory(world, chest.pos);
            if (inv == null) {
                continue;
            }
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack != null && !stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, product)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    public List<ShopEntry> buildShopEntries(MerchantProfile profile) {
        boolean returnAvailable = profile.returnChest != null;
        List<ShopEntry> entries = new ArrayList<>();
        profile.listings.values().stream()
            .filter(listing -> listing.cost != null)
            .sorted(Comparator.comparing(listing -> niceName(listing.product)))
            .forEach(listing -> entries.add(new ShopEntry(
                listing.itemKey,
                listing.product.copy(),
                listing.cost.copy(),
                countStock(profile, listing.product),
                returnAvailable
            )));
        return entries;
    }

    public List<ManageEntry> buildManageEntries(MerchantProfile profile) {
        List<ManageEntry> entries = new ArrayList<>();
        profile.listings.values().stream()
            .sorted(Comparator.comparing(listing -> niceName(listing.product)))
            .forEach(listing -> entries.add(new ManageEntry(listing, countStock(profile, listing.product))));
        return entries;
    }

    // ---------------------------------------------------------------- shop / trading

    public void openShop(ServerPlayerEntity customer, MerchantProfile profile) {
        refreshListingsFromChests(profile);
        ShopScreen.open(customer, this, profile);
    }

    public void openPriceEditor(ServerPlayerEntity player, MerchantProfile profile, String itemKey) {
        Listing listing = profile.listings.get(itemKey);
        if (listing == null) {
            return;
        }
        PriceEditorScreen.open(player, this, profile, listing);
    }

    /** Returns an error message on failure, or {@code null} on success. */
    public String performPurchase(ServerPlayerEntity buyer, MerchantProfile profile, ShopEntry entry, int quantity) {
        if (profile.returnChest == null) {
            return "This merchant has no return chest configured.";
        }
        World returnWorld = findWorld(profile.returnChest.worldKey);
        if (returnWorld == null) {
            return "This merchant's return chest world is not loaded.";
        }
        Inventory returnInventory = getChestInventory(returnWorld, profile.returnChest.pos);
        if (returnInventory == null) {
            return "This merchant's return chest is missing.";
        }

        Listing listing = profile.listings.get(entry.itemKey());
        if (listing == null || listing.cost == null) {
            return "This listing has no barter price set.";
        }

        int available = countStock(profile, listing.product);
        if (available < quantity) {
            return "Insufficient stock for bulk purchase (" + available + "/" + quantity + " available).";
        }

        List<LinkedChest> supplyChests = profile.supplyChests;
        List<Inventory> supplyInventories = new ArrayList<>();
        List<World> supplyWorlds = new ArrayList<>();
        for (LinkedChest chest : supplyChests) {
            World w = findWorld(chest.worldKey);
            Inventory inv = w == null ? null : getChestInventory(w, chest.pos);
            if (inv == null) {
                return "One of this merchant's supply chests is missing.";
            }
            supplyWorlds.add(w);
            supplyInventories.add(inv);
        }

        ItemStack[] buyerContents = buyerStorageContents(buyer);
        InventorySimulation buyerSim = InventorySimulation.copyOf(buyerContents);
        InventorySimulation returnSim = InventorySimulation.copyOf(toArray(returnInventory));
        List<InventorySimulation> supplySims = new ArrayList<>();
        for (Inventory inv : supplyInventories) {
            supplySims.add(InventorySimulation.copyOf(toArray(inv)));
        }

        ItemStack totalCost = InventorySimulation.multiplied(listing.cost, quantity);
        if (totalCost == null) {
            return "Invalid barter stack.";
        }
        if (!returnSim.canFit(totalCost)) {
            return "Return chest cannot hold payment for that bulk purchase.";
        }
        if (!buyerSim.canRemove(totalCost)) {
            return "You do not have the required barter items.";
        }

        int removed = 0;
        for (InventorySimulation supply : supplySims) {
            removed += supply.removeMatching(listing.product, quantity - removed);
            if (removed >= quantity) {
                break;
            }
        }
        if (removed < quantity) {
            return "Insufficient stock for bulk purchase (" + removed + "/" + quantity + " available).";
        }

        ItemStack totalProduct = InventorySimulation.multiplied(listing.product, quantity);
        if (totalProduct == null) {
            return "Invalid product stack.";
        }
        if (!buyerSim.canFit(totalProduct)) {
            return "You do not have enough inventory space.";
        }
        if (!returnSim.add(totalCost)) {
            return "Return chest cannot hold payment for that bulk purchase.";
        }
        if (!buyerSim.remove(totalCost)) {
            return "You do not have the required barter items.";
        }
        if (!buyerSim.add(totalProduct)) {
            return "You do not have enough inventory space.";
        }

        // All simulations succeeded — apply for real.
        applyStorageContents(buyer, buyerSim.contents());
        for (int i = 0; i < supplyInventories.size(); i++) {
            applyInventoryContents(supplyInventories.get(i), supplySims.get(i).contents());
        }
        applyInventoryContents(returnInventory, returnSim.contents());

        scheduleSave(profile);
        return null;
    }

    // ---------------------------------------------------------------- entities / merchant lookup

    public UUID getMerchantOwner(Entity entity) {
        if (entity.getType() != EntityType.VILLAGER) {
            return null;
        }
        return entity.getAttached(MerchantLinkMod.MERCHANT_OWNER);
    }

    public boolean isManagedMerchant(Entity entity) {
        return getMerchantOwner(entity) != null;
    }

    public VillagerEntity getLiveMerchant(MerchantProfile profile) {
        if (profile.merchantUuid == null) {
            return null;
        }
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(profile.merchantUuid);
            if (entity instanceof VillagerEntity villager && villager.isAlive()) {
                return villager;
            }
        }
        return null;
    }

    private void snapMerchantsToHome() {
        for (MerchantProfile profile : profiles.values()) {
            if (!profile.hasMerchantHome()) {
                continue;
            }
            VillagerEntity merchant = getLiveMerchant(profile);
            if (merchant == null) {
                continue;
            }
            double dx = merchant.getX() - profile.merchantX;
            double dy = merchant.getY() - profile.merchantY;
            double dz = merchant.getZ() - profile.merchantZ;
            if (dx * dx + dy * dy + dz * dz > 0.01) {
                merchant.refreshPositionAndAngles(profile.merchantX, profile.merchantY, profile.merchantZ, merchant.getYaw(), merchant.getPitch());
                merchant.setVelocity(Vec3d.ZERO);
            }
        }
    }

    public String merchantTitle(MerchantProfile profile) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(profile.ownerId);
        String name = owner != null ? owner.getGameProfile().name() : null;
        return (name == null ? "Merchant" : name) + "'s Merchant";
    }

    // ---------------------------------------------------------------- helpers

    public Inventory getChestInventory(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!isChestBlock(state)) {
            return null;
        }
        return ChestBlock.getInventory((ChestBlock) state.getBlock(), state, world, pos, true);
    }

    private boolean isChestBlock(BlockState state) {
        return state.getBlock() instanceof ChestBlock;
    }

    private ItemStack[] toArray(Inventory inventory) {
        ItemStack[] array = new ItemStack[inventory.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = inventory.getStack(i);
        }
        return array;
    }

    private void applyInventoryContents(Inventory inventory, ItemStack[] contents) {
        for (int i = 0; i < inventory.size() && i < contents.length; i++) {
            inventory.setStack(i, contents[i] == null ? ItemStack.EMPTY : contents[i]);
        }
        inventory.markDirty();
    }

    private ItemStack[] buyerStorageContents(ServerPlayerEntity player) {
        var main = player.getInventory().getMainStacks();
        ItemStack[] array = new ItemStack[main.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = main.get(i);
        }
        return array;
    }

    private void applyStorageContents(ServerPlayerEntity player, ItemStack[] contents) {
        var main = player.getInventory().getMainStacks();
        for (int i = 0; i < main.size() && i < contents.length; i++) {
            main.set(i, contents[i] == null ? ItemStack.EMPTY : contents[i]);
        }
        player.currentScreenHandler.sendContentUpdates();
        player.getInventory().markDirty();
    }

    public World findWorld(String worldKey) {
        if (worldKey == null) {
            return null;
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldKey));
        return server.getWorld(key);
    }

    public String worldKey(World world) {
        return world.getRegistryKey().getValue().toString();
    }

    public String niceName(ItemStack stack) {
        Identifier id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
        return id.getPath().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public String describeStack(ItemStack stack) {
        return stack.getCount() + "x " + niceName(stack);
    }

    public void message(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(PREFIX_PLAIN + message).formatted(Formatting.GOLD));
    }

    public MinecraftServer server() {
        return server;
    }

    // ---------------------------------------------------------------- view records

    public record LinkedChestRef(UUID ownerId, MerchantProfile profile, LinkedChest chest, LinkKind kind) {}

    public record ShopEntry(String itemKey, ItemStack product, ItemStack cost, int stock, boolean returnAvailable) {}

    public record ManageEntry(Listing listing, int stock) {}
}
