# ESP

A client-side Fabric mod that highlights mobs by reading the floating text labels above them. Type the text you want to find (a name, a word, a mob-type symbol), and ESP outlines every mob whose label matches — through walls with a glow outline, and in line of sight with a translucent box.

Built for Hypixel Skyblock, where almost everything worth finding wears a floating name plate.

**GitHub:** <https://github.com/noname-mods/ESP>

> **Requires [PlayerAPI](https://github.com/noname-mods/PlayerAPI) 1.12.0+, [Fabric API](https://modrinth.com/mod/fabric-api), and [YetAnotherConfigLib](https://modrinth.com/mod/yacl) to run.**
> [ModMenu](https://modrinth.com/mod/modmenu) is optional — it adds a settings button to the mod list.

---

## How It Works

1. Every scan, ESP collects the floating text labels (text displays and custom-named entities) near you.
2. It checks each label's text against your configured patterns.
3. For a matching label, it finds the mob sitting just below it (the mob the label belongs to).
4. That mob is highlighted until it leaves, dies, or stops matching.

Because it matches on **label text**, ESP works on anything Hypixel names — including custom mobs that use a player model, which it detects like any other mob.

---

## Features

### 10 Independent Groups
Ten fully separate highlight groups, each with its own:
- **Patterns** — the text to match
- **Glow colour** — a distinct colour per group
- **Scan radius** — how far out this group looks
- **Scan interval** — how often this group scans (trade responsiveness for performance)
- **Name** — a label shown as the config tab title

### Pattern Matching
Patterns are comma-separated and case-insensitive:
- **Plain term** → matches if the label *contains* that word. `magmatic` matches any label containing "magmatic".
- **Quoted term** → matches only if *all* words inside the quotes appear. `"sea archer"` matches a label containing both "sea" and "archer".
- **Comma = OR** → `"sea archer", muspelheim` matches a label with both "sea" and "archer", **or** any label containing "muspelheim".
- **Unicode escapes** → `\uXXXX` is replaced by that character, so you can match custom server glyphs you can't type — like Hypixel's mandatory-pack **mob-type icons** (Aquatic, Undead, Magmatic, …). Run **`/esp types`** in chat, or the **Mob Type Codes** button on the Global config tab, to see every type with its code; click a line to copy it, then paste into a Patterns field.

### Two-Layer Highlight
- **Glow outline** — the vanilla glowing effect, visible through walls. Great for locating mobs you can't see yet.
- **Depth-tested overlay** — a translucent coloured box matching the mob's hitbox, drawn via PlayerAPI's `EntityHighlightActions`. Visible only in line of sight, it fills the gap left by the glow outline (which thins to a faint border once a mob is directly visible). Toggle and opacity are configurable.

### Sticky Highlights (no flicker)
Once a mob is matched it stays highlighted even if its label briefly stops being sent — useful at the edge of range, where floating text disappears before the mob does. A highlight is dropped the moment the mob despawns/unloads, after a 15-second timeout, or when you change servers/instances (a Hypixel sub-server hop clears everything, since entity IDs don't carry across).

### Global Controls
- **Master toggle** (also bindable to a key)
- **Label search radius** — how far below/around a label to look for its mob
- **Highlight overlay** toggle + opacity
- **Debug logging** — prints per-scan diagnostics (labels seen, matches, sample text) to help dial in patterns

### Mob ESP
Highlights every entity of the types you approve — independent of labels, the entity twin of Block ESP. Open the **Select Entity Types** screen from the Mob ESP config tab (searchable checkbox list of every entity type, Clear All). Matched entities get the same glow outline (through walls) and overlay (line of sight) as the label groups. Use this when you want "all zombies" rather than "the mob under a label that says X".

### Mob Type ESP
Highlights mobs by their Hypixel **bestiary type** (Aquatic, Undead, Magmatic, …) — pick types from a checkbox list on the Mob Type ESP config tab instead of typing icon codes into a group. It reads the custom type glyph Hypixel's resource pack stamps on each name plate and highlights the mob below, using the same glow + overlay as the label groups.

> **Requires Hypixel's resource pack.** Detection keys off the pack's type glyphs, so nothing highlights until the pack is active client-side. (The `\uXXXX` pattern route in a group still works too, for advanced/mixed setups.)

### Block ESP
Highlights every instance of the block types you approve, **through walls**. Open the **Select Blocks** screen from the Block ESP config tab — a searchable list of every block in the game with a checkbox each, plus Clear All (selections save as you tick them). Configurable highlight colour, opacity, and scan radius.

---

## Controls

All keybinds are rebindable in **Options → Controls → ESP**. Both are **unbound by default**.

| Action | Default Key |
|---|---|
| Toggle ESP | *(unbound)* |
| Open Config | *(unbound)* |

Type `/esp` in chat to open the config screen directly.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 26.1.2
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Install [PlayerAPI](https://github.com/noname-mods/PlayerAPI) **1.12.0 or newer**
4. Install [YetAnotherConfigLib](https://modrinth.com/mod/yacl)
5. Install [ModMenu](https://modrinth.com/mod/modmenu) *(optional)*
6. Drop `esp-*.jar` into your `mods` folder

---

## Compatibility

| Minecraft | Fabric Loader | Java |
|---|---|---|
| 26.1.2 | ≥ 0.19.2 | 25 |

**Renderer note:**
- **xray-entity — remove it, you lose nothing.** It forces the glow outline *off* on every mob it hasn't selected, so ESP's highlighted mobs never outline through walls. ESP's **Mob ESP** now does everything xray-entity does (highlight entities by type, through walls), so removing it costs you no capability and restores ESP's glow. Confirmed hard conflict.
- **Sodium / Embeddium / Rubidium (without Iris), EntityCulling** — *may* suppress the glow outline depending on setup, but are not guaranteed to.

ESP detects all of the above on world join and posts a one-time warning (a stronger one for xray-entity). The warning has a **click-to-dismiss** link to stop showing it (you can also run `/esp nowarn` or toggle "Renderer Conflict Warnings" off on the Global config tab). When the through-wall glow is blocked, the depth-tested overlay still highlights mobs in line of sight.

**Range limit:** ESP can only see entities the server sends to your client. On Hypixel that's roughly 40–48 blocks for mobs, and floating text labels often stop arriving sooner — so the per-group scan radius is capped at 48 (past that there is nothing to find).

---

## Minecraft Version Support

This mod targets **one Minecraft version at a time.** When it updates to a new Minecraft version, **previous versions receive zero further support** — no backports, no bug fixes, and a release is never published with support for multiple Minecraft versions at once.

- Want the newest features? You must be on the mod's currently supported Minecraft version.
- Want to stay on an older Minecraft version? Stay on that version's last release — it won't be updated.

The in-game update checker is Minecraft-version aware: if the latest release targets a different Minecraft version than you're running, it tells you so instead of prompting you to install an incompatible build.

---

## Notes

- ESP has no HUD — it's a passive highlighter. The two automation mods (Ceres, Poseidon) have HUDs because they run unattended; ESP doesn't need one.
