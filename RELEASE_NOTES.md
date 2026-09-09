<!--
Release notes for the NEXT release. Rewrite this file before tagging a version -
the CI release job publishes it verbatim as the GitHub Release body, with the
auto-generated commit changelog appended underneath. Write a real summary of what
changed and what it means for a server owner, never leave it as just a changelog
link. See the "Releasing" section in CLAUDE.md.
-->

## What's new in 3.23.0

### Decoration items can now show their own name and lore

Decorative (non-tradable) items placed in a shop were always forced to a blank display name, with attributes, enchantments and any additional tooltip info hidden. That is still the default, but it is no longer the only option.

**How to use it:** place a decoration the usual way (in shop edit mode, left-click an empty slot, then right-click an item in your own inventory), then **left-click that decoration** to toggle between:

- **Hidden** (default) — blank name, no tooltip info. Exactly the old look.
- **Shown** — the item's real display name, lore, enchantments, attributes, potion effects and other NBT tooltip info are displayed normally.

The item's admin tooltip carries a hint line telling you which click toggles it, so it is discoverable in-game without reading docs.

The setting is stored per shop slot as `showMeta` in the shop's yml file. It is absent by default, so **every existing shop keeps its current appearance after updating** — nothing to migrate.

### Bug fix: decoration items lost their metadata on shop rotation

Found while building the above. When a shop rotated, decoration entries were restored from the rotation data using only their material, silently dropping the saved item metadata. Any decoration with a custom name, custom lore or a specific potion type reverted to a plain vanilla item on every rotation. Metadata is now restored correctly, and the new name/lore setting survives rotations too.

### Other

- New translation strings added to both the built-in `ko-KR` and `en-US` language tables.
- Filling a shop slot with a new item now clears the previous occupant's decoration display setting, so it can't be inherited unexpectedly.

### Compatibility

Same as 3.22.0 — Paper, Purpur and Folia on Minecraft 26.2. No config changes required; drop in the new jar and restart.

**Not verified in-game:** this release was built and unit-tested on JDK 25, but the toggle click and the two tooltip appearances were not exercised with a Minecraft client.

