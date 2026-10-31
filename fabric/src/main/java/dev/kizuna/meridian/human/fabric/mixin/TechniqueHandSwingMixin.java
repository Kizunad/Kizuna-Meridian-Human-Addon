package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanTechniqueHost;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerGamePacketListenerImpl.class, priority = 1100)
public abstract class TechniqueHandSwingMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleAnimate", at = @At("HEAD"), cancellable = true)
    private void meridian$blockCastingSwing(ServerboundSwingPacket packet, CallbackInfo callback) {
        // 功法每击自行挥手和做功，客户端原版左键不能再产生第二次出手。
        if (player.level().getServer().isSameThread() && HumanTechniqueHost.isCasting(player.getUUID())) {
            callback.cancel();
        }
    }
}
