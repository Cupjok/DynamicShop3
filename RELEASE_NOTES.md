<!--
Release notes for the NEXT release. Rewrite this file before tagging a version -
the CI release job publishes it verbatim as the GitHub Release body, with the
auto-generated commit changelog appended underneath. Write a real summary of what
changed and what it means for a server owner, never leave it as just a changelog
link. See the "Releasing" section in CLAUDE.md.
-->

## What's new in 3.25.0

A small follow-up to 3.24.0: choose a shop's currency from a list in the shop settings, including every MultiCurrency currency. It also fixes the JobPoint and PlayerPoint buttons, which never actually worked. Nothing to migrate.

### Choose any currency from the shop settings

Open a shop's settings (right-click the shop info sign in the shop, as before). The new **Choose currency...** button shows the shop's current currency. Clicking it opens a list of every currency the server can use:

- Vault (your normal economy, e.g. CMI), Exp, JobPoint and PlayerPoint
- every currency configured in [MultiCurrency](https://github.com/Cupjok/MultiCurrency) (Coins, Gems, Tokens, ...), with its id, symbol and decimal places

The current currency is highlighted. A currency that can't be used right now is greyed out and says why, and clicking it changes nothing. Examples are Jobs or PlayerPoints not being installed, or a MultiCurrency currency being disabled. Click any other currency to switch the shop to it. You are taken back to the shop settings afterwards.

The `/ds shop <shop> currency ...` command still works as before.

### Fixed: the JobPoint and PlayerPoint buttons in the shop settings did nothing

Clicking **JobPoint** or **PlayerPoint** in the shop settings saved a value the plugin didn't recognise, so the shop silently kept trading in Vault money. Both buttons now really switch the shop.

If you clicked one of those buttons in the past, that shop has been trading in Vault money the whole time. Its yml file contains `currency: jp` or `currency: pp`. Those shops are left exactly as they are, so nothing changes without you noticing. Pick the currency again in the settings if you actually want JobPoint or PlayerPoint.

### Compatibility

- Server software: **Paper, Purpur and Folia**, Minecraft **26.2**. MultiCurrency itself doesn't support Folia, so its currencies only appear on Paper/Purpur.
- **No config or shop file changes are required.** Drop the new jar in and restart.

### Verified / not verified

Tested on a Purpur 26.2 server with CMI (Vault), Jobs and MultiCurrency 1.0.0. A test player with shop-edit permission opened the list and switched a shop to a MultiCurrency currency, then to JobPoint, then back to Vault. Clicking PlayerPoint (not installed on that server) was refused, and the shop file was checked after every change.

Not verified in-game: the PlayerPoint currency itself, because PlayerPoints isn't installed on the test server.
