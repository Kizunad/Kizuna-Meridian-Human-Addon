package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.human.server.HumanProjectileBallistics.Path;
import dev.kizuna.meridian.human.server.HumanProjectileBallistics.LobProfile;
import dev.kizuna.meridian.human.server.HumanProjectileBallistics.Vector;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HumanProjectileBallisticsTest {
    private static final double TOLERANCE = 1.0e-9;
    private static final double MAX_SPEED = 3.3;
    private static final int ARRIVAL_TICK = 60;
    private static final LobProfile LOB = new LobProfile(HumanProjectileBallistics.LOB_ANGLE_DEGREES, MAX_SPEED);
    private static final double MIN_ELEVATION = HumanProjectileBallistics.MIN_CONVERGE_ANGLE_DEGREES;
    private static final Vector STATIONARY = new Vector(0, 0, 0);
    private static final Vector OFFSET_TARGET = new Vector(18, -2, 7);
    private static final Vector NEAR_TARGET = new Vector(8, -1, 0);
    private static final Vector FAR_TARGET = new Vector(32, -2, 0);
    private static final Vector ELEVATED_TARGET = new Vector(-12, 3, 5);
    private static final Vector UNREACHABLE_TARGET = new Vector(1000, 0, 0);
    private static final Vector CONVERGE_TARGET = new Vector(20, 0, 0);
    private static final Vector DIRECT_TARGET = new Vector(32, 0, 0);
    private static final Vector SHOOTER_MOVEMENT = new Vector(0.2, 0.1, -0.15);
    private static final double DIRECT_SPEED = 4.5;
    private static final double[] CONVERGE_DURATIONS = {60.5, 52.5, 44.5};
    private static final int LOW_ANGLE_DURATION = 8;
    private static final int EXCESS_SPEED_DURATION = 200;
    private static final double FRACTIONAL_TICK = 0.375;

    @Test
    public void positionMatchesTickSimulationAcrossSearchRange() {
        // 用原版递推逐帧比较闭式积分，包含零时刻、整数边界和不足一 tick 的位移。
        Vector initial = SHOOTER_MOVEMENT;
        double x = 0;
        double y = 0;
        double z = 0;
        double vx = initial.x();
        double vy = initial.y();
        double vz = initial.z();
        for (int tick = 0; tick <= HumanProjectileBallistics.MAX_FLIGHT_TICKS; tick++) {
            assertPosition(new Vector(x, y, z), HumanProjectileBallistics.position(initial, tick));
            Vector partial = new Vector(x + vx * FRACTIONAL_TICK, y + vy * FRACTIONAL_TICK,
                    z + vz * FRACTIONAL_TICK);
            assertPosition(partial, HumanProjectileBallistics.position(initial, tick + FRACTIONAL_TICK));
            x += vx;
            y += vy;
            z += vz;
            vx *= HumanProjectileBallistics.AIR_DRAG;
            vy = vy * HumanProjectileBallistics.AIR_DRAG - HumanProjectileBallistics.GRAVITY;
            vz *= HumanProjectileBallistics.AIR_DRAG;
        }
    }

    @Test
    public void inversionMatchesIndependentTickSimulation() {
        // 独立逐 tick 推进验证先移动、后阻力与重力的原版顺序。
        Vector target = OFFSET_TARGET;
        Vector velocity = HumanProjectileBallistics.velocityTo(target, ARRIVAL_TICK);
        double x = 0;
        double y = 0;
        double z = 0;
        double vx = velocity.x();
        double vy = velocity.y();
        double vz = velocity.z();
        for (int tick = 0; tick < ARRIVAL_TICK; tick++) {
            x += vx;
            y += vy;
            z += vz;
            vx *= HumanProjectileBallistics.AIR_DRAG;
            vy = vy * HumanProjectileBallistics.AIR_DRAG - HumanProjectileBallistics.GRAVITY;
            vz *= HumanProjectileBallistics.AIR_DRAG;
        }
        assertPosition(target, new Vector(x, y, z));
    }

    @Test
    public void lobReachesGroundAtAnimationAngleAndRespectsSpeedCap() {
        // 近远、落差和左右落点共用固定仰角求解；不可达点不突破倍率上限。
        for (Vector target : new Vector[]{NEAR_TARGET, FAR_TARGET, ELEVATED_TARGET}) {
            var shot = HumanProjectileBallistics.lob(target, LOB, STATIONARY);
            assertEquals(Path.LOB, shot.path());
            assertTrue(shot.velocity().length() <= MAX_SPEED);
            assertPosition(target, HumanProjectileBallistics.position(shot.velocity(), shot.flightTicks()));
            double horizontal = Math.hypot(shot.velocity().x(), shot.velocity().z());
            assertEquals(HumanProjectileBallistics.LOB_ANGLE_DEGREES,
                    Math.toDegrees(Math.atan2(shot.velocity().y(), horizontal)), TOLERANCE);
        }
        var unreachable = HumanProjectileBallistics.lob(UNREACHABLE_TARGET, LOB, STATIONARY);
        assertEquals(Path.FALLBACK, unreachable.path());
        assertEquals(MAX_SPEED, unreachable.velocity().length(), TOLERANCE);
    }

    @Test
    public void convergenceMeetsOneArrivalAndFallsBackForLowAngleOrExcessSpeed() {
        // 不同撒放时刻反解到同一目标；不合几何约束的解明确标为普通抛射。
        Vector target = CONVERGE_TARGET;
        for (double duration : CONVERGE_DURATIONS) {
            var shot = HumanProjectileBallistics.converge(target, duration, MIN_ELEVATION, LOB, STATIONARY);
            assertEquals(Path.CONVERGED, shot.path());
            assertPosition(target, HumanProjectileBallistics.position(shot.velocity(), duration));
        }
        assertEquals(Path.FALLBACK,
                HumanProjectileBallistics.converge(target, LOW_ANGLE_DURATION, MIN_ELEVATION, LOB, STATIONARY).path());
        assertEquals(Path.FALLBACK,
                HumanProjectileBallistics.converge(target, EXCESS_SPEED_DURATION, MIN_ELEVATION,
                        LOB, STATIONARY).path());
    }

    @Test
    public void directFlightAccountsForDragAndGravity() {
        // 平射时间不是距离除初速，反解后的速度应回到给定的满弓倍率。
        Vector target = DIRECT_TARGET;
        double speed = DIRECT_SPEED;
        double ticks = HumanProjectileBallistics.directFlightTicks(target, speed, STATIONARY);
        assertTrue(ticks > target.length() / speed);
        assertEquals(speed, HumanProjectileBallistics.velocityTo(target, ticks).length(), TOLERANCE);
    }

    @Test
    public void movingShooterStillReachesLobAndConvergenceTargets() {
        // 反解扣除射手惯性，发射时加回后仍应落在原目标，不重复叠加移动偏差。
        var lob = HumanProjectileBallistics.lob(FAR_TARGET, LOB, SHOOTER_MOVEMENT);
        assertEquals(Path.LOB, lob.path());
        assertPosition(FAR_TARGET,
                HumanProjectileBallistics.position(worldVelocity(lob.velocity()), lob.flightTicks()));
        var converged = HumanProjectileBallistics.converge(CONVERGE_TARGET, ARRIVAL_TICK, MIN_ELEVATION,
                LOB, SHOOTER_MOVEMENT);
        assertEquals(Path.CONVERGED, converged.path());
        assertPosition(CONVERGE_TARGET,
                HumanProjectileBallistics.position(worldVelocity(converged.velocity()), ARRIVAL_TICK));
        double ticks = HumanProjectileBallistics.directFlightTicks(DIRECT_TARGET, DIRECT_SPEED, SHOOTER_MOVEMENT);
        Vector total = HumanProjectileBallistics.velocityTo(DIRECT_TARGET, ticks);
        Vector launch = new Vector(total.x() - SHOOTER_MOVEMENT.x(), total.y() - SHOOTER_MOVEMENT.y(),
                total.z() - SHOOTER_MOVEMENT.z());
        assertEquals(DIRECT_SPEED, launch.length(), TOLERANCE);
    }

    private static Vector worldVelocity(Vector launch) {
        // 模拟原版射击在方向初速之外继承的同一份移动速度。
        return new Vector(launch.x() + SHOOTER_MOVEMENT.x(), launch.y() + SHOOTER_MOVEMENT.y(),
                launch.z() + SHOOTER_MOVEMENT.z());
    }

    private static void assertPosition(Vector expected, Vector actual) {
        // 三轴分别检查，避免距离平方掩盖单轴符号错误。
        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
        assertEquals(expected.z(), actual.z(), TOLERANCE);
    }
}
