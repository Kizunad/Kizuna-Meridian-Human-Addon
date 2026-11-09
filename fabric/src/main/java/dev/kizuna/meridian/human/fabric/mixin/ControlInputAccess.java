package dev.kizuna.meridian.human.fabric.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 结束受控模式时同步清空原版输入缓存，避免下一帧继续使用旧移动向量。 */
@Mixin(ClientInput.class)
public interface ControlInputAccess {
    @Accessor("moveVector")
    void meridian$moveVector(Vec2 vector);
}
