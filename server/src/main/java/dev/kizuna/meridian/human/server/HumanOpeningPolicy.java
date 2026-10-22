package dev.kizuna.meridian.human.server;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.OpeningPolicy;
import dev.kizuna.meridian.core.QiConsumption;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.HumanOpeningConditions;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.IntakeRequirement;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;

import java.util.HashSet;
import java.util.List;

/** 人体开脉条件的唯一裁定者；输入输出都是 Protobuf，不持有可写身体或存档。 */
public final class HumanOpeningPolicy implements OpeningPolicy {
    private static final double TOLERANCE = 1e-9;

    @Override
    public String id() {
        // 精确绑定人体策略，其他生物不会得到人体规则兜底。
        return HumanOpening.POLICY_ID;
    }

    @Override
    public byte[] evaluate(byte[] payload) {
        // 先校验内容配置，再按启动编排和区间检查两个阶段裁定。
        OpeningPolicyRequest request = OpeningProtobuf.decodePolicyRequest(payload);
        HumanOpeningConditions conditions = conditions(request.getRule());
        OpeningPolicyDecision decision;
        if (request.getPhase() == OpeningPolicyPhase.PREPARE_OPENING) {
            decision = prepare(request.getBody(), request.getRule(), conditions);
        } else {
            decision = check(request.getBody(), request.getRule(), conditions, request.getIntervalMillis());
        }
        return decision.toByteArray();
    }

    public static HumanOpeningConditions conditions(MeridianOpeningRule rule) {
        // Any 只承载本包生成的消息；错误类型、未知字段和无法计算的参数均停止执行。
        HumanOpeningConditions conditions;
        try {
            conditions = rule.getPolicyConfig().unpack(HumanOpeningConditions.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
        }
        if (!conditions.getUnknownFields().asMap().isEmpty()
                || conditions.getMinimumRealm() == Realm.REALM_UNSPECIFIED
                || conditions.getMinimumRealm() == Realm.UNRECOGNIZED
                || conditions.getBaseDurationMillis() <= 0
                || conditions.getAdditionalMillisPerOpened() < 0
                || !fraction(conditions.getBodyStartFraction())
                || !fraction(conditions.getTargetStartFraction())
                || !fraction(conditions.getBodyHoldFraction())
                || !fraction(conditions.getTargetHoldFraction())
                || conditions.getBodyHoldFraction() > conditions.getBodyStartFraction()
                || conditions.getTargetHoldFraction() > conditions.getTargetStartFraction()) {
            throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
        }

        var prerequisites = new HashSet<String>();
        for (String prerequisite : conditions.getRequiredOpenMeridianIdsList()) {
            if (prerequisite.isBlank() || !prerequisites.add(prerequisite)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
            }
        }
        for (IntakeRequirement intake : conditions.getIntakesList()) {
            if (!intake.getUnknownFields().asMap().isEmpty() || intake.getOrganIdsCount() == 0
                    || intake.getOrganIdsList().stream().anyMatch(String::isBlank)
                    || new HashSet<>(intake.getOrganIdsList()).size() != intake.getOrganIdsCount()
                    || intake.getWindowMillis() <= 0 || !positive(intake.getMinimumQiPerSecond())
                    || !positive(intake.getMinimumQiPerSecond() * (intake.getWindowMillis() / 1000.0))) {
                throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
            }
        }
        return conditions;
    }

    private static boolean fraction(double value) {
        // 占比是稳定体积的无量纲比例，边界由策略明确声明而不是由元数猜测。
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static boolean positive(double value) {
        // 配置中的速率和累计要求必须在双精度范围内有实际意义。
        return Double.isFinite(value) && value > 0;
    }

    static OpeningPolicyDecision prepare(BodySnapshot body, MeridianOpeningRule rule,
                                         HumanOpeningConditions conditions) {
        // 境界、经脉强度和前置开拓由人体内容控制，框架只传入当前完整快照。
        if (body.getRealmValue() < conditions.getMinimumRealmValue()) {
            return rejected(FailureCode.OPENING_REQUIREMENTS_UNMET);
        }
        MeridianDefinition target = findMeridian(body, rule.getTargetMeridianId());
        var openedIds = body.getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED)
                .map(MeridianState::getId).toList();
        if (target.getResilience() < conditions.getMinimumResilience()
                || !openedIds.containsAll(conditions.getRequiredOpenMeridianIdsList())) {
            return rejected(FailureCode.OPENING_REQUIREMENTS_UNMET);
        }

        // 启动同时看全身和目标经脉的实际体积；不能靠元数显示或临时扩容绕过门槛。
        double bodyFraction = lumenFraction(body, null);
        double targetFraction = lumenFraction(body, target.getId());
        if (bodyFraction + TOLERANCE < conditions.getBodyStartFraction()
                || targetFraction + TOLERANCE < conditions.getTargetStartFraction()) {
            return rejected(FailureCode.INSUFFICIENT_QI);
        }
        return plan(body, rule, conditions);
    }

    static OpeningPolicyDecision plan(BodySnapshot body, MeridianOpeningRule rule,
                                      HumanOpeningConditions conditions) {
        // 参与组只包含目标脉；其他经脉仍可作为来源，但不再存在 Q_N 组合门槛。
        MeridianDefinition target = findMeridian(body, rule.getTargetMeridianId());
        int openedCount = (int) body.getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED)
                .count();
        long requiredMillis = Math.addExact(conditions.getBaseDurationMillis(),
                Math.multiplyExact(conditions.getAdditionalMillisPerOpened(), openedCount));
        var decision = OpeningPolicyDecision.newBuilder().setProtocolVersion(1)
                .setTargetStableVolume(target.getLumenVolume() + rule.getLumenVolumeGain())
                .setRequiredMillis(requiredMillis)
                .addParticipants(OpeningGuidanceTarget.newBuilder()
                        .setMeridianId(target.getId())
                        .setVolume(target.getLumenVolume() * conditions.getTargetStartFraction()));
        List<String> observedOrgans = conditions.getIntakesList().stream()
                .flatMap(intake -> intake.getOrganIdsList().stream()).distinct().toList();
        long window = conditions.getIntakesList().stream()
                .mapToLong(IntakeRequirement::getWindowMillis).max().orElse(0);
        return decision.addAllObservedOrganIds(observedOrgans).setReceiptWindowMillis(window).build();
    }

    private static OpeningPolicyDecision check(BodySnapshot body, MeridianOpeningRule rule,
                                               HumanOpeningConditions conditions, long intervalMillis) {
        // 任务运行中仍检查层级；掉境后不能继续维持高层经脉的拓宽。
        if (body.getRealmValue() < conditions.getMinimumRealmValue()) {
            return rejected(FailureCode.OPENING_REQUIREMENTS_UNMET);
        }
        OpeningTask task = body.getOpeningTask();
        MeridianDefinition targetDefinition = findMeridian(body, rule.getTargetMeridianId());
        MeridianState targetState = body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(rule.getTargetMeridianId())).findFirst().orElseThrow();

        // 准备阶段必须达到启动门槛，维持阶段才使用较低的 60% 门槛。
        boolean holding = task.getStatus() == OpeningTaskStatus.OPENING_HOLDING;
        double bodyThreshold = holding ? conditions.getBodyHoldFraction() : conditions.getBodyStartFraction();
        double targetThreshold = holding ? conditions.getTargetHoldFraction() : conditions.getTargetStartFraction();
        double bodyVolume = lumenVolume(body);
        double targetVolume = lumenVolume(targetState.getLumenQi());

        // 策略投影扣除将由 Framework 事务实际完成的同一笔费用，确保边界计算与扣账一致。
        if (holding && intervalMillis > 0 && rule.getHoldQiPerSecond() > 0) {
            List<Double> chargedVolumes = projectedChargeVolumes(body, rule.getHoldQiPerSecond()
                    * intervalMillis / 1000.0);
            int targetIndex = targetIndex(body, targetState.getId());
            double chargedTargetVolume = chargedVolumes.get(targetIndex);
            double chargedBodyVolume = chargedVolumes.stream().mapToDouble(Double::doubleValue).sum();
            bodyVolume -= chargedBodyVolume;
            targetVolume -= chargedTargetVolume;
        }
        boolean ready = bodyVolume + TOLERANCE >= bodyCapacity(body) * bodyThreshold
                && targetVolume + TOLERANCE >= targetDefinition.getLumenVolume() * targetThreshold;
        ready &= intakeRequirementsSatisfied(body, conditions, task);

        var decision = OpeningPolicyDecision.newBuilder().setProtocolVersion(1).setReady(ready);
        long nextCheckMillis = nextThresholdBoundary(body, rule, conditions, holding);
        long intakeBoundary = nextIntakeBoundary(body, conditions, task);
        if (intakeBoundary > 0) {
            nextCheckMillis = Math.min(nextCheckMillis, intakeBoundary);
        }
        if (nextCheckMillis < Long.MAX_VALUE) {
            decision.setNextCheckDelayMillis(Math.max(1, nextCheckMillis));
        }
        return decision.build();
    }

    private static boolean intakeRequirementsSatisfied(BodySnapshot body, HumanOpeningConditions conditions,
                                                        OpeningTask task) {
        // 持续摄入收据只证明真实肺部入账，不把调试注入或经脉转移算作摄入。
        long now = body.getSettledAt().getElapsedMillis();
        for (IntakeRequirement intake : conditions.getIntakesList()) {
            double received = task.getIntakeSamplesList().stream()
                    .filter(sample -> intake.getOrganIdsList().contains(sample.getOrganId()))
                    .filter(sample -> now - sample.getAtMillis() < intake.getWindowMillis())
                    .mapToDouble(OpeningIntakeSample::getQi).sum();
            double required = intake.getMinimumQiPerSecond() * intake.getWindowMillis() / 1000.0;
            if (received + TOLERANCE < required) {
                return false;
            }
        }
        return true;
    }

    private static long nextThresholdBoundary(BodySnapshot body, MeridianOpeningRule rule,
                                              HumanOpeningConditions conditions, boolean holding) {
        // 只有维持状态需要预约 60% 边界；准备阶段由引导到货事件唤醒。
        if (!holding || rule.getHoldQiPerSecond() <= 0) {
            return Long.MAX_VALUE;
        }
        if (lumenFraction(body, null) <= conditions.getBodyHoldFraction()
                || lumenFraction(body, rule.getTargetMeridianId()) <= conditions.getTargetHoldFraction()) {
            return 0;
        }
        long low = 0;
        long high = 86_400_000;
        while (low + 1 < high) {
            long middle = low + (high - low) / 2;
            List<Double> chargedVolumes = projectedChargeVolumes(body, rule.getHoldQiPerSecond() * middle / 1000.0);
            double bodyAfter = lumenVolume(body) - chargedVolumes.stream().mapToDouble(Double::doubleValue).sum();
            int targetIndex = targetIndex(body, rule.getTargetMeridianId());
            double targetAfter = lumenVolume(body, targetIndex) - chargedVolumes.get(targetIndex);
            MeridianDefinition target = findMeridian(body, rule.getTargetMeridianId());
            boolean remains = bodyAfter >= bodyCapacity(body) * conditions.getBodyHoldFraction()
                    && targetAfter >= target.getLumenVolume() * conditions.getTargetHoldFraction();
            if (remains) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return high;
    }

    private static long nextIntakeBoundary(BodySnapshot body, HumanOpeningConditions conditions, OpeningTask task) {
        // 滑动窗口中的首笔收据过期时重检，避免持续摄入条件在无其他事件时被漏算。
        long now = body.getSettledAt().getElapsedMillis();
        long boundary = Long.MAX_VALUE;
        for (IntakeRequirement intake : conditions.getIntakesList()) {
            for (OpeningIntakeSample sample : task.getIntakeSamplesList()) {
                if (intake.getOrganIdsList().contains(sample.getOrganId())) {
                    boundary = Math.min(boundary, Math.max(1, intake.getWindowMillis() - (now - sample.getAtMillis())));
                }
            }
        }
        return boundary;
    }

    private static List<Double> projectedChargeVolumes(BodySnapshot body, double amount) {
        // 使用 Framework 的统一体积分摊，保证策略投影与实际扣账采用同一混相规则。
        List<dev.kizuna.meridian.core.QiInventory> inventories = body.getMeridianStatesList().stream()
                .map(state -> BodyProtobuf.fromInventory(state.getLumenQi())).toList();
        return QiConsumption.byVolume(inventories, amount).stream()
                .map(dev.kizuna.meridian.core.QiInventory::volume).toList();
    }

    private static double lumenFraction(BodySnapshot body, String meridianId) {
        // 全身与局部门槛都使用占用体积除以稳定容量，空构型不能凭除零通过条件。
        double occupied = meridianId == null ? lumenVolume(body) : lumenVolume(body, meridianId);
        double capacity = meridianId == null ? bodyCapacity(body) : findMeridian(body, meridianId).getLumenVolume();
        return capacity == 0 ? 0 : occupied / capacity;
    }

    private static double lumenVolume(BodySnapshot body) {
        // 只有内腔参与门槛，外壁、器官和在途库存不混入全身体积占用。
        return body.getMeridianStatesList().stream().mapToDouble(state -> lumenVolume(state.getLumenQi())).sum();
    }

    private static double lumenVolume(MeridianState state) {
        // 局部占用读取实际内腔明细，保持与全身汇总相同的计算口径。
        return lumenVolume(state.getLumenQi());
    }

    private static double lumenVolume(dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory inventory) {
        // 混相逐条按实际密度换算，不能用一种基准密度替代整份库存。
        return inventory.getEntriesList().stream().mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
    }

    private static double lumenVolume(BodySnapshot body, int index) {
        // 投影费用按状态列表顺序返回，索引与该列表的内腔保持一一对应。
        return lumenVolume(body.getMeridianStates(index));
    }

    private static double lumenVolume(BodySnapshot body, String meridianId) {
        // 先定位目标，再与投影费用共用同一索引，避免按构型顺序误配账户。
        int index = targetIndex(body, meridianId);
        return lumenVolume(body, index);
    }

    private static double bodyCapacity(BodySnapshot body) {
        // 读取已完成成长后的实际构型；内腔拓宽不连带计入外壁容量。
        return body.getStructure().getMeridiansList().stream().mapToDouble(MeridianDefinition::getLumenVolume).sum();
    }

    private static int targetIndex(BodySnapshot body, String meridianId) {
        // 缺少目标是策略输入不一致，应明确拒绝而非借用另一条经脉的数据。
        for (int index = 0; index < body.getMeridianStatesCount(); index++) {
            if (body.getMeridianStates(index).getId().equals(meridianId)) {
                return index;
            }
        }
        throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
    }

    private static MeridianDefinition findMeridian(BodySnapshot body, String meridianId) {
        // 所有局部门槛都绑定实际构型，不以出生模板覆盖已经成长的稳定体积。
        return body.getStructure().getMeridiansList().stream()
                .filter(meridian -> meridian.getId().equals(meridianId)).findFirst()
                .orElseThrow(() -> HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY));
    }

    private static OpeningPolicyDecision rejected(FailureCode code) {
        // 业务失败保持公开协议失败码，调用者不依靠解析中文字符串判断。
        return OpeningPolicyDecision.newBuilder().setProtocolVersion(1).setRejection(code).build();
    }

}
