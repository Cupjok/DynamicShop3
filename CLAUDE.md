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

**Requires a JDK 25+ toolchain to build** (`maven.compiler.release` is `25`, since Paper 26.2's `paper-api` jar itself is compiled to Java 25 class files — building with an older JDK fails with `bad class file ... wrong version`). If the default `java`/`JAVA_HOME` on your machine is older, point Maven at one explicitly.

Running the built plugin on a server requires a JDK capable of running Java 25 bytecode (25+), consistent with the Paper 26.2 runtime requirement.

If Lombok-generated getters/setters appear missing after `mvn clean`, check that Maven is not running offline and that the compiler has explicit Lombok annotation processing enabled. Do not remove the Lombok `annotationProcessorPaths` block from `pom.xml`; keep its version synchronized with the Lombok dependency.

`maven-shade-plugin` is pinned to `3.6.2` because older versions cannot reliably analyze Java 25 class files.

CI: `.github/workflows/ds.yml` builds on push/PR to `master` and on tags. A tag push also builds the jar and publishes a GitHub Release with it attached.

## Runtime target

- Minecraft/Paper API: tracks current Paper releases (`paper-api` version in `pom.xml`, e.g. `26.2.build.121-stable`). Bump this and `api-version` in `plugin.yml` together when targeting a new MC version.
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

On Paper/Purpur these wrappers behave appropriately as well, so call sites should not add `isFolia()` branching. Do not use `Bukkit.getScheduler()` for new work.

## Conventions already in the codebase

- PascalCase method names exist throughout the original codebase (`GetCurrency`, `RefreshUI`, `SetupVault`) — match the surrounding file instead of converting naming piecemeal.
- Comments mix Korean and English — do not strip or "clean up" existing Korean comments.
- `CustomConfig` + `addDefault(...)` + `copyDefaults(true)` + `.save()` is the standard YAML pattern. Follow it for new config keys.
- Lombok `@Getter`/`@Setter` is used on model classes such as `DSItem`.

## Releasing

Tag pushes publish releases through `.github/workflows/ds.yml`. GitHub generates the release notes automatically from the tag's commit history.

**Do not require, create, commit, or modify a tracked `RELEASE_NOTES.md`.** `RELEASE_NOTES.md` is intentionally a **local-only working file** for release preparation and must not be committed to this repository. If you use it locally to draft or review release notes, leave it untracked/ignored.

For a release:
1. Bump `<version>` in `pom.xml` (feature → minor, fixes only → patch). `plugin.yml` picks it up via `${project.version}`.
2. Update the "ahead of upstream" list in `README.md` if the change is something upstream doesn't have.
3. Commit and push the code/docs changes to `master`, then `git tag <version> && git push origin <version>` (tags in this repo have no `v` prefix).
4. Watch the tag workflow and confirm the GitHub Release and jar asset exist.

If a release needs a polished human-written summary, it may be drafted in the local-only `RELEASE_NOTES.md` or supplied directly when editing the GitHub Release, but **never add `RELEASE_NOTES.md` to a commit**.

## Test servers (local dev machine only, not part of this repo)

If similar local test servers exist, each typically has its own `plugins/` folder. Drop the built jar in, start the server using its own startup script, and check the latest console log for enable/disable errors. Testing against a realistic plugin set is a better signal than a bare-bones instance, especially for Vault-hook and Folia-scheduler changes.

## Where to look for more detail

- `HANDOFF.md` — living session-continuity notes: what's currently mid-flight, what was verified, what's next. Update it before ending a session that leaves work unfinished; trim/archive it once a body of work fully lands and is verified.
- `README.md` — user-facing feature list and the "ahead of upstream" section.
