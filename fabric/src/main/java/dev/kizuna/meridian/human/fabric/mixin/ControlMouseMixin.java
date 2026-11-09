package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.client.HumanControlClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class ControlMouseMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void meridian$controlledScroll(CallbackInfo callback) {
        // 游戏内滚轮不能切换手持物，聊天与暂停菜单仍接收正常滚动。
        if (HumanControlClient.active() && Minecraft.getInstance().gui.screen() == null) {
            callback.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void meridian$controlledView(CallbackInfo callback) {
        // 仅阻止游戏内转头，聊天与暂停菜单的鼠标仍由原版处理。
        if (HumanControlClient.active()) {
            callback.cancel();
        }
    }
}
