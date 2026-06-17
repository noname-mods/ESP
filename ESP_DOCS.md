# ESP — Design & Documentation

**Version:** 1.0.1
**Platform:** Fabric 26.1.2, Java 25
**Dependencies:** PlayerAPI 1.12.0+, Fabric API, YACL v3, ModMenu (optional)
**Mod ID:** `esp`
**Entry point:** `com.esp.EspMod`

---

## AI Session Quick-Start

> **Read this first if you are a new Claude Code session working in this folder.**

### What this mod is

ESP is a **client-side, passive highlighter** for Hypixel Skyblock. It reads the floating text labels
above mobs, matches them against user patterns, finds the mob each matching label belongs to, and
highlights it. It has no automation and no HUD.

### Key dependency

ESP is built on **PlayerAPI** and pulls it from `mavenLocal()`. It uses `EntityHighlightActions`
(entity overlay, 1.10.0), `BlockHighlightActions` (block ESP, 1.11.0), and `UpdateChecker`
(update notifications, 1.12.0) — `fabric.mod.json` therefore requires `playerapi >= 1.12.0`. (An earlier build declared `"*"` and
crashed at runtime against an older PlayerAPI with `NoClassDefFoundError`; keep the version floor.)

If you change PlayerAPI, re-publish it before rebuilding ESP:
```
cd ..\PlayerAPI
./gradlew publishToMavenLocal
```

### Source layout

```
com/esp/
├── EspMod.java                  entry point: keybinds, /esp command, tick wiring, compat check
├── core/
│   ├── EspManager.java          per-tick scan loop + highlight latch (the heart of the mod)
│   ├── EntityEspManager.java    entity-type ESP — highlights all entities of approved types
│   ├── EspConfig.java           GSON config singleton (config/esp/config.json)
│   ├── EspGroup.java            one highlight group (persisted + runtime latch state)
│   ├── RegistryListCache.java   disk-cached block/entity picker lists (version-keyed)
│   └── PatternMatcher.java      compiles a pattern string and tests label text
├── gui/
│   ├── EspConfigScreen.java     YACL screen (Global, Mob ESP, Block ESP, group tabs; gemstone hidden)
│   ├── BlockEspScreen.java      custom block-picker screen (searchable checkbox grid, Clear All)
│   └── EntityEspScreen.java     custom entity-type picker (same widget pattern as BlockEspScreen)
├── mixin/
│   ├── EntityGlowMixin.java          forces isCurrentlyGlowing() true (reads EspManager + EntityEspManager)
│   ├── EntityTeamColorMixin.java     overrides getTeamColor() (label ESP colour wins, else entity ESP)
│   └── ClientPacketListenerMixin.java block-change hooks → BlockEspManager rescan debounce
├── block/
│   ├── BlockEspManager.java     ACTIVE: scans approved blocks, publishes via BlockHighlightActions
│   ├── GemstoneEspManager.java  future cluster layer — present but NOT ticked (see below)
│   └── BlockCluster.java        cluster type used by the future gemstone layer
└── modmenu/
    └── EspModMenuPlugin.java    ModMenu config button
```

### Block ESP active; gemstone cluster layer disabled

`BlockEspManager` (flat "outline every approved block") ships in 1.0.0 and is ticked from
`EspMod.onTick()`. The **clustering** scanner `GemstoneEspManager` (distance, waypoints) is the
future 1.1.0 layer and is switched off — two call sites are commented out **together**:
- `EspMod.onTick()` — the `GemstoneEspManager.getInstance().tick()` call
- `EspConfigScreen.create()` — the `builder.category(buildGemstoneCategory(cfg))` line

`ClientPacketListenerMixin` now drives `BlockEspManager.markDirty()` for rescans.

### Custom GUI note (26.1.2)

26.1.2 replaced immediate-mode `GuiGraphics` drawing with a render-state extraction model
(`Renderable.extractRenderState`), and `Screen`'s constructor is now `Screen(Minecraft, Font,
Component)`. `BlockEspScreen` therefore does **no manual rendering** — it composes self-rendering
vanilla widgets (`Checkbox`, `EditBox`, `Button`, `StringWidget`) and virtualises the grid: only
the checkboxes for the visible rows exist as widgets, recreated on scroll/search via `removeWidget`
+ `addRenderableWidget` (the search box stays alive so typing isn't interrupted).

### Common things you'll need to touch

- **Detection / latch logic** → `EspManager.tick()`
- **What counts as a label / a mob** → the partition loop in `EspManager.tick()` and `getLabelText()`
- **Pattern syntax** → `PatternMatcher`
- **Config options / UI** → `EspConfig` + `EspConfigScreen` (+ `BlockEspScreen` for block picking)
- **Glow rendering** → `EntityGlowMixin` / `EntityTeamColorMixin`
- **Entity overlay** → published from `EspManager` via `EntityHighlightActions` (owner `"esp"`)
- **Mob ESP (entity-type)** → `EntityEspManager`; glow via the mixins (which read it too), overlay via `EntityHighlightActions` (owner `"esp-entities"`)
- **Block ESP** → `BlockEspManager` scans + publishes via `BlockHighlightActions` (owner `"esp-blocks"`)

---

## Architecture

```
EspMod (entry point)
├── registers keybinds (Toggle, Open Config — both unbound by default)
├── registers /esp command
├── checkRenderCompatibility() — warns once; xray-entity = confirmed glow-breaker
│   (forces glow off on non-selected mobs), Sodium-family/EntityCulling = possible
└── subscribes to PlayerAPIEvents.TICK → onTick()

onTick() each tick (when in world):
├── one-time compat chat warning
├── handleKeybinds()
├── EspManager.tick()                      ← label-matched mob highlighting
├── EntityEspManager.tick()                ← entity-type ESP (approved entity types)
├── BlockEspManager.tick()                 ← block ESP (approved block types)
└── (GemstoneEspManager.tick() — future cluster layer, disabled)

EspManager.tick() (when global enabled + in world):
├── instance-transfer guard: if mc.level changed, clear all latch state
├── collect enabled, non-empty groups; compute max scan radius
├── ONE getEntities() query at max radius
├── partition entities once → labels[] and mobPool[]
│   (mobPool excludes ArmorStand/Display; Players are kept — covers player-model mobs)
├── for each group whose interval fired:
│   ├── (re)compile PatternMatcher if the pattern string changed
│   ├── for each in-radius label that matches: find the mob below it (0–6 blocks down,
│   │   within label-search-radius XZ) and latch it (cachedHighlighted + latchExpiry)
└── every tick: merge all groups' latched IDs into the published map, pruning IDs whose
    entity is gone or whose latch expired; publish to glow (volatile map) + overlay
    (EntityHighlightActions.setHighlights("esp", …))
```

**Threading:** the scan runs on the client tick thread. The merged highlight map is stored in a
`volatile` field; the glow mixins read it on the render thread. The map is replaced by reference
(never mutated after publish), so render-thread reads are always consistent.

---

## Highlight latch

The latch (in `EspGroup` / `EspManager`) keeps a mob highlighted after its label is briefly lost.

- **Refresh:** each time a mob is freshly matched, `cachedHighlighted[id] = colour` and
  `latchExpiry[id] = now + 15s`.
- **Drop (per tick, in the merge step):**
  - `mc.level.getEntity(id) == null` → entity gone (despawn/unload) → drop immediately. This is the
    primary exit and makes entity-ID reuse safe (the latch is released the instant the entity goes).
  - `now > latchExpiry[id]` → timeout backstop for a mob that stays loaded but stopped matching.
- **Instance transfer:** when `mc.level` identity changes (Hypixel sub-server hop, dimension change),
  all latch state is wiped — entity IDs are meaningless on the new instance.

This replaced an earlier scan-count grace window (`MISS_GRACE_SCANS`) and fixes the "walk to the edge
of range and the still-visible mob stops glowing" case.

---

## Rendering: two layers

| Layer | Mechanism | Visible | Purpose |
|-------|-----------|---------|---------|
| Glow outline | `EntityGlowMixin.isCurrentlyGlowing()` returns true for highlighted IDs; `EntityTeamColorMixin.getTeamColor()` returns the group colour | Through walls | Locate mobs you can't see |
| Depth-tested overlay | `EntityHighlightActions.setHighlights("esp", map, alpha)` (PlayerAPI, built on the Gizmos API) | Line of sight only | Fill the gap left by the glow outline thinning to a faint border up close |

Both read from the same merged ID→colour map. The overlay layer is what the `overlayEnabled` /
`overlayAlpha` config options control.

**Mob ESP (entity-type)** reuses these exact two layers via `EntityEspManager`: the glow mixins
consult it alongside `EspManager` (label colour wins on overlap), and its overlay is published under
the separate owner `"esp-entities"`, gated by the same global overlay toggle.

---

## Config — `config/esp/config.json`

`EspConfig` (GSON singleton). Fields:

| Field | Default | Meaning |
|-------|---------|---------|
| `globalEnabled` | false | Master toggle |
| `labelSearchRadius` | 4.0 | Blocks (XZ) below/around a label to look for its mob |
| `groups` | 10 groups | Per-group settings (see `EspGroup`) |
| `overlayEnabled` | true | Depth-tested overlay on/off |
| `overlayAlpha` | 0.35 | Overlay box opacity (0.05–1.0) |
| `debugLogging` | false | Per-scan diagnostics to the log |
| `compatWarningDismissed` | false | Suppresses the renderer-conflict warning (set via the chat link / `/esp nowarn`) |
| `entityEsp` | defaults | Mob ESP (entity-type) settings (see below) |
| `blockEsp` | defaults | Block ESP settings (see below) |
| `gemstone` | defaults | Gemstone cluster settings (persisted but unused until 1.1.0) |

`ensureGroups()` always pads/truncates to exactly 10 groups, each with a default distinct colour.

### `EspGroup`

Persisted: `enabled`, `name`, `patterns`, `color` (packed RGB), `scanRadius` (4–48),
`scanIntervalTicks`. Runtime-only (transient): `tickCounter`, `cachedMatcher` / `cachedPatternKey`,
`cachedHighlighted` (id→colour), `latchExpiry` (id→expiry ms).

### `BlockEspSettings` (`blockEsp`)

| Field | Default | Meaning |
|-------|---------|---------|
| `enabled` | false | Master toggle for block ESP |
| `blocks` | `[]` | Registry IDs of approved blocks, e.g. `minecraft:diamond_ore` (edited via `BlockEspScreen`) |
| `scanRadius` | 24.0 | Cube half-extent scanned (8–48) |
| `scanIntervalTicks` | 20 | Fallback ticks between scans |
| `color` | `0x55FFFF` | Box colour (packed RGB) |
| `alpha` | 0.35 | Fill opacity (outline drawn stronger) |

`BlockEspManager` resolves `blocks` → a `Set<Block>` (rebuilt when the list's `hashCode` changes),
scans via the chunk-section palette skip, caps collection at 3000 positions, and publishes to
`BlockHighlightActions` under owner `"esp-blocks"`. It clears that owner when disabled, empty, or on
world change.

### `EntityEspSettings` (`entityEsp`)

| Field | Default | Meaning |
|-------|---------|---------|
| `enabled` | false | Master toggle for Mob ESP |
| `entities` | `[]` | Registry IDs of approved entity types, e.g. `minecraft:zombie` (edited via `EntityEspScreen`) |
| `scanRadius` | 48.0 | Search-box half-extent (8–48; server entity-tracking bound) |
| `scanIntervalTicks` | 10 | Ticks between scans (entities move, so kept low) |
| `color` | `0xFF5555` | Glow + overlay colour (packed RGB) |

`EntityEspManager` resolves `entities` → a `Set<EntityType<?>>` (rebuilt on list change), scans
`getEntities` in an AABB each interval, replaces its `volatile` id→colour map (no latch — entity-type
matching is stable), and publishes the overlay to `EntityHighlightActions` under `"esp-entities"`
(gated by the global `overlayEnabled` / `overlayAlpha`). The glow comes from `EntityGlowMixin` /
`EntityTeamColorMixin`, which consult this manager in addition to `EspManager`.

---

## RegistryListCache

Backs the two picker screens. The block / entity-type lists are generated from the live
`BuiltInRegistries.BLOCK` / `ENTITY_TYPE` (so they always match the running version, including other
mods' content — nothing is hand-maintained), then cached to `config/esp/registry-cache.json` with the
Minecraft version string.

- `blocks()` / `entities()` → `ensureCurrent()`: in-memory if current; else read disk; else generate.
- Regenerates only when the cache is missing, the stored MC version differs, or `regenerate()` is
  called (the **Regenerate Block/Entity Lists** button on the Global config tab — use it after a mod
  change, which the version check alone wouldn't catch).
- Generation is **lazy** (never at startup) and `prewarmAsync()` runs it off the main thread on
  `WORLD_JOIN`, so the first picker open doesn't hitch. `ensureCurrent()` is `synchronized`, so a
  picker opened mid-prewarm just waits for the background build.
- Display names are only resolved correctly once the language is loaded — world-join / menu time
  guarantees that, which is why generation isn't done during `onInitializeClient`.

---

## PatternMatcher

- Splits the pattern string on commas (OR between terms).
- A term wrapped in double quotes → every whitespace-separated word inside must appear in the label
  (AND). An unquoted term → simple substring test.
- All comparisons are case-insensitive.
- `isEmpty()` is true when the pattern string yields no usable terms (group is then skipped).

---

## Build & test

```
./gradlew build              # produces build/libs/esp-1.0.1.jar
./gradlew runClient          # dev client
```

Remember the build order: if PlayerAPI changed, `publishToMavenLocal` it first. Copy both
`esp-1.0.1.jar` **and** a matching `playerapi-1.12.0.jar` into a test instance's mods folder — an
older PlayerAPI will fail the `>=1.12.0` dependency (or, if forced, crash on `BlockHighlightActions` / `EntityHighlightActions`).
