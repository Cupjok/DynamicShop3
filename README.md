[![Fork with Claude Code](https://img.shields.io/badge/Fork%20with-Claude%20Code-D97757?logo=claudecode&logoColor=white)](https://claude.com/claude-code)

Supported Minecraft versions: 1.16.5 - 26.2

Runs on **Paper, Purpur, and Folia**.

Using Java 21+ at runtime. **Building from source requires Java 25+** (Paper 26.2's API is compiled to Java 25 class files) — see `CLAUDE.md` if you're building the plugin yourself.

## Ahead of upstream

This fork (`Cupjok/DynamicShop3`) carries the following beyond the original upstream project:

- **Minecraft 26.2 support** — built against current Paper 26.2, `api-version` and dependencies updated to match.
- **Folia support** (`folia-supported: true`) — every scheduled task (UI refresh, shop rotations, log/backup timers, per-player chat/GUI timeouts) now goes through Paper's region/entity/global/async scheduler APIs instead of the legacy Bukkit scheduler, so the plugin is safe on Folia's per-region threading model. These same APIs behave exactly like the old scheduler on Paper/Purpur, so there's no behavior change there.
- **Broader Vault compatibility** — no longer requires a plugin literally named "Vault." Any Vault-API-compatible economy provider works, including **VaultUnlocked** and **CMIVault**, not just the original Vault plugin.
- **Jobs Reborn points currency, fixed and verified end-to-end** — the `jp` currency hook (buying/selling shop items for Jobs Reborn points) is fully wired and was fixed in a few places: a missing "Jobs not found" guard when reading a player's point balance, and general cleanup of a stray unused duplicate of the hook's code.
- **Bugfixes**: a wrong translation key that showed a raw untranslated string to players opening a PlayerPoints shop when PlayerPoints was disabled; file-handle leaks in the buy/sell log CSV reader and writer; a stock-simulator race that mutated a player's open inventory from a background thread; a chat-input-timeout map with a pre-existing thread-safety bug; a too-short retry window that could cause the plugin to disable itself if the server's economy provider registered late during a busy startup.
- Dependency bumps: PlaceholderAPI, VaultAPI, LocaleLib, opencsv, Lombok.
- Modernized CI (`.github/workflows/ds.yml`) — builds on the correct JDK and auto-publishes a GitHub Release with the built jar when a version tag is pushed.

## Forking / continuing development with AI

If you fork this repo and plan to keep working on it with Claude Code, Cursor, or any other AI coding tool, point that AI at **`CLAUDE.md`** first — it documents the architecture, the build quirks (including the Java 25 requirement above), the Folia scheduler conventions, and where things live in the codebase, so a new session doesn't have to rediscover it all. `HANDOFF.md` is a running log of what was in progress in the most recent work session, useful context if you're picking up right where it left off.

Free version https://www.spigotmc.org/resources/65603/

**Premium version** https://www.spigotmc.org/resources/100058/

**Donation** https://www.paypal.com/paypalme/7sat

Discord https://discord.gg/5HQjBqU

bStats https://bstats.org/plugin/bukkit/DynamicShop
