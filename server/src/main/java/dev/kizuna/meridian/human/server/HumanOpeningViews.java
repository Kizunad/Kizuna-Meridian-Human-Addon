package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.HumanOpeningConditions;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.IntakeRequirement;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.OperationUiMessages.*;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.OpeningPolicyDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static dev.kizuna.meridian.protocol.pb.OperationUiMessages.OperationMeterState.*;

/** 人体条件的只读进度投影；UI 只画卡片，不解释人体配置。 */
public final class HumanOpeningViews {
    private HumanOpeningViews() {
        // 不保存窗口状态或额外真元账本。
    }

    public static MeridianOperationUpdate update(BodySnapshot body, List<MeridianOpeningRule> rules) {
        // meridian 命名空间对应通用开脉请求适配器；实际条件与文字仍由本内容包提供。
        var update = MeridianOperationUpdate.newBuilder().setProtocolVersion(1).setNamespace("meridian")
                .setBodyId(body.getId()).setBodyRevision(body.getRevision());
        for (MeridianOpeningRule rule : rules) {
            if (body.getMeridianStatesList().stream().anyMatch(state -> state.getId().equals(rule.getTargetMeridianId()))) {
                update.addOperations(operation(body, rule));
            }
        }
        return update.build();
    }

    private static MeridianOperationView operation(BodySnapshot body, MeridianOpeningRule option) {
        // 正在执行或暂停的同一规则使用存档参数，内容更新不能偷偷改变一次尝试。
        String targetId = option.getTargetMeridianId();
        boolean active = body.hasOpeningTask() && (body.getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_PREPARING
                || body.getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_HOLDING);
        boolean sameTask = body.hasOpeningTask() && body.getOpeningTask().getRule().getTargetMeridianId().equals(targetId)
                && (active || body.getOpeningTask().getRule().equals(option));
        MeridianOpeningRule rule = sameTask ? body.getOpeningTask().getRule() : option;
        HumanOpeningConditions conditions = HumanOpeningPolicy.conditions(rule);
        OpeningPolicyDecision plan = HumanOpeningPolicy.plan(body, rule, conditions);
        boolean running = sameTask && active;
        boolean adapting = running && body.getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_HOLDING;
        MeridianState selected = body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(targetId)).findFirst().orElseThrow();
        boolean opened = selected.getOpening().getStage() == MeridianOpeningStage.OPENED;
        var result = MeridianOperationView.newBuilder().setId("meridian:opening/" + targetId).setVersion(rule.getVersion())
                .setMeridianId(targetId).setTitle("凝聚真元").setActive(running)
                .setActionId(running ? "pause" : "start").setActionLabel(running ? "暂停凝聚" : "凝聚真元");

        // 只把归一化条形交给 UI，门槛判断复用权威裁定的误差规则。
        double capacity = body.getStructure().getMeridiansList().stream().mapToDouble(MeridianDefinition::getLumenCapacity).sum();
        double storedQi = body.getMeridianStatesList().stream().mapToDouble(state -> HumanOpeningPolicy.total(state.getLumenQi())).sum();
        double preparation = ratio(storedQi, capacity * conditions.getMinimumBodyLumenFraction());
        if (!running && !opened) {
            result.addMeters(meter("preparation", "全身准备", preparation,
                    preparation >= 1 ? METER_READY : METER_WAITING, "先通过吐纳补足全身内腔储量"));
        }
        MeridianOperationMeter targetProgress = targetMeter(body, rule, conditions);
        result.addMeters(targetProgress);
        boolean targetReady = targetProgress.getState() == METER_READY;

        // 当前尝试使用已冻结的参与组与计时，内容更新只能影响下一次启动。
        String thresholdRelation = conditions.getStrictLumenThreshold() ? "大于" : "至少";
        List<OpeningPressureLimit> participants = sameTask ? body.getOpeningTask().getParticipantsList() : plan.getParticipantsList();
        long requiredMillis = sameTask ? body.getOpeningTask().getRequiredMillis() : plan.getRequiredMillis();
        long heldMillis = sameTask ? body.getOpeningTask().getHeldMillis() : 0;
        List<String> ids = participants.stream().map(OpeningPressureLimit::getMeridianId).toList();
        double groupQi = body.getMeridianStatesList().stream().filter(state -> ids.contains(state.getId()))
                .mapToDouble(state -> HumanOpeningPolicy.total(state.getLumenQi())).sum();
        double groupThreshold = participants.stream().mapToDouble(OpeningPressureLimit::getQi).sum();
        boolean groupReady = HumanOpeningPolicy.meets(groupQi, groupThreshold, conditions.getStrictLumenThreshold());
        if (participants.size() > 1) {
            result.addMeters(meter("group", "参与脉供给", ratio(groupQi, groupThreshold),
                    groupReady ? METER_READY : METER_WAITING,
                    String.format(Locale.ROOT, "参与脉实际合计 %.2f 元；需%s %.2f 元", groupQi, thresholdRelation, groupThreshold)));
        }
        MeridianOperationMeter intakeProgress = intakeMeter(body, conditions, sameTask);
        if (conditions.getIntakesCount() > 0) {
            result.addMeters(intakeProgress);
        }
        for (OpeningEffort effort : rule.getEffortsList()) {
            result.addMeters(effort(body, effort));
        }
        // 任务活动包含等待汇聚，只有权威 HOLDING 才表示正在累计连续适应。
        OperationMeterState holdState = METER_WAITING;
        String holdReason = "供给与凝聚同时满足后开始；中断重新计时";
        if (opened) {
            holdState = METER_READY;
            holdReason = "已完成拓宽";
        } else if (adapting) {
            holdState = METER_RUNNING;
            holdReason = "正在连续适应；保持供给与储量";
        } else if (running) {
            if (!targetReady) {
                holdReason = "目标储量不足，尚未开始计时";
            } else if (!groupReady) {
                holdReason = "参与经脉合计储量不足，等待补充";
            } else if (intakeProgress.getFraction() < 1) {
                holdReason = "等待新的灵气摄入，连续计时已重置";
            }
        }
        result.addMeters(meter("hold", "连续适应", ratio(heldMillis, requiredMillis), holdState,
                String.format(Locale.ROOT, "连续适应 %.1f / %.1f 秒；%s", heldMillis / 1000.0, requiredMillis / 1000.0, holdReason)));

        // 按人体启动裁定禁用按钮；点击后核心仍会在事务内重新校验，不信任卡片。
        FailureCode rejection = HumanOpeningPolicy.prepare(body, rule, conditions).getRejection();
        boolean canStart = !active && !opened && rejection == FailureCode.FAILURE_CODE_UNSPECIFIED;
        boolean enabled = !body.getVitality().getDead() && (running || canStart);
        String hint = "保持吐纳并等待真元沿通路汇聚";
        String status = opened ? "已拓宽" : "准备操作";
        if (sameTask) {
            status = switch (body.getOpeningTask().getStatus()) {
                case OPENING_PREPARING -> {
                    if (!targetReady || !groupReady) {
                        yield "汇聚中 · 尚未计时";
                    }
                    yield "等待持续摄入 · 计时已重置";
                }
                case OPENING_HOLDING -> "正在适应";
                case OPENING_COMPLETED -> "拓宽完成";
                case OPENING_PAUSED -> "已暂停";
                case OPENING_EXHAUSTED -> "余力耗尽 · 请休息";
                case OPENING_DAMAGED -> "通路或器官受损";
                default -> "准备操作";
            };
        }
        if (active && !sameTask) {
            hint = "先暂停另一条经脉的操作";
            status = "其他经脉正在操作";
        } else if (!running && !opened && rejection != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            if (rejection == FailureCode.INSUFFICIENT_QI) {
                hint = "全身准备尚不足，请先吐纳";
                status = "储量不足 · 先吐纳";
            } else {
                hint = "经脉强度或前置开拓尚未满足";
                status = "开拓条件不足";
            }
        }
        return result.setEnabled(enabled).setStatus(status).setHint(hint).build();
    }

    private static MeridianOperationMeter targetMeter(BodySnapshot body, MeridianOpeningRule rule,
                                                       HumanOpeningConditions conditions) {
        // 每种相态与属性分别匹配；总量足够不能掩盖某一种所需真元仍不足。
        String targetId = rule.getTargetMeridianId();
        MeridianState target = body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(targetId)).findFirst().orElseThrow();
        double fraction = 1;
        double receivedQi = 0;
        boolean ready = true;
        for (Qi required : rule.getMinimumLumenQi().getEntriesList()) {
            double matchingQi = HumanOpeningPolicy.matching(target.getLumenQi(), required);
            receivedQi += matchingQi;
            fraction = Math.min(fraction, ratio(matchingQi, required.getAmount()));
            ready &= HumanOpeningPolicy.meets(matchingQi, required.getAmount(), conditions.getStrictLumenThreshold());
        }

        // 在途预约单独绘制，悬浮说明只把实际到账量称为目标储量。
        double requiredQi = HumanOpeningPolicy.total(rule.getMinimumLumenQi());
        String relation = conditions.getStrictLumenThreshold() ? "大于" : "至少";
        String guidance = "继续吐纳并汇聚，满足后才开始计时";
        if (ready) {
            guidance = "维持期间仍会消耗真元，需继续补充";
        }
        String hint = String.format(Locale.ROOT, "目标实际到账 %.2f 元；需%s %.2f 元。%s",
                receivedQi, relation, requiredQi, guidance);
        return meter("target", "目标储量", fraction, ready ? METER_READY : METER_WAITING, hint).toBuilder()
                .setIncomingFraction(ratio(incoming(body, targetId), requiredQi)).build();
    }

    private static MeridianOperationMeter intakeMeter(BodySnapshot body, HumanOpeningConditions conditions,
                                                       boolean sameTask) {
        // 摄入要求按各自时间窗取最差完成比例，旧尝试的收据不能支持另一个目标。
        double fraction = 1;
        var hints = new ArrayList<String>();
        for (IntakeRequirement intake : conditions.getIntakesList()) {
            long sinceMillis = body.getSettledAt().getElapsedMillis() - intake.getWindowMillis();
            double receivedQi = 0;
            if (sameTask) {
                receivedQi = body.getOpeningTask().getIntakeSamplesList().stream()
                        .filter(sample -> intake.getOrganIdsList().contains(sample.getOrganId()))
                        .filter(sample -> sample.getAtMillis() > sinceMillis)
                        .mapToDouble(OpeningIntakeSample::getQi).sum();
            }
            double windowSeconds = intake.getWindowMillis() / 1000.0;
            double requiredQi = intake.getMinimumQiPerSecond() * windowSeconds;
            fraction = Math.min(fraction, ratio(receivedQi, requiredQi));
            hints.add(String.format(Locale.ROOT, "最近 %.1f 秒摄入 %.3f / %.3f 元原始灵气",
                    windowSeconds, receivedQi, requiredQi));
        }
        String hint = String.join("；", hints) + "。目标储量另行检查；过久没有摄入会重置连续计时";
        return meter("intake", "持续摄入", fraction, fraction >= 1 ? METER_READY : METER_WAITING, hint);
    }

    private static MeridianOperationMeter effort(BodySnapshot body, OpeningEffort effort) {
        // 器官与组织各读独立疲劳预算，精力不映射为另一份临时账户。
        String id;
        String label;
        double fraction;
        if (effort.hasOrganId()) {
            id = effort.getOrganId();
            OrganDefinition definition = body.getStructure().getOrgansList().stream()
                    .filter(organ -> organ.getId().equals(id)).findFirst().orElseThrow();
            OrganState state = body.getOrganStatesList().stream()
                    .filter(organ -> organ.getId().equals(id)).findFirst().orElseThrow();
            label = definition.getDisplayName() + "余力";
            double maximumJoules = definition.getEndurance().getEnduranceJoules();
            fraction = ratio(maximumJoules - state.getFatigueJoules(), maximumJoules);
        } else {
            id = effort.getTissueId();
            TissueDefinition definition = body.getStructure().getTissuesList().stream()
                    .filter(tissue -> tissue.getId().equals(id)).findFirst().orElseThrow();
            TissueState state = body.getTissueStatesList().stream()
                    .filter(tissue -> tissue.getId().equals(id)).findFirst().orElseThrow();
            label = definition.getDisplayName() + "余力";
            fraction = ratio(definition.getFatigueCapacity() - state.getFatigue(), definition.getFatigueCapacity());
        }
        return meter("effort/" + id, label, fraction, fraction > 0 ? METER_READY : METER_BLOCKED, "停用对应模块后休息恢复");
    }

    private static double incoming(BodySnapshot body, String target) {
        // 在途单独显示，目标容量预约不是已到账余额。
        List<String> ports = body.getStructure().getMeridiansList().stream().filter(meridian -> meridian.getId().equals(target))
                .flatMap(meridian -> meridian.getPortsList().stream()).map(Port::getId).toList();
        List<String> connections = body.getStructure().getConnectionsList().stream().filter(connection -> ports.contains(connection.getTo()))
                .map(Connection::getId).toList();
        return body.getTransitsList().stream().filter(transit -> connections.contains(transit.getConnectionId()))
                .mapToDouble(transit -> HumanOpeningPolicy.total(transit.getCargo())).sum();
    }

    private static double ratio(double amount, double maximum) {
        // 无需求的条视为满足；输出始终是可绘制的有界比例。
        return maximum <= 0 ? 1 : Math.max(0, Math.min(1, amount / maximum));
    }

    private static MeridianOperationMeter meter(String id, String label, double fraction, OperationMeterState state, String hint) {
        // 所有进度条遵守同一展示契约，不把数值编码到文字或异常字符串。
        return MeridianOperationMeter.newBuilder().setId(id).setLabel(label).setFraction(fraction).setState(state).setHint(hint).build();
    }
}
