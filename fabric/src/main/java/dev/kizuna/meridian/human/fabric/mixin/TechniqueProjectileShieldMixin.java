package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanTechniqueArrow;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 仅在原版确认盾实际挡下射击之后，应用目录声明的冷却。 */
@Mixin(LivingEntity.class)
public abstract class TechniqueProjectileShieldMixin {
    private static final float MILLIS_PER_SECOND = 1000;

    @Inject(method = "applyItemBlocking", at = @At("RETURN"))
    private void disableAfterProjectileBlock(ServerLevel level, DamageSource source, float damage,
                                             CallbackInfoReturnable<Float> callback) {
        // 伤害类型、朝向、举盾延迟和穿透都由原版先判定，不能把未格挡命中当作破盾。
        if (callback.getReturnValue() <= 0 || !(source.getDirectEntity() instanceof HumanTechniqueArrow arrow)) {
            return;
        }
        long millis = arrow.meridianHumanShieldCooldown();
        if (millis <= 0) {
            return;
        }
        LivingEntity target = (LivingEntity) (Object) this;
        ItemStack shield = target.getItemBlockingWith();
        if (shield == null) {
            return;
        }
        BlocksAttacks blocking = shield.get(DataComponents.BLOCKS_ATTACKS);
        if (blocking != null) {
            blocking.disable(level, target, millis / MILLIS_PER_SECOND, shield);
        }
    }
}
