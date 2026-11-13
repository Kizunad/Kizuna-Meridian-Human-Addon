package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrike;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** 拳脚打击的击退：推开、横推、绊、踩落、掀起都走原版速度入口。 */
final class HumanLimbKnockback {
    private static final double TRIP_UPWARD_METERS_PER_SECOND = 2;
    private static final double TICKS_PER_SECOND = 20;

    private HumanLimbKnockback() {
        // 击退只按一次命中的参数改目标速度，不持有实体。
    }

    static void apply(Push push) {
        // 击退方式来自目录；没有击退时什么也不做。
        switch (push.strike().getKnockbackKind()) {
            case PUSH -> push(push);
            case SIDEWAYS -> sideways(push);
            case TRIP -> trip(push.target());
            case DOWN -> down(push.target(), amount(push));
            case LIFT -> lift(push);
            case NONE -> { }
            default -> throw new IllegalArgumentException("未知肢击击退方式");
        }
    }

    private static double amount(Push push) {
        // 目录里的击退大小，单位是原版击退强度。
        return push.strike().getKnockback().getValue();
    }

    private static void push(Push push) {
        // 推开沿攻击者到目标的反方向计算，击退抗性仍由原版处理。
        Vec3 delta = push.actor().position().subtract(push.target().position());
        push.target().knockback(amount(push), delta.x, delta.z, push.source(), push.dose());
    }

    private static void sideways(Push push) {
        // 横推沿当前打击朝向的右侧，身前与身后打击的方向都保持可预测。
        Vec3 facing = push.facing();
        Vec3 side = new Vec3(-facing.z, 0, facing.x);
        push.target().knockback(amount(push), side.x, side.z, push.source(), push.dose());
    }

    private static void trip(LivingEntity target) {
        // 绊倒清空水平速度，以固定每秒速度挑起，覆盖原有下落或跳跃速度。
        double upwardPerTick = TRIP_UPWARD_METERS_PER_SECOND / TICKS_PER_SECOND;
        target.setDeltaMovement(0, upwardPerTick, 0);
        target.hurtMarked = true;
    }

    private static void down(LivingEntity target, double amount) {
        // 踩落不抹掉目标横向运动，只确保竖直速度向下。
        Vec3 velocity = target.getDeltaMovement();
        double downward = Math.min(velocity.y, -amount);
        target.setDeltaMovement(velocity.x, downward, velocity.z);
        target.hurtMarked = true;
    }

    private static void lift(Push push) {
        // 掀起先按目录大小推开（可为零），再把竖直速度设成目录速度乘 (1 − 击退抗性)。
        push(push);
        LivingEntity target = push.target();
        double resistance = Math.clamp(target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1);
        double upwardPerTick = push.strike().getLiftSpeed().getValue() / TICKS_PER_SECOND * (1 - resistance);
        Vec3 velocity = target.getDeltaMovement();
        target.setDeltaMovement(velocity.x, upwardPerTick, velocity.z);
        target.hurtMarked = true;
    }

    /** 一次命中的击退参数；朝向是打击方向的水平单位向量。 */
    record Push(LivingEntity actor, LivingEntity target, LimbStrike strike, DamageSource source, float dose,
                Vec3 facing) { }
}
