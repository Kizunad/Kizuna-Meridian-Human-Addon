package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.OpeningTask;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.HumanOpeningConditions;
import dev.kizuna.meridian.human.server.HumanOpeningPolicy;
import dev.kizuna.meridian.protocol.OpeningProtobuf;

import java.util.Locale;

/** 开脉命令读取同一权威身体；不缓存或推测进度。 */
final class HumanOpeningNumbers {
    private HumanOpeningNumbers() {
        // 文本展示不推进身体时间。
    }

    static String summary(BodySnapshot body) {
        // 全身准备比例始终以稳定容量为分母，临时增压额度另列。
        double capacity = body.structure().meridians().stream().mapToDouble(item -> item.lumenCapacity()).sum();
        String reserves = String.format(Locale.ROOT, "全身内腔 %.2f / %.2f 元，启动至少 %.2f 元。",
                body.lumenQiTotal(), capacity, capacity * .8);
        OpeningTask task = body.openingTask();
        if (task == null) {
            return reserves + " 使用 /meridian opening start <经脉 ID> 开始，随后在吐纳窗口持续摄入。";
        }
        String status = switch (task.status()) {
            case PREPARING -> "汇集增压（储量或近期摄入尚不足）";
            case HOLDING -> "连续适应";
            case PAUSED -> "已暂停";
            case EXHAUSTED -> "耐力或精力不足";
            case DAMAGED -> "参与经脉或器官失能";
            case COMPLETED -> "开脉完成";
        };
        double local = body.meridianStates().stream().filter(item -> item.id().equals(task.rule().targetMeridianId()))
                .findFirst().orElseThrow().lumenQi().totalQi();
        double requiredGroup = task.participants().stream().mapToDouble(item -> item.qi()).sum();
        double group = body.meridianStates().stream()
                .filter(item -> task.participants().stream().anyMatch(limit -> limit.meridianId().equals(item.id())))
                .mapToDouble(item -> item.lumenQi().totalQi()).sum();
        var brain = body.organStates().stream().filter(item -> item.id().value().equals("brain")).findFirst();
        double brainFatigue = brain.map(item -> item.fatigueJoules()).orElse(0.0);
        // 门槛符号与实际规则一致，界面明确区分维持消耗和原有脑疲劳。
        String thresholdSign = ">=";
        HumanOpeningConditions conditions = HumanOpeningPolicy.conditions(OpeningProtobuf.toRule(task.rule()));
        if (conditions.getStrictLumenThreshold()) {
            thresholdSign = ">";
        }
        return reserves + String.format(Locale.ROOT,
                "\n%s：%s；目标 %.2f 元（需 %s %.2f）；参与组 %.2f 元（需 %s %.2f）；维持 %.1f / %.1f 秒；维持耗散 %.2f 元/秒；脑疲劳 %.1f J。",
                task.rule().displayName(), status, local, thresholdSign, task.rule().minimumLumenQi().totalQi(),
                group, thresholdSign, requiredGroup, task.heldMillis() / 1000.0, task.requiredMillis() / 1000.0,
                task.rule().holdQiPerSecond(), brainFatigue);
    }
}
