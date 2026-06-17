package com.esp.mixin;

import com.esp.core.EntityEspManager;
import com.esp.core.EspConfig;
import com.esp.core.EspManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Returns the ESP group's configured colour from {@link Entity#getTeamColor()},
 * which the vanilla outline renderer uses to tint the glow effect.
 *
 * <p>Only overrides entities that are in the ESP highlight map; all other entities
 * continue to use their normal team colour (or white if not on a team).</p>
 */
@Mixin(Entity.class)
public abstract class EntityTeamColorMixin {

    // Throttle debug prints — this method runs every frame for every entity.
    private static long esp$lastDebugLog = 0;

    @Inject(method = "getTeamColor", at = @At("RETURN"), cancellable = true)
    private void esp$overrideGlowColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        int id = self.getId();
        EspManager em = EspManager.getInstance();
        EntityEspManager eem = EntityEspManager.getInstance();

        // Label-matched ESP takes priority; entity-type ESP is the fallback source.
        // Explicit branches (no ternary) so there's no int/Integer mixing — a
        // conditional that mixes int and null unboxes the whole expression to int
        // and NPEs on the null path for every non-highlighted entity.
        int color;
        if (em.isHighlighted(id)) {
            color = em.getColor(id);
        } else if (eem.isHighlighted(id)) {
            color = eem.getColor(id);
        } else {
            return; // not highlighted by either — leave the vanilla team colour
        }

        cir.setReturnValue(color);

        if (EspConfig.getInstance().isDebugLogging()) {
            long now = System.currentTimeMillis();
            if (now - esp$lastDebugLog > 1000) {
                esp$lastDebugLog = now;
                System.out.println("[ESP][DEBUG][Render] getTeamColor forced to "
                        + String.format("#%06X", color) + " for "
                        + self.getType().toShortString() + " (id=" + id + ")");
            }
        }
    }
}
