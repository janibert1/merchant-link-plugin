# MerchantLink

A Paper plugin for **Horizons SMP** that lets players spawn their own personal
"merchant" villager and link it to physical chests, turning it into a simple
player-run shop: players stock a **supply chest**, set barter prices, and
other players buy from the villager while payment lands in a **return
chest**.

## What it does

Each player can own one merchant:

- `/ml spawn` spawns a stationary, invulnerable villager at the player's
  location, tagged as that player's merchant (AI, gravity, and collision are
  disabled, and it's protected from damage, combustion, and being knocked
  off its spot — it's automatically snapped back to its home location if it
  ever drifts).
- The owner links up to **2 supply chests** and **1 return chest** to the
  merchant via `/ml link supply` / `/ml link return`, then left-clicking the
  target chest.
- Items found in a linked supply chest are automatically picked up as
  **listings** (unpriced until the owner sets a barter cost for them via the
  in-game price editor GUI, reachable from `/ml manage`).
- When another player right-clicks the merchant villager, it opens a shop
  GUI showing all priced listings, current stock (computed live from the
  linked supply chests), and the barter price for each.
- Buyers purchase by clicking a listing: left-click buys 1, right-click buys
  8, shift-click buys 64. The plugin simulates the whole trade first
  (inventory space, stock availability, return-chest capacity) before
  touching any real inventory, and rolls back the buyer's inventory if
  anything goes wrong applying the change.
- Linked chests are kept in sync with their in-world contents on open/close,
  and are protected from explosions, fire, and burning while linked.
  Double chests are handled correctly (either half being linked protects/
  matches the whole chest).
- All merchant/chest/listing state is persisted to a per-server SQLite
  database (`merchantlink.db` in the plugin's data folder, WAL mode) and
  reloaded on startup, with debounced async saves to avoid blocking the
  main thread on every change.

In short: it's a "shopkeeper" plugin, but instead of a config-driven virtual
shop, the shop's stock and prices are driven by real chests a player fills
and reads directly from the physical inventory.

## Commands

All subcommands are under `/ml` (see `plugin.yml`):

| Command | Effect |
|---|---|
| `/ml spawn` | Spawn your merchant villager at your current location |
| `/ml remove` / `/ml despawn` | Remove your merchant villager |
| `/ml link supply` | Left-click a chest next to link it as a supply chest (max 2; linking a 3rd prompts you to replace one) |
| `/ml link return` | Left-click a chest to link it as your return (payment) chest |
| `/ml manage` | Open the management GUI: view/unlink chests, remove the merchant, set barter prices, or delete listings (drop-key on a listing) |

Permission: `merchantlink.use` (default: granted to everyone).

## Configuration

`config.yml` currently has no tunable settings — it's a placeholder noting
that MerchantLink stores per-listing barter pairs in SQLite and does not use
any global currency/pricing config.

## Building

```
cd plugin
mvn package
```

Produces `target/merchant-link-plugin-1.0.jar` (~14MB — `maven-shade-plugin`
now bundles `sqlite-jdbc`, including its native libraries for every
platform it supports, directly into the jar).

This repo also contains a Fabric mod reimplementation of the same feature
set — see [`/mod`](../mod) and the [top-level README](../README.md) for how
the two relate.

**2026-09-08: fixed the standalone-jar issue** — `pom.xml` now shades
`org.xerial:sqlite-jdbc` into the built jar (`ServicesResourceTransformer`
keeps the driver's own `META-INF/services` registration intact; no package
relocation, since the code loads the driver only via `DriverManager` +
the `jdbc:sqlite:` URL scheme, not a hardcoded class reference). Verified
with a real, standalone round-trip test (`java -cp
target/merchant-link-plugin-1.0.jar`, no other classpath entries): opened a
real SQLite connection, created a table, inserted and read back a row.
The jar now works correctly dropped into any Paper server on its own —
previously it depended on `sqlite-jdbc` already being present on the
server's classpath from some other plugin, which most servers won't have.

## Installing

Drop the built jar into your Paper server's `plugins/` folder and restart
(or `/reload`, though a full restart is recommended). Built and tested
against Paper API `1.21.11-R0.1-SNAPSHOT` (`api-version: "26.1.2"` in
`plugin.yml`).

## Background

One of a set of small custom plugins written for **Horizons SMP**, a
semi-vanilla Paper server, to add lightweight player-economy features
without a full economy plugin.
