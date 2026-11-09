package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.client.HumanControlClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class ControlScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void meridian$controlledInventory(Screen screen, CallbackInfo callback) {
        // 本地背包没有开包请求，必须在客户端拦截容器页面；聊天和暂停照常。
        if (HumanControlClient.active() && screen instanceof AbstractContainerScreen<?>) {
            callback.cancel();
        }
    }
}
