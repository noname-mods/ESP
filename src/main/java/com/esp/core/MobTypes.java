package com.esp.core;

import java.util.List;

/**
 * Hypixel Skyblock mob-type icons. The mandatory resource pack renders each mob type
 * with a custom Private-Use-Area glyph on its name plate; this maps every type name to
 * its codepoint so users can highlight "all Aquatic mobs", etc.
 *
 * <p>Since the glyphs can't be typed, users enter the {@link Type#escape() \\uXXXX}
 * form in an ESP pattern (see {@link PatternMatcher}). The {@code /esp types} command
 * and the config button list them with click-to-copy.</p>
 */
public final class MobTypes {

    private MobTypes() {}

    public record Type(String name, int codepoint) {
        /** The literal glyph character (renders as the icon with the pack loaded). */
        public String glyph()  { return new String(Character.toChars(codepoint)); }
        /** The pattern escape a user types into a pattern field, e.g. {@code \\uE072}. */
        public String escape() { return String.format("\\u%04X", codepoint); }
        /** Human-readable codepoint, e.g. {@code U+E072}. */
        public String hex()    { return String.format("U+%04X", codepoint); }
    }

    /** All 23 mob types, alphabetical. Codepoints captured from the pack via DebugKit. */
    public static final List<Type> ALL = List.of(
            new Type("Airborne",     0xE070),
            new Type("Animal",       0xE071),
            new Type("Aquatic",      0xE072),
            new Type("Arcane",       0xE073),
            new Type("Arthropod",    0xE074),
            new Type("Construct",    0xE075),
            new Type("Cubic",        0xE076),
            new Type("Elusive",      0xE077),
            new Type("Ender",        0xE078),
            new Type("Frozen",       0xE079),
            new Type("Glacial",      0xE07A),
            new Type("Humanoid",     0xE07B),
            new Type("Infernal",     0xE07C),
            new Type("Magmatic",     0xE07D),
            new Type("Mythological", 0xE07E),
            new Type("Pest",         0xE018),
            new Type("Shielded",     0xE080),
            new Type("Skeletal",     0xE081),
            new Type("Spooky",       0xE082),
            new Type("Subterranean", 0xE083),
            new Type("Undead",       0xE084),
            new Type("Wither",       0xE085),
            new Type("Woodland",     0xE086)
    );

    /** Looks up a type by (case-insensitive) name, or {@code null} if unknown. */
    public static Type byName(String name) {
        if (name == null) return null;
        for (Type t : ALL) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return null;
    }

    /**
     * Builds a {@code PatternMatcher} string from selected type names: the literal
     * glyph of each known type, comma-joined. Each glyph becomes a "contains"
     * token, so a label carrying any selected type's icon matches. Unknown names
     * are skipped. Returns {@code ""} if nothing resolves.
     */
    public static String patternFor(List<String> typeNames) {
        if (typeNames == null || typeNames.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String name : typeNames) {
            Type t = byName(name);
            if (t == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t.glyph());
        }
        return sb.toString();
    }
}
