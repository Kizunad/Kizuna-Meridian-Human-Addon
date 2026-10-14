package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyActions;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.human.server.HumanErrors;

import java.util.List;
import java.util.Locale;

/** 只读数值投影，聊天、实时观察与测试共享同一权威能力来源。 */
final class HumanNumbers {
    private HumanNumbers() {
        // 不缓存或修改任何耐力值。
    }

    static String summary(HumanPlayerBody player) {
        // 汇总八块下肢肌肉的最低耐力比例，避免平均值掩盖已经疲劳的局部。
        BodySnapshot body = player.snapshot();
        double minimumRatio = body.structure().tissues().stream()
                .filter(tissue -> tissue.id().value().matches("(left|right)_(gluteus_maximus|rectus_femoris|gastrocnemius|flexor_digitorum_brevis)"))
                .mapToDouble(tissue -> TissueCapabilities.available(body, tissue.id()).enduranceJoules()
                        / tissue.capacity().enduranceJoules()).min().orElse(0);
        List<String> actions = body.actions().stream().map(action -> action.definition().displayName()).toList();
        return String.format(Locale.ROOT, "%s | 下肢余力 %.2f%% | 动作 %s | 身体版本 %d",
                player.motion().displayName, minimumRatio * 100, actions.isEmpty() ? "无" : String.join("、", actions), body.revision());
    }

    static String tissue(BodySnapshot body, String tissueId) {
        // 当前力量、功率和耐力由框架按实际健康与疲劳派生，查询不会另外扣资源。
        Anatomy.TissueDefinition definition = body.structure().tissues().stream()
                .filter(tissue -> tissue.id().value().equals(tissueId)).findFirst()
                .orElseThrow(() -> HumanErrors.invalid(HumanErrors.TISSUE_MISSING));
        var state = body.tissueStates().stream().filter(tissue -> tissue.id().equals(definition.id())).findFirst().orElseThrow();
        Anatomy.TissueCapacity available = TissueCapabilities.available(body, definition.id());
        boolean occupied = BodyActions.usedTissues(body).contains(definition.id());
        return String.format(Locale.ROOT, "%s [%s] | 疲劳 %.3f/%.0f | 耐力 %.2f/%.2f J | 力量 %.2f N | 爆发 %.2f W | %s | 恢复半衰期 %.0fs",
                definition.displayName(), tissueId, state.fatigue(), definition.fatigueCapacity(),
                available.enduranceJoules(), definition.capacity().enduranceJoules(), available.strengthNewtons(),
                available.burstWatts(), occupied ? "占用中" : "空闲", definition.recoveryHalfLifeSeconds());
    }
}
