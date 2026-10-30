package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HealBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.Healing;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 持续移动在组织修复后重试占用，被拒时不结算运动疲劳。 */
public final class HumanMovementDamageTest {
    private static final long SECOND_MILLIS = 1000;
    private static final String SOURCE = "human_test:movement_recovery";

    @Test
    public void rejectedMovementRetriesAtBoundaryAndResumesAfterTissueRepair() {
        // 同一种移动跨越多个边界，只有组织修复并成功建立占用后才允许做功。
        var body = HumanPlayerBody.born(UUID.randomUUID(), "movement-recovery", 0);
        var muscle = body.snapshot().structure().tissues().stream()
                .filter(tissue -> tissue.id().value().equals("left_gastrocnemius"))
                .findFirst().orElseThrow();
        var target = HealthTarget.newBuilder().setTissueId(muscle.id().value()).build();
        var damage = DamageBody.newBuilder()
                .setSourceId(SOURCE)
                .setDamageType(SOURCE)
                .setKind(DamageKind.CUSTOM)
                .setHit(target)
                .setDamageHp(muscle.maxHp())
                .build();
        apply(body, 0, BodyCommand.newBuilder().setDamageBody(damage));
        assertTrue(body.observe(0, HumanPlayerBody.Motion.WALK));
        assertTrue(body.observe(SECOND_MILLIS, HumanPlayerBody.Motion.WALK));
        assertTrue(body.snapshot().actions().isEmpty());
        assertEquals(0, fatigue(body), 0);

        repair(body, target, muscle.maxHp());
        assertTrue(body.observe(SECOND_MILLIS * 2, HumanPlayerBody.Motion.WALK));
        assertEquals(1, body.snapshot().actions().size());
        assertEquals(0, fatigue(body), 0);
        body.settle(SECOND_MILLIS * 3);
        assertTrue(fatigue(body) > 0);
    }

    private static void repair(HumanPlayerBody body, HealthTarget target, double hp) {
        // 修复沿用可信健康命令，不能通过替换快照绕过实际组织状态变更。
        var dose = Healing.newBuilder().setTarget(target).setHp(hp).build();
        var healing = HealBody.newBuilder().setSourceId(SOURCE).addDoses(dose).build();
        apply(body, SECOND_MILLIS, BodyCommand.newBuilder().setHealBody(healing));
    }

    private static void apply(HumanPlayerBody body, long millis, BodyCommand.Builder operation) {
        // 每次提交读取当前版本，保留真实宿主结算与核心健康事务。
        body.settle(millis);
        var snapshot = body.snapshot();
        var command = operation
                .setBodyId(snapshot.id().value())
                .setExpectedRevision(snapshot.revision())
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        var response = body.applyHealth(millis, request);
        assertTrue(response.toString(), response.hasCommandCommitted());
    }

    private static double fatigue(HumanPlayerBody body) {
        // 累计所有运动组织疲劳，避免只检查受伤肌肉而漏掉其他肌肉扣费。
        return body.snapshot().tissueStates().stream().mapToDouble(tissue -> tissue.fatigue()).sum();
    }
}
