package com.esp.mixin;

import com.esp.block.BlockEspManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Listens for server-side block change packets and notifies
 * {@link BlockEspManager} so it can debounce-rescan after changes.
 *
 * <p>Both single-block ({@code handleBlockUpdate}) and bulk section updates
 * ({@code handleChunkBlocksUpdate}) are intercepted.  The manager applies a
 * short debounce so a rapid burst (explosion, piston array, batch-mine) only
 * triggers one rescan rather than one per packet.</p>
 *
 * <p>These handlers run on the main game thread (Minecraft.execute-scheduled
 * by the network layer), so no additional synchronisation is needed.</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void esp$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        BlockEspManager.getInstance().markDirty();
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void esp$onChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        BlockEspManager.getInstance().markDirty();
    }
}
