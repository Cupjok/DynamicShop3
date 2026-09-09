<!--
Release notes for the NEXT release. Rewrite this file before tagging a version -
the CI release job publishes it verbatim as the GitHub Release body, with the
auto-generated commit changelog appended underneath. Write a real summary of what
changed and what it means for a server owner, never leave it as just a changelog
link. See the "Releasing" section in CLAUDE.md.
-->

## What's new in 3.23.1

A bugfix release. No new features, no config changes, nothing to migrate — every existing shop, price and setting keeps working exactly as before.

### Fixed: the shop editor crashed when deleting an item you had not added yet

If you opened the item palette (left-click an empty slot in shop edit mode), then **shift + left-clicked** an item to jump straight into its settings screen, and then pressed the **Remove** button (the bone), the plugin threw an error and the screen stopped responding:

```
Could not pass event InventoryClickEvent to DynamicShop
java.lang.NullPointerException: Cannot invoke "String.getBytes()" because "mat" is null
```

That settings screen is opened *before* the item exists in the shop, so the delete code was trying to look up a shop slot that was still empty. It now recognises an empty slot and simply closes back to the shop, as you would expect.

Nothing else in the shop was affected when this happened — no data was lost, it was purely the click that failed — but the error was spammed to the console every time an admin hit that button, and the GUI had to be closed and reopened.

The same missing-data check was applied to a few other places that read a shop slot's material (item lookup and the shop rotation code), so a shop file that was hand-edited or left half-written can no longer produce the same crash there.

### Fixed: a scary "Fatal error!" message the very first time the plugin starts

On a brand-new install, the console printed:

```
Fatal error! Config Setup Fail. File name: User
```

The plugin was trying to create `User.yml` a moment before its own `plugins/DynamicShop/` folder existed. It recovered on its own and the file was created anyway, so nothing was actually broken — but the wording made it look like a serious failure on a first boot. The plugin now creates its data folder first, so the message is gone.

### Compatibility

- Server software: **Paper, Purpur and Folia** (and Paper/Purpur forks), Minecraft **26.2**.
- Economy: any Vault-API-compatible provider (Vault, VaultUnlocked, CMIVault/CMI economy, ...), or XP / PlayerPoints / Jobs Reborn points per shop.
- **No config or shop file changes are required.** Drop the new jar in and restart.

### Verified / not verified

Verified in-game on a Purpur 26.2 server with a full plugin set (CMI economy via Vault, LuckPerms, WorldGuard, PlaceholderAPI, MMOItems and others): the crash above no longer occurs, and deleting shop items normally still works and is saved correctly.

The first-run "Fatal error!" fix was verified by code inspection and a clean build only — reproducing it requires a completely fresh install, which was not re-run on a test server.
