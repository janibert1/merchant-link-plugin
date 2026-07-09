package nl.jdries.merchantlink;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MerchantLinkPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[MerchantLink] " + ChatColor.RESET;
    private static final int MAX_SUPPLY_CHESTS = 2;
    private static final int SHOP_SIZE = 54;
    private static final int SHOP_SLOTS = 45;
    private static final int MANAGE_SIZE = 54;
    private static final int PRICE_EDITOR_SIZE = 27;
    private static final int SNAP_TICKS = 20;
    private static final long SAVE_DEBOUNCE_TICKS = 10L;

    private final Map<UUID, MerchantProfile> profiles = new HashMap<>();
    private final Map<UUID, PendingLink> pendingLinks = new HashMap<>();
    private final Map<UUID, PendingReplacement> pendingReplacements = new HashMap<>();
    private final Map<UUID, ShopContext> openShopContexts = new HashMap<>();
    private final Map<UUID, ManageContext> openManageContexts = new HashMap<>();
    private final Map<UUID, PriceEditorContext> openPriceEditors = new HashMap<>();
    private final Map<UUID, BukkitTask> pendingSaveTasks = new HashMap<>();

    private NamespacedKey merchantOwnerKey;
    private File databaseFile;
    private BukkitTask merchantSnapTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        merchantOwnerKey = new NamespacedKey(this, "merchant-owner");
        databaseFile = new File(getDataFolder(), "merchantlink.db");

        initDatabase();
        loadProfiles();

        Objects.requireNonNull(getCommand("ml")).setExecutor(this);
        Objects.requireNonNull(getCommand("ml")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        merchantSnapTask = Bukkit.getScheduler().runTaskTimer(this, this::snapMerchantsToHome, SNAP_TICKS, SNAP_TICKS);
        getLogger().info("MerchantLink enabled.");
    }

    @Override
    public void onDisable() {
        if (merchantSnapTask != null) {
            merchantSnapTask.cancel();
        }
        for (BukkitTask task : pendingSaveTasks.values()) {
            task.cancel();
        }
        pendingSaveTasks.clear();
        saveAllProfilesNow();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(PREFIX + "/ml spawn, /ml remove, /ml link supply, /ml link return, /ml manage");
            return true;
        }

        MerchantProfile profile = getOrCreateProfile(player.getUniqueId());
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> handleSpawn(player, profile);
            case "remove", "despawn" -> handleRemove(player, profile);
            case "link" -> handleLink(player, profile, args);
            case "manage" -> handleManage(player, profile);
            default -> {
                player.sendMessage(PREFIX + "Unknown subcommand.");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(List.of("spawn", "remove", "despawn", "link", "manage"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("link")) {
            return filterPrefix(List.of("supply", "return"), args[1]);
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMerchantInteract(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }

        UUID ownerId = getMerchantOwner(villager);
        if (ownerId == null) {
            return;
        }

        event.setCancelled(true);
        MerchantProfile profile = profiles.get(ownerId);
        if (profile == null) {
            event.getPlayer().sendMessage(PREFIX + "This merchant is not configured.");
            return;
        }

        refreshLinkedChests(profile);
        for (LinkedChest chest : profile.supplyChests) {
            absorbListings(profile, chest.contents);
        }
        openShop(event.getPlayer(), profile);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestLinkClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        PendingLink pending = pendingLinks.get(event.getPlayer().getUniqueId());
        if (pending == null || event.getClickedBlock() == null) {
            return;
        }

        event.setCancelled(true);
        MerchantProfile profile = getOrCreateProfile(event.getPlayer().getUniqueId());
        completeLink(event.getPlayer(), profile, pending.kind, event.getClickedBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLinkedChestOpen(InventoryOpenEvent event) {
        Location chestLocation = getInventoryChestLocation(event.getInventory());
        if (chestLocation == null) {
            return;
        }

        LinkedChestRef ref = findLinkedChest(chestLocation);
        if (ref == null) {
            return;
        }

        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!ref.ownerId.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(PREFIX + "No permission.");
            return;
        }

        syncVirtualToPhysical(ref.chest);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLinkedChestClose(InventoryCloseEvent event) {
        Location chestLocation = getInventoryChestLocation(event.getInventory());
        if (chestLocation == null) {
            return;
        }

        LinkedChestRef ref = findLinkedChest(chestLocation);
        if (ref == null) {
            return;
        }

        syncPhysicalToVirtual(ref.chest, event.getInventory());
        scheduleProfileSave(ref.profile);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLinkedChestBreak(BlockBreakEvent event) {
        LinkedChestRef ref = findLinkedChest(event.getBlock().getLocation());
        if (ref == null) {
            return;
        }

        if (!ref.ownerId.equals(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PREFIX + "No permission.");
            return;
        }

        syncVirtualToPhysical(ref.chest);
        if (ref.kind == LinkKind.SUPPLY) {
            ref.profile.supplyChests.remove(ref.chest);
        } else {
            ref.profile.returnChest = null;
        }
        scheduleProfileSave(ref.profile);
        event.getPlayer().sendMessage(PREFIX + ref.kind.display + " chest unlinked.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (holder instanceof ShopHolder) {
            event.setCancelled(true);
            handleShopClick(player, event.getRawSlot(), event.getClick());
            return;
        }
        if (holder instanceof ManageHolder) {
            event.setCancelled(true);
            handleManageClick(player, event.getRawSlot(), event.getClick());
            return;
        }
        if (holder instanceof ReplaceHolder) {
            event.setCancelled(true);
            handleReplaceClick(player, event.getRawSlot());
            return;
        }
        if (holder instanceof PriceEditorHolder) {
            handlePriceEditorClick(event, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryCloseCleanup(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (holder instanceof ShopHolder) {
            flushProfileSave(profiles.get(player.getUniqueId()));
            openShopContexts.remove(player.getUniqueId());
        } else if (holder instanceof ManageHolder) {
            flushProfileSave(profiles.get(player.getUniqueId()));
            openManageContexts.remove(player.getUniqueId());
        } else if (holder instanceof ReplaceHolder) {
            pendingReplacements.remove(player.getUniqueId());
        } else if (holder instanceof PriceEditorHolder) {
            if (openPriceEditors.remove(player.getUniqueId()) != null) {
                ItemStack inCostSlot = event.getInventory().getItem(13);
                if (inCostSlot != null && inCostSlot.getType() != Material.AIR) {
                    player.getInventory().addItem(inCostSlot.clone()).values()
                        .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isProtectedLinkedChest(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isProtectedLinkedChest(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (isProtectedLinkedChest(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (isProtectedLinkedChest(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantDamage(EntityDamageEvent event) {
        if (isManagedMerchant(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantCombust(EntityCombustEvent event) {
        if (isManagedMerchant(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private MerchantProfile getOrCreateProfile(UUID ownerId) {
        return profiles.computeIfAbsent(ownerId, MerchantProfile::new);
    }

    private boolean handleSpawn(Player player, MerchantProfile profile) {
        if (getLiveMerchant(profile) != null) {
            player.sendMessage(PREFIX + "You already have a merchant.");
            return true;
        }

        Villager villager = player.getWorld().spawn(player.getLocation(), Villager.class, spawned -> {
            spawned.setAI(false);
            spawned.setInvulnerable(true);
            spawned.setGravity(false);
            spawned.setCollidable(false);
            spawned.setCanPickupItems(false);
            spawned.setSilent(true);
            spawned.setRemoveWhenFarAway(false);
            spawned.setVelocity(new Vector(0, 0, 0));
            spawned.setProfession(Villager.Profession.LIBRARIAN);
            spawned.customName(Component.text(player.getName() + "'s Merchant"));
            spawned.setCustomNameVisible(true);
            spawned.getPersistentDataContainer().set(merchantOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        });

        profile.merchantUuid = villager.getUniqueId();
        profile.merchantLocation = villager.getLocation().clone();
        scheduleProfileSave(profile);
        player.sendMessage(PREFIX + "Merchant spawned.");
        return true;
    }

    private boolean handleRemove(Player player, MerchantProfile profile) {
        Villager merchant = getLiveMerchant(profile);
        if (merchant != null) {
            merchant.remove();
        }
        profile.merchantUuid = null;
        profile.merchantLocation = null;
        scheduleProfileSave(profile);
        player.sendMessage(PREFIX + "Merchant removed.");
        return true;
    }

    private boolean handleLink(Player player, MerchantProfile profile, String[] args) {
        if (args.length < 2) {
            player.sendMessage(PREFIX + "Usage: /ml link supply or /ml link return");
            return true;
        }

        LinkKind kind = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "supply" -> LinkKind.SUPPLY;
            case "return" -> LinkKind.RETURN;
            default -> null;
        };
        if (kind == null) {
            player.sendMessage(PREFIX + "Unknown link type.");
            return true;
        }

        pendingLinks.put(player.getUniqueId(), new PendingLink(kind));
        pendingReplacements.remove(player.getUniqueId());
        player.sendMessage(PREFIX + "Left-click the chest to link as your " + kind.display + " chest.");
        return true;
    }

    private boolean handleManage(Player player, MerchantProfile profile) {
        refreshLinkedChests(profile);
        Inventory inventory = Bukkit.createInventory(new ManageHolder(profile.ownerId), MANAGE_SIZE, "MerchantLink Manage");
        ManageContext context = buildManageContext(profile);
        openManageContexts.put(player.getUniqueId(), context);
        fillManageInventory(inventory, profile, context);
        player.openInventory(inventory);
        return true;
    }

    private void completeLink(Player player, MerchantProfile profile, LinkKind kind, Location location) {
        pendingLinks.remove(player.getUniqueId());

        if (location == null || !isChestBlock(location.getBlock().getType())) {
            player.sendMessage(PREFIX + "You must right-click a chest to link it.");
            return;
        }
        if (!sameWorldOrMissingMerchant(profile, location)) {
            player.sendMessage(PREFIX + "Linked chests must be in the same world as your merchant.");
            return;
        }

        LinkedChestRef current = findLinkedChest(location);
        if (current != null) {
            if (current.ownerId.equals(profile.ownerId)) {
                player.sendMessage(PREFIX + "That chest is already linked to your merchant.");
            } else {
                player.sendMessage(PREFIX + "That chest is already linked to another merchant.");
            }
            return;
        }

        refreshLinkedChests(profile);
        LinkedChest linkedChest = createLinkedChest(location, kind);
        if (linkedChest == null) {
            player.sendMessage(PREFIX + "Failed to link that chest.");
            return;
        }

        if (kind == LinkKind.RETURN) {
            profile.returnChest = linkedChest;
            scheduleProfileSave(profile);
            player.sendMessage(PREFIX + "Return chest linked.");
            return;
        }

        absorbListings(profile, linkedChest.contents);
        if (profile.supplyChests.size() < MAX_SUPPLY_CHESTS) {
            profile.supplyChests.add(linkedChest);
            scheduleProfileSave(profile);
            player.sendMessage(PREFIX + "Supply chest linked.");
            return;
        }

        pendingReplacements.put(player.getUniqueId(), new PendingReplacement(linkedChest));
        openReplaceInventory(player, profile);
    }

    private void openReplaceInventory(Player player, MerchantProfile profile) {
        Inventory inventory = Bukkit.createInventory(new ReplaceHolder(profile.ownerId), 27, "Replace Supply Link");
        for (int i = 0; i < profile.supplyChests.size(); i++) {
            inventory.setItem(11 + (i * 4), linkedChestIcon(profile.supplyChests.get(i), "Click to replace"));
        }
        player.openInventory(inventory);
        player.sendMessage(PREFIX + "Choose which supply chest to replace.");
    }

    private void openShop(Player customer, MerchantProfile profile) {
        Inventory inventory = Bukkit.createInventory(new ShopHolder(profile.ownerId), SHOP_SIZE, merchantTitle(profile));
        ShopContext context = buildShopContext(profile);
        openShopContexts.put(customer.getUniqueId(), context);
        fillShopInventory(inventory, context);
        customer.openInventory(inventory);
    }

    private void openPriceEditor(Player player, MerchantProfile profile, Listing listing) {
        Inventory inventory = Bukkit.createInventory(new PriceEditorHolder(profile.ownerId), PRICE_EDITOR_SIZE, "Set Barter Price");
        inventory.setItem(11, productPreviewItem(listing));
        inventory.setItem(15, namedItem(Material.LIME_WOOL, ChatColor.GREEN + "Save Listing", List.of(
            ChatColor.GRAY + "Place the cost stack in the center slot."
        )));
        inventory.setItem(22, namedItem(Material.BARRIER, ChatColor.RED + "Cancel", List.of(
            ChatColor.GRAY + "Close without changes."
        )));
        openPriceEditors.put(player.getUniqueId(), new PriceEditorContext(profile.ownerId, listing.itemKey));
        player.openInventory(inventory);
    }

    private ManageContext buildManageContext(MerchantProfile profile) {
        List<ManageEntry> entries = new ArrayList<>();
        profile.listings.values().stream()
            .sorted(Comparator.comparing(entry -> niceName(entry.product.getType())))
            .forEach(entry -> entries.add(new ManageEntry(entry, countStockForProduct(profile, entry.product))));
        return new ManageContext(profile.ownerId, entries);
    }

    private ShopContext buildShopContext(MerchantProfile profile) {
        boolean returnAvailable = profile.returnChest != null;
        List<ShopListing> listings = new ArrayList<>();
        profile.listings.values().stream()
            .filter(entry -> entry.cost != null)
            .sorted(Comparator.comparing(entry -> niceName(entry.product.getType())))
            .forEach(entry -> listings.add(new ShopListing(
                entry.itemKey,
                entry.product.clone(),
                entry.cost == null ? null : entry.cost.clone(),
                countStockForProduct(profile, entry.product),
                returnAvailable
            )));
        return new ShopContext(profile.ownerId, listings, returnAvailable);
    }

    private void fillManageInventory(Inventory inventory, MerchantProfile profile, ManageContext context) {
        inventory.clear();
        inventory.setItem(10, profile.supplyChests.size() > 0
            ? linkedChestIcon(profile.supplyChests.get(0), "Click to unlink")
            : namedItem(Material.CHEST, ChatColor.DARK_GRAY + "Supply Chest 1", List.of(ChatColor.GRAY + "Not linked")));
        inventory.setItem(12, profile.supplyChests.size() > 1
            ? linkedChestIcon(profile.supplyChests.get(1), "Click to unlink")
            : namedItem(Material.CHEST, ChatColor.DARK_GRAY + "Supply Chest 2", List.of(ChatColor.GRAY + "Not linked")));
        inventory.setItem(14, profile.returnChest != null
            ? linkedChestIcon(profile.returnChest, "Click to unlink")
            : namedItem(Material.ENDER_CHEST, ChatColor.DARK_GRAY + "Return Chest", List.of(ChatColor.GRAY + "Not linked")));
        inventory.setItem(16, namedItem(Material.VILLAGER_SPAWN_EGG, ChatColor.RED + "Remove Merchant", List.of(
            profile.merchantLocation == null ? ChatColor.GRAY + "No merchant spawned" : formatLocation(profile.merchantLocation)
        )));
        inventory.setItem(22, namedItem(Material.BOOK, ChatColor.AQUA + "Listing Controls", List.of(
            ChatColor.GRAY + "Left click a listing: set barter price",
            ChatColor.GRAY + "Drop key on a listing: delete listing",
            ChatColor.GRAY + "Listings stay visible while out of stock"
        )));

        for (int i = 0; i < Math.min(context.entries.size(), 27); i++) {
            inventory.setItem(27 + i, manageListingItem(context.entries.get(i)));
        }
    }

    private void fillShopInventory(Inventory inventory, ShopContext context) {
        inventory.clear();
        if (context.listings.isEmpty()) {
            inventory.setItem(22, namedItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "No Listings", List.of(
                ChatColor.GRAY + "This merchant has nothing listed yet."
            )));
            return;
        }

        for (int i = 0; i < Math.min(context.listings.size(), SHOP_SLOTS); i++) {
            inventory.setItem(i, shopItem(context.listings.get(i)));
        }
    }

    private void handleManageClick(Player player, int rawSlot, ClickType click) {
        ManageContext context = openManageContexts.get(player.getUniqueId());
        if (context == null) {
            return;
        }
        MerchantProfile profile = profiles.get(context.ownerId);
        if (profile == null || !profile.ownerId.equals(player.getUniqueId())) {
            return;
        }

        if (rawSlot == 10 && profile.supplyChests.size() > 0) {
            profile.supplyChests.remove(0);
            scheduleProfileSave(profile);
            handleManage(player, profile);
            return;
        }
        if (rawSlot == 12 && profile.supplyChests.size() > 1) {
            profile.supplyChests.remove(1);
            scheduleProfileSave(profile);
            handleManage(player, profile);
            return;
        }
        if (rawSlot == 14 && profile.returnChest != null) {
            profile.returnChest = null;
            scheduleProfileSave(profile);
            handleManage(player, profile);
            return;
        }
        if (rawSlot == 16) {
            handleRemove(player, profile);
            player.closeInventory();
            return;
        }
        if (rawSlot < 27 || rawSlot >= MANAGE_SIZE) {
            return;
        }

        int index = rawSlot - 27;
        if (index >= context.entries.size()) {
            return;
        }

        Listing listing = context.entries.get(index).listing;
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            profile.listings.remove(listing.itemKey);
            scheduleProfileSave(profile);
            handleManage(player, profile);
            player.sendMessage(PREFIX + "Listing removed.");
            return;
        }

        openPriceEditor(player, profile, listing);
    }

    private void handleReplaceClick(Player player, int rawSlot) {
        PendingReplacement replacement = pendingReplacements.get(player.getUniqueId());
        MerchantProfile profile = profiles.get(player.getUniqueId());
        if (replacement == null || profile == null) {
            return;
        }

        int index = switch (rawSlot) {
            case 11 -> 0;
            case 15 -> 1;
            default -> -1;
        };
        if (index < 0 || index >= profile.supplyChests.size()) {
            return;
        }

        profile.supplyChests.set(index, replacement.newChest);
        absorbListings(profile, replacement.newChest.contents);
        pendingReplacements.remove(player.getUniqueId());
        scheduleProfileSave(profile);
        player.closeInventory();
        player.sendMessage(PREFIX + "Supply chest replaced.");
    }

    private void handlePriceEditorClick(InventoryClickEvent event, Player player) {
        if (event.getRawSlot() != 13 && event.getRawSlot() != 15 && event.getRawSlot() != 22 && event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
        }
        // Shift-click from player inventory routes to slot 0, not slot 13 — block it
        if (event.isShiftClick() && event.getClickedInventory() != event.getView().getTopInventory()) {
            event.setCancelled(true);
            return;
        }
        PriceEditorContext context = openPriceEditors.get(player.getUniqueId());
        if (context == null) {
            return;
        }
        MerchantProfile profile = profiles.get(context.ownerId);
        if (profile == null) {
            return;
        }
        Listing listing = profile.listings.get(context.itemKey);
        if (listing == null) {
            player.closeInventory();
            return;
        }

        if (event.getClickedInventory() == event.getView().getTopInventory() && event.getRawSlot() == 15) {
            event.setCancelled(true);
            ItemStack cost = event.getView().getTopInventory().getItem(13);
            if (cost == null || cost.getType() == Material.AIR) {
                player.sendMessage(PREFIX + "Place the barter cost stack in the center slot first.");
                return;
            }
            listing.cost = cost.clone();
            scheduleProfileSave(profile);
            player.sendMessage(PREFIX + "Listing price saved.");
            handleManage(player, profile);
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory() && event.getRawSlot() == 22) {
            event.setCancelled(true);
            handleManage(player, profile);
        }
    }

    private void handleShopClick(Player player, int rawSlot, ClickType click) {
        if (rawSlot < 0 || rawSlot >= SHOP_SLOTS) {
            return;
        }
        ShopContext context = openShopContexts.get(player.getUniqueId());
        if (context == null || rawSlot >= context.listings.size()) {
            return;
        }

        int quantity = switch (click) {
            case LEFT -> 1;
            case RIGHT -> 8;
            case SHIFT_LEFT, SHIFT_RIGHT -> 64;
            default -> 0;
        };
        if (quantity == 0) {
            return;
        }

        MerchantProfile profile = profiles.get(context.ownerId);
        if (profile == null) {
            player.sendMessage(PREFIX + "Merchant profile missing.");
            player.closeInventory();
            return;
        }

        ShopListing listing = context.listings.get(rawSlot);
        String failure = performPurchase(player, profile, listing, quantity);
        if (failure != null) {
            player.sendMessage(PREFIX + failure);
        } else {
            player.sendMessage(PREFIX + "Purchased " + quantity + "x " + niceName(listing.product.getType()) + ".");
        }

        refreshLinkedChests(profile);
        openShop(player, profile);
    }

    private String performPurchase(Player player, MerchantProfile profile, ShopListing shopListing, int quantity) {
        if (profile.returnChest == null) {
            return "This merchant has no return chest configured.";
        }

        refreshLinkedChests(profile);
        Listing listing = profile.listings.get(shopListing.itemKey);
        if (listing == null || listing.cost == null) {
            return "This listing has no barter price set.";
        }

        int available = countStockForProduct(profile, listing.product);
        if (available < quantity) {
            return "Insufficient stock for bulk purchase (" + available + "/" + quantity + " available).";
        }

        InventorySimulation buyerSim = InventorySimulation.fromPlayer(player);
        InventorySimulation returnSim = InventorySimulation.fromContents(profile.returnChest.contents);
        List<InventorySimulation> supplySims = new ArrayList<>();
        for (LinkedChest chest : profile.supplyChests) {
            supplySims.add(InventorySimulation.fromContents(chest.contents));
        }

        ItemStack totalCost = multipliedStack(listing.cost, quantity);
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

        ItemStack totalProduct = multipliedStack(listing.product, quantity);
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

        ItemStack[] originalBuyer = copyContents(player.getInventory().getStorageContents());
        player.getInventory().setStorageContents(copyContents(buyerSim.contents));
        for (int i = 0; i < profile.supplyChests.size(); i++) {
            profile.supplyChests.get(i).contents = copyContents(supplySims.get(i).contents);
        }
        profile.returnChest.contents = copyContents(returnSim.contents);

        scheduleProfileSave(profile);
        for (LinkedChest chest : profile.supplyChests) {
            syncVirtualToPhysical(chest);
        }
        syncVirtualToPhysical(profile.returnChest);

        if (!inventoryContentsEqual(player.getInventory().getStorageContents(), buyerSim.contents)) {
            player.getInventory().setStorageContents(originalBuyer);
            return "Trade failed while applying inventory changes.";
        }
        return null;
    }

    private void refreshLinkedChests(MerchantProfile profile) {
        for (LinkedChest chest : profile.supplyChests) {
            syncPhysicalToVirtual(chest, getChestInventory(chest.location));
        }
        if (profile.returnChest != null) {
            syncPhysicalToVirtual(profile.returnChest, getChestInventory(profile.returnChest.location));
        }
    }

    private void syncPhysicalToVirtual(LinkedChest chest, Inventory inventory) {
        if (chest == null || inventory == null) {
            return;
        }
        chest.contents = copyContents(inventory.getContents());
    }

    private void syncVirtualToPhysical(LinkedChest chest) {
        if (chest == null) {
            return;
        }
        Inventory inventory = getChestInventory(chest.location);
        if (inventory == null) {
            return;
        }
        inventory.setContents(copyContents(chest.contents));
        BlockState state = chest.location.getBlock().getState();
        if (state instanceof Chest blockChest) {
            blockChest.update(true, false);
        }
    }

    private void absorbListings(MerchantProfile profile, ItemStack[] contents) {
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            ItemStack single = singleItem(stack);
            String key = itemKey(single);
            profile.listings.computeIfAbsent(key, ignored -> new Listing(key, single, null));
        }
    }

    private LinkedChest createLinkedChest(Location location, LinkKind kind) {
        Inventory inventory = getChestInventory(location);
        if (inventory == null) {
            return null;
        }
        return new LinkedChest(location.getBlock().getLocation(), copyContents(inventory.getContents()), kind);
    }

    private int countStockForProduct(MerchantProfile profile, ItemStack product) {
        int total = 0;
        for (LinkedChest chest : profile.supplyChests) {
            for (ItemStack stack : chest.contents) {
                if (stack != null && stack.getType() != Material.AIR && stack.isSimilar(product)) {
                    total += stack.getAmount();
                }
            }
        }
        return total;
    }

    private void snapMerchantsToHome() {
        for (MerchantProfile profile : profiles.values()) {
            if (profile.merchantLocation == null) {
                continue;
            }
            Villager merchant = getLiveMerchant(profile);
            if (merchant == null) {
                continue;
            }
            if (!sameBlockish(merchant.getLocation(), profile.merchantLocation)) {
                merchant.teleport(profile.merchantLocation);
                merchant.setVelocity(new Vector(0, 0, 0));
            }
        }
    }

    private void scheduleProfileSave(MerchantProfile profile) {
        if (profile == null) {
            return;
        }
        BukkitTask existing = pendingSaveTasks.remove(profile.ownerId);
        if (existing != null) {
            existing.cancel();
        }
        MerchantProfile snapshot = profile.copy();
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            saveProfileNow(snapshot);
            pendingSaveTasks.remove(snapshot.ownerId);
        }, SAVE_DEBOUNCE_TICKS);
        pendingSaveTasks.put(profile.ownerId, task);
    }

    private void flushProfileSave(MerchantProfile profile) {
        if (profile == null) {
            return;
        }
        BukkitTask existing = pendingSaveTasks.remove(profile.ownerId);
        if (existing != null) {
            existing.cancel();
        }
        MerchantProfile snapshot = profile.copy();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveProfileNow(snapshot));
    }

    private void saveAllProfilesNow() {
        for (MerchantProfile profile : profiles.values()) {
            saveProfileNow(profile.copy());
        }
    }

    private void saveProfileNow(MerchantProfile profile) {
        try (Connection connection = openConnection()) {
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
                if (profile.merchantLocation == null || profile.merchantLocation.getWorld() == null) {
                    insertProfile.setString(3, null);
                    insertProfile.setDouble(4, 0);
                    insertProfile.setDouble(5, 0);
                    insertProfile.setDouble(6, 0);
                } else {
                    insertProfile.setString(3, profile.merchantLocation.getWorld().getName());
                    insertProfile.setDouble(4, profile.merchantLocation.getX());
                    insertProfile.setDouble(5, profile.merchantLocation.getY());
                    insertProfile.setDouble(6, profile.merchantLocation.getZ());
                }
                insertProfile.executeUpdate();
            }

            try (PreparedStatement insertChest = connection.prepareStatement(
                "INSERT INTO linked_chests(owner_uuid, kind, slot_index, world, x, y, z, contents_blob) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
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
                for (Listing listing : profile.listings.values()) {
                    insertListing.setString(1, profile.ownerId.toString());
                    insertListing.setString(2, listing.itemKey);
                    insertListing.setString(3, serializeItem(listing.product));
                    insertListing.setString(4, listing.cost == null ? null : serializeItem(listing.cost));
                    insertListing.addBatch();
                }
                insertListing.executeBatch();
            }

            connection.commit();
        } catch (SQLException e) {
            getLogger().warning("Failed to save MerchantLink data for " + profile.ownerId + ": " + e.getMessage());
        }
    }

    private void writeChestRow(PreparedStatement statement, UUID ownerId, LinkKind kind, int slotIndex, LinkedChest chest) throws SQLException {
        statement.setString(1, ownerId.toString());
        statement.setString(2, kind.name());
        statement.setInt(3, slotIndex);
        statement.setString(4, Objects.requireNonNull(chest.location.getWorld()).getName());
        statement.setInt(5, chest.location.getBlockX());
        statement.setInt(6, chest.location.getBlockY());
        statement.setInt(7, chest.location.getBlockZ());
        statement.setString(8, serializeContents(chest.contents));
        statement.addBatch();
    }

    private void initDatabase() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Failed to create plugin data folder");
        }
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
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
                    "contents_blob TEXT NOT NULL," +
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

    private void loadProfiles() {
        profiles.clear();
        try (Connection connection = openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid, merchant_uuid, merchant_world, merchant_x, merchant_y, merchant_z FROM merchant_profiles"
            ); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID ownerId = UUID.fromString(rs.getString("owner_uuid"));
                    MerchantProfile profile = getOrCreateProfile(ownerId);
                    String merchantUuid = rs.getString("merchant_uuid");
                    profile.merchantUuid = merchantUuid == null ? null : UUID.fromString(merchantUuid);
                    String worldName = rs.getString("merchant_world");
                    if (worldName != null) {
                        World world = Bukkit.getWorld(worldName);
                        if (world != null) {
                            profile.merchantLocation = new Location(world, rs.getDouble("merchant_x"), rs.getDouble("merchant_y"), rs.getDouble("merchant_z"));
                        }
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid, kind, slot_index, world, x, y, z, contents_blob FROM linked_chests ORDER BY slot_index ASC"
            ); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    MerchantProfile profile = getOrCreateProfile(UUID.fromString(rs.getString("owner_uuid")));
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world == null) {
                        continue;
                    }
                    LinkedChest chest = new LinkedChest(
                        new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                        deserializeContents(rs.getString("contents_blob")),
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
                while (rs.next()) {
                    MerchantProfile profile = getOrCreateProfile(UUID.fromString(rs.getString("owner_uuid")));
                    ItemStack product = deserializeItem(rs.getString("product_blob"));
                    if (product == null) {
                        continue;
                    }
                    String costBlob = rs.getString("cost_blob");
                    ItemStack cost = costBlob == null ? null : deserializeItem(costBlob);
                    profile.listings.put(rs.getString("item_key"), new Listing(rs.getString("item_key"), product, cost));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load MerchantLink data", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    private boolean isManagedMerchant(Entity entity) {
        return entity.getType() == EntityType.VILLAGER && getMerchantOwner((Villager) entity) != null;
    }

    private Villager getLiveMerchant(MerchantProfile profile) {
        if (profile.merchantUuid == null) {
            return null;
        }
        for (World world : Bukkit.getWorlds()) {
            Entity entity = world.getEntity(profile.merchantUuid);
            if (entity instanceof Villager villager && !villager.isDead()) {
                return villager;
            }
        }
        return null;
    }

    private UUID getMerchantOwner(Villager villager) {
        String owner = villager.getPersistentDataContainer().get(merchantOwnerKey, PersistentDataType.STRING);
        if (owner == null) {
            return null;
        }
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private LinkedChestRef findLinkedChestDirect(Location location) {
        for (MerchantProfile profile : profiles.values()) {
            for (LinkedChest chest : profile.supplyChests) {
                if (sameBlock(chest.location, location)) {
                    return new LinkedChestRef(profile.ownerId, profile, chest, LinkKind.SUPPLY);
                }
            }
            if (profile.returnChest != null && sameBlock(profile.returnChest.location, location)) {
                return new LinkedChestRef(profile.ownerId, profile, profile.returnChest, LinkKind.RETURN);
            }
        }
        return null;
    }

    private LinkedChestRef findLinkedChest(Location location) {
        LinkedChestRef ref = findLinkedChestDirect(location);
        if (ref != null) return ref;
        // Also match the non-registered half of a double chest
        BlockState state = location.getBlock().getState();
        if (state instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                for (Object side : new Object[]{dc.getLeftSide(), dc.getRightSide()}) {
                    if (!(side instanceof Chest half)) continue;
                    Location halfLoc = half.getLocation();
                    if (sameBlock(halfLoc, location)) continue;
                    ref = findLinkedChestDirect(halfLoc);
                    if (ref != null) return ref;
                }
            }
        }
        return null;
    }

    private boolean isProtectedLinkedChest(Location location) {
        return findLinkedChest(location) != null;
    }

    private Inventory getChestInventory(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }
        BlockState state = location.getBlock().getState();
        if (!(state instanceof Chest chest)) {
            return null;
        }
        return chest.getInventory();
    }

    private boolean sameWorldOrMissingMerchant(MerchantProfile profile, Location location) {
        return profile.merchantLocation == null
            || (profile.merchantLocation.getWorld() != null && profile.merchantLocation.getWorld().equals(location.getWorld()));
    }

    private Location getInventoryChestLocation(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest dc) {
            for (Object side : new Object[]{dc.getLeftSide(), dc.getRightSide()}) {
                if (!(side instanceof Chest half)) continue;
                Location halfLoc = half.getLocation().getBlock().getLocation();
                if (findLinkedChestDirect(halfLoc) != null) return halfLoc;
            }
            // Neither half is directly registered — fall through to location below
        }
        Location location = inventory.getLocation();
        return location == null ? null : location.getBlock().getLocation();
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
            && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private boolean sameBlockish(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
            && a.distanceSquared(b) < 0.01;
    }

    private boolean isChestBlock(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }

    private ItemStack singleItem(ItemStack stack) {
        ItemStack clone = stack.clone();
        clone.setAmount(1);
        return clone;
    }

    private ItemStack multipliedStack(ItemStack stack, int multiplier) {
        if (stack == null || stack.getType() == Material.AIR || multiplier <= 0) {
            return null;
        }
        ItemStack clone = stack.clone();
        clone.setAmount(stack.getAmount() * multiplier);
        return clone;
    }

    private ItemStack[] copyContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    private boolean inventoryContentsEqual(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            ItemStack left = a[i];
            ItemStack right = b[i];
            if (left == null && right == null) {
                continue;
            }
            if (left == null || right == null) {
                return false;
            }
            if (!left.equals(right)) {
                return false;
            }
        }
        return true;
    }

    private String itemKey(ItemStack stack) {
        return serializeItem(singleItem(stack));
    }

    private String serializeItem(ItemStack item) {
        return serializeContents(new ItemStack[] { singleItem(item) });
    }

    private String serializeContents(ItemStack[] contents) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(output)) {
                stream.writeInt(contents.length);
                for (ItemStack stack : contents) {
                    stream.writeObject(stack);
                }
            }
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize contents", e);
        }
    }

    private ItemStack deserializeItem(String blob) {
        ItemStack[] contents = deserializeContents(blob);
        return contents.length == 0 ? null : contents[0];
    }

    private ItemStack[] deserializeContents(String blob) {
        try {
            byte[] data = Base64.getDecoder().decode(blob);
            try (BukkitObjectInputStream stream = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
                int length = stream.readInt();
                ItemStack[] contents = new ItemStack[length];
                for (int i = 0; i < length; i++) {
                    Object object = stream.readObject();
                    contents[i] = object instanceof ItemStack stack ? stack : null;
                }
                return contents;
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to deserialize contents", e);
        }
    }

    private ItemStack manageListingItem(ManageEntry entry) {
        ItemStack display = entry.listing.product.clone();
        display.setAmount(Math.max(1, Math.min(64, entry.stock)));
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Stock: " + entry.stock);
            if (entry.listing.cost == null) {
                lore.add(ChatColor.RED + "UNPRICED");
                lore.add(ChatColor.GRAY + "Hidden from public shop until priced");
            } else {
                lore.add(ChatColor.GRAY + "Price: " + describeStack(entry.listing.cost));
            }
            lore.add(ChatColor.YELLOW + "Left click: set barter price");
            lore.add(ChatColor.RED + "Drop key: delete listing");
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack shopItem(ShopListing listing) {
        ItemStack display = listing.product.clone();
        display.setAmount(1);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Stock: " + listing.stock);
            lore.add(ChatColor.GRAY + "Price: " + (listing.cost == null ? "Not set" : describeStack(listing.cost)));
            if (listing.cost == null) {
                lore.add(ChatColor.RED + "PRICE NOT SET");
            } else if (!listing.returnAvailable) {
                lore.add(ChatColor.RED + "MERCHANT RETURN CHEST MISSING");
            } else if (listing.stock <= 0) {
                lore.add(ChatColor.RED + "OUT OF STOCK");
            }
            lore.add(ChatColor.YELLOW + "Left-Click to buy 1");
            lore.add(ChatColor.GOLD + "Right-Click to buy 8");
            lore.add(ChatColor.RED + "Shift-Click to buy 64");
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack productPreviewItem(Listing listing) {
        ItemStack display = listing.product.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Product: " + describeStack(singleItem(listing.product)));
            lore.add(ChatColor.GRAY + "Current price: " + (listing.cost == null ? "Not set" : describeStack(listing.cost)));
            lore.add(ChatColor.GRAY + "Place the new cost stack in the center slot.");
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack linkedChestIcon(LinkedChest chest, String actionLine) {
        Material icon = chest.kind == LinkKind.RETURN ? Material.ENDER_CHEST : Material.CHEST;
        return namedItem(icon, ChatColor.GOLD + chest.kind.display + " Chest", List.of(
            formatLocation(chest.location),
            ChatColor.GRAY + "Stored items: " + countItems(chest.contents),
            ChatColor.YELLOW + actionLine
        ));
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private int countItems(ItemStack[] contents) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (stack != null && stack.getType() != Material.AIR) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private String describeStack(ItemStack stack) {
        return stack.getAmount() + "x " + niceName(stack.getType());
    }

    private String niceName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return ChatColor.GRAY + "Unknown location";
        }
        return ChatColor.GRAY + location.getWorld().getName() + " "
            + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private String merchantTitle(MerchantProfile profile) {
        String owner = Bukkit.getOfflinePlayer(profile.ownerId).getName();
        return owner == null ? "Merchant" : owner + "'s Merchant";
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    private static final class MerchantProfile {
        private final UUID ownerId;
        private UUID merchantUuid;
        private Location merchantLocation;
        private final List<LinkedChest> supplyChests = new ArrayList<>();
        private LinkedChest returnChest;
        private final Map<String, Listing> listings = new LinkedHashMap<>();

        private MerchantProfile(UUID ownerId) {
            this.ownerId = ownerId;
        }

        private MerchantProfile copy() {
            MerchantProfile copy = new MerchantProfile(ownerId);
            copy.merchantUuid = merchantUuid;
            copy.merchantLocation = merchantLocation == null ? null : merchantLocation.clone();
            for (LinkedChest chest : supplyChests) {
                copy.supplyChests.add(chest.copy());
            }
            copy.returnChest = returnChest == null ? null : returnChest.copy();
            for (Map.Entry<String, Listing> entry : listings.entrySet()) {
                copy.listings.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        }
    }

    private static final class LinkedChest {
        private final Location location;
        private ItemStack[] contents;
        private final LinkKind kind;

        private LinkedChest(Location location, ItemStack[] contents, LinkKind kind) {
            this.location = location;
            this.contents = contents;
            this.kind = kind;
        }

        private LinkedChest copy() {
            return new LinkedChest(location.clone(), copyItemArray(contents), kind);
        }
    }

    private static final class Listing {
        private final String itemKey;
        private final ItemStack product;
        private ItemStack cost;

        private Listing(String itemKey, ItemStack product, ItemStack cost) {
            this.itemKey = itemKey;
            this.product = product;
            this.cost = cost;
        }

        private Listing copy() {
            return new Listing(itemKey, product.clone(), cost == null ? null : cost.clone());
        }
    }

    private record ManageEntry(Listing listing, int stock) {}

    private record ShopListing(String itemKey, ItemStack product, ItemStack cost, int stock, boolean returnAvailable) {}

    private enum LinkKind {
        SUPPLY("Supply"),
        RETURN("Return");

        private final String display;

        LinkKind(String display) {
            this.display = display;
        }
    }

    private record PendingLink(LinkKind kind) {}

    private record PendingReplacement(LinkedChest newChest) {}

    private record LinkedChestRef(UUID ownerId, MerchantProfile profile, LinkedChest chest, LinkKind kind) {}

    private record ShopContext(UUID ownerId, List<ShopListing> listings, boolean returnAvailable) {}

    private record ManageContext(UUID ownerId, List<ManageEntry> entries) {}

    private record PriceEditorContext(UUID ownerId, String itemKey) {}

    private interface MerchantLinkHolder extends InventoryHolder {
        @Override
        default Inventory getInventory() {
            return null;
        }
    }

    private record ShopHolder(UUID ownerId) implements MerchantLinkHolder {}

    private record ManageHolder(UUID ownerId) implements MerchantLinkHolder {}

    private record ReplaceHolder(UUID ownerId) implements MerchantLinkHolder {}

    private record PriceEditorHolder(UUID ownerId) implements MerchantLinkHolder {}

    private static final class InventorySimulation {
        private final ItemStack[] contents;

        private InventorySimulation(ItemStack[] contents) {
            this.contents = contents;
        }

        private static InventorySimulation fromContents(ItemStack[] contents) {
            return new InventorySimulation(copyItemArray(contents));
        }

        private static InventorySimulation fromPlayer(Player player) {
            return new InventorySimulation(copyItemArray(player.getInventory().getStorageContents()));
        }

        private boolean canFit(ItemStack item) {
            return fromContents(contents).add(item);
        }

        private boolean add(ItemStack item) {
            int remaining = item.getAmount();
            for (ItemStack stack : contents) {
                if (stack == null || stack.getType() == Material.AIR || !stack.isSimilar(item)) {
                    continue;
                }
                int room = stack.getMaxStackSize() - stack.getAmount();
                int moved = Math.min(room, remaining);
                stack.setAmount(stack.getAmount() + moved);
                remaining -= moved;
                if (remaining <= 0) {
                    return true;
                }
            }
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] != null && contents[i].getType() != Material.AIR) {
                    continue;
                }
                ItemStack clone = item.clone();
                int moved = Math.min(clone.getMaxStackSize(), remaining);
                clone.setAmount(moved);
                contents[i] = clone;
                remaining -= moved;
                if (remaining <= 0) {
                    return true;
                }
            }
            return remaining <= 0;
        }

        private boolean canRemove(ItemStack item) {
            return fromContents(contents).remove(item);
        }

        private boolean remove(ItemStack item) {
            int remaining = item.getAmount();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType() == Material.AIR || !stack.isSimilar(item)) {
                    continue;
                }
                int moved = Math.min(stack.getAmount(), remaining);
                stack.setAmount(stack.getAmount() - moved);
                if (stack.getAmount() <= 0) {
                    contents[i] = null;
                }
                remaining -= moved;
                if (remaining <= 0) {
                    return true;
                }
            }
            return false;
        }

        private int removeMatching(ItemStack target, int amount) {
            int removed = 0;
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType() == Material.AIR || !stack.isSimilar(target)) {
                    continue;
                }
                int moved = Math.min(stack.getAmount(), amount - removed);
                stack.setAmount(stack.getAmount() - moved);
                if (stack.getAmount() <= 0) {
                    contents[i] = null;
                }
                removed += moved;
                if (removed >= amount) {
                    return removed;
                }
            }
            return removed;
        }
    }

    private static ItemStack[] copyItemArray(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }
}
