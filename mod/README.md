# MerchantLink (Fabric mod)

A server-side [Fabric](https://fabricmc.net/) mod for **Horizons SMP** that ports the
[`/plugin`](../plugin) Paper plugin's feature set to vanilla/Fabric: players spawn a
personal "merchant" villager, link it to physical chests, and run a simple barter shop —
stock a **supply chest**, set barter prices, and other players buy from the villager while
payment lands in a **return chest**.

This is a genuine reimplementation against Fabric/vanilla APIs, not a stub — see
[Behavioral differences from the plugin](#behavioral-differences-from-the-plugin) below for
the handful of places where Fabric's APIs don't have a clean 1:1 equivalent to Bukkit's.

## What it does

Each player can own one merchant:

- `/ml spawn` spawns a stationary, invulnerable villager at the player's location, tagged
  as that player's merchant (AI, gravity are disabled, it's silent, persistent, invulnerable
  to all damage, and non-clipping so it can't be shoved around — it's also snapped back to
  its home position every second in case anything does manage to move it).
- The owner links up to **2 supply chests** and **1 return chest** to the merchant via
  `/ml link supply` / `/ml link return`, then left-clicking the target chest.
- Items found in a linked supply chest are automatically picked up as **listings**
  (unpriced until the owner sets a barter cost for them via the in-game price editor GUI,
  reachable from `/ml manage`).
- When another player right-clicks the merchant villager, it opens a shop GUI (instead of
  the vanilla trade UI) showing all priced listings, current stock (computed live from the
  linked supply chests), and the barter price for each.
- Buyers purchase by clicking a listing: left-click buys 1, right-click buys 8, shift-click
  buys 64. The whole trade is simulated first (inventory space, stock availability,
  return-chest capacity) before touching any real inventory, and nothing is applied unless
  every step of the simulation succeeds.
- Linked chests are protected from being broken by non-owners; the owner breaking one
  unlinks it automatically.
- All merchant/chest/listing state is persisted to a per-server SQLite database
  (`config/merchant-link/merchantlink.db`) and reloaded on startup, with debounced
  background saves so normal play doesn't block the server thread on every change.

## Commands

All subcommands are under `/ml`:

| Command | Effect |
|---|---|
| `/ml spawn` | Spawn your merchant villager at your current location |
| `/ml remove` / `/ml despawn` | Remove your merchant villager |
| `/ml link supply` | Left-click a chest to link it as a supply chest (max 2; linking a 3rd opens a GUI to pick which one to replace) |
| `/ml link return` | Left-click a chest to link it as your return (payment) chest |
| `/ml manage` | Open the management GUI: view/unlink chests, remove the merchant, set barter prices, or delete listings |

There is no permission node — anyone who can run commands can use `/ml` (same default as
the plugin's `merchantlink.use: true`).

## Behavioral differences from the plugin

This mod aims for feature parity, but a few things were adapted or simplified against
Fabric's APIs rather than blocked on a perfect match. All are intentional engineering calls,
documented here rather than silently skipped:

- **No mixins, no custom entity.** The merchant is a plain vanilla `VillagerEntity` with
  `NoAI`, `Invulnerable`, `NoGravity`, `Silent`, `Persistent`, and `noClip` set — this covers
  "frozen in place, can't be hurt, doesn't wander, doesn't despawn" the same way the plugin's
  Bukkit calls do. There is no direct Fabric/vanilla equivalent to Bukkit's
  `Entity#setCollidable(false)`; `noClip = true` is the closest practical substitute (it also
  stops the entity from being pushed by other mobs). The merchant's villager **profession is
  left at its default** (unemployed) rather than forced to Librarian — purely cosmetic, no
  functional impact.
- **Owner identity** is stored via Fabric API's data-attachment API
  (`fabric-data-attachment-api-v1`, persistent `AttachmentType<UUID>`) directly on the
  villager entity, the direct equivalent of the plugin's `PersistentDataContainer` tag.
- **Linked chests are read live, not cached.** The plugin caches a copy of each linked
  chest's contents and syncs it on inventory open/close. This mod always reads the physical
  block entity's `Inventory` directly (via vanilla's `ChestBlock.getInventory(...)`, which
  also transparently merges double chests) at the moment it's needed — simpler, and always
  correct even if the chest was changed by a hopper or another mod while nobody had it open.
  One consequence: if you link one half of a chest and someone later places another chest
  next to it, stock scanning correctly reads both halves together (vanilla's double-chest
  merge), but only the originally-linked half is break-protected — breaking the other half
  won't unlink anything, it'll just silently split the double chest back to a single one.
- **No explosion/fire protection for linked chests or the merchant.** Fabric API does not
  currently expose an equivalent to Bukkit's `BlockExplodeEvent` / `EntityExplodeEvent` /
  `BlockIgniteEvent` / `BlockBurnEvent`. The merchant villager itself is still fully immune
  to all damage (including fire and explosions) via vanilla's invulnerability flag, but
  linked chests are **not** protected from TNT or fire — keep them away from griefable areas,
  or set the `mobGriefing` gamerule off as a coarse mitigation. Chest-breaking by
  non-owning players *is* fully protected (via `PlayerBlockBreakEvents.BEFORE`).
- **Listing deletion uses the vanilla "throw" action (default key: `Q`, `Ctrl+Q` for the
  stack variant)** instead of the plugin's "drop key on a listing" — same physical keybind,
  same intent, just phrased in Fabric/vanilla terms (`SlotActionType.THROW`) rather than
  Bukkit's `ClickType.DROP`.
- **Item (de)serialization** uses vanilla's own `ItemStack.CODEC` encoded to SNBT text
  (component-aware — enchantments, custom names, etc. all round-trip) instead of the
  plugin's Bukkit-specific `BukkitObjectOutputStream`.
- **The GUI "manage" listing area is capped at 27 entries** with no pagination, matching the
  plugin's own behavior exactly (it also just truncates past 27 — neither implementation
  scrolls).
- **The sqlite-jdbc driver is bundled into the mod jar** (via Loom's `include
  implementation(...)`, the same pattern this repo's sister project `horizons-fabric` uses
  for bundling BCrypt) — this is actually a fix over the Paper plugin, which depends on
  `sqlite-jdbc` but has no shade/assembly step, so that dependency is *not* bundled into its
  jar (see the note in `../plugin/README.md`).

## Compatibility

| | |
|---|---|
| Minecraft version | `1.21.11` |
| Fabric Loader | `>= 0.19.0` (built against `0.19.2`) |
| Fabric API | `0.141.3+1.21.11` |
| Fabric Loom (build tool) | `1.16.1` |
| Java | 21 |

This mod is **server-only** (`"environment": "server"` in `fabric.mod.json`) — no client
code, and it does not need to be installed on player clients.

## Building

```sh
cd mod
gradle build   # or ./gradlew build if you generate a wrapper first
```

The first build downloads Minecraft/Yarn mappings and Fabric API into Gradle's cache and
decompiles/remaps Minecraft — this can take a while. The built, ready-to-deploy mod jar is
written to `build/libs/merchant-link-<version>.jar` (the plain jar in `build/libs` is the
remapped one meant for distribution; ignore `build/devlibs/*-dev.jar`, that one's for the
dev environment only).

## Installing

1. Set up a [Fabric server](https://fabricmc.net/use/server/) for Minecraft 1.21.11 with
   the matching Fabric Loader and [Fabric API](https://modrinth.com/mod/fabric-api) mod
   installed.
2. Drop the built jar (`merchant-link-<version>.jar`) into the server's `mods/` folder.
3. Start the server. Data is written to `config/merchant-link/merchantlink.db` on first run.

No installation is required on the client side.

## Background

One of a set of small custom plugins/mods written for Horizons SMP. See the
[top-level README](../README.md) for how this relates to the `/plugin` Paper implementation.
