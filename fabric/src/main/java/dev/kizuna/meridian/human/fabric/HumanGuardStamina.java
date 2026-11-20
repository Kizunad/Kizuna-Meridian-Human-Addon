package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.DamageAttachment;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.server.GuardState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric.GuardTool;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.GuardStamina;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueGuardStamina;
import dev.kizuna.meridian.technique.server.TechniqueState;

import java.util.Optional;

/** 玩家和生人共用的守窗身体结算，不持有实体、时钟或独立的余额。 */
public final class HumanGuardStamina {
    private static final double MILLIS_PER_SECOND = 1000;
    public static final String BROKEN = "meridian_human_host.guard.broken";
    public static final String WEAK = "meridian_human_host.guard.weak";

    private HumanGuardStamina() {
        // 计算依赖已结算的身体版本。
    }

    public static Optional<String> rejection(BodySnapshot body, String technique, GuardStamina rules) {
        // 破防截止和恢复比例均须满足；普通松手不会留下破防记录。
        var progress = TechniqueState.read(body);
        boolean broken = progress.containsGuardBreakEnds(technique);
        if (broken && body.settledAt().elapsedMillis() < progress.getGuardBreakEndsOrThrow(technique)) {
            return Optional.of(BROKEN);
        }
        var stamina = HumanStamina.from(body);
        double available = stamina.availableJoules();
        double capacity = stamina.capacityJoules();
        double required = capacity * rules.getRestartRatio();
        // 框架逐块计算 1 - 疲劳比，再求总和；阈值相等时允许满额焦耳的一个浮点末位。
        if (available <= 0 || broken && required - available > Math.ulp(capacity)) {
            return Optional.of(WEAK);
        }
        return Optional.empty();
    }

    public static Optional<TechniqueProgress> holding(BodySnapshot body) {
        // 死亡或没有动作的身体无需解码施放进度。
        if (body.vitality().dead() || body.actions().isEmpty()
                || !body.addonProgress().containsKey(TechniqueState.KEY)) {
            return Optional.empty();
        }
        var progress = TechniqueState.read(body);
        var guard = progress.getActiveCast().getSustained();
        if (!guard.getGuard().hasStamina() || guard.hasEndRequestedMillis()) {
            return Optional.empty();
        }
        return Optional.of(progress);
    }

    public static long boundary(BodySnapshot body, TechniqueProgress progress, long target) {
        // 长推进在成架和耗尽处分段，释放占用后剩余时间可正常恢复。
        var active = progress.getActiveCast();
        long now = body.settledAt().elapsedMillis();
        long warmup = active.getWarmupEndMillis();
        if (now < warmup) {
            return Math.min(target, warmup);
        }
        var rules = active.getSustained().getGuard().getStamina();
        double available = HumanStamina.from(body).availableJoules();
        long duration = Math.max(1, (long) Math.ceil(available * MILLIS_PER_SECOND / rules.getJoulesPerSecond()));
        return now + Math.min(target - now, duration);
    }

    public static Optional<BodyActivity> payment(BodySnapshot body, TechniqueProgress progress) {
        // 已付游标随疲劳同笔提交，同一身体时刻允许刷新受伤或治好的占用组织。
        var active = progress.getActiveCast();
        long now = body.settledAt().elapsedMillis();
        long warmup = active.getWarmupEndMillis();
        if (now < warmup) {
            return Optional.empty();
        }
        var guard = active.getSustained();
        long paid = warmup;
        if (guard.hasStaminaPaidMillis()) {
            paid = guard.getStaminaPaidMillis();
        }
        double joules = (now - paid) * guard.getGuard().getStamina().getJoulesPerSecond() / MILLIS_PER_SECOND;
        var stamina = HumanStamina.from(body);
        BodyActivity plan;
        if (joules >= stamina.availableJoules()) {
            plan = TechniqueGuardStamina.exhaust(body, progress);
        } else {
            plan = TechniqueGuardStamina.hold(body, progress, stamina.workingMuscles());
        }
        if (plan.equals(BodyActivity.getDefaultInstance())) {
            return Optional.empty();
        }
        return Optional.of(plan.toBuilder().addAllTissueFatigue(stamina.cost(joules)).build());
    }

    public static DamageAttachment hit(BodyMessages.BodySnapshot before, GuardState state,
                                       double preventedDamage, GuardTool tool) {
        // 同一个命中版本先付维持费，再冻结附带费用；本击受伤后的掉耐力不重复计价。
        var body = BodyProtobuf.fromMessage(before);
        var progress = TechniqueState.read(body);
        var active = progress.getActiveCast();
        var guard = active.getSustained();
        if (!before.getId().equals(state.bodyId()) || !active.getRequest().getCastId().equals(state.castId())
                || !guard.getGuard().equals(state.guard()) || guard.hasEndRequestedMillis()
                || !guard.hasStaminaPaidMillis() || guard.getStaminaPaidMillis() != body.settledAt().elapsedMillis()) {
            throw new IllegalStateException("命中时的守式身份或结算时刻与身体不符");
        }
        var stamina = HumanStamina.from(body);
        double due = preventedDamage * guard.getGuard().getStamina().getJoulesPerDamagePrevented();
        var attachment = DamageAttachment.newBuilder().addAllTissueFatigue(stamina.cost(due));
        if (due >= stamina.availableJoules()) {
            var contact = tool == GuardTool.WEAPON ? TechniqueGuardStamina.Contact.WEAPON
                    : TechniqueGuardStamina.Contact.FOREARM;
            var exhausted = TechniqueGuardStamina.exhaust(body, progress, contact);
            attachment.addAllStopActions(exhausted.getStopActionsList());
            attachment.putAllProgress(exhausted.getProgressMap());
        }
        return attachment.build();
    }
}
