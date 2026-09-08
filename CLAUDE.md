# CLAUDE.md

Guidance for Claude Code (or any future agent/session) working in this repo. Read this first, then `HANDOFF.md` for what's in flight right now.

## What this is

**DynamicShop** — a Bukkit/Paper plugin (Java 21) providing a full GUI shop with dynamic (supply/demand-driven) pricing. Maven project, single module, shaded into one jar.

- `groupId`/`artifactId`: `me.sat7:DynamicShop`
- Main class: `me.sat7.dynamicshop.DynamicShop`
- Free version: https://www.spigotmc.org/resources/65603/
- Premium version: https://www.spigotmc.org/resources/100058/
- Original upstream author: sat7. This fork/repo (`Cupjok/DynamicShop3`) carries forward fixes and features beyond upstream — see the "Ahead of upstream" section in `README.md` for the current list, keep it updated when you add something upstream doesn't have.

## Build & test

```
mvn clean package        # produces target/DynamicShop-<version>.jar (shaded)
mvn compile               # quick compile check
mvn test                  # unit tests (src/test/java) — JUnit 4
```

Requires network access on first build (Paper/JitPack/etc. repositories are not mirrored locally).

**Requires a JDK 25+ toolchain to build** (`maven.compiler.release` is `25`, since Paper 26.2's `paper-api` jar itself is compiled to Java 25 class files — building with an older JDK fails with `bad class file ... wrong version`). If the default `java`/`JAVA_HOME` on your machine is older, point Maven at one explicitly, e.g. on this dev machine:
```
export JAVA_HOME="$(brew --prefix openjdk@25)"   # or any JDK 25/26+ install
mvn clean package
```
Running the *built plugin* on a server is fine on any JDK that can run Java 25 bytecode (25 or newer) — this constraint is a **build-time** requirement, not a server requirement beyond what Paper 26.2 itself already needs to run.

If you ever see phantom "cannot find symbol" errors for Lombok-generated getters/setters (`getMaxStock`, `getMedian`, etc. on `DSItem`, or `getCurrentTax`/`setCurrentTax` on `ConfigUtil`) with a `mvn clean` build, check two things before assuming it's a real code issue:
1. Maven running **offline** (`-o`) skips annotation processing entirely — always build online.
2. The JDK in use doesn't support **implicit annotation processing** anymore (JDK 25+ disabled auto-discovering processors on the classpath with no explicit opt-in). This is why `maven-compiler-plugin` in `pom.xml` explicitly declares `<annotationProcessorPaths>` pointing at Lombok — don't remove that block, and if you bump the Lombok version, update it in both the `<dependencies>` block and this `<annotationProcessorPaths>` block (they're two separate coordinates Maven doesn't automatically keep in sync).

Also note: `maven-shade-plugin` needs to be recent enough to read Java 25 class files for its `minimizeJar` analysis (ASM version bundled inside the plugin) — we're pinned to `3.6.2`; don't downgrade it, older versions fail package with `Unsupported class file major version 69`.

CI: `.github/workflows/ds.yml` builds on push/PR to `master` and on tags (JDK 21, Temurin). A tag push (`v*` or any tag) also runs the `release` job, which builds the jar and publishes a GitHub Release with it attached via `softprops/action-gh-release`.

## Runtime target

- Minecraft/Paper API: tracks current Paper releases (`paper-api` version in `pom.xml`, e.g. `26.2.build.121-stable` for Paper 26.2). Bump this and `api-version` in `plugin.yml` together when targeting a new MC version — check `https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml` for available builds.
- Supported server software: **Paper, Purpur, and Folia** (`folia-supported: true` in `plugin.yml`). Purpur is a Paper fork so it's covered by Paper compatibility automatically. Folia requires everything scheduling-related to go through the region/entity/global/async schedulers — see "Folia scheduling rules" below, don't reintroduce `Bukkit.getScheduler()` / `BukkitRunnable` / `BukkitTask`.
- Java 21 (`maven.compiler.source/target`).

## Architecture map

```
me.sat7.dynamicshop
├── DynamicShop.java        plugin main class: onEnable/onDisable, hook setup (Vault/PAPI/Jobs/PlayerPoints/LocaleLib),
│                           repeating tasks (backup, log save/cull, UI refresh tick), Economy accessor
├── DynaShopAPI.java        public-ish facade used by commands/GUIs to open shops, settings, etc.
├── UpdateChecker.java      SpigotMC version check (async)
├── commands/               /ds (Root/DSCMD), /shop (Optional), /sell — subcommands under commands/shop/*
│                           for per-shop admin actions (add item, currency, flags, rotation, tax, ...)
├── constants/Constants.java  permission nodes + currency-type string constants (Vault/Exp/PlayerPoint/JobPoint)
├── economyhook/            Jobs (points) and PlayerPoints integrations — thin wrappers guarded by an
│                           `xxxActive` boolean flipped in DynamicShop's hookIntoX() methods
├── events/                 Bukkit listeners: chat input capture (OnChat), sign shop clicks, inventory clicks,
│                           join/quit cleanup, buy/sell event firing
├── files/CustomConfig.java YAML config file wrapper (load/get/save/addDefault pattern used everywhere)
├── guis/                   All inventory-based UI screens (Shop, ItemTrade, ShopSettings, ItemSettings,
│                           RotationEditor, PageEditor, StockSimulator, LogViewer, StartPage, ...).
│                           UIManager tracks "which UI is this player currently looking at" and drives the
│                           per-second UI refresh tick.
├── models/DSItem.java      Lombok @Getter/@Setter POJO for one shop item's pricing/stock state
├── transactions/           Buy.java / Sell.java / Calc.java — the actual price math + currency debit/credit
└── utilities/              ShopUtil (huge — shop data CRUD, currency dispatch, YAML shop files),
                            ConfigUtil (config.yml accessors), WorthUtil (worth.yml / recommended values),
                            RotationUtil (scheduled shop rotations), LangUtil (all UI strings, ko-KR + en-US
                            built in, see below), SchedulerUtil (Folia-safe scheduler wrappers — use these,
                            not Bukkit.getScheduler()), TabCompleteUtil, UserUtil, HashUtil, MathUtil, etc.
```

Shop data lives in per-shop YAML files (via `CustomConfig`), not a database. `Options.currency` on a shop's config selects one of `vault` / `exp` / `pp` / `jp` (see `Constants.S_*` and `ShopUtil.GetCurrency`).

### Localization

`LangUtil.setupLangFile()` hardcodes both `ko-KR` and `en-US` string tables directly in Java (not resource files) via `ccLang.get().addDefault(key, value)`. If you add a new UI string, add it to **both** language blocks in that one method, plus reference it with `t(player, "KEY")` / `n(number)` helpers. `config.yml`'s `Language` key picks which one loads.

### Currencies / economy hooks

Four currency backends, dispatched by `Options.currency` per shop:
- `vault` — Vault `Economy` service (see below for the fork-compatibility nuance)
- `exp` — player XP points, no external plugin needed
- `pp` — PlayerPoints plugin (`economyhook/PlayerpointHook.java`, guarded by `PlayerpointHook.isPPActive`)
- `jp` — Jobs (Jobs Reborn) points (`economyhook/JobsHook.java`, guarded by `JobsHook.jobsRebornActive`)

**Vault compatibility:** don't gate on `getPluginManager().getPlugin("Vault") != null` — several popular drop-in replacements (VaultUnlocked, CMIVault) register the standard `net.milkbowl.vault.economy.Economy` service via Bukkit's `ServicesManager` without necessarily existing under the exact plugin name "Vault". The only correct check is whether `getServicesManager().getRegistration(Economy.class)` resolves (see `DynamicShop.SetupRSP()`, which retries a few times on enable to allow for load-order races). `Vault` is a **softdepend**, not a hard `depend`, for the same reason.

### Folia scheduling rules

Use `utilities/SchedulerUtil.java` instead of the legacy Bukkit scheduler anywhere in this codebase:
- Global, not location/entity-specific (economy setup polling, file I/O, backups, log writes) → `runGlobal*` (wraps `Bukkit.getGlobalRegionScheduler()`).
- Off-main-thread work with no world/entity access mid-task → `runAsync*` (wraps `Bukkit.getAsyncScheduler()`).
- Anything touching a specific block/shop location (rotations tied to a physical shop) → `runAtLocation*` (wraps `Bukkit.getRegionScheduler()`).
- Anything touching a specific player's inventory/GUI/chat state → `runForEntity` (wraps `player.getScheduler()`).

On Paper/Purpur (non-Folia) these all behave like the old main-thread/async scheduler, so this is safe everywhere, not just under Folia — there's no need for `isFolia()` branching in call sites. If you add a new repeating task or delayed callback, pick the right bucket above rather than reaching for `Bukkit.getScheduler()`.

## Conventions already in the codebase (follow them, don't fight them)

- PascalCase method names are the existing house style in large swaths of this codebase (`GetCurrency`, `RefreshUI`, `SetupVault`) even though it's not idiomatic Java — match the surrounding file rather than converting to camelCase piecemeal.
- Comments mix Korean and English (the original codebase is Korean-authored) — don't strip or "clean up" existing Korean comments, and it's fine to write new comments in English.
- `CustomConfig` + `addDefault(...)` + `copyDefaults(true)` + `.save()` is the standard pattern for anything YAML-backed (config, lang, shop data, worth data). Follow it for new config keys instead of hand-rolling file I/O.
- Lombok `@Getter`/`@Setter` on model classes (`DSItem`) — if `mvn compile` can't find a getter that's clearly annotated, it's the offline/annotation-processor issue above, not a missing method.

## Test servers (local dev machine only, not part of this repo)

On the machine this was originally developed on, real Paper/Purpur/Folia server installs live in a sibling directory outside the repo (not checked in — this won't exist on a fresh clone or a different machine/fork). If you have similar local test servers, each typically has its own `plugins/` folder — drop the built jar in, start with the server's own `run.sh`/`cmd.sh`/`start-bg.sh` (scripts vary per server), and check `logs/latest.log` (or the equivalent console log) for enable/disable errors. Testing against a realistic plugin set (LuckPerms, WorldGuard, CMI, PlaceholderAPI, an actual Vault-compatible economy provider, etc.) is a much better signal than a bare-bones test instance — in particular, Vault-hook and Folia-scheduler changes should be verified against a real Jobs/economy plugin and a real Folia (or Folia-fork, e.g. Canvas) server before assuming they work.

## Where to look for more detail

- `HANDOFF.md` — living session-continuity notes: what's currently mid-flight, what was verified, what's next. Update it before ending a session that leaves work unfinished; trim/archive it once a body of work fully lands and is verified.
- `README.md` — user-facing feature list and the "ahead of upstream" changelog-ish section.
