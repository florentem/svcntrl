package com.svcntrl.mixin;

import com.svcntrl.core.PreviewManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;

import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Inject(method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onSendPacketHead(Packet<?> packet, CallbackInfo ci) {
        if (!PreviewManager.getInstance().hasAnyPreviews()) return;
        ServerCommonNetworkHandler handler = (ServerCommonNetworkHandler) (Object) this;
        if (handler instanceof ServerPlayNetworkHandler playHandler) {
            if (!PreviewManager.getInstance().hasPreview(playHandler.getPlayer().getUuid())) return;

            Entity entityToHide = null;
            if (packet instanceof EntitySpawnS2CPacket spawnPacket) {
                entityToHide = ((net.minecraft.server.world.ServerWorld)playHandler.getPlayer().getWorld()).getEntityById(spawnPacket.getEntityId());
            } else if (packet instanceof EntityTrackerUpdateS2CPacket trackerPacket) {
                entityToHide = ((net.minecraft.server.world.ServerWorld)playHandler.getPlayer().getWorld()).getEntityById(trackerPacket.id());
            }
            
            if (entityToHide != null && PreviewManager.getInstance().isEntityHidden(playHandler.getPlayer(), entityToHide)) {
                ci.cancel();
            }
        }
    }
    @Inject(method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V", at = @At("TAIL"))
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (!PreviewManager.getInstance().hasAnyPreviews()) return;
        if (packet instanceof ChunkDataS2CPacket chunkPacket) {
            ServerCommonNetworkHandler handler = (ServerCommonNetworkHandler) (Object) this;
            if (handler instanceof ServerPlayNetworkHandler playHandler) {
                PreviewManager.getInstance().onChunkSent(playHandler.getPlayer(), chunkPacket.getChunkX(), chunkPacket.getChunkZ());
            }
        }
    }
}
