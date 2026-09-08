# MerchantLink

Player-run merchant shops for **Horizons SMP** — a villager tied to an owner,
linked to physical chests for stock and payment, with a real barter-price GUI
instead of a config-driven virtual shop. This repo has two independent
implementations of the same feature set for two different server platforms:

| Directory | Platform | Status |
|---|---|---|
| [`/plugin`](plugin) | Paper/Bukkit (Java plugin) | Original implementation — feature-complete, correctly targets the live server's actual version (`api-version: "26.1.2"`), and its standalone-jar dependency bug is now fixed (2026-09-08, see [`/plugin`'s README](plugin/README.md#building)) |
| [`/mod`](mod) | Fabric (server-side mod) | Port of the plugin — feature-complete, released, **built against Minecraft 1.21.11, not verified against the live server's actual 26.1.2** (see [`/mod`'s README](mod/README.md#-known-limitation-this-does-not-match-the-live-horizons-smp-servers-version)) |

They are **not interoperable** — each is a full standalone implementation for
its own platform, with its own build, its own data storage, and its own
README with full command/feature docs. Pick whichever matches your server.
Both are released separately under [Releases](../../releases) (tagged
`plugin-vX.Y.Z` and `mod-vX.Y.Z`).

## Feature summary (applies to both)

- `/ml spawn` — spawn a stationary, invulnerable, AI-disabled merchant
  villager at your location, owned by you.
- `/ml link supply` / `/ml link return` — left-click a chest to link it as a
  supply chest (max 2) or your return/payment chest.
- Items placed in a supply chest become listings automatically; set a barter
  price for each via the management GUI (`/ml manage`).
- Other players right-click your merchant to open a shop GUI showing priced
  listings, live stock, and barter cost; buy 1/8/64 at a time.
- Trades are simulated fully (inventory space, stock, return-chest capacity)
  before anything is touched, with rollback on failure.
- Linked chests are protected from explosions/fire while linked, and kept in
  sync with their physical contents.
- All state persists across restarts.

See each variant's own README for exact commands, permissions/config, and
platform-specific implementation notes and limitations — the two
implementations were written independently against each platform's native
APIs and may differ in small edge-case behaviors. Notably, the Fabric mod
reads linked-chest contents live off the physical block entity instead of
caching a copy, bundles its own SQLite driver (the Paper plugin doesn't
shade its driver into its jar), and does not protect linked chests from
explosions/fire (no clean Fabric API equivalent to Bukkit's explosion/ignite
events) — see [`/mod`](mod)'s README for the full list of intentional
deviations.

## Background

One of a set of small custom plugins/mods written for Horizons SMP, a
semi-vanilla server that runs Paper as its main platform, to add lightweight
player-economy features without a full economy plugin.
