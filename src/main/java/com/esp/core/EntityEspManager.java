package com.esp.core;

import com.playerapi.EntityHighlightActions;
import com.playerapi.PlayerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entity-type ESP: highlights every entity whose type is on the approved list —
 * the entity twin of {@link com.esp.block.BlockEspManager}. Uses the same
 * two-layer highlight as the label-based {@link EspManager}: a glow outline
 * (through walls, applied by the entity mixins which read {@link #isHighlighted})
 * and the depth-tested overlay (line of sight, via PlayerAPI's
 * {@link EntityHighlightActions}).
 *
 * <p>Unlike the label ESP there is no flaky-detection latch — entity-type
 * matching is stable, so each scan simply rebuilds and replaces the set.</p>
 */
public class EntityEspManager {

    private static final EntityEspManager INSTANCE = new EntityEspManager();
    public static EntityEspManager getInstance() { return INSTANCE; }
    private EntityEspManager() {}

    /** Namespace used when publishing to PlayerAPI's overlay service. */
    private static final String OVERLAY_OWNER = "esp-entities";

    /** Entity ID → packed RGB colour. Replaced atomically each scan; read on the render thread. */
    private volatile Map<Integer, Integer> highlighted = Map.of();

    private Set<EntityType<?>> targets = Set.of();
    private int      targetsKey   = 0;
    private int      tickCounter  = 0;
    private Object   lastLevel    = null;
    private boolean  publishedOverlay = false;

    // ── Render-thread queries (called by the glow mixins) ──────────────────────

    public boolean isHighlighted(int entityId) { return highlighted.containsKey(entityId); }
    public int getColor(int entityId)           { return highlighted.getOrDefault(entityId, 0xFFFFFF); }

    // ── Tick ───────────────────────────────────────────────────────────────────

    public void tick() {
        EspConfig cfg = EspConfig.getInstance();
        EspConfig.EntityEspSettings s = cfg.getEntityEspSettings();

        if (!cfg.isGlobalEnabled() || !s.enabled || s.entities.isEmpty() || !PlayerInfo.isInWorld()) {
            clear();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (mc.player == null || level == null) return;

        // World / instance change → reset (entity IDs don't carry across).
        if (level != lastLevel) {
            lastLevel   = level;
            highlighted = Map.of();
            EntityHighlightActions.clearOwner(OVERLAY_OWNER);
            publishedOverlay = false;
        }

        // Rebuild the resolved target set when the configured list changes.
        int key = s.entities.hashCode();
        if (key != targetsKey || targets.isEmpty()) {
            targets    = resolveTargets(s.entities);
            targetsKey = key;
        }
        if (targets.isEmpty()) { clear(); return; }

        if (++tickCounter < s.scanIntervalTicks) return;
        tickCounter = 0;

        double r = s.scanRadius;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        AABB box = new AABB(px - r, py - r, pz - r, px + r, py + r, pz + r);

        Map<Integer, Integer> map = new HashMap<>();
        for (Entity e : level.getEntities(mc.player, box)) {
            if (targets.contains(e.getType())) map.put(e.getId(), s.color);
        }
        highlighted = map;

        // Overlay layer reuses the global overlay toggle / opacity, like the label ESP.
        if (!map.isEmpty() && cfg.isOverlayEnabled()) {
            EntityHighlightActions.setHighlights(OVERLAY_OWNER, map, (float) cfg.getOverlayAlpha());
            publishedOverlay = true;
        } else if (publishedOverlay) {
            EntityHighlightActions.clearOwner(OVERLAY_OWNER);
            publishedOverlay = false;
        }
    }

    private void clear() {
        if (!highlighted.isEmpty()) highlighted = Map.of();
        if (publishedOverlay) {
            EntityHighlightActions.clearOwner(OVERLAY_OWNER);
            publishedOverlay = false;
        }
    }

    private static Set<EntityType<?>> resolveTargets(List<String> ids) {
        Set<EntityType<?>> set = new HashSet<>();
        for (String id : ids) {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) continue;
            BuiltInRegistries.ENTITY_TYPE.getOptional(rl).ifPresent(set::add);
        }
        return set;
    }
}
