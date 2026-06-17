package com.esp.block;

import com.esp.core.EspConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Scans the area around the player for red stained glass / panes (Hypixel ruby
 * stand-ins), clusters them spatially, and exposes a distance-sorted snapshot.
 *
 * <h2>Scan strategy</h2>
 * <ol>
 *   <li>Iterate <em>chunk sections</em> (16×16×16) rather than individual blocks.</li>
 *   <li>Each section is skipped instantly via {@code hasOnlyAir()} or a palette
 *       membership check ({@code maybeHas}) — O(palette_size) ≈ O(1–16) per section.</li>
 *   <li>Full 4 096-block iteration only runs for sections whose palette actually
 *       contains a target block (typically 0–3 sections in a whole scan).</li>
 *   <li>Unloaded chunk columns are skipped before touching any block data.</li>
 * </ol>
 * This pushes the practical scan radius to 100+ blocks with negligible main-thread cost.
 *
 * <h2>Rescan triggers (event-driven)</h2>
 * <ul>
 *   <li><b>World / dimension change</b> — detected by reference equality on the level.</li>
 *   <li><b>Player moved ≥ 8 blocks</b> from the last scan origin.</li>
 *   <li><b>Block change packet</b> — {@code markDirty()} is called by the packet-listener
 *       mixin; a short debounce prevents flooding on rapid multi-block updates.</li>
 *   <li><b>Fallback timer</b> — {@code scanIntervalTicks} ensures the list refreshes
 *       even when the player is standing still and nothing is being mined.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #tick()} and {@link #markDirty()} are both called on the game thread.
 * {@link #getClusters()} is called from the render thread; safety is achieved by
 * replacing the entire reference atomically (volatile + immutable list).
 */
public class GemstoneEspManager {

    // ── Singleton ──────────────────────────────────────────────────────────────

    private static GemstoneEspManager INSTANCE;

    private GemstoneEspManager() {}

    public static GemstoneEspManager getInstance() {
        if (INSTANCE == null) INSTANCE = new GemstoneEspManager();
        return INSTANCE;
    }

    // ── Target blocks ──────────────────────────────────────────────────────────

    private static final Set<Block> TARGET_BLOCKS = Set.of(
            Blocks.RED_STAINED_GLASS,
            Blocks.RED_STAINED_GLASS_PANE
    );

    // ── Tuning constants ───────────────────────────────────────────────────────

    /** Player must move this far before a position-triggered rescan fires. */
    private static final double RESCAN_DIST_SQ       = 8.0 * 8.0;

    /**
     * Minimum ticks between any two scans regardless of other triggers.
     * Prevents flooding when many block-change packets arrive at once.
     */
    private static final int MIN_RESCAN_INTERVAL     = 5;

    /**
     * After a block-change dirty flag is set, wait this many ticks before
     * actually scanning (debounce burst updates like explosions or piston arrays).
     */
    private static final int BLOCK_CHANGE_DEBOUNCE   = 8;

    // ── State ──────────────────────────────────────────────────────────────────

    private volatile List<BlockCluster> clusters = List.of();

    /** Ticks since the last completed scan. */
    private int ticksSinceLastScan    = 0;

    /** Countdown set by markDirty; scan fires when this reaches 0. */
    private int dirtyCountdown        = 0;

    /** Reference to the last level — used to detect world/dimension changes. */
    private ClientLevel lastLevel     = null;

    /** Player position at the time of the last scan. */
    private Vec3 lastScanOrigin       = null;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the latest sorted cluster snapshot (index 0 = nearest).
     * Safe to call from any thread.
     */
    public List<BlockCluster> getClusters() { return clusters; }

    /** Convenience — nearest cluster, or {@code null} if none found. */
    public BlockCluster getNearest() {
        List<BlockCluster> snap = clusters;
        return snap.isEmpty() ? null : snap.get(0);
    }

    /**
     * Called by the packet-listener mixin whenever a block-change packet is
     * received. Sets a debounced dirty flag so the next eligible tick rescans.
     */
    public void markDirty() {
        // Only arm the countdown if one isn't already running — first change wins
        if (dirtyCountdown <= 0) {
            dirtyCountdown = BLOCK_CHANGE_DEBOUNCE;
        }
    }

    // ── Tick ───────────────────────────────────────────────────────────────────

    /** Called every game tick from {@code EspMod.onTick()}. Game thread only. */
    public void tick() {
        EspConfig.GemstoneSettings cfg = EspConfig.getInstance().getGemstoneSettings();

        if (!cfg.enabled) {
            if (!clusters.isEmpty()) {
                clusters      = List.of();
                lastScanOrigin = null;
            }
            dirtyCountdown = 0;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level  = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        // ── World / dimension change → full reset ──────────────────────────────
        if (level != lastLevel) {
            lastLevel      = level;
            lastScanOrigin = null;
            clusters       = List.of();
            ticksSinceLastScan = cfg.scanIntervalTicks; // force immediate scan
            dirtyCountdown = 0;
        }

        ticksSinceLastScan++;

        // ── Respect minimum rescan interval ────────────────────────────────────
        if (ticksSinceLastScan < MIN_RESCAN_INTERVAL) return;

        // ── Evaluate rescan triggers ───────────────────────────────────────────
        Vec3 pos = player.position();

        boolean movedFar = lastScanOrigin == null
                || lastScanOrigin.distanceToSqr(pos) > RESCAN_DIST_SQ;

        // Tick down dirty debounce
        boolean dirtyFired = false;
        if (dirtyCountdown > 0) {
            dirtyCountdown--;
            if (dirtyCountdown == 0) dirtyFired = true;
        }

        boolean timerFired = ticksSinceLastScan >= cfg.scanIntervalTicks;

        if (!movedFar && !dirtyFired && !timerFired) return;

        // ── Run scan ───────────────────────────────────────────────────────────
        ticksSinceLastScan = 0;
        lastScanOrigin     = pos;
        clusters           = buildClusters(level, player, cfg);
    }

    // ── Chunk-section optimised block scan ─────────────────────────────────────

    private List<BlockCluster> buildClusters(ClientLevel level,
                                              LocalPlayer player,
                                              EspConfig.GemstoneSettings cfg) {
        BlockPos origin = player.blockPosition();
        int r = (int) cfg.scanRadius;
        Vec3 playerVec = player.position();

        // ── Chunk-coordinate range ─────────────────────────────────────────────
        int minCX = (origin.getX() - r) >> 4;
        int maxCX = (origin.getX() + r) >> 4;
        int minCZ = (origin.getZ() - r) >> 4;
        int maxCZ = (origin.getZ() + r) >> 4;

        // ── Section-Y range clamped to world bounds ────────────────────────────
        int minBuildY    = level.getMinY();
        int sectionCount = level.getSectionsCount();
        int minSY = Math.max(((origin.getY() - r) - minBuildY) >> 4, 0);
        int maxSY = Math.min(((origin.getY() + r) - minBuildY) >> 4, sectionCount - 1);

        List<BlockPos> found = new ArrayList<>();

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {

                // ── Skip unloaded chunks ───────────────────────────────────────
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = (LevelChunk) level.getChunk(cx, cz);

                for (int sy = minSY; sy <= maxSY; sy++) {
                    LevelChunkSection section = chunk.getSection(sy);

                    // ── Fast-skip 1: all air ───────────────────────────────────
                    if (section.hasOnlyAir()) continue;

                    // ── Fast-skip 2: palette membership check ──────────────────
                    // maybeHas walks the section's palette (~1-16 entries) and
                    // returns false immediately if no target block is registered.
                    if (!section.getStates().maybeHas(
                            state -> TARGET_BLOCKS.contains(state.getBlock()))) continue;

                    // ── Section contains a target — iterate its 4 096 blocks ──
                    int baseX = cx << 4;
                    int baseY = minBuildY + (sy << 4);
                    int baseZ = cz << 4;

                    for (int lx = 0; lx < 16; lx++) {
                        int bx = baseX + lx;
                        if (bx < origin.getX() - r || bx > origin.getX() + r) continue;

                        for (int ly = 0; ly < 16; ly++) {
                            int by = baseY + ly;
                            if (by < origin.getY() - r || by > origin.getY() + r) continue;

                            for (int lz = 0; lz < 16; lz++) {
                                int bz = baseZ + lz;
                                if (bz < origin.getZ() - r || bz > origin.getZ() + r) continue;

                                // Use the section reference directly — avoids chunk lookup
                                if (TARGET_BLOCKS.contains(
                                        section.getBlockState(lx, ly, lz).getBlock())) {
                                    found.add(new BlockPos(bx, by, bz));
                                }
                            }
                        }
                    }
                }
            }
        }

        if (found.isEmpty()) return List.of();

        // ── BFS clustering ─────────────────────────────────────────────────────
        double mergeDistSq = cfg.clusterMergeDistance * cfg.clusterMergeDistance;
        Set<BlockPos> unvisited = new HashSet<>(found);
        List<BlockCluster> result = new ArrayList<>();

        while (!unvisited.isEmpty()) {
            BlockPos seed = unvisited.iterator().next();
            unvisited.remove(seed);

            List<BlockPos> members = new ArrayList<>();
            members.add(seed);

            Queue<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                Iterator<BlockPos> it = unvisited.iterator();
                while (it.hasNext()) {
                    BlockPos candidate = it.next();
                    if (current.distSqr(candidate) <= mergeDistSq) {
                        it.remove();
                        members.add(candidate);
                        queue.add(candidate);
                    }
                }
            }

            result.add(new BlockCluster(members, playerVec));
        }

        result.sort(Comparator.comparingDouble(c -> c.distanceToPlayer));
        return Collections.unmodifiableList(result);
    }
}
