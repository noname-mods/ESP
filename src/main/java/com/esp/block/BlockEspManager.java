package com.esp.block;

import com.esp.core.EspConfig;
import com.playerapi.BlockHighlightActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Simple block ESP: highlights every instance of each user-approved block type
 * within range, through walls. Reuses the chunk-section palette scan from the
 * gemstone prototype but drops clustering — it just collects matching positions
 * and publishes them to PlayerAPI's {@link BlockHighlightActions}.
 *
 * <p>The full gemstone layer (clustering, nearest-distance, waypoints) will be
 * built on top of this later; for now it's a flat "outline these blocks" pass.</p>
 *
 * <h2>Threading</h2>
 * {@link #tick()} and {@link #markDirty()} run on the game thread. Highlight
 * publishing also happens on the game thread, so there's no shared mutable state
 * read off-thread.
 */
public class BlockEspManager {

    private static BlockEspManager INSTANCE;
    private BlockEspManager() {}
    public static BlockEspManager getInstance() {
        if (INSTANCE == null) INSTANCE = new BlockEspManager();
        return INSTANCE;
    }

    /** Namespace under which block highlights are published to PlayerAPI. */
    private static final String OWNER = "esp-blocks";

    /** Player must move this far (blocks) before a position-triggered rescan fires. */
    private static final double RESCAN_DIST_SQ     = 8.0 * 8.0;
    /** Minimum ticks between any two scans, regardless of trigger. */
    private static final int    MIN_RESCAN_INTERVAL = 5;
    /** Debounce after a block-change packet before rescanning. */
    private static final int    BLOCK_CHANGE_DEBOUNCE = 8;
    /**
     * Hard cap on highlighted blocks per scan — keeps a careless selection
     * (e.g. "stone" at a large radius) from flooding the gizmo draw. The scan
     * stops collecting once this is hit.
     */
    private static final int    MAX_HIGHLIGHTS     = 3000;

    // ── State ──────────────────────────────────────────────────────────────────

    private int         ticksSinceLastScan = 0;
    private int         dirtyCountdown     = 0;
    private ClientLevel lastLevel          = null;
    private Vec3        lastScanOrigin     = null;

    /** Resolved target blocks, rebuilt when the config block list changes. */
    private Set<Block>  targets            = Set.of();
    private int         targetsKey         = 0;

    /** True once we've published a non-empty set, so we know to clear on disable. */
    private boolean     published          = false;

    public void markDirty() {
        if (dirtyCountdown <= 0) dirtyCountdown = BLOCK_CHANGE_DEBOUNCE;
    }

    // ── Tick ───────────────────────────────────────────────────────────────────

    public void tick() {
        EspConfig.BlockEspSettings cfg = EspConfig.getInstance().getBlockEspSettings();

        if (!cfg.enabled || cfg.blocks.isEmpty()) {
            if (published) {
                BlockHighlightActions.clearOwner(OWNER);
                published      = false;
                lastScanOrigin = null;
            }
            dirtyCountdown = 0;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level  = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        // Rebuild the resolved target set when the configured list changes.
        int key = cfg.blocks.hashCode();
        if (key != targetsKey || targets.isEmpty()) {
            targets    = resolveTargets(cfg.blocks);
            targetsKey = key;
        }
        if (targets.isEmpty()) {
            if (published) { BlockHighlightActions.clearOwner(OWNER); published = false; }
            return;
        }

        // World / dimension change → reset and force an immediate scan.
        if (level != lastLevel) {
            lastLevel          = level;
            lastScanOrigin     = null;
            ticksSinceLastScan = cfg.scanIntervalTicks;
            dirtyCountdown     = 0;
            BlockHighlightActions.clearOwner(OWNER);
            published          = false;
        }

        ticksSinceLastScan++;
        if (ticksSinceLastScan < MIN_RESCAN_INTERVAL) return;

        Vec3 pos = player.position();
        boolean movedFar = lastScanOrigin == null || lastScanOrigin.distanceToSqr(pos) > RESCAN_DIST_SQ;

        boolean dirtyFired = false;
        if (dirtyCountdown > 0 && --dirtyCountdown == 0) dirtyFired = true;

        boolean timerFired = ticksSinceLastScan >= cfg.scanIntervalTicks;
        if (!movedFar && !dirtyFired && !timerFired) return;

        ticksSinceLastScan = 0;
        lastScanOrigin     = pos;

        List<BlockPos> found = scan(level, player, cfg);
        if (found.isEmpty()) {
            if (published) { BlockHighlightActions.clearOwner(OWNER); published = false; }
        } else {
            BlockHighlightActions.setHighlights(OWNER, found, cfg.color, (float) cfg.alpha);
            published = true;
        }
    }

    // ── Resolve registry IDs → Block instances ─────────────────────────────────

    private static Set<Block> resolveTargets(List<String> ids) {
        Set<Block> set = new HashSet<>();
        for (String id : ids) {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) continue;
            Block b = BuiltInRegistries.BLOCK.getValue(rl);
            if (b != null && b != Blocks.AIR) set.add(b);
        }
        return set;
    }

    // ── Chunk-section optimised block scan (no clustering) ─────────────────────

    private List<BlockPos> scan(ClientLevel level, LocalPlayer player, EspConfig.BlockEspSettings cfg) {
        BlockPos origin = player.blockPosition();
        int r = (int) cfg.scanRadius;

        int minCX = (origin.getX() - r) >> 4;
        int maxCX = (origin.getX() + r) >> 4;
        int minCZ = (origin.getZ() - r) >> 4;
        int maxCZ = (origin.getZ() + r) >> 4;

        int minBuildY    = level.getMinY();
        int sectionCount = level.getSectionsCount();
        int minSY = Math.max(((origin.getY() - r) - minBuildY) >> 4, 0);
        int maxSY = Math.min(((origin.getY() + r) - minBuildY) >> 4, sectionCount - 1);

        List<BlockPos> found = new ArrayList<>();

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = (LevelChunk) level.getChunk(cx, cz);

                for (int sy = minSY; sy <= maxSY; sy++) {
                    LevelChunkSection section = chunk.getSection(sy);
                    if (section.hasOnlyAir()) continue;
                    if (!section.getStates().maybeHas(s -> targets.contains(s.getBlock()))) continue;

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
                                if (targets.contains(section.getBlockState(lx, ly, lz).getBlock())) {
                                    found.add(new BlockPos(bx, by, bz));
                                    if (found.size() >= MAX_HIGHLIGHTS) return found;
                                }
                            }
                        }
                    }
                }
            }
        }
        return found;
    }
}
