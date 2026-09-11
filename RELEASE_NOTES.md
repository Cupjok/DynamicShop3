<!--
Release notes for the NEXT release. Rewrite this file before tagging a version -
the CI release job publishes it verbatim as the GitHub Release body, with the
auto-generated commit changelog appended underneath. Write a real summary of what
changed and what it means for a server owner, never leave it as just a changelog
link. See the "Releasing" section in CLAUDE.md.
-->

## What's new in 3.24.0

Two new features: shops can now trade in **MultiCurrency** currencies, and shop names in the shop menu support **full hex colours and formatting**. Both are opt-in. Without MultiCurrency installed and without changing your shop names, everything looks and works exactly as before, and there is nothing to migrate.

### Shops can use MultiCurrency currencies (optional)

If you run the [MultiCurrency](https://github.com/Cupjok/MultiCurrency) plugin (Coins, Gems, Tokens, Event Points, ...), any shop can now buy and sell in one of its currencies. Your normal economy (CMI, EssentialsX, ... through Vault) is not touched. DynamicShop does not replace or re-register the Vault economy, and Vault/CMI shops keep working as they did.

**How to use it**

```
/ds shop <shop> currency multicurrency:<currency id>
```

For example, `/ds shop GemShop currency multicurrency:gems`. Tab completion lists your MultiCurrency currencies. You can also write `currency: MultiCurrency:gems` under `Options:` in the shop's yml. To switch a shop back, use `/ds shop <shop> currency vault` (or click the Vault button in the shop settings).

**What players see**

- Prices, the "my balance" button and the shop balance are shown in the currency's own format (for example `💎 10`).
- Prices are rounded to the currency's decimal places. Buying rounds up and selling rounds down. For example, a sell price of 7.5 in a 0-decimal currency pays 7.
- A purchase is only handed out after MultiCurrency confirms the payment, usually within the same second. If the player can't afford the whole amount, the purchase is refused as a whole with "Not enough balance". Unlike Vault shops, it does not buy as many items as the balance allows.
- If a player logs out while a purchase is being paid, the items are delivered the next time they join.
- If a sale can't be paid (for example, the player's balance would go over the currency's maximum), the items are given back.

**Why it's safe**

- Every purchase gets its own ID the moment it is clicked. The ID is saved to `plugins/DynamicShop/MultiCurrencyOrders.yml` before any money moves, and MultiCurrency receives it as an idempotency key.
- If the database hiccups and the result is unknown, DynamicShop asks again with the same key until it gets a definite answer. The same applies after a restart or crash. A payment is never applied twice, and a player is never told "failed" when the money might have been taken.
- If the server stops at the exact moment items are being handed out, the order is flagged in the log for an admin instead of being guessed. It is never given twice.
- Entries marked `REVIEW` in `MultiCurrencyOrders.yml` are the only ones that need you. The server log says what to check; delete the entry once handled.

`/ds shop <shop> account transfer <player> <amount>` from a MultiCurrency shop's account also pays out in that currency.

### Full colour and formatting for shop names

Shop titles (`Options.title` in the shop yml), the start page title and the start page buttons (`Startpage.yml`) — both the button name (**Rename**) and its description (**Change Lore**, `/ds` → shift + right-click a button) — now support every colour Minecraft can show, not just the 16 legacy colours:

| You write | You get |
|---|---|
| `&a`, `§a`, `&l`, `&o`, `&n`, `&m`, `&k`, `&r` | legacy colours and formats, as before |
| `#FF5555Name` or `&#FF5555Name` or `&x&F&F&5&5&5&5Name` | hex colour (bare `#FF5555` needs `UI.UseHexColorCode: true`, the default) |
| `#FF5555Red #00FFAAMint #7289DABlue` | several colours in one name |
| `&#FF5555&lBold` | hex + bold |
| `<#FF5555>`, `<red>`, `<bold>`, `<italic>`, `<underlined>`, `<strikethrough>` | MiniMessage colour/format tags |
| `<gradient:#FF5555:#7289DA>Fade</gradient>`, `<rainbow>Party</rainbow>` | gradients and rainbow |

Legacy-style colour codes reset bold/italic/etc. just like in vanilla, so put the colour first: `&#FF5555&lName`, not `&l&#FF5555Name`.

Only visual formatting is accepted. Click, hover, command, insertion, font and similar tags are not interpreted. They are shown as plain text, so a shop name can never run a command or change the menu. A typo in a tag never breaks the menu either; it just shows as text.

**Existing shops are not affected.** Plain names, `§`-coloured names and the default start page look exactly the same as before, and ordinary text with an ampersand such as `R&D Shop`, `A&B Shop` or `Shop & More` stays plain text. (A lower-case code or digit written directly after a word, like `Red&aGreen`, is still a colour code, as in classic Minecraft formatting.)

In button descriptions, `/` starts a new line (the start page's `LineBreak` setting), so don't write closing tags like `</gradient>` there; every tag ends automatically at the end of its line.

### Compatibility

- Server software: **Paper, Purpur and Folia** (and Paper/Purpur forks), Minecraft **26.2**. MultiCurrency itself does not support Folia, so the MultiCurrency feature is Paper/Purpur only; the colour feature works everywhere.
- MultiCurrency support needs **MultiCurrency 1.0.0 or newer** and is optional (`softdepend`).
- Economy: unchanged. Any Vault-API-compatible provider (Vault, VaultUnlocked, CMI economy, ...), or XP / PlayerPoints / Jobs Reborn points per shop.
- **No config or shop file changes are required.** Drop the new jar in and restart.

### Verified / not verified

Tested on a Purpur 26.2 server with CMI economy (through Vault), MultiCurrency 1.0.0 (SQLite), Jobs, LuckPerms, WorldGuard, PlaceholderAPI and others. Two test players (bots) did the following:

- bought and sold for gems and coins, with and without enough balance, and with limited stock
- fired five rapid purchases with only enough for two, and got exactly two
- logged out mid-purchase
- sat through restarts with unfinished orders, including a simulated "paid but server crashed before recording it", which resolved without charging twice and delivered once
- received a refund for an undeliverable purchase

Vault stayed on CMIEconomy and CMI purchases kept working. The coloured shop names and button descriptions (including editing a description in-game with Change Lore) were checked in the exact form the server sends them to the client: hex, several hex colours, bold, italic, gradient, `R&D Shop`-style text, and deliberately broken formatting.

Not verified: a real `OUTCOME_UNKNOWN` from MultiCurrency's database. It can't be triggered from outside MultiCurrency, so the retry logic for it is covered by automated tests and by the restart simulation. MariaDB/MySQL storage for MultiCurrency was not re-tested here, only SQLite.
