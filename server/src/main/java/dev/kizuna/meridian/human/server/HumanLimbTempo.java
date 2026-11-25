package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.TissueCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 玩家与类人生物共用的人体动作速度投影；只缓存构型索引，不保存身体状态。 */
public final class HumanLimbTempo {
    public enum Side { LEFT, RIGHT }
    private static final String MUSCLE_KIND = "human:muscle";
    private static final double LEG_COUNT = 2;
    private static final Map<Side, Set<String>> ARMS = Map.of(
            Side.LEFT, Set.of("left_upper_arm", "left_forearm", "left_hand"),
            Side.RIGHT, Set.of("right_upper_arm", "right_forearm", "right_hand"));
    private static final Map<Side, Set<String>> LEGS = Map.of(
            Side.LEFT, Set.of("left_thigh", "left_lower_leg", "left_foot"),
            Side.RIGHT, Set.of("right_thigh", "right_lower_leg", "right_foot"));
    private static final Map<Side, String> GLUTEUS = Map.of(
            Side.LEFT, "left_gluteus_maximus", Side.RIGHT, "right_gluteus_maximus");
    private final BodyStructure structure;
    private final Map<Side, List<Anatomy.TissueId>> arms;
    private final Map<Side, List<Anatomy.TissueId>> legs;

    public HumanLimbTempo(BodyStructure structure) {
        // 构型只在创建或改造时索引，平时按肌肉 ID 读取实时曲线。
        this.structure = structure;
        var leftArm = new ArrayList<Anatomy.TissueId>();
        var rightArm = new ArrayList<Anatomy.TissueId>();
        var leftLeg = new ArrayList<Anatomy.TissueId>();
        var rightLeg = new ArrayList<Anatomy.TissueId>();
        for (var tissue : structure.tissues()) {
            if (!tissue.kind().value().equals(MUSCLE_KIND)) {
                continue;
            }
            String part = tissue.partId().value();
            var id = tissue.id();
            String tissueId = id.value();
            if (ARMS.get(Side.LEFT).contains(part)) {
                leftArm.add(id);
            } else if (ARMS.get(Side.RIGHT).contains(part)) {
                rightArm.add(id);
            } else if (LEGS.get(Side.LEFT).contains(part) || tissueId.equals(GLUTEUS.get(Side.LEFT))) {
                leftLeg.add(id);
            } else if (LEGS.get(Side.RIGHT).contains(part) || tissueId.equals(GLUTEUS.get(Side.RIGHT))) {
                rightLeg.add(id);
            }
        }
        arms = Map.of(Side.LEFT, List.copyOf(leftArm), Side.RIGHT, List.copyOf(rightArm));
        legs = Map.of(Side.LEFT, List.copyOf(leftLeg), Side.RIGHT, List.copyOf(rightLeg));
    }

    public boolean matches(BodySnapshot body) {
        // 外部缓存据此在身体构型改变时重建。
        return structure.equals(body.structure());
    }

    static boolean armMuscle(Anatomy.TissueDefinition tissue) {
        // 挥击用臂、前臂和手掌的肌肉。
        String part = tissue.partId().value();
        return tissue.kind().value().equals(MUSCLE_KIND)
                && (ARMS.get(Side.LEFT).contains(part) || ARMS.get(Side.RIGHT).contains(part));
    }

    static boolean legMuscle(Anatomy.TissueDefinition tissue) {
        // 步态包括足部和附着在骨盆的臀大肌。
        String part = tissue.partId().value();
        return tissue.kind().value().equals(MUSCLE_KIND)
                && (LEGS.get(Side.LEFT).contains(part) || LEGS.get(Side.RIGHT).contains(part)
                || GLUTEUS.containsValue(tissue.id().value()));
    }

    public Multipliers at(BodySnapshot body, Side mainHand, long millis) {
        // 主手臂取最慢，双腿分别取最慢后平均，胸肌和副手只影响其绑定功法。
        double arm = minimum(body, arms.get(mainHand), millis);
        double left = minimum(body, legs.get(Side.LEFT), millis);
        double right = minimum(body, legs.get(Side.RIGHT), millis);
        return new Multipliers(arm, (left + right) / LEG_COUNT);
    }

    private static double minimum(BodySnapshot body, List<Anatomy.TissueId> ids, long millis) {
        // 空肢体没有额外药物倍率；允许未来合法加速曲线高于一。
        double minimum = Double.POSITIVE_INFINITY;
        for (var id : ids) {
            minimum = Math.min(minimum, TissueCapabilities.tempoMultiplierAt(body, id, millis));
        }
        if (minimum == Double.POSITIVE_INFINITY) {
            return 1;
        }
        return minimum;
    }

    public record Multipliers(double arm, double movement) { }
}
