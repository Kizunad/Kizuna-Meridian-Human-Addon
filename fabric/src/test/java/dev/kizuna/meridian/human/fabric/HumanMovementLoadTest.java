package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.TissueCapabilities;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 行走负荷倍率只放大下肢肌肉的目标功率，实际做功仍受爆发上限截断。 */
public final class HumanMovementLoadTest {
    private static final long SECOND_MILLIS = 1000;
    private static final double WALK_SECONDS = 1;
    private static final double DOUBLE_LOAD = 2;
    // 疲劳折算耐力只经过少量浮点乘除，比较实际消耗时允许相对误差。
    private static final double RELATIVE_TOLERANCE = 1e-9;
    private static final String MUSCLE = "left_rectus_femoris";

    @Test
    public void doubleLoadSpendsTwiceTheEnduranceOfAnUnloadedSecond() {
        // 同样走 1 秒，倍率 2 的目标功率仍低于爆发上限，耐力消耗正好翻倍。
        double unloaded = spentWalking(HumanMovementLoads.UNLOADED);
        double loaded = spentWalking(DOUBLE_LOAD);
        assertTrue(unloaded > 0);
        assertEquals(unloaded * DOUBLE_LOAD, loaded, loaded * RELATIVE_TOLERANCE);
    }

    @Test
    public void loadAboveBurstIsCappedAtBurstPower() {
        // 倍率先放大目标功率，再照旧被满状态爆发功率截断；耐力足够支付这 1 秒。
        var body = HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        Anatomy.TissueDefinition muscle = muscle(body);
        Anatomy.TissueCapacity fresh = TissueCapabilities.available(body.snapshot(), muscle.id());
        double walkWatts = muscle.capacity().enduranceJoules() / HumanPlayerBody.WALK_ENDURANCE_SECONDS;
        assertTrue(walkWatts * DOUBLE_LOAD < fresh.burstWatts());
        assertTrue(fresh.burstWatts() * WALK_SECONDS < fresh.enduranceJoules());
        double overBurst = fresh.burstWatts() / walkWatts * DOUBLE_LOAD;
        double burstJoules = fresh.burstWatts() * WALK_SECONDS;
        assertEquals(burstJoules, spentWalking(overBurst), burstJoules * RELATIVE_TOLERANCE);
    }

    private static double spentWalking(double multiplier) {
        // 新生身体从 0 毫秒开始行走，结算到 1 秒后比较同一肌肉的剩余耐力。
        var body = HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        body.bindMovementLoad(() -> multiplier);
        Anatomy.TissueDefinition muscle = muscle(body);
        double before = TissueCapabilities.available(body.snapshot(), muscle.id()).enduranceJoules();
        assertTrue(body.observe(0, HumanPlayerBody.Motion.WALK));
        body.settle(SECOND_MILLIS);
        double after = TissueCapabilities.available(body.snapshot(), muscle.id()).enduranceJoules();
        return before - after;
    }

    private static Anatomy.TissueDefinition muscle(HumanPlayerBody body) {
        // 股直肌属于行走动作的占用组织，左右两侧同值，取左侧即可。
        return body.snapshot().structure().tissues().stream()
                .filter(tissue -> tissue.id().value().equals(MUSCLE))
                .findFirst().orElseThrow();
    }
}
