package com.esp.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent configuration for ESP. Backed by GSON and stored at
 * {@code <game_dir>/config/esp/config.json}.
 */
public class EspConfig {

    private static EspConfig INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("esp/config.json");

    // ── Default glow colours — one per group, visually distinct ───────────────
    private static final int[] DEFAULT_COLORS = {
        0xFF5555, // Red
        0x55FF55, // Green
        0x5555FF, // Blue
        0xFFFF55, // Yellow
        0xFF55FF, // Magenta
        0x55FFFF, // Cyan
        0xFF8800, // Orange
        0xFF88AA, // Pink
        0xAAFF88, // Lime
        0xFFFFFF  // White
    };

    // ── Gemstone ESP settings ─────────────────────────────────────────────────

    /**
     * Nested config block for the block-scanning (gemstone) ESP feature.
     * Adding new block-type scanners later follows the same pattern.
     */
    public static class GemstoneSettings {
        /** Master toggle for gemstone block scanning. */
        public boolean enabled            = false;
        /** How far (in blocks) from the player to scan each axis (cube scan). */
        public double  scanRadius         = 30.0;
        /** Ticks between scans — 20 = 1 s, 40 = 2 s. */
        public int     scanIntervalTicks  = 40;
        /**
         * Blocks within this distance of any cluster member are merged into
         * that cluster. Raise to merge loose veins; lower to keep them split.
         */
        public double  clusterMergeDistance = 5.0;
    }

    // ── Block ESP settings ─────────────────────────────────────────────────────

    /**
     * Simple block ESP: highlights every instance of each approved block type in
     * range, through walls. The full gemstone layer (clustering, distance,
     * waypoints) is built on top of this later.
     */
    public static class BlockEspSettings {
        /** Master toggle for block ESP. */
        public boolean enabled           = false;
        /** Registry IDs of approved blocks, e.g. {@code "minecraft:diamond_ore"}. */
        public List<String> blocks       = new ArrayList<>();
        /** Cube half-extent scanned around the player. */
        public double  scanRadius        = 24.0;
        /** Ticks between scans — 20 = 1 s. */
        public int     scanIntervalTicks = 20;
        /** Highlight colour as packed RGB (no alpha). */
        public int     color             = 0x55FFFF;
        /** Fill opacity of the highlight box, 0.0–1.0 (the outline is stronger). */
        public double  alpha             = 0.35;
    }

    // ── Entity (mob) ESP settings ──────────────────────────────────────────────

    /**
     * Highlights every entity of each approved entity type in range — the entity
     * twin of {@link BlockEspSettings}. Uses the same two-layer highlight as the
     * label-based ESP (glow through walls + depth-tested overlay), so it reuses
     * the global {@code overlayEnabled} / {@code overlayAlpha} settings.
     */
    public static class EntityEspSettings {
        /** Master toggle for entity-type ESP. */
        public boolean enabled           = false;
        /** Registry IDs of approved entity types, e.g. {@code "minecraft:zombie"}. */
        public List<String> entities     = new ArrayList<>();
        /** Half-extent of the search box around the player (server tracking ≈ 48). */
        public double  scanRadius        = 48.0;
        /** Ticks between scans — entities move, so 10 (0.5 s) keeps it responsive. */
        public int     scanIntervalTicks = 10;
        /** Glow / overlay colour as packed RGB (no alpha). */
        public int     color             = 0xFF5555;
    }

    // ── Persisted fields ──────────────────────────────────────────────────────

    public boolean       globalEnabled     = false;
    /** Radius in blocks to search for a mob below a matching label. */
    public double        labelSearchRadius = 4.0;
    public List<EspGroup> groups           = new ArrayList<>();
    public GemstoneSettings gemstone       = new GemstoneSettings();
    public BlockEspSettings blockEsp       = new BlockEspSettings();
    public EntityEspSettings entityEsp     = new EntityEspSettings();
    /**
     * When true, EspManager / GemstoneEspManager print scan diagnostics to the
     * game log: entity/label counts, per-group match results, and the nearest
     * match's coordinates. Intended for testing pattern configs, not normal use.
     */
    public boolean       debugLogging      = false;
    /**
     * Master toggle for the depth-tested hitbox-overlay highlight (drawn via
     * PlayerAPI's EntityHighlightActions). Complements the glow outline, which
     * shows through walls but reduces to a thin border once in line of sight.
     */
    public boolean       overlayEnabled    = true;
    /** Opacity of the overlay box fill, 0.0–1.0. */
    public double         overlayAlpha     = 0.35;
    /**
     * When true, the one-time renderer-compatibility warning (shown on world join
     * when a glow-conflicting mod like xray-entity is present) is suppressed. Set
     * by clicking the "disable these warnings" link in the warning message.
     */
    public boolean        compatWarningDismissed = false;
    /** When true (default), ESP checks GitHub for a newer release on world join. */
    public boolean        updateCheckEnabled = true;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private EspConfig() {}

    public static EspConfig getInstance() {
        if (INSTANCE == null) INSTANCE = new EspConfig();
        return INSTANCE;
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    public void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                EspConfig loaded = GSON.fromJson(json, EspConfig.class);
                if (loaded != null) {
                    this.globalEnabled     = loaded.globalEnabled;
                    this.labelSearchRadius = loaded.labelSearchRadius > 0
                                            ? loaded.labelSearchRadius : 4.0;
                    if (loaded.groups    != null) this.groups    = loaded.groups;
                    if (loaded.gemstone  != null) this.gemstone  = loaded.gemstone;
                    if (loaded.blockEsp  != null) {
                        this.blockEsp = loaded.blockEsp;
                        if (this.blockEsp.blocks == null) this.blockEsp.blocks = new ArrayList<>();
                    }
                    if (loaded.entityEsp != null) {
                        this.entityEsp = loaded.entityEsp;
                        if (this.entityEsp.entities == null) this.entityEsp.entities = new ArrayList<>();
                    }
                    this.debugLogging      = loaded.debugLogging;
                    this.overlayEnabled    = loaded.overlayEnabled;
                    this.overlayAlpha      = loaded.overlayAlpha > 0 ? loaded.overlayAlpha : 0.35;
                    this.compatWarningDismissed = loaded.compatWarningDismissed;
                    // Default-true boolean: GSON leaves missing fields at the JVM default
                    // (false), so check presence in the raw JSON to preserve the true default
                    // for configs written before this field existed.
                    com.google.gson.JsonObject obj =
                            com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    this.updateCheckEnabled = !obj.has("updateCheckEnabled")
                            || obj.get("updateCheckEnabled").getAsBoolean();
                }
            } catch (Exception e) {
                System.err.println("[ESP] Failed to load config: " + e.getMessage());
            }
        }
        ensureGroups();
        save();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[ESP] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Guarantees exactly 10 groups exist, padding with defaults if the file
     * was created with fewer (or is brand new).
     */
    private void ensureGroups() {
        while (groups.size() < 10) {
            int i = groups.size();
            EspGroup g = new EspGroup();
            g.name  = "Group " + (i + 1);
            g.color = DEFAULT_COLORS[i % DEFAULT_COLORS.length];
            groups.add(g);
        }
        if (groups.size() > 10) groups = new ArrayList<>(groups.subList(0, 10));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isGlobalEnabled()              { return globalEnabled; }
    public void    setGlobalEnabled(boolean v)    { globalEnabled = v; save(); }

    public double  getLabelSearchRadius()         { return labelSearchRadius; }
    public void    setLabelSearchRadius(double v) { labelSearchRadius = v; save(); }

    public List<EspGroup> getGroups()             { return groups; }

    public GemstoneSettings getGemstoneSettings() { return gemstone; }

    public BlockEspSettings getBlockEspSettings() { return blockEsp; }

    public EntityEspSettings getEntityEspSettings() { return entityEsp; }

    public boolean isDebugLogging()               { return debugLogging; }
    public void    setDebugLogging(boolean v)     { debugLogging = v; save(); }

    public boolean isOverlayEnabled()             { return overlayEnabled; }
    public void    setOverlayEnabled(boolean v)   { overlayEnabled = v; save(); }

    public double  getOverlayAlpha()              { return overlayAlpha; }
    public void    setOverlayAlpha(double v)      { overlayAlpha = v; save(); }

    public boolean isCompatWarningDismissed()         { return compatWarningDismissed; }
    public void    setCompatWarningDismissed(boolean v) { compatWarningDismissed = v; save(); }

    public boolean isUpdateCheckEnabled()             { return updateCheckEnabled; }
    public void    setUpdateCheckEnabled(boolean v)   { updateCheckEnabled = v; save(); }
}
