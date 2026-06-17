package com.esp.core;

/**
 * Configuration for one ESP group.
 * Serialised to JSON by GSON. Transient fields are runtime-only.
 */
public class EspGroup {

    // ── Persisted ─────────────────────────────────────────────────────────────

    public boolean enabled           = false;
    public String  name              = "Group";
    /** Raw comma-separated pattern string as entered by the user. */
    public String  patterns          = "";
    /** Glow outline colour as a packed RGB int (no alpha). */
    public int     color             = 0xFFFFFF;
    /** Radius in blocks to search for label entities. */
    public double  scanRadius        = 16.0;
    /** How many ticks between scans for this group. */
    public int     scanIntervalTicks = 10;

    // ── Runtime-only (not serialised) ─────────────────────────────────────────

    /** Counts up each tick; resets when it reaches scanIntervalTicks. */
    public transient int tickCounter = 0;

    /**
     * Cached compiled pattern — rebuilt only when {@code patterns} changes.
     * Avoids re-parsing the string every scan interval.
     */
    public transient PatternMatcher cachedMatcher = null;

    /** The value of {@code patterns} when {@link #cachedMatcher} was last built. */
    public transient String cachedPatternKey = null;

    /**
     * Currently-latched highlights for this group: entity-ID → packed RGB colour.
     * Populated by EspManager whenever a mob is freshly matched, and merged into
     * the global highlight map every tick so entries persist between this group's
     * scans.
     *
     * <p>An entry survives losing its label (e.g. the floating text display
     * stops being sent by the server as you walk away, while the mob entity is
     * still tracked) until one of two things happens — see {@link #latchExpiry}:
     * the entity despawns/unloads client-side, or the latch times out. This
     * fixes the "walk to the edge of range and the still-visible mob stops
     * glowing" case, and absorbs transient single-scan detection misses without
     * flicker.</p>
     */
    public transient java.util.Map<Integer, Integer> cachedHighlighted =
            new java.util.HashMap<>();

    /**
     * Entity-ID → wall-clock time (ms, {@link System#currentTimeMillis()}) at
     * which that highlight's latch expires. Refreshed every time the mob is
     * freshly matched. Once the current time passes this value the entry is
     * dropped from {@link #cachedHighlighted} (the timeout backstop); it is also
     * dropped immediately, ahead of expiry, if the entity is no longer present
     * in the level (despawn / unload / server-instance transfer).
     */
    public transient java.util.Map<Integer, Long> latchExpiry =
            new java.util.HashMap<>();
}
