package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueStaggers;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 倒地的玩家趴着爬：两端都按同步过来的倒地减速修饰摆爬行姿势，起来后照原版重新选姿势。 */
@Mixin(Player.class)
public abstract class PlayerDownPoseMixin {
    @Inject(method = "getDesiredPose", at = @At("HEAD"), cancellable = true)
    private void meridian$crawlWhileDown(CallbackInfoReturnable<Pose> callback) {
        // 观战、骑乘仍由原版在外层排除；爬行姿势最矮，总放得下。
        if (TechniqueStaggers.downed((Player) (Object) this)) {
            callback.setReturnValue(Pose.SWIMMING);
        }
    }
}
