package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.HumanOpeningConditions;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.IntakeRequirement;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
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
        // 每条经脉都复用同一组体积进度条，人体内容只提供策略和模型身份。
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
        // 正在执行的任务使用已冻结规则，内容包更新不能改变当前尝试的时长或目标。
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

        // 进度条以体积为分子，避免高密度真元因为元数较大而显示错误满载。
        double bodyCapacity = body.getStructure().getMeridiansList().stream()
                .mapToDouble(MeridianDefinition::getLumenVolume).sum();
        double bodyOccupied = body.getMeridianStatesList().stream()
                .mapToDouble(state -> volume(state.getLumenQi())).sum();
        double preparation = ratio(bodyOccupied, bodyCapacity * conditions.getBodyStartFraction());
        if (!running && !opened) {
            result.addMeters(meter("preparation", "全身准备", preparation,
                    preparation >= 1 ? METER_READY : METER_WAITING, "先通过吐纳补足全身内腔占用"));
        }
        MeridianOperationMeter targetProgress = targetMeter(body, rule, conditions);
        result.addMeters(targetProgress);
        boolean targetReady = targetProgress.getState() == METER_READY;

        long requiredMillis = sameTask ? body.getOpeningTask().getRequiredMillis() : plan.getRequiredMillis();
        long heldMillis = sameTask ? body.getOpeningTask().getHeldMillis() : 0;
        MeridianOperationMeter intakeProgress = intakeMeter(body, conditions, sameTask);
        if (conditions.getIntakesCount() > 0) {
            result.addMeters(intakeProgress);
        }
        for (OpeningEffort effort : rule.getEffortsList()) {
            result.addMeters(effort(body, effort));
        }

        // 只有 HOLDING 才累计连续适应；跌破 60% 后由权威策略把计时清零。
        OperationMeterState holdState = METER_WAITING;
        String holdReason = "目标与全身占用满足后开始；跌破维持线会清零计时";
        if (opened) {
            holdState = METER_READY;
            holdReason = "已完成拓宽";
        } else if (adapting) {
            holdState = METER_RUNNING;
            holdReason = "正在连续适应；保持吐纳并维持占用";
        } else if (running && !targetReady) {
            holdReason = "目标经脉尚未达到启动占用";
        } else if (running && intakeProgress.getFraction() < 1) {
            holdReason = "等待新的灵气摄入，连续计时已重置";
        }
        result.addMeters(meter("hold", "连续适应", ratio(heldMillis, requiredMillis), holdState,
                String.format(Locale.ROOT, "连续适应 %.1f / %.1f 秒；%s", heldMillis / 1000.0,
                        requiredMillis / 1000.0, holdReason)));

        // 按人体启动裁定禁用按钮；点击后核心仍会在事务内重新校验，不信任卡片。
        FailureCode rejection = HumanOpeningPolicy.prepare(body, rule, conditions).getRejection();
        boolean canStart = !active && !opened && rejection == FailureCode.FAILURE_CODE_UNSPECIFIED;
        boolean enabled = !body.getVitality().getDead() && (running || canStart);
        String hint = "保持吐纳并等待真元沿通路汇聚";
        String status = opened ? "已拓宽" : "准备操作";
        if (sameTask) {
            status = switch (body.getOpeningTask().getStatus()) {
                case OPENING_PREPARING -> targetReady ? "等待持续摄入 · 计时已重置" : "汇聚中 · 尚未计时";
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
            hint = rejection == FailureCode.INSUFFICIENT_QI ? "全身或目标占用尚不足，请先吐纳" : "经脉强度或前置开拓尚未满足";
            status = rejection == FailureCode.INSUFFICIENT_QI ? "占用不足 · 先吐纳" : "开拓条件不足";
        }
        return result.setEnabled(enabled).setStatus(status).setHint(hint).build();
    }

    private static MeridianOperationMeter targetMeter(BodySnapshot body, MeridianOpeningRule rule,
                                                       HumanOpeningConditions conditions) {
        // 目标显示实际占用相对于启动线的比例，维持线由服务端策略单独判断。
        MeridianState target = body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(rule.getTargetMeridianId())).findFirst().orElseThrow();
        MeridianDefinition definition = body.getStructure().getMeridiansList().stream()
                .filter(item -> item.getId().equals(rule.getTargetMeridianId())).findFirst().orElseThrow();
        double occupied = volume(target.getLumenQi());
        double required = definition.getLumenVolume() * conditions.getTargetStartFraction();
        boolean ready = occupied + 1e-9 >= required;
        String hint = ready ? "达到启动占用；维持期间门槛为目标脉 60%" : "继续汇聚真元至目标脉启动占用线";
        return meter("target", "目标经脉", ratio(occupied, required),
                ready ? METER_READY : METER_WAITING, hint).toBuilder()
                .setIncomingFraction(ratio(incoming(body, target.getId()), required)).build();
    }

    private static MeridianOperationMeter intakeMeter(BodySnapshot body, HumanOpeningConditions conditions,
                                                       boolean sameTask) {
        // 摄入要求按时间窗取最差完成比例，过期收据不能继续支持连续维持。
        double fraction = 1;
        var hints = new ArrayList<String>();
        for (IntakeRequirement intake : conditions.getIntakesList()) {
            long sinceMillis = body.getSettledAt().getElapsedMillis() - intake.getWindowMillis();
            double received = 0;
            if (sameTask) {
                received = body.getOpeningTask().getIntakeSamplesList().stream()
                        .filter(sample -> intake.getOrganIdsList().contains(sample.getOrganId()))
                        .filter(sample -> sample.getAtMillis() > sinceMillis)
                        .mapToDouble(OpeningIntakeSample::getQi).sum();
            }
            double required = intake.getMinimumQiPerSecond() * intake.getWindowMillis() / 1000.0;
            fraction = Math.min(fraction, ratio(received, required));
            hints.add(String.format(Locale.ROOT, "最近 %.1f 秒摄入 %.3f / %.3f 元原始灵气",
                    intake.getWindowMillis() / 1000.0, received, required));
        }
        return meter("intake", "持续摄入", fraction, fraction >= 1 ? METER_READY : METER_WAITING,
                String.join("；", hints) + "。过久没有摄入会重置连续计时");
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
            double maximum = definition.getEndurance().getEnduranceJoules();
            fraction = ratio(maximum - state.getFatigueJoules(), maximum);
        } else {
            id = effort.getTissueId();
            TissueDefinition definition = body.getStructure().getTissuesList().stream()
                    .filter(tissue -> tissue.getId().equals(id)).findFirst().orElseThrow();
            TissueState state = body.getTissueStatesList().stream()
                    .filter(tissue -> tissue.getId().equals(id)).findFirst().orElseThrow();
            label = definition.getDisplayName() + "余力";
            fraction = ratio(definition.getFatigueCapacity() - state.getFatigue(), definition.getFatigueCapacity());
        }
        return meter("effort/" + id, label, fraction, fraction > 0 ? METER_READY : METER_BLOCKED,
                "停用对应模块后休息恢复");
    }

    private static double incoming(BodySnapshot body, String target) {
        // 在途单独显示为体积，目标容量预约不是已到账余额。
        List<String> ports = body.getStructure().getMeridiansList().stream()
                .filter(meridian -> meridian.getId().equals(target)).flatMap(meridian -> meridian.getPortsList().stream())
                .map(Port::getId).toList();
        List<String> connections = body.getStructure().getConnectionsList().stream()
                .filter(connection -> ports.contains(connection.getTo())).map(Connection::getId).toList();
        return body.getTransitsList().stream().filter(transit -> connections.contains(transit.getConnectionId()))
                .mapToDouble(transit -> volume(transit.getCargo())).sum();
    }

    private static double volume(QiInventory inventory) {
        // 混相明细分别按数量除以密度换算，再相加得到实际空间占用。
        return inventory.getEntriesList().stream().mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
    }

    private static double ratio(double amount, double maximum) {
        // 无需求的条视为满足；输出始终是可绘制的有界比例。
        return maximum <= 0 ? 1 : Math.max(0, Math.min(1, amount / maximum));
    }

    private static MeridianOperationMeter meter(String id, String label, double fraction,
                                                 OperationMeterState state, String hint) {
        // 所有进度条遵守同一展示契约，不把数值编码到文字或异常字符串。
        return MeridianOperationMeter.newBuilder().setId(id).setLabel(label).setFraction(fraction)
                .setState(state).setHint(hint).build();
    }
}
