package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanStaggerLock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class StaggerItemMixin {
    @Inject(method = "startUsingItem", at = @At("HEAD"), cancellable = true)
    private void meridian$staggerUse(InteractionHand hand, CallbackInfo callback) {
        // 包括弓、盾与食物的直接使用入口，防止绕过交互事件重新开始动作。
        if ((Object) this instanceof ServerPlayer player && HumanStaggerLock.isLocked(player.getUUID())) {
            callback.cancel();
        }
    }
}
