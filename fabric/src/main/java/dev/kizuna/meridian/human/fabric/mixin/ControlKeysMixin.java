package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.client.HumanControlClient;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public abstract class ControlKeysMixin {
    @Shadow private int clickCount;

    @Inject(method = {"isDown", "consumeClick"}, at = @At("HEAD"), cancellable = true)
    private void meridian$controlledKey(CallbackInfoReturnable<Boolean> callback) {
        // 连战斗 UI 的独立键位也失效，积压点击立即丢弃，结束后不能补发。
        if (!HumanControlClient.allows((KeyMapping) (Object) this)) {
            clickCount = 0;
            callback.setReturnValue(false /* pressed */);
        }
    }
}
