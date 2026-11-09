package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanControl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 服务端拒绝受控玩家主动发来的物品、攻击与方块操作。 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ControlPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = {"handlePlayerAction", "handleUseItemOn", "handleUseItem", "handleSetCarriedItem",
            "handleAttack", "handleInteract", "handleContainerClick", "handlePlaceRecipe",
            "handleContainerButtonClick", "handleSetCreativeModeSlot", "handlePickItemFromBlock",
            "handlePickItemFromEntity"}, at = @At("HEAD"), cancellable = true)
    private void meridian$rejectControlledAction(CallbackInfo callback) {
        // 网络线程先由原版排入主线程，再检查会话；攻击由 Human 直接调用，不走玩家包。
        if (player.level().getServer().isSameThread() && HumanControl.controlled(player.getUUID())) {
            player.containerMenu.sendAllDataToRemote();
            callback.cancel();
        }
    }
}
