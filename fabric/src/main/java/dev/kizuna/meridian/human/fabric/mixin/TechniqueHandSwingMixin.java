package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanTechniqueHost;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerPlayNetworkHandler.class, priority = 1100)
public abstract class TechniqueHandSwingMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onHandSwing", at = @At("HEAD"), cancellable = true)
    private void meridian$blockCastingSwing(HandSwingC2SPacket packet, CallbackInfo callback) {
        // 功法每击自行挥手和做功，客户端原版左键不能再产生第二次出手。
        if (player.getServer().isOnThread() && HumanTechniqueHost.isCasting(player.getUuid())) {
            callback.cancel();
        }
    }
}
