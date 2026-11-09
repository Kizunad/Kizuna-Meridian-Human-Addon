package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.client.HumanControlClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class ControlInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void meridian$controlledInput(CallbackInfo callback) {
        // 服务端意图覆盖移动、跳和疾跑；左键攻击由服务端执行，不再额外发攻击包。
        if (!HumanControlClient.active()) {
            return;
        }
        var intent = HumanControlClient.sample();
        float forward = intent.getForward();
        float sideways = intent.getSideways();
        boolean sprint = intent.getSprint();
        keyPresses = new Input(forward > 0, forward < 0, sideways > 0, sideways < 0,
                intent.getJump(), false /* shift */, sprint);
        moveVector = new Vec2(sideways, forward);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.setYRot(intent.getYaw());
            player.setXRot(intent.getPitch());
            player.setSprinting(sprint);
        }
        callback.cancel();
    }
}
