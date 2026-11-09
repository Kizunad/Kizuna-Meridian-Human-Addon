package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanControl;
import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ControlMenuMixin {
    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void meridian$rejectControlledMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> callback) {
        // 服务器发起的容器交互也遵守受控门禁，聊天与暂停不经过容器入口。
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (HumanControl.controlled(player.getUUID())) {
            callback.setReturnValue(OptionalInt.empty());
        }
    }
}
