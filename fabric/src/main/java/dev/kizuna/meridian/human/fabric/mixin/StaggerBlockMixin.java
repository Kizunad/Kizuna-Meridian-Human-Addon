package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanStaggerLock;
import dev.kizuna.meridian.human.fabric.HumanControl;
import dev.kizuna.meridian.human.fabric.HumanTechniqueHost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(ServerPlayerGameMode.class)
public abstract class StaggerBlockMixin {
    private static final int CLEAR_PROGRESS = -1;

    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;
    @Shadow private boolean isDestroyingBlock;
    @Shadow private boolean hasDelayedDestroy;
    @Shadow private BlockPos destroyPos;
    @Shadow private BlockPos delayedDestroyPos;

    @Inject(method = "tick", at = @At("HEAD"))
    private void meridian$stopDigging(CallbackInfo callback) {
        // 已开始或等待服务器补完的挖掘都要取消，解锁后必须重新开始。
        UUID playerId = player.getUUID();
        if (!HumanStaggerLock.isLocked(playerId) && !HumanControl.controlled(playerId)
                && !HumanTechniqueHost.isCasting(playerId)) {
            return;
        }
        int entityId = player.getId();
        if (isDestroyingBlock) {
            level.destroyBlockProgress(entityId, destroyPos, CLEAR_PROGRESS);
            isDestroyingBlock = false;
        }
        if (hasDelayedDestroy) {
            level.destroyBlockProgress(entityId, delayedDestroyPos, CLEAR_PROGRESS);
            hasDelayedDestroy = false;
        }
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void meridian$blockDestruction(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        // 同 tick 的完成包不能在下一次 tick 清进度以前破坏方块。
        UUID playerId = player.getUUID();
        if (HumanStaggerLock.isLocked(playerId) || HumanControl.controlled(playerId)
                || HumanTechniqueHost.isCasting(playerId)) {
            callback.setReturnValue(false /* destroyed */);
        }
    }
}
