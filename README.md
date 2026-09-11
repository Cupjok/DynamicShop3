# 🛒 DynamicShop3

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.16.5--26.2-green">
  <img src="https://img.shields.io/badge/Paper-Purpur-Folia-blue">
  <img src="https://img.shields.io/badge/Java-21%2B-orange">
  <img src="https://img.shields.io/github/v/release/Cupjok/DynamicShop3">
  <img src="https://img.shields.io/badge/Fork%20by-Claude%20Code-orange">
</p>

<p align="center">
  A powerful and customizable dynamic shop plugin for modern Minecraft servers.
</p>

> A maintained fork of DynamicShop with additional Minecraft version support, Folia compatibility, broader economy support, bug fixes, and new features.

## ✨ Features

- 📈 **Dynamic pricing** — Prices change based on shop activity and configured rules
- 🛒 **GUI-based shops** — Create and customize shops for your server
- 💰 **Multiple currencies** — Vault-compatible economies, PlayerPoints, Jobs Reborn points, and optional MultiCurrency
- 🌐 **Folia support** — Designed for Paper, Purpur, and Folia
- 🎨 **Modern text formatting** — Legacy colors, hex colors, gradients, and supported MiniMessage visual tags
- 🔄 **Shop rotations** — Configurable stock and rotating shops
- 📦 **Item metadata support** — Decorative items can optionally keep their names, lore, and tooltip information
- 🛡️ **Safe transactions** — Protection against incomplete and duplicate payments
- 💾 **Persistent data** — Unfinished MultiCurrency orders survive restarts and can be recovered safely

## 🎮 Supported Platforms

| Platform | Support |
|---|---|
| Paper | ✅ |
| Purpur | ✅ |
| Folia | ✅ |

### Minecraft Versions

**1.16.5 – 26.2**

### Java

- **Runtime:** Java 21+
- **Building from source:** Java 25+

> Paper 26.2's API is compiled for Java 25, so Java 25+ is required when building from source.

## 💰 Economy & Currency Support

| Integration | Type | Description | Link |
|---|---|---|---|
| Vault-compatible Economy | Economy | Works with Vault API-compatible economy providers | [Vault](https://www.spigotmc.org/resources/vault.34315/) |
| PlayerPoints | Points | Use PlayerPoints as a shop currency | [PlayerPoints](https://www.spigotmc.org/resources/playerpoints.80745/) |
| Jobs Reborn | Points | Use Jobs Reborn points through the `jp` currency integration | [Jobs Reborn](https://www.spigotmc.org/resources/jobs-reborn.4216/) |
| MultiCurrency | Currency | Use server-defined currencies such as Coins, Gems, and Tokens | [MultiCurrency](https://github.com/Cupjok/MultiCurrency) |

MultiCurrency is optional and uses the plugin's public API with transaction protection and recovery for unfinished orders.

## 📈 Dynamic Pricing

DynamicShop3 can adjust prices based on shop activity, including buying, selling, stock levels, and configured pricing rules.

This allows servers to create economies where item prices naturally change over time.

## 🎨 Modern Text & Colors

Shop titles, the start page, buttons, names, and lore support modern formatting, including:

- Legacy colors such as `&a` and `§a`
- Hex colors such as `&#FF5555` and `#FF5555`
- `&x&F&F&5&5&5&5` hex formatting
- MiniMessage visual tags such as `<#FF5555>`, `<bold>`, `<italic>`, `<gradient>`, and `<rainbow>`

Normal text such as `R&D Shop` and `Shop & More` remains unchanged.

## 🎮 Commands

| Command | Alias | Description |
|---|---|---|
| `/DynamicShop` | `/ds` | Open the DynamicShop GUI |
| `/shop` | — | Open the shop menu |
| `/sell` | — | Sell items |

## 🔐 Permissions

| Permission | Default | Description |
|---|---|---|
| `dshop.use` | `true` | Allows use of `/ds` |
| `dshop.sell` | `true` | Allows use of `/sell` |
| `dshop.use.qsell` | `true` | Allows use of `/ds qsell` |

## 🛠️ Installation

1. Download the latest DynamicShop3 release.
2. Place the `.jar` file into your server's `plugins` folder.
3. Install the economy and optional integration plugins you want to use.
4. Start or restart the server.
5. Configure DynamicShop3 in `plugins/DynamicShop/`.

## 📚 Documentation

Detailed configuration, shop setup, commands, permissions, and integrations can be documented in the repository Wiki.

## 🔧 Development

DynamicShop3 is maintained as a modern fork with a focus on current Minecraft server compatibility.

### Building from source

Requirements:

- Java 25+
- Maven
- Paper API 26.2

For contributors and developers, see `CLAUDE.md` for project architecture, development conventions, build information, and Folia scheduler conventions.

## 🔗 Links

- **Source:** [GitHub](https://github.com/Cupjok/DynamicShop3)
- **Free Version:** [SpigotMC](https://www.spigotmc.org/resources/65603/)
- **Premium Version:** [SpigotMC](https://www.spigotmc.org/resources/100058/)
- **MultiCurrency:** [GitHub](https://github.com/Cupjok/MultiCurrency)
- **Discord:** [Join the community](https://discord.gg/5HQjBqU)
- **Statistics:** [bStats](https://bstats.org/plugin/bukkit/DynamicShop)

---

### Credits

DynamicShop3 is a maintained fork of the original DynamicShop project.

Additional development includes Minecraft 26.2 support, Folia compatibility, expanded economy integrations, MultiCurrency support, bug fixes, and other improvements.
