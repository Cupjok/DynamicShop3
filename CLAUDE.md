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
mvn compile              # quick compile check
mvn test                 # unit tests (src/test/java) — JUnit 4
```

Requires network access on first build (Paper/JitPack/etc. repositories are not mirrored locally).

**One jar supports Minecraft 1.21 through 26.x.** It is compiled with `maven.compiler.release` `21` against `paper-api` `1.21-R0.1-SNAPSHOT` (`paper.api.version` in `pom.xml`). Do not raise either for a new feature. Code must only use API that exists in 1.21 and still exists in the newest version. When a newer API is needed, look it up through reflection with a 1.21 fallback (example: `SchedulerUtil.IsGlobalThread`).

**Requires a JDK 25+ toolchain to build.** The vendored MultiCurrency API jar is Java 25 bytecode, and only a JDK 25+ `javac` can read it. `--release 21` still produces Java 21 class files. JDK 21 fails with `cannot access me.cupjok.multicurrency.api...`.

Checks for every change that touches the Bukkit/Paper API:
- `mvn -Pcompat-26 compile` compiles the same sources against the 26.2 API and catches APIs removed after 1.21.
- `python3 tools/api-kind-check.py target/DynamicShop-<v>.jar <paper-api-1.21.jar> <newer paper-api jars...>` lists API types the jar calls methods on whose kind changed (class ↔ interface, enum → interface), plus members missing in a newer API. Example: `org.bukkit.Sound` is an enum up to 1.21.1 and an interface from 1.21.3. So never call methods on it (`Sound.valueOf`, `name()`, `values()`). Use `SoundUtil.GetSound(name)` or a constant field such as `Sound.ENTITY_EXPERIENCE_ORB_PICKUP`. The same applies to other registry types (`Biome`, `Particle`, `Attribute`, `Enchantment`, `PotionEffectType`, ...).
- Load-test on real servers (see "Test servers").

If Lombok-generated getters/setters appear missing after `mvn clean`, check that Maven is not running offline and that the compiler has explicit Lombok annotation processing enabled. Do not remove the Lombok `annotationProcessorPaths` block from `pom.xml`; keep its version synchronized with the Lombok dependency.

`maven-shade-plugin` is pinned to `3.6.2` because older versions cannot reliably analyze Java 25 class files.

CI: `.github/workflows/ds.yml` builds on push/PR to `master` and on tags. A tag push also builds the jar and publishes a GitHub Release with it attached.

## Runtime target

- Minecraft/Paper API: compiled against the **oldest** supported version (1.21), `api-version: '1.21'` in `plugin.yml`. Newer servers load it too. Do not bump either when a new Minecraft version comes out. Instead, run the `compat-26` compile (update its `paper.api.version` to the newest API), the API-kind check and the server test matrix against the new version. Dropping 1.21 support is a data/behaviour-impacting decision for the maintainer.
- Supported server software: **Paper, Purpur, and Folia** (`folia-supported: true` in `plugin.yml`).
- Folia requires scheduling through the region/entity/global/async schedulers — see "Folia scheduling rules" below. Do not reintroduce `Bukkit.getScheduler()` / `BukkitRunnable` / `BukkitTask`.

## Architecture map

```
me.sat7.dynamicshop
├── DynamicShop.java        plugin main class: onEnable/onDisable, hook setup and repeating tasks
├── DynaShopAPI.java        public-ish facade used by commands/GUIs to open shops, settings, etc.
├── UpdateChecker.java      SpigotMC version check (async)
├── commands/               /ds, /shop and /sell commands and per-shop admin actions
├── constants/Constants.java  permission nodes + currency-type constants
├── economyhook/            Jobs and PlayerPoints integrations
├── events/                 Bukkit listeners for chat, signs, inventory, join/quit and trade events
├── files/CustomConfig.java YAML config file wrapper
├── guis/                   inventory-based UI screens and UIManager
├── models/DSItem.java      Lombok model for one shop item's pricing/stock state
├── transactions/           Buy.java / Sell.java / Calc.java — price math + currency debit/credit
└── utilities/              ShopUtil, ConfigUtil, WorthUtil, RotationUtil, LangUtil, SchedulerUtil,
                            TabCompleteUtil, UserUtil, HashUtil, MathUtil, ShopNameFormatter, etc.
```

Shop data lives in per-shop YAML files, not a database. `Options.currency` selects the shop currency.

### Localization

`LangUtil.setupLangFile()` contains both `ko-KR` and `en-US` string tables directly in Java. If you add a new UI string, add it to **both** language blocks and reference it through the existing translation helpers. `config.yml`'s `Language` key selects the language.

Changing an existing default text is safe: `files/DefaultsSync` (called for both language files and `Layout.yml`) remembers the last default written for each key in `plugins/DynamicShop/.defaults/<file>.yml`. On start, a value that still equals the old default is replaced with the new default. A customised value is kept and logged once. Do not print "please edit your language file" banners. The snapshot only exists from 3.26 on. If an older default needs to change, pass its old text(s) in the `knownOldDefaults` map of that `DefaultsSync.Apply` call.

### Currencies / economy hooks

Currency backends include Vault, XP, PlayerPoints, Jobs points, and `MultiCurrency:<id>`.

Everywhere a shop's currency is branched on, the final `else` means Vault. A MultiCurrency shop must never reach that `else`: `ShopUtil.GetCurrency()` returns `MultiCurrency:<id>` for it, and every branch site needs an explicit MultiCurrency case.

### MultiCurrency integration

Optional (`softdepend: MultiCurrency`). DynamicShop talks to MultiCurrency only through the released public API `me.cupjok.multicurrency:multicurrency-api`. Never touch its database, implementation classes or config, and never change the MultiCurrency project from here.

- `economyhook/MultiCurrencyHook` — safe to load without MultiCurrency; currency lookup, `BigDecimal` conversion and display-only balance cache.
- `economyhook/MultiCurrencyBridge` — the only class importing the API.
- `guis/CurrencySelector` — lists built-in and MultiCurrency currencies and writes canonical `Options.currency` values.
- `transactions/MultiCurrencyTrade` — buy/sell/payout flow + journal; `MultiCurrencyOrder` — one order; `MultiCurrencyOutcome` — classifies a `TransactionResult`.

Transaction rules (do not weaken):
1. **No balance check before charging.** Use the transaction API's result for insufficient funds; never add a check-then-act `has()`/`balance()` pre-check. `balance()` is for GUI display only.
2. **Write-ahead journal.** Every order is journaled before the first API call. Retries and restarts must reuse the same idempotency key.
3. **OUTCOME_UNKNOWN is never a failure.** Unknown outcomes must be retried or resolved using the existing idempotency/history mechanism.
4. **Items only after a definitive APPLIED.** Preserve the existing order state machine and never automatically re-give items after an ambiguous crash state.
5. **Sells take the items first** and save player data before depositing, so a crash cannot leave the player with both. A refused deposit gives the items back.
6. Futures completing on a MultiCurrency DB thread must hop back through `SchedulerUtil` before touching Bukkit or the journal. MultiCurrency does not support Folia, so this path is Paper/Purpur only.

### Shop-name formatting

Shop titles, start-page titles, button names and relevant lore use `utilities/ShopNameFormatter` and Adventure Components. Preserve its current safe visual-only tag policy and regression coverage. Do not widen the resolver to interactive MiniMessage tags, and do not use the formatter for internal shop file names.

**Vault compatibility:** do not gate on `getPluginManager().getPlugin("Vault") != null`. Check whether the standard `net.milkbowl.vault.economy.Economy` service resolves through Bukkit's `ServicesManager`, because drop-in Vault replacements may register the service without using the plugin name `Vault`.

### Folia scheduling rules

Use `utilities/SchedulerUtil.java` instead of the legacy Bukkit scheduler anywhere in this codebase:
- Global, not location/entity-specific → `runGlobal*`.
- Off-main-thread work with no world/entity access → `runAsync*`.
- A specific block/shop location → `runAtLocation*`.
- A specific player's inventory/GUI/chat state → `runForEntity`.

- Console commands (`Bukkit.dispatchCommand(Bukkit.getConsoleSender(), …)`) → `SchedulerUtil.DispatchConsoleCommand`. Folia throws `Dispatching command async` for a console command off the global thread, for example from a GUI click or a player command.
- Chat messages with components → `player.sendMessage(Component)`. Never send them through a console `tellraw` command.

On Paper/Purpur these wrappers behave appropriately as well, so call sites should not add `isFolia()` branching. Do not use `Bukkit.getScheduler()` for new work.

## User data safety (mandatory for every change)

Every fix and every new feature must be safe for the data existing servers already have. That covers shop files, `config.yml`, language files, `Layout.yml`, `Startpage.yml`, `QuickSell.yml`, `Sign.yml`, `Sound.yml`, `Worth_V2.yml`, `User.yml`, rotation data, logs and the MultiCurrency journal. It applies to data written by any earlier version of this fork and by the original upstream DynamicShop (last release 3.120.2). Updating must stay "replace the jar, restart": no manual steps, no lost settings, no reset shops.

- Never require deleting or regenerating `config.yml` or any other data file.
- Never rename or remove an existing key, or change what an existing value means, without migration code. Use `ConfigUtil` with a `PluginConfigVersion` bump, or `ShopUtil.BackwardCompatibility()`. Old values must keep working or be converted automatically.
- Add new settings with `addDefault(...)`, so they are added to existing files. When a built-in default text changes, rely on `DefaultsSync` (see "Localization"). Never print "please edit your file" banners.
- Read and write YAML only through `CustomConfig` (atomic save, never overwrites a file it could not read).
- Existing shops must behave the same after an update unless the change is the point of the release. A behaviour change for existing setups counts as data impact (see below).
- Test with existing data, not only with fresh files. Test data from an older release, and for format-sensitive changes, upstream-format data (see HANDOFF.md, "Upstream → this fork migration test").

**If a change is big enough to affect user data, stop and tell the maintainer before implementing it.** Examples: a manual step, a reset, a regenerated file, a key that cannot be migrated automatically, or a changed default behaviour for existing shops. Explain the impact and the alternatives, and let the maintainer decide. If it goes ahead, state it clearly in the release notes and the README.

## Conventions already in the codebase

- PascalCase method names exist throughout the original codebase (`GetCurrency`, `RefreshUI`, `SetupVault`) — match the surrounding file instead of converting naming piecemeal.
- Comments mix Korean and English — do not strip or "clean up" existing Korean comments.
- `CustomConfig` + `addDefault(...)` + `copyDefaults(true)` + `.save()` is the standard YAML pattern. Follow it for new config keys.
- `CustomConfig.save()` writes atomically. It refuses to overwrite a file that failed to parse at load time (a `.broken-<time>` copy is made). Never bypass this with a direct `YamlConfiguration.save(file)`. Code that lists a data folder must only load `*.yml` files.
- Lombok `@Getter`/`@Setter` is used on model classes such as `DSItem`.

## Releasing

Tag pushes publish releases through `.github/workflows/ds.yml`. GitHub generates the release notes automatically from the tag's commit history.

**Do not require, create, commit, or modify a tracked `RELEASE_NOTES.md`.** `RELEASE_NOTES.md` is intentionally a **local-only working file** for release preparation and must not be committed to this repository. If you use it locally to draft or review release notes, leave it untracked/ignored.

For a release:
1. Bump `<version>` in `pom.xml` (feature → minor, fixes only → patch). `plugin.yml` picks it up via `${project.version}`.
2. Update the "ahead of upstream" list in `README.md` if the change is something upstream doesn't have. Confirm the release follows "User data safety"; any data-impacting change must be called out in the release notes.
3. Commit and push the code/docs changes to `master`, then `git tag <version> && git push origin <version>` (tags in this repo have no `v` prefix).
4. Watch the tag workflow and confirm the GitHub Release and jar asset exist.

If a release needs a polished human-written summary, it may be drafted in the local-only `RELEASE_NOTES.md` or supplied directly when editing the GitHub Release, but **never add `RELEASE_NOTES.md` to a commit**.

## Test servers (local dev machine only, not part of this repo)

If similar local test servers exist, each typically has its own `plugins/` folder. Drop the built jar in, start the server using its own startup script, and check the latest console log for enable/disable errors. Testing against a realistic plugin set is a better signal than a bare-bones instance, especially for Vault-hook and Folia-scheduler changes.

**Version matrix (every release, and every change that touches the Bukkit/Paper API):** `tools/compat-matrix/` boots one throwaway server per server jar. It installs VaultUnlocked plus a test-only in-memory economy (`testeco/`, build with `testeco/build.sh`), then runs `createshop`/`add`/`enable`/`reload` from the console, stops the server and grades the log (`grade.sh`). See the header of `run-matrix.sh` for the folder layout. Server jars come from `https://fill.papermc.io/v3/projects/{paper,folia}/versions/<v>/builds/latest` and `https://api.purpurmc.org/v2/purpur/<v>/latest/download`. Cover at least the first and last build of each supported line: 1.21, 1.21.1, 1.21.4 (first with `Sound` as an interface), 1.21.11, 26.1.x, 26.2, plus Folia and Purpur. The matrix does not open GUIs (no player). GUI changes still need an in-game or bot test.

## Where to look for more detail

- `HANDOFF.md` — living session-continuity notes: what's currently mid-flight, what was verified, what's next. Update it before ending a session that leaves work unfinished; trim/archive it once a body of work fully lands and is verified.
- `README.md` — user-facing feature list and the "ahead of upstream" section.
