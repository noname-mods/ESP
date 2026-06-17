package com.esp.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * An immutable snapshot of a group of nearby matching blocks, along with the
 * exact geometric centre of those blocks and the distance from the local player
 * to that centre at the time of the last scan.
 *
 * <p>Clusters are rebuilt from scratch each scan; nothing is mutated after
 * construction.</p>
 */
public final class BlockCluster {

    /** Block-coordinate centre (floored average). Used for labelling / waypoints. */
    public final BlockPos centroid;

    /** Sub-block-accurate centre (centre of each block face averaged). */
    public final Vec3 exactCenter;

    /** How many blocks make up this cluster. */
    public final int size;

    /** Straight-line distance from the player to {@link #exactCenter}, in blocks. */
    public final double distanceToPlayer;

    /**
     * @param members     every BlockPos belonging to this cluster (≥ 1 entry)
     * @param playerPos   player's eye / foot position at scan time
     */
    public BlockCluster(List<BlockPos> members, Vec3 playerPos) {
        this.size = members.size();

        double sx = 0, sy = 0, sz = 0;
        for (BlockPos p : members) {
            sx += p.getX() + 0.5;   // +0.5 → block centre
            sy += p.getY() + 0.5;
            sz += p.getZ() + 0.5;
        }
        double cx = sx / size, cy = sy / size, cz = sz / size;

        this.exactCenter      = new Vec3(cx, cy, cz);
        this.centroid         = new BlockPos((int) Math.floor(cx),
                                             (int) Math.floor(cy),
                                             (int) Math.floor(cz));
        this.distanceToPlayer = exactCenter.distanceTo(playerPos);
    }

    @Override
    public String toString() {
        return String.format("BlockCluster{size=%d, centroid=%s, dist=%.1f}",
                size, centroid, distanceToPlayer);
    }
}
