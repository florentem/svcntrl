package com.svcntrl.mixin;

import com.svcntrl.core.PreviewManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.server.network.ServerCommonPacketListenerImpl.class)
public class ServerCommonNetworkHandlerMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onSendPacketHead(Packet<?> packet, CallbackInfo ci) {
        if (!PreviewManager.getInstance().hasAnyPreviews()) return;
        net.minecraft.server.network.ServerCommonPacketListenerImpl handler = (net.minecraft.server.network.ServerCommonPacketListenerImpl) (Object) this;
        if (!(handler instanceof ServerGamePacketListenerImpl playHandler)) return;
        if (!PreviewManager.getInstance().hasPreview(playHandler.getPlayer().getUUID())) return;

            Entity entityToHide = null;
            if (packet instanceof ClientboundAddEntityPacket spawnPacket) {
                entityToHide = ((net.minecraft.server.level.ServerLevel)playHandler.getPlayer().level()).getEntity(spawnPacket.getId());
            } else if (packet instanceof ClientboundSetEntityDataPacket trackerPacket) {
                entityToHide = ((net.minecraft.server.level.ServerLevel)playHandler.getPlayer().level()).getEntity(trackerPacket.id());
            }
            
        if (entityToHide != null && PreviewManager.getInstance().isEntityHidden(playHandler.getPlayer(), entityToHide)) {
            ci.cancel();
        }
    }
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("TAIL"))
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (!PreviewManager.getInstance().hasAnyPreviews()) return;
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            net.minecraft.server.network.ServerCommonPacketListenerImpl handler = (net.minecraft.server.network.ServerCommonPacketListenerImpl) (Object) this;
        if (!(handler instanceof ServerGamePacketListenerImpl playHandler)) return;
            PreviewManager.getInstance().onChunkSent(playHandler.getPlayer(), chunkPacket.getX(), chunkPacket.getZ());
        }
    }
}
