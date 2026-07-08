package com.esp.core;

import com.playerapi.EntityHighlightActions;
import com.playerapi.PlayerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core scan loop for ESP.
 *
 * <h2>Scan strategy (per tick)</h2>
 * <ol>
 *   <li>Determine the maximum scan radius across all enabled groups.</li>
 *   <li>Fetch entities <strong>once</strong> from the level with that radius.</li>
 *   <li>Partition the list into <em>labels</em> (text display / custom-named) and
 *       <em>mob candidates</em> (living, non-player, non-armor-stand, non-display) in
 *       a single pass — both subsequent loops only see the relevant subset.</li>
 *   <li>For each group whose scan interval has fired:
 *     <ul>
 *       <li>Get (or lazily build) the {@link PatternMatcher} — rebuilt only when
 *           the pattern string actually changes.</li>
 *       <li>Walk only the <em>labels</em> list; skip any outside this group's radius.</li>
 *       <li>For each matched label call {@link #findMobNearLabel} against only the
 *           pre-filtered mob list.</li>
 *       <li>Store results in {@code group.cachedHighlighted}.</li>
 *     </ul>
 *   </li>
 *   <li>Merge <strong>all</strong> groups' cached results into the published map
 *       (first-group-wins priority). This runs every tick regardless of which groups
 *       scanned, so entries from long-interval groups are never prematurely wiped.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * Written on the game thread (full reference replacement), read on the render thread
 * via a volatile field. The published map is never mutated after assignment.
 */
public class EspManager {

    private static final EspManager INSTANCE = new EspManager();
    public static EspManager getInstance() { return INSTANCE; }
    private EspManager() {}

    /**
     * How long (ms) a highlight stays latched after its label was last matched.
     * Once a mob has been identified, its glow persists even if the floating
     * text label stops arriving (server stops sending the display as you walk
     * away while the mob entity is still tracked). This is only a backstop —
     * the highlight is dropped immediately, ahead of this timeout, the moment
     * the entity is no longer present client-side (see the per-tick prune).
     */
    private static final long LATCH_TIMEOUT_MS = 15_000L;

    /** Namespace used when publishing highlights to PlayerAPI's shared overlay service. */
    private static final String OVERLAY_OWNER = "esp";

    /**
     * Identity of the {@code ClientLevel} seen on the previous tick. When this
     * changes — including a Hypixel sub-server / instance transfer, which swaps
     * the client level — every group's latch state is wiped, because entity IDs
     * from the old instance are meaningless (and reusable) on the new one.
     */
    private Object lastLevel = null;

    /**
     * Synthetic, non-persisted group backing the "Mob Type ESP" section. It is not
     * one of the ten user groups — each tick it's re-synced from
     * {@link EspConfig.MobTypeEspSettings} (colour, radius, interval, and a pattern
     * string of the selected type glyphs) and, when active, appended to the scan
     * list so it flows through the exact same label→mob resolve / latch / overlay
     * path as the pattern groups. Kept as a field so its latch survives between ticks.
     */
    private final EspGroup mobTypeGroup = new EspGroup();

    /**
     * Entity ID → packed RGB glow colour for every currently highlighted entity.
     * Replaced atomically each tick; render thread reads are always consistent.
     */
    private volatile Map<Integer, Integer> highlighted = Map.of();

    // ── Pre-partitioned entity lists — rebuilt each tick, reused across groups ──

    /** Small record pairing a label entity with its already-extracted text. */
    private record LabelCandidate(Entity entity, String text) {}

    // ── Tick ──────────────────────────────────────────────────────────────────

    public void tick() {
        EspConfig cfg = EspConfig.getInstance();

        if (!cfg.isGlobalEnabled() || !PlayerInfo.isInWorld()) {
            if (!highlighted.isEmpty()) {
                highlighted = Map.of();
                wipeAllLatches(cfg);
            }
            lastLevel = null;
            EntityHighlightActions.clearOwner(OVERLAY_OWNER);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // ── Instance-transfer guard ────────────────────────────────────────────
        // Changing Hypixel sub-servers (or any world/dimension change) replaces
        // the client level. Entity IDs do not carry across, so wipe all latched
        // highlights — otherwise a latched ID could collide with an unrelated
        // entity that the new instance happens to assign the same ID.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            wipeAllLatches(cfg);
            highlighted = Map.of();
            EntityHighlightActions.clearOwner(OVERLAY_OWNER);
        }

        // ── Collect only enabled, non-empty groups ─────────────────────────────
        List<EspGroup> activeGroups = new ArrayList<>();
        double maxRadius = 0;
        for (EspGroup g : cfg.getGroups()) {
            if (g.enabled && !g.patterns.isBlank()) {
                activeGroups.add(g);
                if (g.scanRadius > maxRadius) maxRadius = g.scanRadius;
            }
        }

        // Sync the synthetic Mob-Type group and include it when active. Done before
        // the empty-check so its radius contributes to the single entity fetch.
        EspGroup mtg = syncMobTypeGroup(cfg);
        if (mtg != null) {
            activeGroups.add(mtg);
            if (mtg.scanRadius > maxRadius) maxRadius = mtg.scanRadius;
        }

        if (activeGroups.isEmpty()) {
            if (!highlighted.isEmpty()) highlighted = Map.of();
            wipeAllLatches(cfg);
            EntityHighlightActions.clearOwner(OVERLAY_OWNER);
            return;
        }

        // ── Single entity list fetch at max radius ─────────────────────────────
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        AABB searchBox = new AABB(
                px - maxRadius, py - maxRadius, pz - maxRadius,
                px + maxRadius, py + maxRadius, pz + maxRadius);
        List<Entity> nearby = mc.level.getEntities(mc.player, searchBox);

        // ── One-pass partition into labels and mob candidates ──────────────────
        List<LabelCandidate> labels   = new ArrayList<>();
        List<Entity>         mobPool  = new ArrayList<>();

        for (Entity e : nearby) {
            String text = getLabelText(e);
            if (text != null) {
                labels.add(new LabelCandidate(e, text));
            } else if (e instanceof LivingEntity
                    && !(e instanceof ArmorStand)
                    && !(e instanceof Display)) {
                // NOTE: Player entities are intentionally NOT excluded here.
                // Many SkyBlock custom mobs are rendered as player-model entities
                // (RemotePlayer / AbstractClientPlayer with a custom skin); filtering
                // out `instanceof Player` made those mobs undetectable. The local
                // player is already excluded by getEntities(mc.player, …) above, and
                // highlighting is gated by a matching label hovering over the mob —
                // real players don't carry those labels, so they won't be highlighted.
                mobPool.add(e);
            }
        }

        double labelSearchRadius = cfg.getLabelSearchRadius();
        boolean debug = cfg.isDebugLogging();

        // Peek whether any group's interval will fire this tick — gates the
        // (otherwise per-tick) fetch summary log so it only prints on real scans.
        boolean willScan = false;
        for (EspGroup g : activeGroups) {
            if (g.tickCounter + 1 >= g.scanIntervalTicks) { willScan = true; break; }
        }
        if (debug && willScan) {
            System.out.println("[ESP][DEBUG] Scan tick: " + nearby.size() + " entities in range ("
                    + (int) maxRadius + " blocks), " + labels.size() + " labels, "
                    + mobPool.size() + " mob candidates.");
        }

        // ── Per-group scan (only groups whose interval fired) ──────────────────
        for (EspGroup group : activeGroups) {
            group.tickCounter++;
            if (group.tickCounter < group.scanIntervalTicks) continue;
            group.tickCounter = 0;

            // Lazy matcher — only rebuild when the pattern string changes
            if (group.cachedMatcher == null
                    || !group.patterns.equals(group.cachedPatternKey)) {
                group.cachedMatcher   = new PatternMatcher(group.patterns);
                group.cachedPatternKey = group.patterns;
            }
            PatternMatcher matcher = group.cachedMatcher;
            if (matcher.isEmpty()) {
                group.cachedHighlighted.clear();
                group.latchExpiry.clear();
                if (debug) {
                    System.out.println("[ESP][DEBUG] Group '" + group.name
                            + "': pattern string is empty/unparseable — skipped.");
                }
                continue;
            }

            double r = group.scanRadius;
            long now = System.currentTimeMillis();

            int inRadius      = 0;
            int matchedCount  = 0;
            int resolvedCount = 0;
            LabelCandidate firstMatch = null;
            Entity         firstMatchMob = null;

            for (LabelCandidate label : labels) {
                // Per-group radius trim (label list may include entities up to maxRadius)
                double lx = label.entity.getX(), ly = label.entity.getY(), lz = label.entity.getZ();
                if (Math.abs(lx - px) > r || Math.abs(ly - py) > r || Math.abs(lz - pz) > r) continue;
                inRadius++;

                if (!matcher.matches(label.text)) continue;
                matchedCount++;

                Entity mob = findMobNearLabel(label.entity.position(), mobPool, labelSearchRadius);
                if (mob != null) {
                    // Latch (or refresh) this mob. The colour is always the
                    // group's current colour; the expiry is pushed forward so
                    // the highlight survives losing its label for up to
                    // LATCH_TIMEOUT_MS. Actual removal happens in the per-tick
                    // prune below (entity-gone → immediate, expiry → backstop).
                    group.cachedHighlighted.put(mob.getId(), group.color);
                    group.latchExpiry.put(mob.getId(), now + LATCH_TIMEOUT_MS);
                    resolvedCount++;
                }
                if (firstMatch == null) {
                    firstMatch    = label;
                    firstMatchMob = mob;
                }
            }

            if (debug) {
                System.out.println("[ESP][DEBUG] Group '" + group.name + "' (pattern=\""
                        + group.patterns + "\", radius=" + r + "): "
                        + inRadius + " labels in radius, " + matchedCount + " matched pattern, "
                        + resolvedCount + " resolved to a mob.");

                if (matchedCount == 0 && inRadius > 0) {
                    // Show a few sample texts so the user can see exactly what's
                    // being compared against their pattern (formatting, casing, etc).
                    StringBuilder samples = new StringBuilder();
                    int shown = 0;
                    for (LabelCandidate l : labels) {
                        double lx = l.entity.getX(), ly = l.entity.getY(), lz = l.entity.getZ();
                        if (Math.abs(lx - px) > r || Math.abs(ly - py) > r || Math.abs(lz - pz) > r) continue;
                        if (shown > 0) samples.append(" | ");
                        samples.append('"').append(l.text).append('"');
                        if (++shown >= 5) break;
                    }
                    System.out.println("[ESP][DEBUG]   No matches. Sample label text seen: "
                            + (samples.length() > 0 ? samples : "(none)"));
                } else if (matchedCount > 0 && firstMatch != null) {
                    Vec3 lp = firstMatch.entity.position();
                    String coords = String.format("(%.1f, %.1f, %.1f)", lp.x, lp.y, lp.z);
                    if (firstMatchMob != null) {
                        Vec3 mp = firstMatchMob.position();
                        double dist = mp.distanceTo(mc.player.position());
                        System.out.println("[ESP][DEBUG]   First match: text=\"" + firstMatch.text
                                + "\" at " + coords + " -> resolved mob "
                                + firstMatchMob.getType().toShortString()
                                + " (id=" + firstMatchMob.getId() + ") at distance "
                                + String.format("%.1f", dist) + " blocks.");
                    } else {
                        System.out.println("[ESP][DEBUG]   First match: text=\"" + firstMatch.text
                                + "\" at " + coords + " -> NO mob found within "
                                + labelSearchRadius + " blocks (XZ) / 0-6 blocks below label (Y). "
                                + mobPool.size() + " mob candidates were in the full scan pool.");
                    }
                } else if (inRadius == 0) {
                    System.out.println("[ESP][DEBUG]   No labels at all within " + r
                            + " blocks of player. Total labels found anywhere in scan: " + labels.size());
                }
            }
        }

        // ── Merge all groups' latched highlights into the published map ────────
        // Runs every tick (not just on scan ticks) so two things stay prompt and
        // independent of each group's scan interval:
        //   1. Removal — an entry is pruned the instant its entity is gone from
        //      the level (despawn / unload / killed, like Poseidon dropping a
        //      sea creature once it's no longer there), or once its latch has
        //      expired (the label-lost backstop). Pruning here also frees the
        //      latched ID so a future reused ID can't inherit a stale highlight.
        //   2. Persistence — long-interval groups are never wiped between scans.
        // First group in list wins when two groups match the same entity.
        long nowMerge = System.currentTimeMillis();
        Map<Integer, Integer> merged = new HashMap<>();
        for (EspGroup group : activeGroups) {
            var it = group.cachedHighlighted.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Integer> entry = it.next();
                int id = entry.getKey();

                // Entity gone → drop immediately, ahead of any timeout.
                if (mc.level.getEntity(id) == null) {
                    it.remove();
                    group.latchExpiry.remove(id);
                    continue;
                }
                // Latch timed out → drop (label was lost long enough ago).
                Long expiry = group.latchExpiry.get(id);
                if (expiry == null || nowMerge > expiry) {
                    it.remove();
                    group.latchExpiry.remove(id);
                    continue;
                }
                merged.putIfAbsent(id, entry.getValue());
            }
        }
        highlighted = merged;

        // ── Publish to PlayerAPI's depth-tested overlay (in-LOS complement to glow) ─
        if (cfg.isOverlayEnabled()) {
            EntityHighlightActions.setHighlights(OVERLAY_OWNER, merged, (float) cfg.getOverlayAlpha());
        } else {
            EntityHighlightActions.clearOwner(OVERLAY_OWNER);
        }
    }

    // ── Render-thread queries (called by mixins) ──────────────────────────────

    public boolean isHighlighted(int entityId) {
        return highlighted.containsKey(entityId);
    }

    public int getColor(int entityId) {
        return highlighted.getOrDefault(entityId, 0xFFFFFF);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Clears every group's latch state — the ten user groups plus the synthetic Mob-Type group. */
    private void wipeAllLatches(EspConfig cfg) {
        for (EspGroup g : cfg.getGroups()) {
            g.cachedHighlighted.clear();
            g.latchExpiry.clear();
        }
        mobTypeGroup.cachedHighlighted.clear();
        mobTypeGroup.latchExpiry.clear();
    }

    /**
     * Re-syncs {@link #mobTypeGroup} from {@link EspConfig.MobTypeEspSettings} and
     * returns it when it should scan this tick, or {@code null} when the feature is
     * off / no types are selected (its latch is cleared in that case). The pattern
     * string is the selected types' literal glyphs (see {@link MobTypes#patternFor}),
     * so it matches any name plate carrying one of those pack icons.
     */
    private EspGroup syncMobTypeGroup(EspConfig cfg) {
        EspConfig.MobTypeEspSettings s = cfg.getMobTypeEspSettings();
        String pattern = MobTypes.patternFor(s.types);
        if (!s.enabled || pattern.isEmpty()) {
            mobTypeGroup.cachedHighlighted.clear();
            mobTypeGroup.latchExpiry.clear();
            return null;
        }
        mobTypeGroup.enabled           = true;
        mobTypeGroup.name              = "Mob Types";
        mobTypeGroup.patterns          = pattern;
        mobTypeGroup.color             = s.color;
        mobTypeGroup.scanRadius        = s.scanRadius;
        mobTypeGroup.scanIntervalTicks = s.scanIntervalTicks;
        return mobTypeGroup;
    }

    /**
     * Returns the plain-text content of a label entity, or {@code null} if this
     * entity carries no visible text and should be skipped entirely.
     */
    private static String getLabelText(Entity e) {
        if (e instanceof Display.TextDisplay td) {
            Component c = td.getText();
            return c != null ? c.getString() : null;
        }
        Component name = e.getCustomName();
        return name != null ? name.getString() : null;
    }

    /**
     * Finds the nearest entity in {@code mobPool} within {@code searchRadius}
     * blocks (XZ) of {@code labelPos}, where the label sits 0–6 blocks above
     * the mob's feet.
     *
     * <p>The pool is pre-filtered (living, non-player, non-display) so this
     * method only needs positional checks.</p>
     */
    private static Entity findMobNearLabel(Vec3 labelPos,
                                           List<Entity> mobPool,
                                           double searchRadius) {
        Entity closest     = null;
        double closestDist = Double.MAX_VALUE;
        double r2          = searchRadius * searchRadius;

        for (Entity e : mobPool) {
            double dx = labelPos.x - e.getX();
            double dy = labelPos.y - e.getY(); // positive = label is above entity
            double dz = labelPos.z - e.getZ();

            if (dy < 0.0 || dy > 6.0) continue; // label must be at or above entity

            double xzDist2 = dx * dx + dz * dz;
            if (xzDist2 < r2 && xzDist2 < closestDist) {
                closestDist = xzDist2;
                closest     = e;
            }
        }
        return closest;
    }
}
