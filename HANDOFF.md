# HANDOFF.md

Living session-continuity notes. Read `CLAUDE.md` first for the durable architecture reference — this file is the "what's actually going on right now" doc. Update it whenever you leave work mid-flight; trim it once things fully land and are verified (don't let it grow forever as a changelog — that's what git history / README are for).

## Session 2026-09-12 (after the 3.24.0 release): currency selector GUI — released as 3.25.0

- New `guis/CurrencySelector` (UI_TYPE `CurrencySelector`, opened with `DynaShopAPI.openCurrencySelector`): shop
  settings → new button **slot 31 "Choose currency..."** (shows the current currency) → 54-slot list of every currency:
  Vault, Exp, JobPoint, PlayerPoint (slots 0-3) and every MultiCurrency currency (slots 9-44). Current = yellow pane +
  "Selected"; unavailable (Jobs/PlayerPoints missing, MultiCurrency currency disabled) = gray pane + reason, click refused
  with the existing error message. Writes canonical `Options.currency` values (`Vault`, `Exp`, `JobPoint`,
  `PlayerPoint`, `MultiCurrency:<id>`) and returns to shop settings. 5 new lang keys (ko-KR + en-US).
- **Pre-existing bug fixed on the way**: the old ShopSettings JobPoint/PlayerPoint buttons wrote `jp`/`pp`, which
  `ShopUtil.GetCurrency()` has always read as **Vault** — clicking them silently kept the shop on Vault. They now write
  `JobPoint`/`PlayerPoint`. Shop files that already contain `jp`/`pp` are *not* reinterpreted (they have been trading
  in Vault money; switching them silently would change live prices' currency) — re-select the currency once.
- Tests: `mvn clean verify` 39/39 (new: selector values round-trip through `GetCurrency`). Real server (bot with the
  edit permission): shop info right-click → settings → slot 31 → picked gems (`MultiCurrency:gems` saved, button shows
  it), JobPoint (saved as `JobPoint`, shown Selected), PlayerPoint (not installed → refused, unchanged), Vault
  (restored). No errors.
- Released as **3.25.0** (user also verified in-game on the test server).

## Session 2026-09-11: MultiCurrency integration + full-colour shop names (3.24.0, not yet committed)

Two features, both opt-in: (1) shops can trade in a MultiCurrency currency, (2) shop names in the shop menu
accept hex/MiniMessage colours. The MultiCurrency project itself was **not** modified (hard rule for this work).

### What was built
- **MultiCurrency backend** — `Options.currency: MultiCurrency:<id>` (set with `/ds shop <shop> currency multicurrency:<id>`,
  tab-completed). Architecture, transaction rules and the idempotency design are written down in `CLAUDE.md`
  ("MultiCurrency integration") — read that before touching any of it. In short:
  - API only (`multicurrency-api` 1.0.0, vendored under `lib/` as an in-repo Maven repo because MultiCurrency is not
    on any public repository; the jar is the unmodified `MultiCurrency-API-1.0.0.jar` release asset, its `javap`
    signatures were diffed against the MultiCurrency source and are identical). `provided` scope, not shaded.
  - `MultiCurrencyHook` (no API types) / `MultiCurrencyBridge` (only API user) / `MultiCurrencyTrade` (engine +
    journal) / `MultiCurrencyOrder` / `MultiCurrencyOutcome`.
  - Buy: same quantity/stock/limit loop as `Buy.buy()` minus the balance check → stock + per-player limit reserved →
    order journaled → one atomic `withdraw` with key `dynamicshop3:buy:<uuid>` → items only after APPLIED.
  - Sell: items removed + `player.saveData()` → journaled → `deposit` (`dynamicshop3:sell:<uuid>`) → items returned if
    refused. Account payout: `dynamicshop3:payout:<uuid>`. Refund of an undeliverable paid buy: `dynamicshop3:refund:<uuid>`.
  - `OUTCOME_UNKNOWN`/exception → retried with the same key after 1 s, 3 s, 10 s, then every 60 s and on every start.
    After a possible earlier commit, only `INSUFFICIENT_FUNDS`/`BALANCE_LIMIT_EXCEEDED` (checked inside
    MultiCurrency's DB transaction after its key lookup — verified by reading `CurrencyService.execute`) plus a
    `history()` key lookup can cancel an order. `DUPLICATE_TRANSACTION` = the earlier attempt committed.
  - Journal `plugins/DynamicShop/MultiCurrencyOrders.yml`, atomic write. `GIVING` at startup → `REVIEW` (SEVERE log,
    never re-given). Unreadable journal → moved aside, never overwritten; invalid entries → logged + `.bak` copy.
  - Display: prices/balances in the currency's own `format()`, buy prices rounded up, sell prices down (as charged/paid).
  - Behaviour difference vs Vault shops (intentional, documented in RELEASE_NOTES/README): no partial fill when the
    player can't afford the full amount — a partial fill would need a balance read first (check-then-act).
- **Shop-name colours** — `utilities/ShopNameFormatter`, used for the shop GUI title (`Options.title`), start page title
  and start page button names. Syntax + safety rules in `CLAUDE.md` ("Shop-name formatting") and README.
  Only visual MiniMessage tags are registered; click/hover/insert/font/etc. stay literal; `sanitize()` strips any
  interactive style; malformed input never throws. Internal shop *file* names (ShopList, admin GUI titles, signs) are
  lookup keys and deliberately not formatted.

### Files changed
New: `economyhook/MultiCurrencyHook.java`, `economyhook/MultiCurrencyBridge.java`, `transactions/MultiCurrencyTrade.java`,
`transactions/MultiCurrencyOrder.java`, `transactions/MultiCurrencyOutcome.java`, `utilities/ShopNameFormatter.java`,
tests `transactions/MultiCurrencyTest.java`, `utilities/ShopNameFormatterTest.java`, `lib/` (vendored API jar + pom).
Modified: `Buy`, `Sell` (MultiCurrency branches; quick-sell item removal extracted into `RemoveQuickSellItems`, no
behaviour change; `RunBuy/SellCommand` name-based overloads), `Shop`, `ItemTrade`, `StartPage`, `ShopSettings` (a
MultiCurrency shop no longer shows as "Vault"), `Account`, `commands/shop/Currency`, `ShopUtil` (`GetCurrency` never
falls back to Vault for a MultiCurrency shop; quick-buy groups each MultiCurrency currency separately),
`TabCompleteUtil`, `LangUtil` (13 keys, ko-KR + en-US), `JoinQuit`, `DynamicShop`, `Constants`, `plugin.yml`
(softdepend), `pom.xml` (3.24.0, repo + dependency), `.gitignore` (allow `lib/**/*.jar`), docs.

### Tests
- **Unit (automated, `mvn clean verify`)**: 33 tests at first, 38 after the final pass (below), 0 failures. `ShopNameFormatterTest` (19): plain, legacy `&`/`§`,
  single hex in 7 spellings, lowercase hex, multiple hex, hex+bold, hex+italic, multiple formats + gradient, all legacy
  formats, 23 malformed inputs, 11 interactive/unsafe tags (click/hover/insert/font/key/lang/selector/score/nbt/newline),
  sanitize(), item names non-italic by default, length cap, existing config compatibility (Sample Shop, default
  Startpage.yml, `§3`+name buttons). `MultiCurrencyTest` (14): outcome classification for every FailureReason
  (first attempt vs after possible commit), idempotency keys (stable, distinct refund key, ≤128 chars), journal
  round-trip, invalid entries rejected, amount rounding (CEILING/FLOOR, float noise, scale), currency-id validation,
  shop currency parsing (never Vault for a broken MultiCurrency id), atomic journal write.
- **Integration tests**: none separate from the above (no MockBukkit in this project); the end-to-end behaviour was
  covered on the real server instead.
- **Real server + bots (2026-09-11)** — designated local test server "Survival SMP Purpur 26.2 test" (Purpur 26.2 build
  2632, CMI 9.8.9.10 economy via Vault 1.7.3-CMI, MultiCurrency 1.0.0 on SQLite, Jobs, LuckPerms, WorldGuard, PAPI,
  MMOItems, ViaVersion/ViaBackwards, …). Two offline-mode Mineflayer bots (MCBotA/MCBotB, protocol 26.1 via
  ViaBackwards) drove the real GUIs; balances checked with `/cbal` and the MultiCurrency ledger (`/currency history`).
  - Load: MultiCurrency now enables before DynamicShop (softdepend); `'MultiCurrency' Found`; no DynamicShop
    warnings/errors on enable or disable. `vault-info` → Economy: CMIEconomy (DynamicShop does not register one).
  - Buy 1 diamond (10 gems): 100→90, +1. Buy 2: →70, +2. Sell 1 (7.5 → 7, 0 decimals): →77. Coins (2 decimals):
    2.50 charged exactly (95.50→93.00). Insufficient (5 gems, price 10): refused, no item, balance unchanged.
  - Burst: 25 gems, 5 rapid buy clicks → exactly 2 bought, 2 "not enough" (5th click dropped client-side), balance 5,
    +2 diamonds; ledger shows exactly two withdraws.
  - Finite stock 5 → bought 4 (40 gems), stock 1 (the existing "keep 1" rule).
  - Vault/CMI shop on the same server: bought for 1.00, CMI 1000.00→999.00 — unchanged behaviour.
  - Disconnect right after the buy click: charged once, item delivered before the quit was processed and kept on rejoin.
  - Restart recovery with a crafted journal: PENDING buy → charged 10 once → delivered on join; PENDING sell → paid 7
    once; PENDING buy the player can't afford → cancelled, not charged; GIVING → REVIEW + SEVERE log, not delivered;
    OWE_ITEMS with an unreadable item → refunded with the refund key on join. Then the "paid but crashed before
    recording" case: the same buy re-marked PENDING with its original key → resent → resolved as already paid, **no
    second withdraw in the ledger**, delivered exactly once, nothing more on rejoin.
  - A deliberately broken journal (bad YAML structure) was handled: entries logged as invalid, `.bak` copy kept.
  - Shop GUI titles and start-page button names checked in the exact component data the server sends the client:
    plain (dark_aqua), legacy green/gold, `#FF5555`, `#FF5555`/`#00FFAA`/`#7289DA` in one name, hex+bold, hex+italic,
    gradient per letter, mixed segments, malformed name shown as literal text with no click/hover event; button names
    not italic unless asked. An unknown currency (`MultiCurrency:does_not_exist`) refuses to open with a clear message.
  - The user's own client was also connected during the test and opened `/ds`; pixel-level visual confirmation by a
    human is not recorded here.
- **Not tested**: a real `OUTCOME_UNKNOWN` from MultiCurrency's database (cannot be injected from outside without
  changing MultiCurrency; covered by unit tests + the restart simulation); MultiCurrency on MariaDB; Folia (MultiCurrency
  doesn't support it).

### Final regression pass (2026-09-12)
- **`&` false positives fixed** (`ShopNameFormatter.toMiniMessage`): an `&` glued between a letter/digit of normal text
  and an UPPER-case letter is literal text (`R&D Shop`, `A&B Shop`, `M&M`, `Fish&Chips`); `& ` was already literal.
  Unchanged: codes at the start / after a space / after another code (`&AGreen`, `&c&LBold`), lower-case codes glued to
  text (`Red&aGreen`), digits, `&#RRGGBB`, `&x&…`, `§` (never text), MiniMessage.
- **Change Lore colours fixed**: start-page button lore (`/ds` → shift + right-click a button → Change Lore; stored by
  `OnChat` as `"§f" + input`, split by `Options.LineBreak`) now goes through the same `ShopNameFormatter`
  (`formatLore`) as Rename, including the admin hint lines (legacy `§` codes, rendered the same as before).
  Note: `/` is the default lore *line separator*, so a MiniMessage closing tag (`</gradient>`) inside lore splits the
  line — tags auto-close at the end of each line anyway, so just omit the closing tag (pre-existing LineBreak rule).
- **Automated**: `mvn clean verify` → 38 tests, 0 failures (5 new: `&` in normal text, legit legacy/hex codes next to
  text, Change Lore formatting, malformed lore, existing plain/legacy lore unchanged).
- **Real server** (same designated server, new 3.24.0 build deployed and verified by jar hash, Mineflayer bots):
  `/ds` start page — names and lore for plain, legacy, hex, 3 hex colours in one line, hex+bold, italic+underline,
  strikethrough+obfuscated, gradient, 3-line lore, `R&D & More` / `A&B Shop` / `Shop & More` as plain text,
  malformed lore shown as text with no click/hover event. Shop GUI titles `R&D Shop`, `A&B Shop`, `Shop & More`
  plain (dark_aqua). The real Change Lore editor (GUI + chat input) was driven by a bot with the edit permission:
  `&aGreen &#FF5555Hex&lBold/R&D & More/&#00FFAA&oMint italic/<click:…>Evil</click> <bold &z` rendered correctly,
  no events. Regression: MultiCurrency buy (70→60 gems, +1), CMI/Vault buy (999.00→998.00), finite stock 5 → 4 bought
  → stock 1, restart recovery (pending buy charged once and delivered on join; unaffordable pending buy cancelled,
  not charged), duplicate protection (same key re-sent after a simulated crash → no second withdraw in the
  MultiCurrency ledger, item delivered exactly once, nothing on rejoin). No DynamicShop errors in the log.
- **`/shop`**: the released 3.23.1 jar (pre-3.24.0) was deployed on the same server and behaves identically (`/ds shop`
  opens the start page, `/shop` opens nothing for the bots) → **pre-existing, out of scope, left untouched**.
- The sell-side crash window below is unchanged (documented, not redesigned in this pass).

### Known limitations / risks
- Unresolved (unknown) orders keep their stock reservation until resolved; stock/limit reservations are only given back
  for orders placed in the same server run (after a restart we can't know if the reservation reached the shop file).
- Crash windows that cannot be closed without MultiCurrency/Bukkit changes: a sell crash between `saveData()` and the
  journal write (same tick, milliseconds) loses the items unrecorded; a crash while items are being given → `REVIEW`
  (admin decides). Both are logged or bounded, never silent double payment/delivery.
- The journal (fsync) and `player.saveData()` are written on the main thread per MultiCurrency trade — fine for normal
  shop traffic, worth watching on very busy servers.
- A payout (`account transfer`) checks the shop balance when issued, not when MultiCurrency confirms.
- A sell confirmed while the player is offline skips the per-player trade-limit record and the Bukkit event.
- `&` + a lower-case colour letter or a digit glued to text is still a colour code (`Red&aGreen`, `Buy 1&2` → `&2`),
  matching classic legacy behaviour; only the upper-case-after-a-word case (`R&D`) is treated as text.
- `/shop` does not open the start page for the test bots while `/ds` and `/ds shop` do — confirmed pre-existing (the
  released 3.23.1 behaves the same); not investigated further.

### Test server state left behind
Final 3.24.0 jar deployed as `plugins/DynamicShop-3.24.0.jar` (hash matches the build; the previous
`DynamicShop-3.22.0.jar` was removed, it is on the 3.22.0 GitHub release). Test fixtures kept for a manual look: shops
`Title*.yml` (incl. `TitleRD`, `TitleAB`, `TitleMore`), `MCGems.yml`, `MCCoins.yml`, `MCBroken.yml` in
`plugins/DynamicShop/Shop/`, and a test `Startpage.yml` with coloured lore (original saved next to it as
`Startpage.yml.pre-colour-test`). Delete those and restore the original start page when done. Bots removed from the
whitelist and their temporary edit permission removed; crafted journal entries and journal backups removed. Bot MultiCurrency/CMI balances remain in those plugins'
data (like MultiCurrency's own earlier bot test).

### Next steps
1. Optional human visual check of `/ds` colours on the test server, then remove the fixtures.
2. Commit, push, tag `3.24.0` (see "Releasing" in CLAUDE.md; `RELEASE_NOTES.md` is written).
3. Possible follow-up: the pre-existing `/shop` start-page behaviour.

---

## Current effort (started 2026-09-08): MC 26.2 upgrade + Folia support + Jobs hook + bugfixes

Goal, in the user's words: upgrade to latest game version (26.2), bring back the Jobs Reborn points hook, fix visible bugs, support Paper + Purpur + Folia (and popular Vault-compatible economy plugins), document what's new vs upstream in README, commit/push/build a release, test on the local test servers.

### Done
- `pom.xml`: version → `3.22.0`, `paper-api` → `26.2.build.121-stable` (matches the local test servers' Paper build 121), bumped `PlaceholderAPI` 2.11.6→2.12.3, `VaultAPI` 1.7→1.7.1, `LocaleLib` 4.1.0→4.1.5, `opencsv` 5.9→5.10, `lombok` 1.18.34→1.18.48, `maven-shade-plugin` 3.6.0→3.6.2. `Jobs` stayed at `v5.2.2.3` (see "Known traps").
- `plugin.yml`: `api-version` → `'26.2'`, added `folia-supported: true`, moved `Vault` from hard `depend` into `softdepend` (now `softdepend: [Vault, PlaceholderAPI, Jobs, LocaleLib, PlayerPoints]`, `depend` removed).
- **Build was actually broken by the Java-version jump and it wasn't obvious** — see "Java 25 build requirement" below, this ate most of the session's time. Fixed; `mvn clean package` now succeeds end-to-end producing `target/DynamicShop-3.22.0.jar`.
- `.github/workflows/ds.yml`: was building with JDK 8 (broken — project requires much newer) and had a dead `{BUILDID}` sed step referencing a placeholder that no longer exists in `pom.xml`. Fixed to JDK 25 (matching the real requirement, see below), added a `release` job that runs on tag pushes and publishes a GitHub Release with the built jar via `softprops/action-gh-release`.
- **Folia scheduler migration** (done by a background fork) — replaced every `Bukkit.getScheduler()` / `BukkitRunnable` / `BukkitTask` call site with Paper's region/entity/global/async scheduler APIs via new `utilities/SchedulerUtil.java`. Touched: `DynamicShop.java`, `guis/UIManager.java`, `guis/LogViewer.java`, `guis/StockSimulator.java`, `utilities/ShopUtil.java`, `utilities/RotationUtil.java`, `commands/SetTax.java`, `events/OnChat.java`, `UpdateChecker.java`. Rotation tasks (`RotationUtil`) use the global region scheduler (rotation only touches YAML config files, never a block/`Location`, so no location-scheduling was needed — confirmed by reading `ApplyNextRotation`/`ApplyRotation` fully). `RotationUtil.CancelAllRotationTasks()` added, called from `onDisable`.
- **Vault-fork compatibility** — `DynamicShop.SetupVault()` no longer hard-requires a plugin literally named `Vault`; it only logs informationally and always calls `SetupRSP()`, which is now the sole path that can `disablePlugin` (after its existing 3-retry `Economy`-service-registration check fails). This means CMIVault, VaultUnlocked, or any other Vault-API-compatible provider works even if it doesn't register under the exact name "Vault". (VaultUnlocked's own `plugin.yml` happens to declare `name: Vault` itself, so it always worked either way — this fix mainly matters for others like CMIVault.)
- **Dead code removed** — `src/main/java/me/sat7/dynamicshop/jobshook/JobsHook.java` (and the now-empty `jobshook/` package) was a byte-for-byte unused duplicate of `economyhook/JobsHook.java`, zero references anywhere. Deleted.
- **Jobs Reborn points hook**: turned out to *not* be missing at all. `economyhook/JobsHook.java` is fully implemented and wired into `Buy.java`, `Sell.java`, `ItemTrade.java`, `Shop.java`, `ShopSettings.java`, `DynamicShop.hookIntoJobs()`, and both `ko-KR`/`en-US` lang strings already existed. `Jobs` was already in `plugin.yml`'s softdepend list. The user believed they'd deleted it; they hadn't, or it was reverted/merged back before this session. **README should say "verified and fixed", not "restored"** — see bugs fixed below for what was actually wrong with it.
- **Bugs fixed** (by the fork, file:line, failure scenario):
  1. `guis/Shop.java` — wrong lang key `ERR.PLAYER_POINT_NOT_FOUND` (singular) vs the real key `ERR.PLAYER_POINTS_NOT_FOUND`; a player opening a PlayerPoints-currency shop with PlayerPoints disabled saw the raw untranslated key instead of a message.
  2. `economyhook/JobsHook.getCurJobPoints` — no `jobsRebornActive` guard (unlike `addJobsPoint`); if Jobs is disabled/crashes while a JobPoint-currency trade UI is open, the periodic UI refresh would NPE repeatedly for that player. Added guard + null-check, returns 0.0 safely.
  3. `utilities/LogUtil.SaveLogToCSV` — `CSVWriter` never closed on the exception path; runs on a forever-repeating async timer, so a persistent write failure (disk full/permissions) leaked a file handle every cycle indefinitely. Now try-with-resources.
  4. `utilities/LogUtil.LoadDataFromCSV` — `CSVReader` never closed at all; leaked a file handle every time a player opened the Log Viewer GUI. Now try-with-resources.
  5. `guis/StockSimulator.RunSimulation()` — used to mutate the player's live open inventory from a background async thread throughout the loop, not just at the end (unsafe on vanilla Bukkit too, not just Folia). Now runs entirely on the player's entity scheduler.
  6. `events/OnChat.runnableMap` — plain `HashMap<UUID,Integer>` read/written from `AsyncPlayerChatEvent` (genuinely async) and scheduler-callback threads concurrently — pre-existing thread-safety bug independent of Folia. Changed to `ConcurrentHashMap<UUID,ScheduledTask>`; cancellation now uses `.remove()` (also fixes a stale-entry map leak on disconnect-without-further-chat).
  7. One more legacy `org.apache.commons.lang.ArrayUtils` usage in `StockSimulator.java` was relying on an undeclared transitive dependency that isn't reliably present (see Jobs POM note below) — replaced with a plain loop, no functional change, and removes a fragile hidden dependency.
- `CLAUDE.md` created and kept up to date with the Java 25 build requirement.

### Java 25 build requirement — the big time sink this session, read before touching the build again
None of this was caused by our edits — it's an artifact of jumping `paper-api` forward to 26.2. Three independent things had to all be fixed together, and any one missing reproduces confusing, misleading errors:
1. **Paper 26.2's `paper-api` jar is compiled to Java 25 class files** (major version 69). Building with `maven.compiler.source/target=21` (the old setting) makes javac refuse to even link against it: `bad class file ... wrong version 69.0, should be 65.0`. Fixed: `pom.xml` now sets `<maven.compiler.release>25</maven.compiler.release>` (replacing separate source/target).
2. **JDK 25+ disabled implicit annotation processing** — javac no longer auto-discovers annotation processors (like Lombok) on the classpath unless explicitly told to. Maven's compiler plugin relies on that implicit discovery unless you configure `<annotationProcessorPaths>` yourself. Without the fix, Lombok silently generates *zero* getters/setters, with no warning at all, which cascades into dozens of misleading "cannot find symbol" errors on completely unrelated classes (`DSItem`, `ConfigUtil`) that look like a dependency-resolution problem but aren't. Fixed: `maven-compiler-plugin` config in `pom.xml` now explicitly declares Lombok under `<annotationProcessorPaths>`. **If you ever bump the Lombok version, update it in both places** (the `<dependencies>` entry and this block) — Maven doesn't keep them in sync automatically.
3. **`maven-shade-plugin` 3.6.0's bundled ASM can't read Java 25 class files** during its `minimizeJar` analysis: `Unsupported class file major version 69`. Fixed: bumped to `3.6.2`.

This machine's default `java`/`JAVA_HOME` is JDK 26 (Homebrew keg), which can *run* Java-25-bytecode fine but was not what surfaced the above cleanly — `openjdk@25` was installed via `brew install openjdk@25` for a known-good build JDK. To build:
```
export JAVA_HOME="$(brew --prefix openjdk@25)"
mvn clean package
```
**Do not "fix" apparent Lombok/dependency errors by fiddling with the Jobs dependency version** — that was a red herring chased at length before the real cause (above) was found. See next section.

### Known traps / things not to redo
- **Jobs (`com.github.Zrips:Jobs`) jitpack POM is invalid at essentially every tag** (`v5.2.2.3` *and* `v5.2.6.3` both throw `'dependencies.dependency.systemPath' ... must specify an absolute path` for several of Jobs' own optional soft-integrations, e.g. `de.keyle:mypet`, `me.arsmagica:pyrofishingpro`, `net.Zrips:CMILib` depending on tag). This is a Maven **POM validation** error (happens before dependency-graph pruning), so `<exclusions>` in our pom.xml cannot work around it — don't waste time on that again. Maven treats it as non-fatal (drops Jobs' transitive deps and continues), which is fine since we only need Jobs' own classes (`com.gamingmesh.jobs.*`) at `provided` scope — we never needed its transitive deps anyway, once the one accidental `commons-lang` usage above was removed. Net effect: this warning is permanently expected in build output and is not itself a real problem; **it looked like the cause of the Lombok breakage but was a coincidence** — the real cause was the Java 25 issue above, discovered only after ruling this out empirically (reverting Jobs version, then removing Jobs entirely, and the Lombok failure persisted both times).
- Don't re-run `mvn -o` (offline) to "quickly check" compile — it also breaks annotation processing (for a different reason than the JDK-25 one) and produces the same misleading symptom. Always compile online.
- Don't spawn a second fork for work already assigned to a running one — earlier in this session two forks were briefly running concurrently on overlapping files (`DynamicShop.java`) by mistake; the duplicate was caught and killed within ~10s before it edited anything, but be careful: use `SendMessage` to an already-running fork for follow-up instructions on the same files, not a fresh `Agent` call.

### Test results (all 4 local servers, console/log verification — no in-game client)
- **Survival SMP Purpur 26.2 test**: enabled cleanly. Real Jobs plugin (5.2.6.3, installed this session alongside its `CMILib` hard-dependency) detected (`'Jobs Reborn' Found`). Vault here is CMI's own bundled shim (`Vault 1.7.3-CMI`) — found and hooked with zero code changes needed, direct validation of the Vault-fork-compatibility fix. `/ds reload` works. Zero DynamicShop-related exceptions.
- **Survival SMP Paper 26.2 test**: enabled cleanly. Vault here is VaultUnlocked 2.20.2 (self-declares `name: Vault`). Zero exceptions anywhere in the log.
- **Survival SMP Purpur 26.2 Optimize**: DynamicShop self-disabled ("Disabled due to no Vault dependency found!"). **Not a bug** — confirmed by checking the log that *every* other Vault-dependent plugin on this specific server (MMOItems, ProtectionStones, CMI, ItemEdit, Citizens, EnesOrder, SmartSpawner, ProfitMultiplier) also reports no working economy backend (`"Vault was found but economy engine is missing"`, `"Not Hooked"`, etc.) — this server genuinely has no EssentialsX/CMI-economy configured, unrelated to our changes. Correct, consistent behavior. Along the way, bumped `SetupRSP()`'s retry budget from 3 to 15 attempts (`DynamicShop.java`) since 3 (~6s) is a thin margin for a provider that registers late in a busy startup on some servers — genuine defensive improvement, kept even though it wasn't the actual cause here.
- **Survival SMP Folia 26.2 test**: enabled cleanly via `folia-supported: true`. Directly confirmed Folia enforces that flag: the same startup log shows Canvas (Folia fork) **refusing to load** `ForbiddenEnchants v1.0.3` with `"not marked as supporting Folia"` — proof our flag is load-bearing, not cosmetic. Economy here is zEssentials via VaultUnlocked. `/ds reload` works. No DynamicShop-related exceptions (two unrelated ones in the log: Canvas rejecting ForbiddenEnchants, and PhoenixCrates' license-server auth failing, both pre-existing/unrelated).
- All 4 servers were stopped after their test (`stop-bg.sh`). `Purpur 26.2 Optimize` and `Purpur 26.2 test` share `server-port=25565` — never run both at once.
- Not tested: actual in-game GUI interaction (shop browsing, buy/sell clicks, Jobs-point trades) — no Minecraft client available in this environment. What's verified is "loads, hooks correctly, no exceptions, reload works" across all 3 platforms plus the one deliberately-absent-economy edge case.

### Done (session complete)
- README.md updated with an "ahead of upstream" section (Paper/Purpur/Folia 26.2 support, Vault-fork compatibility, Jobs hook fix, bugfixes, dependency bumps, Java 25 build note); "Supported Minecraft versions" line now ends at `26.2`.
- Committed and pushed to `master` (`7e03f1b`, then `ec3f18c` for a small CI follow-up — see below).
- Tagged `3.22.0` and pushed the tag.
- Published the GitHub Release at `3.22.0` (`gh release create 3.22.0 target/DynamicShop-3.22.0.jar --generate-notes`) with the locally-built, tested jar attached: https://github.com/Cupjok/DynamicShop3/releases/tag/3.22.0

### CI note — fork Actions needed a one-time manual enable (resolved)
Pushing the `3.22.0` tag did not auto-trigger the CI workflow at first, even though `push`/`tags` triggers were configured correctly and `repos/.../actions/permissions` reported `enabled: true`. Diagnosed by adding a `workflow_dispatch:` trigger and manually running it (`gh workflow run ds.yml --ref master`) — that run succeeded end-to-end on JDK 25 in ~50s, proving the workflow itself was correct. Root cause: the repo is a **fork** of `Telesphoreo/DynamicShop3`, and GitHub disables automatic push/PR-triggered Action runs on forks until the owner manually clicks through a one-time consent in the web UI (Actions tab → "I understand my workflows, go ahead and enable them") — no API/`gh` CLI way to do that for someone. The user has since enabled it themselves. If a future push/tag still doesn't trigger a run, re-check `gh api repos/Cupjok/DynamicShop3/actions/runs` for an `event: push` entry to confirm before assuming it's broken again.

Everything the user asked for in this session is done: MC 26.2 upgrade, Folia support (+Paper/Purpur), Jobs Reborn points hook fixed, Vault-fork compatibility (VaultUnlocked/CMIVault), bug sweep, README updated, tested on all 4 local servers, committed, pushed, tagged, and released.

---

## Session 2026-09-09: decoration items can show their own name/lore

Feature request (user's words): when you place a decoration item into a shop in admin edit mode (left-click an empty slot → right-click an item in your own inventory), there should be a choice whether that item's name and lore are displayed. If chosen, its NBT (name/lore/enchants/etc.) shows normally; if not, the plugin's original behavior (blank name, everything hidden) applies.

### What was implemented
- New per-slot key in the shop yml: `<slot>.showMeta: true`. Absent = old behavior (hidden), so **every existing shop is unchanged by default**.
- `guis/Shop.java`
  - `ShowItems()` deco branch: when `showMeta` is set, the item's stored `ItemMeta` is used as-is (no forced `" "` display name, no `HIDE_ATTRIBUTES`/`HIDE_ENCHANTS`/`HIDE_ADDITIONAL_TOOLTIP` flags) and its own lore is kept, with the admin-only hint lines appended below it. When unset, the original code path runs untouched.
  - `OnClickItemSlot()`: **left-click on a decoration item** (admin, `dynamicshop.admin.shop.edit`) toggles the flag, saves, syncs rotation data, messages the player, and refreshes the UI. Left-click on a *tradable* item still opens the trade GUI (that branch is checked first), and left-click on a deco previously did nothing at all, so no existing interaction was taken over.
  - The item's tooltip carries the hint line (`SHOP.DECO_SHOW_META_LORE` / `SHOP.DECO_HIDE_META_LORE`) so the toggle is discoverable in-GUI without docs.
- `utilities/LangUtil.java` — 4 new keys in **both** `ko-KR` and `en-US` blocks: `SHOP.DECO_SHOW_META_LORE`, `SHOP.DECO_HIDE_META_LORE`, `MESSAGE.DECO_META_SHOWN`, `MESSAGE.DECO_META_HIDDEN`.
- `utilities/ShopUtil.java` — `addItemToShop()` clears `showMeta` when (re)filling a slot, so a new item never inherits the previous occupant's display setting.
- `utilities/RotationUtil.java` — `showMeta` is written into rotation data (`CreateRotationDataFromCurrentShop`, `UpdateCurrentRotationData`) and restored in `ApplyRotation`. **Also fixed a pre-existing quirk found here**: `ApplyRotation` restored only `.mat` for deco entries and silently dropped `.itemStack`, so decoration items lost all their metadata (custom name, lore, potion type, ...) on every rotation. It now restores `.itemStack` too — required for this feature to survive a rotation, and a fix in its own right.

### Verified
- `mvn clean package` succeeds on JDK 25 (`target/DynamicShop-3.22.0.jar`), unit tests pass.
- **Not** verified in-game (no Minecraft client in this environment): the actual toggle click, the two tooltip appearances, and rotation round-tripping of a deco item with custom meta. Worth a manual pass on a local test server before releasing.

### Release process, written down (2026-09-09)
User feedback after the 3.23.0 release: the GitHub Release body was just the auto-generated `**Full Changelog**: ...` link, which is useless to a server owner deciding whether to update. Every release needs a real written summary of what changed and what it means for them.

- The 3.23.0 release notes were rewritten in place (`gh release edit`) with a proper summary.
- `RELEASE_NOTES.md` (repo root) is now the body for the *next* release; `.github/workflows/ds.yml`'s release job passes it as `body_path` and still appends the auto-generated commit changelog underneath.
- The full release sequence and what the notes must cover is documented in the new "Releasing" section of `CLAUDE.md` (mirrored into `AGENTS.md`).
- **Rewrite `RELEASE_NOTES.md` as part of the change, before tagging** — if it's left stale, the next release ships the previous version's notes.
