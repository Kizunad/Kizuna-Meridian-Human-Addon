package dev.kizuna.meridian.human.server;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.OpeningPolicy;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.*;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        var ids = new HashSet<String>();
        double totalQi = 0;
        double maximumThresholdQi = 0;
        for (PressureThreshold limit : conditions.getThresholdsList()) {
            if (limit.getMeridianId().isBlank() || !ids.add(limit.getMeridianId())
                    || !positive(limit.getQi()) || !limit.getUnknownFields().asMap().isEmpty()) {
                throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
            }
            totalQi += limit.getQi();
            maximumThresholdQi = Math.max(maximumThresholdQi, limit.getQi());
        }
        long maximumDuration = conditions.getBaseDurationMillis();
        try {
            maximumDuration = Math.addExact(maximumDuration, Math.multiplyExact(
                    conditions.getAdditionalMillisPerOpened(), Math.max(0, ids.size() - 1)));
        } catch (ArithmeticException overflow) {
            throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
        }
        if (conditions.getMinimumRealm() == Realm.REALM_UNSPECIFIED || conditions.getMinimumRealm() == Realm.UNRECOGNIZED
                || !conditions.getUnknownFields().asMap().isEmpty() || !ids.contains(rule.getTargetMeridianId())
                || !positive(totalQi) || conditions.getBaseDurationMillis() <= 0
                || conditions.getAdditionalMillisPerOpened() < 0 || maximumDuration <= 0
                || !Double.isFinite(rule.getHoldQiPerSecond() * (maximumDuration / 1000.0))
                || !Double.isFinite(conditions.getMinimumResilience()) || conditions.getMinimumResilience() < 0
                || !Double.isFinite(conditions.getMinimumBodyLumenFraction())
                || conditions.getMinimumBodyLumenFraction() < 0 || conditions.getMinimumBodyLumenFraction() > 1) {
            throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
        }
        if (conditions.hasGroupPressureGrowth()) {
            // 递增参数随规则固定；注册时验证最大全组仍可计算，避免启动后才发现溢出。
            GroupPressureGrowth growth = conditions.getGroupPressureGrowth();
            if (!growth.getUnknownFields().asMap().isEmpty() || !positive(growth.getBaseIncrementQi())
                    || !Double.isFinite(growth.getStepIncrementQi()) || growth.getStepIncrementQi() < 0) {
                throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
            }
            groupPressureQi(maximumThresholdQi, ids.size() - 1, growth);
        }
        var prerequisites = new HashSet<String>();
        for (String prerequisite : conditions.getRequiredOpenMeridianIdsList()) {
            if (prerequisite.isBlank() || prerequisite.equals(rule.getTargetMeridianId()) || !prerequisites.add(prerequisite)) {
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

    private static boolean positive(double value) {
        // 配置中的容量和费用必须在双精度范围内有实际意义。
        return Double.isFinite(value) && value > 0;
    }

    private static double groupPressureQi(double targetQi, int openedCount, GroupPressureGrowth growth) {
        // 第二条的递增序号为 1；累加 1 到已开条数，使本次要求不依赖开脉名称或选择顺序。
        double baseGrowthQi = growth.getBaseIncrementQi() * openedCount;
        double accumulatedSteps = openedCount * (openedCount + 1.0) / 2;
        double stepGrowthQi = growth.getStepIncrementQi() * accumulatedSteps;
        double requiredQi = targetQi + baseGrowthQi + stepGrowthQi;
        if (!positive(requiredQi)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
        }
        return requiredQi;
    }

    static OpeningPolicyDecision prepare(BodySnapshot body, MeridianOpeningRule rule,
                                                 HumanOpeningConditions conditions) {
        // 境界由权威快照提供，直接提交请求也不能绕过尚未解锁的经脉。
        if (body.getRealmValue() < conditions.getMinimumRealmValue()) {
            return rejected(FailureCode.OPENING_REQUIREMENTS_UNMET);
        }

        // 全身准备比例只在启动时检查；运行期真元正在汇聚，不能再按该比例中断。
        MeridianDefinition target = body.getStructure().getMeridiansList().stream()
                .filter(meridian -> meridian.getId().equals(rule.getTargetMeridianId())).findFirst().orElseThrow();
        var openedIds = body.getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED)
                .map(MeridianState::getId).toList();
        if (target.getResilience() < conditions.getMinimumResilience()
                || !openedIds.containsAll(conditions.getRequiredOpenMeridianIdsList())) {
            return rejected(FailureCode.OPENING_REQUIREMENTS_UNMET);
        }
        double storedQi = body.getMeridianStatesList().stream().mapToDouble(state -> total(state.getLumenQi())).sum();
        double stableCapacity = body.getStructure().getMeridiansList().stream().mapToDouble(MeridianDefinition::getLumenCapacity).sum();
        if (storedQi + TOLERANCE < stableCapacity * conditions.getMinimumBodyLumenFraction()) {
            return rejected(FailureCode.INSUFFICIENT_QI);
        }

        return plan(body, rule, conditions);
    }

    static OpeningPolicyDecision plan(BodySnapshot body, MeridianOpeningRule rule, HumanOpeningConditions conditions) {
        // 共享实际执行与进度展示的编排，不让界面重新发明参与组与持续时间。
        MeridianDefinition target = body.getStructure().getMeridiansList().stream()
                .filter(meridian -> meridian.getId().equals(rule.getTargetMeridianId())).findFirst().orElseThrow();
        List<String> openedIds = body.getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED)
                .map(MeridianState::getId).toList();
        var decision = OpeningPolicyDecision.newBuilder().setProtocolVersion(1)
                .setTargetStableCapacity(target.getLumenCapacity() + rule.getLumenCapacityGain());
        List<PressureThreshold> participants = conditions.getThresholdsList().stream()
                .filter(limit -> limit.getMeridianId().equals(target.getId()) || openedIds.contains(limit.getMeridianId()))
                .toList();
        int openedCount = participants.size() - 1;
        long duration = Math.addExact(conditions.getBaseDurationMillis(),
                Math.multiplyExact(conditions.getAdditionalMillisPerOpened(), openedCount));

        // 目标保持自身凝聚要求，其余储量由已开脉均分，让自动引导的实际目标与组合门槛一致。
        double openedMeridianQi = 0;
        if (conditions.hasGroupPressureGrowth() && openedCount > 0) {
            double targetQi = participants.stream().filter(limit -> limit.getMeridianId().equals(target.getId()))
                    .findFirst().orElseThrow().getQi();
            double groupQi = groupPressureQi(targetQi, openedCount, conditions.getGroupPressureGrowth());
            openedMeridianQi = (groupQi - targetQi) / openedCount;
        }
        for (PressureThreshold limit : participants) {
            double requiredQi = limit.getQi();
            if (conditions.hasGroupPressureGrowth() && !limit.getMeridianId().equals(target.getId())) {
                requiredQi = openedMeridianQi;
            }
            decision.addParticipants(OpeningPressureLimit.newBuilder().setMeridianId(limit.getMeridianId()).setQi(requiredQi));
        }
        List<String> observedOrgans = conditions.getIntakesList().stream()
                .flatMap(intake -> intake.getOrganIdsList().stream()).distinct().toList();
        long window = conditions.getIntakesList().stream().mapToLong(IntakeRequirement::getWindowMillis).max().orElse(0);
        return decision.setRequiredMillis(duration).addAllObservedOrganIds(observedOrgans).setReceiptWindowMillis(window).build();
    }

    private static OpeningPolicyDecision check(BodySnapshot body, MeridianOpeningRule rule,
                                               HumanOpeningConditions conditions, long intervalMillis) {
        // 任务运行中也检查层级；境界下降后不能继续维持高层经脉的拓宽。
        if (body.getRealmValue() < conditions.getMinimumRealmValue()) {
            return OpeningPolicyDecision.newBuilder().setProtocolVersion(1).setReady(false).build();
        }

        // 先用旧余额投影这一段维持消耗；区间末尾到货不能补回此前中断的适应时间。
        OpeningTask task = body.getOpeningTask();
        MeridianState target = body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(rule.getTargetMeridianId())).findFirst().orElseThrow();
        boolean holding = task.getStatus() == OpeningTaskStatus.OPENING_HOLDING;
        double consumptionPerSecond = rule.getHoldQiPerSecond();
        double consumedQi = 0;
        if (holding) {
            consumedQi = consumptionPerSecond * (intervalMillis / 1000.0);
        }
        double targetMinimumQi = total(rule.getMinimumLumenQi());
        Set<String> participantIds = task.getParticipantsList().stream()
                .map(OpeningPressureLimit::getMeridianId).collect(Collectors.toSet());
        double groupQi = body.getMeridianStatesList().stream()
                .filter(state -> participantIds.contains(state.getId()))
                .mapToDouble(state -> total(state.getLumenQi())).sum();
        double groupMinimumQi = task.getParticipantsList().stream().mapToDouble(OpeningPressureLimit::getQi).sum();
        boolean ready = meets(groupQi - consumedQi, groupMinimumQi, conditions.getStrictLumenThreshold());

        // 各属性按最低配比承担耗散；预计跌破任一门槛前必须再次结算。
        double nextCheckDelayMillis = Double.POSITIVE_INFINITY;
        for (Qi minimum : rule.getMinimumLumenQi().getEntriesList()) {
            double storedQi = matching(target.getLumenQi(), minimum);
            double consumptionShare = minimum.getAmount() / targetMinimumQi;
            double remainingQi = storedQi - consumedQi * consumptionShare;
            ready &= meets(remainingQi, minimum.getAmount(), conditions.getStrictLumenThreshold());
            if (holding && consumptionPerSecond > 0) {
                double untilThresholdMillis = (storedQi - minimum.getAmount())
                        / (consumptionPerSecond * consumptionShare) * 1000;
                nextCheckDelayMillis = Math.min(nextCheckDelayMillis, untilThresholdMillis);
            }
        }
        if (holding && consumptionPerSecond > 0) {
            double untilGroupThresholdMillis = (groupQi - groupMinimumQi) / consumptionPerSecond * 1000;
            nextCheckDelayMillis = Math.min(nextCheckDelayMillis, untilGroupThresholdMillis);
            double qiBeforeRefill = total(target.getLumenQi()) - targetMinimumQi - rule.getGuidanceBufferQi() / 2;
            if (rule.getGuidanceBufferQi() > 0 && qiBeforeRefill > TOLERANCE) {
                double untilRefillMillis = qiBeforeRefill / consumptionPerSecond * 1000;
                nextCheckDelayMillis = Math.min(nextCheckDelayMillis, untilRefillMillis);
            }
        }

        // 摄入收据按起点判断：到过期边界前的时间有效，边界后的 refresh 才重置。
        long now = body.getSettledAt().getElapsedMillis();
        for (IntakeRequirement intake : conditions.getIntakesList()) {
            double receivedQi = 0;
            for (OpeningIntakeSample sample : task.getIntakeSamplesList()) {
                long age = now - sample.getAtMillis();
                if (intake.getOrganIdsList().contains(sample.getOrganId()) && age < intake.getWindowMillis()) {
                    receivedQi += sample.getQi();
                    nextCheckDelayMillis = Math.min(nextCheckDelayMillis, intake.getWindowMillis() - age);
                }
            }
            double requiredQi = intake.getMinimumQiPerSecond() * (intake.getWindowMillis() / 1000.0);
            ready &= receivedQi + TOLERANCE >= requiredQi;
        }
        var decision = OpeningPolicyDecision.newBuilder().setProtocolVersion(1).setReady(ready);
        if (Double.isFinite(nextCheckDelayMillis) && nextCheckDelayMillis < Long.MAX_VALUE) {
            // 浮点余额可能将整毫秒算得略大，扣除纳秒级舍入误差，保持分段推进结果一致。
            long roundedDelayMillis = (long) Math.ceil(nextCheckDelayMillis - 1e-6);
            decision.setNextCheckDelayMillis(Math.max(1, roundedDelayMillis));
        }
        return decision.build();
    }

    private static OpeningPolicyDecision rejected(FailureCode code) {
        // 业务失败保持公开协议失败码，调用者不依靠解析中文字符串判断。
        return OpeningPolicyDecision.newBuilder().setProtocolVersion(1).setRejection(code).build();
    }

    static boolean meets(double amount, double required, boolean strict) {
        // 临界点误差不能让严格大于的要求退化成大于等于。
        return strict ? amount > required + TOLERANCE : amount + TOLERANCE >= required;
    }

    static double total(QiInventory inventory) {
        // 仅聚合现有明细，不能把在途预约或外壁储备记成内腔余额。
        return inventory.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
    }

    static double matching(QiInventory inventory, Qi minimum) {
        // 同属性不同相态分别对账。
        return inventory.getEntriesList().stream()
                .filter(qi -> qi.getPhase() == minimum.getPhase() && qi.getAttribute().equals(minimum.getAttribute()))
                .mapToDouble(Qi::getAmount).sum();
    }
}
