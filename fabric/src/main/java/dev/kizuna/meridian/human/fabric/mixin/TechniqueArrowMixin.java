package dev.kizuna.meridian.human.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanTechniqueArrow;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 额外穿透与盾冷却附着在原版箭上，不替换实体类型或命中计算。 */
@Mixin(AbstractArrow.class)
public abstract class TechniqueArrowMixin implements HumanTechniqueArrow {
    @Unique
    private static final String SHIELD_COOLDOWN_KEY = "meridian_human:shield_cooldown_millis";
    @Unique
    private long meridianHumanShieldCooldownMillis;

    @Override
    @Invoker("setPierceLevel")
    public abstract void meridianHumanSetPiercing(byte piercing);

    @Override
    public void meridianHumanShieldCooldown(long millis) {
        // 只由服务端功法执行器写入，普通原版箭保持零。
        meridianHumanShieldCooldownMillis = millis;
    }

    @Override
    public long meridianHumanShieldCooldown() {
        // 伤害入口只读箭自己的参数，施放结束后仍能正确处理晚到的箭。
        return meridianHumanShieldCooldownMillis;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void saveShieldCooldown(ValueOutput output, CallbackInfo callback) {
        // 随原版箭保存，卸载区块后不丢失已经发出的破盾语义。
        if (meridianHumanShieldCooldownMillis > 0) {
            output.putLong(SHIELD_COOLDOWN_KEY, meridianHumanShieldCooldownMillis);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void readShieldCooldown(ValueInput input, CallbackInfo callback) {
        // 未经功法发射的原版箭自然没有附加参数。
        meridianHumanShieldCooldownMillis = input.getLongOr(SHIELD_COOLDOWN_KEY, 0);
    }
}
