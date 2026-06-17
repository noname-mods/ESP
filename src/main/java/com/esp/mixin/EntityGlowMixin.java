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
 * Makes entities in the ESP highlight map appear glowing by overriding
 * {@link Entity#isCurrentlyGlowing()} on the client side.
 *
 * <p>This only affects rendering — no server packets are sent and the
 * entity's actual data is not modified.</p>
 */
@Mixin(Entity.class)
public abstract class EntityGlowMixin {

    // Throttle debug prints — this method runs every frame for every entity.
    private static long esp$lastDebugLog = 0;

    @Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
    private void esp$forceGlow(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return; // already glowing — no need to override
        Entity self = (Entity) (Object) this;
        if (EspManager.getInstance().isHighlighted(self.getId())
                || EntityEspManager.getInstance().isHighlighted(self.getId())) {
            cir.setReturnValue(true);

            if (EspConfig.getInstance().isDebugLogging()) {
                long now = System.currentTimeMillis();
                if (now - esp$lastDebugLog > 1000) {
                    esp$lastDebugLog = now;
                    System.out.println("[ESP][DEBUG][Render] isCurrentlyGlowing forced TRUE for "
                            + self.getType().toShortString() + " (id=" + self.getId() + ")");
                }
            }
        }
    }
}
