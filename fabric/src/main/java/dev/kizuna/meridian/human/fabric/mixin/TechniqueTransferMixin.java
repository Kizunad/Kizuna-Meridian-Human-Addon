package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanTechniqueHost;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class TechniqueTransferMixin {
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)"
            + "Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"))
    private void meridian$leaveWorld(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> callback) {
        // 换世界前旧宿主仍认领原实体，STOP/UNLOCK 必须在这一刻执行。
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (transition.newLevel() != player.level()) {
            HumanTechniqueHost.beforeUnload(player);
        }
    }

    @Inject(method = "restoreFrom", at = @At("HEAD"))
    private void meridian$replacePlayer(ServerPlayer previous, boolean alive, CallbackInfo callback) {
        // 活着换实体与死亡重生都先清旧实体；不能把旧属性修饰复制到新实体。
        HumanTechniqueHost.beforeUnload(previous);
    }
}
