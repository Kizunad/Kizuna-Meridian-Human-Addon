package dev.kizuna.meridian.human;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyActivityExecution;
import org.junit.Test;

import static org.junit.Assert.*;

public final class HumanStaminaTest {
    private static final double ADULT_JOULES = 91_400;
    private static final int MUSCLES = 18;
    private static final String THIGH = "left_rectus_femoris";
    private static final String CHEST = "right_pectoralis_major";
    private static final double THIGH_JOULES = 9_000;
    private static final double PECTORAL_JOULES = 6_000;
    private static final double FATIGUE_CAPACITY = 100;
    private static final double HALF = 0.5;
    private static final double QUARTER = 0.25;
    private static final double RUN_WATTS = 120;
    private static final double RUN_SECONDS = 2;
    private static final double TOLERANCE = 1e-8;

    @Test
    public void freshAdultAndRunningLegUseTheSameMuscleBudget() {
        // 出生模板里每侧九块运动肌，跑步腿肌的实际做功减少同一份焦耳。
        var body = adult();
        var fresh = HumanStamina.from(body);
        assertEquals(ADULT_JOULES, fresh.capacityJoules(), 0);
        assertEquals(ADULT_JOULES, fresh.availableJoules(), 0);
        assertEquals(MUSCLES, fresh.cost(1).size());
        var load = new TissueCapabilities.Exertion(new Anatomy.TissueId(THIGH), 0, RUN_WATTS, RUN_SECONDS);
        var tired = HumanStamina.from(TissueCapabilities.exert(body, load));
        assertEquals(ADULT_JOULES - RUN_WATTS * RUN_SECONDS, tired.availableJoules(), TOLERANCE);
        assertEquals(ADULT_JOULES, tired.capacityJoules(), 0);
    }

    @Test
    public void trainedLegRaisesHealthyCapacityAndAvailableEndurance() {
        // 单块已巩固的耐力成长在健康时同时计入当前焦耳和满额基准。
        var candidate = BodyProtobuf.toMessage(adult()).toBuilder();
        for (var tissue : candidate.getTissueStatesBuilderList()) {
            if (tissue.getId().equals(THIGH)) {
                tissue.setTrainingGrowth(QUARTER);
            }
        }
        var trained = HumanStamina.from(BodyProtobuf.fromMessage(candidate.build()));
        double expected = ADULT_JOULES + THIGH_JOULES * QUARTER;
        assertEquals(expected, trained.capacityJoules(), 0);
        assertEquals(expected, trained.availableJoules(), 0);
        assertEquals(1, trained.remainingRatio(), 0);
    }

    @Test
    public void injuredTrainedAndExhaustedMusclesPayInProportion() {
        // 一条腿的主肌耗尽并有已巩固成长；另一侧胸肌受伤且已有疲劳。
        var body = unevenBody();
        var before = HumanStamina.from(body);
        double expected = ADULT_JOULES - THIGH_JOULES - PECTORAL_JOULES * (1 - HALF * HALF);
        assertEquals(expected, before.availableJoules(), TOLERANCE);
        double capacity = ADULT_JOULES + THIGH_JOULES * QUARTER;
        assertEquals(capacity, before.capacityJoules(), 0);
        assertEquals(expected / capacity, before.remainingRatio(), TOLERANCE);

        var changes = before.cost(expected * QUARTER);
        assertEquals(MUSCLES - 1, changes.size());
        var activity = BodyActivity.newBuilder().addAllTissueFatigue(changes).build();
        var after = BodyActivityExecution.apply(body, activity, "human:test");
        assertEquals(expected * (1 - QUARTER), HumanStamina.from(after).availableJoules(), TOLERANCE);
        assertEquals(body.organStates(), after.organStates());
        assertEquals(body.partStates(), after.partStates());
        assertEquals(body.meridianStates(), after.meridianStates());
        var originalStates = body.tissueStates();
        var updatedStates = after.tissueStates();
        for (int index = 0; index < originalStates.size(); index++) {
            var original = originalStates.get(index);
            var updated = updatedStates.get(index);
            var id = original.id();
            double previous = TissueCapabilities.available(body, id).enduranceJoules();
            if (changes.stream().noneMatch(change -> change.getTissueId().equals(id.value()))) {
                assertEquals(original, updated);
            } else {
                assertEquals(previous * (1 - QUARTER),
                        TissueCapabilities.available(after, id).enduranceJoules(), TOLERANCE);
                assertEquals(original.pendingTrainingLoad(), updated.pendingTrainingLoad(), 0);
                assertEquals(original.trainingGrowth(), updated.trainingGrowth(), 0);
            }
        }
    }

    @Test
    public void unrepresentableShareDoesNotRejectOtherMuscles() {
        // 胸肌只剩最后一格浮点余量，微小分摊要舍掉，健康组织仍应付费。
        var candidate = BodyProtobuf.toMessage(adult()).toBuilder();
        candidate.getTissueStatesBuilderList().stream().filter(tissue -> tissue.getId().equals(CHEST))
                .findFirst().orElseThrow().setFatigue(Math.nextDown(FATIGUE_CAPACITY));
        var body = BodyProtobuf.fromMessage(candidate.build());
        var budget = HumanStamina.from(body);
        var shares = budget.cost(1);
        assertEquals(MUSCLES - 1, shares.size());
        assertFalse(shares.stream().anyMatch(share -> share.getTissueId().equals(CHEST)));
        var activity = BodyActivity.newBuilder().addAllTissueFatigue(shares).build();
        var paid = BodyActivityExecution.apply(body, activity, "human:test");
        assertEquals(budget.availableJoules() - 1, HumanStamina.from(paid).availableJoules(), TOLERANCE);
    }

    @Test
    public void overpaymentExhaustsOnlyParticipatingMuscles() {
        // 即使旧构型缺少呼吸标签也不能动用膈肌；超额只取尽能付的组织。
        var candidate = BodyProtobuf.toMessage(adult()).toBuilder();
        for (var tissue : candidate.getStructureBuilder().getTissuesBuilderList()) {
            tissue.clearTags();
        }
        var body = BodyProtobuf.fromMessage(candidate.build());
        var budget = HumanStamina.from(body);
        assertTrue(budget.cost(0).isEmpty());
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> budget.cost(invalid));
        }
        var drained = BodyActivity.newBuilder().addAllTissueFatigue(budget.cost(Double.MAX_VALUE)).build();
        var empty = BodyActivityExecution.apply(body, drained, "human:test");
        var remaining = HumanStamina.from(empty);
        assertEquals(0, remaining.availableJoules(), 0);
        assertEquals(0, remaining.remainingRatio(), 0);
        assertTrue(remaining.cost(1).isEmpty());
        for (var tissue : empty.tissueStates()) {
            String id = tissue.id().value();
            if (id.equals("diaphragm") || id.equals("attention_network")) {
                assertEquals(0, tissue.fatigue(), 0);
            }
        }
    }

    private static BodySnapshot unevenBody() {
        // 不同部位、健康和疲劳比例使按基础焦耳或按块均摊产生可观测偏差。
        var candidate = BodyProtobuf.toMessage(adult()).toBuilder();
        for (var tissue : candidate.getTissueStatesBuilderList()) {
            String id = tissue.getId();
            if (id.equals(THIGH)) {
                tissue.setFatigue(FATIGUE_CAPACITY);
                tissue.setTrainingGrowth(QUARTER);
                tissue.setPendingTrainingLoad(RUN_WATTS * RUN_SECONDS);
            } else if (id.equals(CHEST)) {
                tissue.setHp(tissue.getHp() * HALF);
                tissue.setFatigue(FATIGUE_CAPACITY * HALF);
            }
        }
        return BodyProtobuf.fromMessage(candidate.build());
    }

    private static BodySnapshot adult() {
        // 使用真实人体模板创建独立出生快照。
        var template = HumanContent.bundle().getTemplates(0);
        var time = BodyMessages.TimeCursor.newBuilder()
                .setDomain("test:world")
                .setEpoch("stamina")
                .setElapsedMillis(0)
                .build();
        return BodyProtobuf.decodeSnapshot(HumanBirth.candidate(template, "stamina-human", time));
    }
}
