package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BoundSlot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ConvergeProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.DrawStrengthArm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LobProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModuleKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TargetLockIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBinding;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.human.fabric.HumanProjectileGameTest.settings;
import static dev.kizuna.meridian.human.fabric.HumanTechniqueTestCatalog.quantity;

/** 汇聚反解经过实际服务器 tick；四支箭保持原版重力与碰撞。 */
public final class HumanProjectileConvergenceGameTest {
    private static final long MILLIS_PER_TICK = 50;
    private static final int REFERENCE_TICK = 50;
    private static final int VOLLEY_INTERVAL = 8;
    private static final int VERIFY_TICK = 60;
    private static final int HIGH_SHOTS = 3;
    private static final double RANGE = 2;
    private static final double LOB_ANGLE = 50;
    private static final double MIN_ELEVATION = 45;
    private static final double MAX_SPEED_MULTIPLIER = 1.1;
    private static final double REFERENCE_SPEED_MULTIPLIER = 1.5;
    private static final long ARRIVAL_DELAY_MILLIS = 100;
    private static final long CLIP_START_MILLIS = 1200;
    private static final double MAX_ARRIVAL_SPREAD_TICKS = 3;
    private static final double HEIGHT_TOLERANCE = 0.3;
    private static final double ELEVATION_TOLERANCE = 0.01;
    private static final String CAST_ID = "projectile_convergence";
    private static final long PROJECTION_START_MILLIS = 1000;
    private static final long PROJECTION_DURATION_MILLIS = 3000;
    private static final long PROJECTION_RECOVERY_MILLIS = 4000;
    private static final double PROJECTION_STRENGTH_PEAK = 1.4;
    private static final double VELOCITY_EPSILON = 0.000001;

    @GameTest(maxTicks = DEFAULT_TIMEOUT, skyAccess = true)
    public void lockedPointAndClipOffsetKeepFourVanillaArrowsWithinThreeTicks(GameTestHelper context) {
        // 用短距离留在测试围栏内，时间差和高抛约束保持天地同至的原定义。
        var shots = new HumanProjectileGameTest.Shots(context);
        shots.player.setGameMode(GameType.CREATIVE);
        shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        long startMillis = context.getLevel().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        long startTick = context.getTick();
        Vec3 target = shots.player.getEyePosition().add(0, 0, RANGE);
        lock(shots, startMillis);
        List<Crossing> observed = new ArrayList<>();
        scheduleShots(shots, observed, startMillis, startTick);
        context.onEachTick(() -> {
            // 逐段插值过靶平面的时刻，避免只按整数 tick 掩盖 100 ms 到达差。
            for (Crossing crossing : observed) {
                crossing.sample(context.getTick(), target);
            }
        });
        context.runAtTickTime(startTick + VERIFY_TICK, () -> {
            // 箭与目标选择都由正式宿主完成，验收后清理所有已发射实体和在线玩家。
            try (shots) {
                verify(context, observed, target);
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, skyAccess = true)
    public void lateFirstShotFallsBackWithoutRewindingBody(GameTestHelper context) {
        // 首箭到达宿主时参考平射已经过期，仍确认普通抛射且不向身体请求回溯。
        try (var shots = new HumanProjectileGameTest.Shots(context)) {
            shots.player.setGameMode(GameType.CREATIVE);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            long now = context.getLevel().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
            lock(shots, now);
            LaunchProjectile projectile = highShot().toBuilder()
                    .setMultiplyDrawStrength(true /* multiplyDrawStrength */)
                    .setDrawStrengthArm(DrawStrengthArm.OTHER_ARM)
                    .build();
            EffectIntent template = shots.intent(projectile, Hand.MAIN_HAND);
            var launch = template.getLaunchProjectile().toBuilder()
                    .setCastStartedMillis(0)
                    .setClipStartMillis(projectile.getConverge().getReferenceShotMillis() - 1)
                    .build();
            EffectIntent effect = template.toBuilder()
                    .setCastId(CAST_ID)
                    .setScheduledMillis(0)
                    .setLockTarget(true /* lockTarget */)
                    .setLaunchProjectile(launch)
                    .build();
            long revision = shots.fixture.inspect().getBody().getRevision();
            shots.fixture.submit(effect);
            Vec3 velocity = shots.arrows().getFirst().getDeltaMovement();
            double elevation = Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance()));
            context.assertTrue(Math.abs(elevation - LOB_ANGLE) < ELEVATION_TOLERANCE, "迟到首箭未退为普通抛射");
            context.assertTrue(shots.fixture.inspect().getBody().getRevision() == revision, "迟到首箭改写了身体");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, skyAccess = true)
    public void pendingCurveOnlyProjectsMatchingCastWithoutChangingBody(GameTestHelper context) {
        // 同一未来曲线无论已登记还是待登记都给出相同弹道，其他施放不能借用待登记增益。
        try (var shots = new HumanProjectileGameTest.Shots(context)) {
            shots.player.setGameMode(GameType.CREATIVE);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            BodySnapshot initial = shots.fixture.inspect().getBody();
            long started = initial.getSettledAt().getElapsedMillis();
            TechniqueProgress pending = pendingProjection(initial);
            BodyActivity scheduled = BodyActivity.newBuilder()
                    .putProgress(TechniqueState.KEY, TechniqueState.pack(pending))
                    .build();
            submitProjection(shots, scheduled);
            BodySnapshot beforePrediction = shots.fixture.inspect().getBody();
            Vec3 pendingVelocity = projectionShot(shots, CAST_ID, started);
            Vec3 unrelatedVelocity = projectionShot(shots, CAST_ID + "_other", started);
            context.assertTrue(pendingVelocity.distanceTo(unrelatedVelocity) > VELOCITY_EPSILON,
                    "其他施放借用了待登记曲线，或当前施放漏算待登记曲线");
            context.assertTrue(beforePrediction.equals(shots.fixture.inspect().getBody()), "仰射预估改写了身体");
            HumanoidArm otherArm = HumanProjectileEffects.drawArm(
                    shots.player, InteractionHand.MAIN_HAND, DrawStrengthArm.OTHER_ARM);
            context.assertTrue(VanillaDamageFabric.armCoefficient(shots.player, otherArm) == 1,
                    "待登记曲线提前影响实际拉弦力量");
            registerProjection(shots, pending);
            BodySnapshot beforeRegisteredPrediction = shots.fixture.inspect().getBody();
            Vec3 registeredVelocity = projectionShot(shots, CAST_ID, started);
            context.assertTrue(pendingVelocity.distanceTo(registeredVelocity) < VELOCITY_EPSILON,
                    "待登记曲线与相同已登记曲线的参考平射预测不同");
            context.assertTrue(beforeRegisteredPrediction.equals(shots.fixture.inspect().getBody()),
                    "已登记曲线的仰射预估改写了身体");
        }
        context.succeed();
    }

    private static TechniqueProgress pendingProjection(BodySnapshot body) {
        // 通过正式宿主活动保存可信施放状态，曲线绑定真实人体手臂组织。
        List<String> tissues = body.getStructure().getTissuesList().stream()
                .filter(tissue -> tissue.getTagsList().contains("human:arm_muscle"))
                .map(tissue -> tissue.getId()).toList();
        BoundSlot slot = BoundSlot.newBuilder()
                .setSlotId("arms")
                .setModule(ModuleKind.TISSUE)
                .addAllModuleIds(tissues)
                .build();
        TechniqueBinding binding = TechniqueBinding.newBuilder()
                .setBodyId(body.getId())
                .setRevision(body.getRevision())
                .addSlots(slot)
                .build();
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId("human_test:projection")
                .setVersion(1)
                .build();
        CastRequest request = CastRequest.newBuilder()
                .setBodyId(body.getId())
                .setTechnique(reference)
                .setCastId(CAST_ID)
                .build();
        long started = body.getSettledAt().getElapsedMillis();
        ActiveCast active = ActiveCast.newBuilder()
                .setRequest(request)
                .setBinding(binding)
                .setStartedMillis(started)
                .setWarmupEndMillis(started + PROJECTION_DURATION_MILLIS)
                .setActiveEndMillis(started + PROJECTION_DURATION_MILLIS)
                .setRecoveryEndMillis(started + PROJECTION_RECOVERY_MILLIS)
                .setActionInstanceId("human_projection")
                .setInterruptibleByDamage(true /* interruptibleByDamage */)
                .addAllPendingModifiers(projectionModifiers(tissues, started))
                .build();
        return TechniqueState.empty().toBuilder().setActiveCast(active).build();
    }

    private static List<CapabilityModifier> projectionModifiers(List<String> tissues, long started) {
        // 每块组织使用独立身份；未来参考点落在平顶，当前撒放仍处于曲线起点之前。
        CurvePoint first = CurvePoint.newBuilder()
                .setOffsetMillis(0)
                .setValue(1)
                .build();
        CurvePoint last = CurvePoint.newBuilder()
                .setOffsetMillis(PROJECTION_DURATION_MILLIS)
                .setValue(1)
                .build();
        String encodedCast = HexFormat.of().formatHex(CAST_ID.getBytes(StandardCharsets.US_ASCII));
        var modifiers = new ArrayList<CapabilityModifier>();
        for (int index = 0; index < tissues.size(); index++) {
            CapabilityModifier modifier = CapabilityModifier.newBuilder()
                    .setId("meridian_technique:cast/" + encodedCast + "/" + index)
                    .setSource(TechniqueHost.CALLER_ID)
                    .setTissueId(tissues.get(index))
                    .setStartMillis(started + PROJECTION_START_MILLIS)
                    .addCurve(first)
                    .addCurve(last)
                    .setStrengthPeak(PROJECTION_STRENGTH_PEAK)
                    .setBurstPeak(1)
                    .setEndurancePeak(1)
                    .setRecoveryPeak(1)
                    .build();
            modifiers.add(modifier);
        }
        return List.copyOf(modifiers);
    }

    private static void registerProjection(HumanProjectileGameTest.Shots shots, TechniqueProgress pending) {
        // 对照使用完全相同的已登记未来曲线，并原子清理待登记施放。
        BodyActivity activity = BodyActivity.newBuilder()
                .addAllAddModifiers(pending.getActiveCast().getPendingModifiersList())
                .putProgress(TechniqueState.KEY, TechniqueState.pack(TechniqueState.empty()))
                .build();
        submitProjection(shots, activity);
    }

    private static void submitProjection(HumanProjectileGameTest.Shots shots, BodyActivity activity) {
        // 夹具状态经过正式 Protobuf 入口、版本检查与 Framework 活动提交。
        var request = TechniqueHostRequest.newBuilder()
                .setEntityUuid(shots.player.getStringUUID())
                .setRequestId(UUID.randomUUID().toString())
                .setExpectedRevision(shots.fixture.inspect().getBody().getRevision())
                .setActivity(activity)
                .build();
        byte[] response = new HumanTechniqueHost().exchange(TechniqueProtocol.encodeHost(request));
        var result = TechniqueProtocol.decodeHostResponse(response);
        shots.context.assertTrue(result.hasActivity(), "参考曲线夹具活动被拒绝：" + result);
    }

    private static Vec3 projectionShot(HumanProjectileGameTest.Shots shots, String castId, long started) {
        // 每次使用独立公开执行器和真实原版箭，避免前次到达时间缓存掩盖预测差异。
        shots.removeProjectiles();
        shots.player.setYRot(0);
        LaunchProjectile projectile = highShot().toBuilder()
                .setMultiplyDrawStrength(true /* multiplyDrawStrength */)
                .setDrawStrengthArm(DrawStrengthArm.OTHER_ARM)
                .build();
        EffectIntent template = shots.intent(projectile, Hand.MAIN_HAND);
        var launch = template.getLaunchProjectile().toBuilder()
                .setCastStartedMillis(started)
                .setClipStartMillis(CLIP_START_MILLIS)
                .build();
        EffectIntent shot = template.toBuilder()
                .setCastId(castId)
                .setLockTarget(true /* lockTarget */)
                .setLaunchProjectile(launch)
                .build();
        TargetLockIntent target = TargetLockIntent.newBuilder()
                .setMaximumDistance(quantity(RANGE, Unit.METER))
                .build();
        EffectIntent lock = shot.toBuilder().setTargetLock(target).build();
        var executor = new HumanProjectileEffects(shots.player, entity -> entity != shots.player);
        executor.lockTarget(lock);
        executor.launch(shot);
        return shots.arrows().getFirst().getDeltaMovement();
    }

    private static void lock(HumanProjectileGameTest.Shots shots, long startMillis) {
        // 锁定发生在首箭之前；随后故意转视角，射击仍应使用这一落点。
        TargetLockIntent target = TargetLockIntent.newBuilder()
                .setMaximumDistance(quantity(RANGE, Unit.METER))
                .build();
        EffectIntent lock = EffectIntent.newBuilder()
                .setCastId(CAST_ID)
                .setEffectId("lock_target")
                .setSelectedHand(Hand.MAIN_HAND)
                .setScheduledMillis(startMillis)
                .setLockTarget(true /* lockTarget */)
                .setTargetLock(target)
                .build();
        shots.fixture.submit(lock);
        shots.player.setYRot((float) MIN_ELEVATION);
    }

    private static void scheduleShots(HumanProjectileGameTest.Shots shots, List<Crossing> observed,
                                      long startMillis, long startTick) {
        // 三次仰射与参考平射按同一个身体起点和原剪辑偏移下发。
        LaunchProjectile high = highShot();
        shoot(shots, observed, high, startMillis);
        for (int index = 1; index < HIGH_SHOTS; index++) {
            long releaseTick = startTick + index * VOLLEY_INTERVAL;
            shots.context.runAtTickTime(releaseTick, () -> shoot(shots, observed, high, startMillis));
        }
        LaunchProjectile direct = settings(ProjectileWeaponCategory.BOW)
                .setSpeedMultiplier(quantity(REFERENCE_SPEED_MULTIPLIER, Unit.RATIO))
                .build();
        shots.context.runAtTickTime(startTick + REFERENCE_TICK, () -> shoot(shots, observed, direct, startMillis));
    }

    private static LaunchProjectile highShot() {
        // 明确原剪辑参考点与偏移，两者之差是从本次起手算起的实际平射时刻。
        LobProjectile fallback = LobProjectile.newBuilder()
                .setMaximumDistance(quantity(RANGE, Unit.METER))
                .setRadius(quantity(0, Unit.METER))
                .setElevation(quantity(LOB_ANGLE, Unit.DEGREE))
                .setMaximumSpeedMultiplier(quantity(MAX_SPEED_MULTIPLIER, Unit.RATIO))
                .build();
        ConvergeProjectile converge = ConvergeProjectile.newBuilder()
                .setReferenceShotMillis(REFERENCE_TICK * MILLIS_PER_TICK + CLIP_START_MILLIS)
                .setReferenceSpeedMultiplier(quantity(REFERENCE_SPEED_MULTIPLIER, Unit.RATIO))
                .setArrivalDelayMillis(ARRIVAL_DELAY_MILLIS)
                .setFallback(fallback)
                .setMinimumElevation(quantity(MIN_ELEVATION, Unit.DEGREE))
                .build();
        return settings(ProjectileWeaponCategory.BOW).setConverge(converge).build();
    }

    private static void shoot(HumanProjectileGameTest.Shots shots, List<Crossing> observed,
                              LaunchProjectile projectile, long started) {
        // 按实体身份找这一发，不能把先前还在天空中的箭误认为新发射。
        Set<UUID> previous = new HashSet<>();
        for (Crossing crossing : observed) {
            previous.add(crossing.arrow.getUUID());
        }
        EffectIntent template = shots.intent(projectile, Hand.MAIN_HAND);
        var launch = template.getLaunchProjectile().toBuilder()
                .setCastStartedMillis(started)
                .setClipStartMillis(CLIP_START_MILLIS)
                .build();
        EffectIntent effect = template.toBuilder()
                .setCastId(CAST_ID)
                .setEffectId("shot_" + observed.size())
                .setLockTarget(true /* lockTarget */)
                .setLaunchProjectile(launch)
                .build();
        shots.fixture.submit(effect);
        AbstractArrow arrow = shots.arrows().stream().filter(candidate -> !previous.contains(candidate.getUUID()))
                .findFirst().orElseThrow();
        observed.add(new Crossing(arrow, shots.context.getTick()));
    }

    private static void verify(GameTestHelper context, List<Crossing> observed, Vec3 target) {
        // 四支均须经过同一目标平面，仰射在平射之后且总差不超过 150 ms。
        context.assertTrue(observed.size() == HIGH_SHOTS + 1, "汇聚射击支数不足");
        double earliest = Double.POSITIVE_INFINITY;
        double latest = 0;
        for (Crossing crossing : observed) {
            context.assertTrue(Double.isFinite(crossing.arrival), "原版箭没有飞到锁定落点");
            context.assertTrue(Math.abs(crossing.height - target.y) < HEIGHT_TOLERANCE, "箭到靶时高度不符");
            earliest = Math.min(earliest, crossing.arrival);
            latest = Math.max(latest, crossing.arrival);
        }
        context.assertTrue(latest - earliest <= MAX_ARRIVAL_SPREAD_TICKS, "汇聚实际到达时差超过 150 ms");
        double direct = observed.getLast().arrival;
        for (Crossing crossing : observed.subList(0, HIGH_SHOTS)) {
            context.assertTrue(crossing.arrival > direct, "仰射早于平射到达");
        }
    }

    private static final class Crossing {
        private final AbstractArrow arrow;
        private Vec3 previous;
        private long previousTick;
        private double arrival = Double.NaN;
        private double height;

        private Crossing(AbstractArrow arrow, long tick) {
            // 初始采样从原版实体的真实出生点开始。
            this.arrow = arrow;
            previous = arrow.position();
            previousTick = tick;
        }

        private void sample(long tick, Vec3 target) {
            // 插值只用于观察，绝不修改箭的位置、速度或命中。
            if (Double.isFinite(arrival)) {
                return;
            }
            Vec3 current = arrow.position();
            if (previous.z < target.z && current.z >= target.z) {
                double fraction = (target.z - previous.z) / (current.z - previous.z);
                arrival = previousTick + fraction * (tick - previousTick);
                height = previous.y + fraction * (current.y - previous.y);
            }
            previous = current;
            previousTick = tick;
        }
    }
}
