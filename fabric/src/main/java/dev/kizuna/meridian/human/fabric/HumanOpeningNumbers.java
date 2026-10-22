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
        // 全身准备比例以稳定体积为分母；元数只作为用户熟悉的派生显示。
        double capacity = body.structure().meridians().stream().mapToDouble(item -> item.lumenVolume()).sum();
        double occupied = body.meridianStates().stream().mapToDouble(item -> item.lumenQi().volume()).sum();
        String reserves = String.format(Locale.ROOT, "全身内腔 %.2f / %.2f 体积，启动至少 %.2f 体积。",
                occupied, capacity, capacity * .8);
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
                .findFirst().orElseThrow().lumenQi().volume();
        var brain = body.organStates().stream().filter(item -> item.id().value().equals("brain")).findFirst();
        double brainFatigue = brain.map(item -> item.fatigueJoules()).orElse(0.0);
        HumanOpeningConditions conditions = HumanOpeningPolicy.conditions(OpeningProtobuf.toRule(task.rule()));
        return reserves + String.format(Locale.ROOT,
                "\n%s：%s；目标占用 %.2f 体积（启动线 %.0f%%）；维持 %.1f / %.1f 秒；维持耗散 %.2f 元/秒；脑疲劳 %.1f J。",
                task.rule().displayName(), status, local, conditions.getTargetStartFraction() * 100,
                task.heldMillis() / 1000.0, task.requiredMillis() / 1000.0,
                task.rule().holdQiPerSecond(), brainFatigue);
    }
}
