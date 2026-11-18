package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanTechniqueHost;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class GuardSprintMixin {
    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void meridian$guardSprint(boolean sprinting, CallbackInfo callback) {
        // 包含玩家指令包与直接实体调用，守的任一施放阶段都不允许开始疾跑。
        if (sprinting && (Object) this instanceof ServerPlayer player
                && HumanTechniqueHost.blocksSprint(player.getUUID())) {
            callback.cancel();
        }
    }
}
