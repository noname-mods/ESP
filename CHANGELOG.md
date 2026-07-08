# ESP Changelog

## [Unreleased]

---

## [1.0.4] - 2026-07-08

### Fixed
- **Restored a Mob Type ESP optimization intended for 1.0.3.** The scan loop now rebuilds its
  internal glyph pattern only when the selected types change, rather than every tick — removing a
  per-tick allocation and repeated registry-name lookups. This was meant to ship in 1.0.3 but was
  mistakenly dropped at the last second; no behavioural change.

---

## [1.0.3] - 2026-07-06

### Added
- **Mob Type ESP.** A dedicated config section (its own tab, alongside Mob ESP and Block ESP)
  that highlights mobs by their Hypixel **bestiary type** — Aquatic, Undead, Magmatic, and the
  other 20. Pick types from a checkbox list (**Select Mob Types**) instead of typing icon codes
  into a group; each entry shows the type icon. Its own colour, scan radius, and interval; runs
  through the same label→mob resolve, latch, and overlay as the pattern groups.

### Notes
- **Requires Hypixel's mandatory resource pack.** Detection reads the custom type glyph the pack
  stamps on each name plate, so Mob Type ESP highlights nothing until the pack is active
  client-side. Shipping this ahead of the pack going mandatory is intentional — enable it once the
  pack is live. (The `\uXXXX` pattern route from 1.0.2 still works for advanced/mixed setups.)

---

## [1.1.0] — Coming Soon

### Planned
- **Gemstone cluster layer** — built on top of Block ESP: groups highlighted blocks into spatial
  clusters, surfaces the nearest one with distance, and renders waypoints.

---

## [1.0.2] - 2026-07-01

### Added
- **Unicode-escape patterns.** Pattern fields now accept `\uXXXX` escapes — replaced by that
  character before matching — so you can match custom server glyphs you can't type, such as
  Hypixel's mandatory-pack mob-type icons (e.g. the escape for U+E072 matches Aquatic mobs).
- **Mob-type code reference (in-mod).** `/esp types`, and the **Mob Type Codes** button on the
  Global config tab, print every Hypixel mob type with its `\u` code to chat. Each line is
  click-to-copy — copy the code and paste it into a group's Patterns field.

---

## [1.0.1] - 2026-06-17

### Hotfix
- Fixed a crash (`NullPointerException` in `getTeamColor`) that fired whenever an entity **not**
  highlighted by ESP was rendered — i.e. immediately in any populated area such as a Hypixel lobby.
  An `int`/`Integer` ternary in `EntityTeamColorMixin` unboxed `null` on the non-highlighted path;
  rewritten with explicit branches so it can't unbox null.

---

## [1.0.0]

### Platform
- Built for Minecraft 26.1.2 (Fabric 0.149.1+26.1.2, Loader 0.19.2, Java 25)
- Requires PlayerAPI 1.12.0+ (`EntityHighlightActions` for the entity overlay, `BlockHighlightActions` for block ESP)

### Added

**Label-based entity highlighting**
- Scans nearby floating text labels (text displays and custom-named entities) and highlights the
  mob beneath any label whose text matches a configured pattern.
- Detects mobs that use a player model (e.g. Skyblock custom mobs), not just vanilla mob types.

**10 configurable groups**
- Each group has its own patterns, glow colour, scan radius, scan interval, and display name.
- One config tab per group, generated through YACL.

**Pattern matching**
- Comma-separated, case-insensitive terms (OR between terms).
- Plain term → substring match. Quoted term → all words inside the quotes must appear (AND).
- Mob-type symbols (e.g. `⚓`, `♆`) match as ordinary text.

**Two-layer highlight**
- Vanilla glow outline (visible through walls) via `EntityGlowMixin` / `EntityTeamColorMixin`.
- Depth-tested translucent hitbox overlay (visible in line of sight) via PlayerAPI's
  `EntityHighlightActions`. Toggle and opacity are configurable.

**Sticky highlights / anti-flicker**
- Once matched, a mob stays highlighted by entity ID through brief label losses.
- Dropped immediately when the entity despawns/unloads, after a 15-second timeout, or on a
  server/instance change (entity IDs don't carry across Hypixel sub-servers).

**Mob ESP (entity-type)**
- Highlights every entity whose type you approve — independent of labels — with the same two-layer
  highlight as the label groups (glow through walls + depth-tested overlay in line of sight).
- A custom selection screen (opened from the Mob ESP config tab) lists every entity type with a
  checkbox, search, and Clear All. Configurable colour, radius (capped at 48), and interval.
- The glow mixins consult both the label ESP and the entity-type ESP; label matches take priority
  for colour when an entity is in both.

**Block ESP**
- Highlights every instance of the block types you approve, **through walls**, via PlayerAPI's
  `BlockHighlightActions`.
- A custom selection screen (opened from the Block ESP config tab) lists every registered block
  with a checkbox, a search box, and Clear All. Selections save as you tick them.
- Reuses the chunk-section palette scan (skips empty/non-matching sections); rescans on movement,
  block-change packets, and a configurable interval. Configurable colour, opacity, and radius
  (capped at 48), with a 3000-block safety cap per scan.

**Global controls**
- Master enable toggle (bindable to a key), label search radius, overlay toggle + opacity,
  and a debug-logging mode that prints per-scan diagnostics for tuning patterns.

**Performance**
- A single entity query per tick at the largest active radius, partitioned once into labels and
  mob candidates and reused across all groups.
- Per-group scan intervals so long-range groups can scan less often.
- Per-group scan radius capped at 48 blocks (the practical server entity-tracking limit).

**Picker lists (disk-cached)**
- The block and entity-type pickers are generated from the live game registries (always match the
  running version, including other mods' content — no maintained list). The result is cached to
  `config/esp/registry-cache.json`, keyed on the Minecraft version, so it's only regenerated when
  the version changes or you press **Regenerate Block/Entity Lists** in the config (e.g. after a
  mod change). Generation is lazy and pre-warmed off-thread on world join, so it never hitches.

**Integration**
- ModMenu config button and `/esp` command to open the config screen.
- Update checker (via PlayerAPI's shared `UpdateChecker`): on world join, notifies in chat if a newer
  GitHub release exists, with a click-to-hide link and a different message when the latest release
  targets a different Minecraft version. Toggle on the Global config tab (default on).
- One-time renderer-compatibility warning on world join. `xray-entity` is flagged as a confirmed
  glow-breaker (it forces glowing off on non-selected mobs); since ESP's Mob ESP now replicates its
  functionality, the warning notes you lose nothing by removing it — and doing so restores the glow.
  Sodium/Embeddium/Rubidium without Iris and EntityCulling are flagged as possible conflicts. The
  warning carries a click-to-dismiss link (`/esp nowarn` or the "Renderer Conflict Warnings" toggle).
