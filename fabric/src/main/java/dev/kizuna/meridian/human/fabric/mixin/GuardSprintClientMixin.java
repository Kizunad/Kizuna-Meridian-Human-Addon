package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.client.HumanGuardClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class GuardSprintClientMixin {
    @Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
    private void meridian$preventSprintPrediction(CallbackInfoReturnable<Boolean> callback) {
        // 双击前进和疾跑键共用原版起跑条件，守时均不发起预测。
        if (HumanGuardClient.blocksSprint()) {
            callback.setReturnValue(false /* canSprint */);
        }
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void meridian$stopExistingSprint(CallbackInfo callback) {
        // 服务端确认守式后当帧停止已开始的疾跑。
        if (HumanGuardClient.blocksSprint()) {
            ((LocalPlayer) (Object) this).setSprinting(false /* sprinting */);
        }
    }
}
