package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.TissueFatigueIncrease;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 同一身体快照的运动肌耐力，单位为焦耳。 */
public final class HumanStamina {
    private static final DefinitionId MUSCLE = new DefinitionId("human:muscle");
    private static final DefinitionId BREATHING = new DefinitionId("human:breathing");
    private static final Anatomy.TissueId DIAPHRAGM = new Anatomy.TissueId("diaphragm");

    private record Muscle(Anatomy.TissueId id, double fatigue, double remainingFatiguePoints) {}

    private final double availableJoules;
    private final double capacityJoules;
    private final List<Muscle> payingMuscles;
    private final List<String> workingMuscles;

    private HumanStamina(double availableJoules, double capacityJoules, List<Muscle> payingMuscles,
                         List<String> workingMuscles) {
        // 固定取样时的余额和参与肌肉，保证同一次分摊只使用一个身体版本。
        this.availableJoules = availableJoules;
        this.capacityJoules = capacityJoules;
        this.payingMuscles = List.copyOf(payingMuscles);
        this.workingMuscles = List.copyOf(workingMuscles);
    }

    public static HumanStamina from(BodySnapshot body) {
        // 实例可以保有旧构型、伤残或训练成长，按组织身份对齐状态与定义。
        var states = body.tissueStates().stream()
                .collect(Collectors.toMap(AnatomyState.Tissue::id, state -> state));
        double available = 0;
        double capacity = 0;
        var paying = new ArrayList<Muscle>();
        var working = new ArrayList<String>();
        var parts = body.partStates().stream()
                .collect(Collectors.toMap(AnatomyState.Part::id, AnatomyState.Part::hp));
        for (var definition : body.structure().tissues()) {
            var id = definition.id();
            if (!MUSCLE.equals(definition.kind()) || DIAPHRAGM.equals(id) || definition.tags().contains(BREATHING)) {
                continue;
            }
            var state = states.get(id);
            if (state.hp() > 0 && parts.get(definition.partId()) > 0) {
                working.add(id.value());
            }
            capacity += definition.capacity().enduranceJoules() * (1 + state.trainingGrowth());
            double endurance = TissueCapabilities.available(body, id).enduranceJoules();
            if (endurance > 0) {
                available += endurance;
                double fatigue = state.fatigue();
                paying.add(new Muscle(id, fatigue, definition.fatigueCapacity() - fatigue));
            }
        }
        if (!Double.isFinite(available) || !Double.isFinite(capacity)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_STAMINA);
        }
        return new HumanStamina(available, capacity, paying, working);
    }

    public List<String> workingMuscles() {
        // 已经累满但仍能工作的肌肉也需占用，否则守中会自然恢复。
        return workingMuscles;
    }

    public double availableJoules() {
        // 包含当前耐力修正的实际可付总量。
        return availableJoules;
    }

    public double capacityJoules() {
        // 健康、无疲劳时的训练后基准，保留受伤和失能肌肉。
        return capacityJoules;
    }

    public double remainingRatio() {
        // 无运动耐力时比例为零，临时增益使当前预算超过基准时显示为满额。
        if (capacityJoules == 0) {
            return 0;
        }
        return Math.min(1, availableJoules / capacityJoules);
    }

    public List<TissueFatigueIncrease> cost(double requestedJoules) {
        // 每块付出相同比例的有效焦耳，等价于消耗其剩余疲劳点的相同比例。
        if (!Double.isFinite(requestedJoules) || requestedJoules < 0) {
            throw HumanErrors.invalid(HumanErrors.INVALID_STAMINA);
        }
        if (requestedJoules == 0 || availableJoules == 0) {
            return List.of();
        }
        double fraction = Math.min(requestedJoules, availableJoules) / availableJoules;
        var increases = new ArrayList<TissueFatigueIncrease>(payingMuscles.size());
        for (var muscle : payingMuscles) {
            double points = muscle.remainingFatiguePoints() * fraction;
            double fatigue = muscle.fatigue();
            if (fatigue + points == fatigue) {
                continue;
            }
            var increase = TissueFatigueIncrease.newBuilder()
                    .setTissueId(muscle.id().value())
                    .setPoints(points)
                    .build();
            increases.add(increase);
        }
        return List.copyOf(increases);
    }
}
