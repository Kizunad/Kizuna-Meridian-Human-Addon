package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.server.BreathingModifiers;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueBreathingStatuses;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.List;
import java.util.UUID;

/** 用正式登录、功法服务和原版状态回调核对运功的两个方向。 */
public final class HumanBreathingPracticeGameTest {
    private static final String ID = "meridian_human_test:practice";
    private static final String ENVIRONMENT = "meridian_human_test:breathing_practice";
    private static final int AFTER_WARMUP = 5;
    private static final int CONTROL_MILLIS = 200;
    private static final long OFFLINE_MILLIS = 60_000;
    private static final long HOLD_INTERVAL = 1000;
    private static final long MILLIS_PER_SECOND = 1000;
    private static final double EPSILON = 1e-9;

    @GameTest(environment = ENVIRONMENT, maxTicks = HumanGameTestTicks.DEFAULT_TIMEOUT)
    public void guardBreathingAndInjuryShareTheSameBodyClock(GameTestHelper context) {
        // 先走真实学习、起式与举架，再用独立的身体时钟一次越过成架边界。
        var fixture = new HumanTechniqueTestPlayer(context);
        start(context, fixture);
        context.runAfterDelay(AFTER_WARMUP, () -> {
            try (fixture) {
                var guard = HumanGuardTestCatalog.start(fixture.player);
                context.assertTrue(guard.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                        "举架失败");
                var body = new HumanPlayerBody(fixture.body().snapshot(), List.of(),
                        dev.kizuna.meridian.core.QiInventory.empty());
                var progress = body.progress().toBuilder()
                        .setQiNotesRead(true /* read */)
                        .setGatheringScrollRead(true /* read */)
                        .build();
                body.writeProgress(progress);
                checkSharedClock(context, body);
                context.succeed();
            }
        });
    }

    private static void checkSharedClock(GameTestHelper context, HumanPlayerBody body) {
        // 长区间只扣一次持守与运功费用。
        // 同一时刻的吐纳、受伤重放不得再推进任一游标。
        var before = body.snapshot();
        long start = before.settledAt().elapsedMillis();
        long end = TechniqueState.read(before).getActiveCast().getWarmupEndMillis() + HOLD_INTERVAL;
        var practice = TechniqueBreathingPractice.active(before).orElseThrow();
        body.connectGathering(start, response -> { });
        var control = BreathControl.newBuilder()
                .setAction(BreathAction.BEGIN_BREATHING)
                .setAutomatic(true /* automatic */)
                .setSequence(1)
                .build();
        var request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("clock")
                .setBodyId(before.id().value())
                .setBreath(control)
                .build();
        context.assertTrue(body.gather(start, request).getView().getBreathing().getActive(), "吐纳未开始");
        body.advanceGathering(end);
        body.settle(end);
        var paid = body.snapshot();
        context.assertTrue(TechniqueBreathingPractice.active(paid).orElseThrow().getPaidMillis() == end,
                "运功游标错");
        var held = TechniqueState.read(paid).getActiveCast().getSustained();
        context.assertTrue(held.hasStaminaPaidMillis() && held.getStaminaPaidMillis() == end, "持守游标错");
        double fee = practice.getDefinition().getQiPerSecond() * (end - practice.getPaidMillis()) / MILLIS_PER_SECOND;
        context.assertTrue(Math.abs(before.totalQi() - paid.totalQi() - fee) < EPSILON, "运功重复扣费");
        var injury = injury(body);
        context.assertTrue(body.applyHealth(end, injury).hasCommandCommitted(), "受伤未结算");
        body.settle(end);
        var once = body.snapshot();
        context.assertTrue(body.applyHealth(end, injury).hasCommandCommitted(), "同刻伤势不能重放");
        context.assertTrue(once.equals(body.snapshot()) && once.settledAt().elapsedMillis() == end,
                "同刻改变了身体");
    }

    private static RequestEnvelope injury(HumanPlayerBody body) {
        // 伤害入口持有刚读出的身体版本，按同一事件身份重试。
        var target = HealthTarget.newBuilder().setPartId("head").build();
        var hit = DamageBody.newBuilder()
                .setSourceId("sample:clock")
                .setDamageType("minecraft:arrow")
                .setKind(DamageKind.PIERCING)
                .setDamageHp(1)
                .setHit(target)
                .build();
        var before = body.snapshot();
        var command = BodyCommand.newBuilder()
                .setBodyId(before.id().value())
                .setExpectedRevision(before.revision())
                .setDamageBody(hit)
                .build();
        return RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("practice-clock-injury")
                .setBodyCommand(command)
                .build();
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = HumanGameTestTicks.DEFAULT_TIMEOUT)
    public void loginClearsASavedIconWithoutPractice(GameTestHelper context) {
        // 模拟原版先还原了无限图标，身体文件里却没有对应运功。
        var effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(ID)).orElseThrow();
        try (var fixture = new HumanTechniqueTestPlayer(context, UUID.randomUUID(), (server, level, profile, info) -> {
            var player = new ServerPlayer(server, level, profile, info);
            var restored = new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION);
            player.getActiveEffectsMap().put(effect, restored);
            context.assertTrue(player.hasEffect(effect), "登录前未带旧图标");
            return player;
        })) {
            assertEnded(context, fixture);
            context.succeed();
        }
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = HumanGameTestTicks.DEFAULT_TIMEOUT)
    public void savedPracticeCannotChargeOfflineAndDisconnectEndsTheOnlineOriginal(GameTestHelper context) {
        // 同一有效存档分成正常断线和异常恢复两条路径，两者都必须收掉运功。
        var fixture = new HumanTechniqueTestPlayer(context);
        start(context, fixture);
        context.runAfterDelay(AFTER_WARMUP, () -> {
            var original = fixture.body();
            try (fixture) {
                original.settle(HumanTechniqueHost.nowMillis());
                context.assertTrue(TechniqueBreathingPractice.active(original.snapshot()).isPresent(),
                        "未存下运功");
                var saved = fixture.state().save(new CompoundTag());
                var restored = HumanWorldState.read(saved).find(fixture.player.getUUID());
                var before = restored.snapshot();
                restored.resume(before.settledAt().elapsedMillis() + OFFLINE_MILLIS);
                var after = restored.snapshot();
                context.assertTrue(Math.abs(before.totalQi() - after.totalQi()) <= EPSILON, "离线期间扣了元");
                context.assertTrue(TechniqueBreathingPractice.active(after).isEmpty(), "重登后仍在运功");
                context.assertTrue(BreathingModifiers.NORMAL.equals(BreathingModifiers.read(after)),
                        "重登后残留因子");
            }
            context.assertTrue(TechniqueBreathingPractice.active(original.snapshot()).isEmpty(),
                    "断线后仍在运功");
            context.succeed();
        });
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = HumanGameTestTicks.DEFAULT_TIMEOUT)
    public void travelEndsPractice(GameTestHelper context) {
        // 走原版传送入口，在维度切换回调返回时检查图标和身体。
        var fixture = new HumanTechniqueTestPlayer(context);
        start(context, fixture);
        context.runAfterDelay(AFTER_WARMUP, () -> {
            try (fixture) {
                var player = fixture.player;
                context.assertTrue(TechniqueBreathingPractice.active(fixture.body().snapshot()).isPresent(),
                        "未起式");
                var destination = player.level().getServer().getLevel(Level.NETHER);
                player.teleportTo(destination, player.getX(), player.getY(), player.getZ(), Set.of(),
                        player.getYRot(), player.getXRot(), true /* resetCamera */);
                assertEnded(context, fixture);
                context.succeed();
            }
        });
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = HumanGameTestTicks.DEFAULT_TIMEOUT)
    public void deathEndsPractice(GameTestHelper context) {
        // 原版确认死亡后清除运功，不等复活或下次呼吸。
        var fixture = new HumanTechniqueTestPlayer(context);
        start(context, fixture);
        context.runAfterDelay(AFTER_WARMUP, () -> {
            try (fixture) {
                var player = fixture.player;
                context.assertTrue(TechniqueBreathingPractice.active(fixture.body().snapshot()).isPresent(),
                        "未起式");
                player.die(player.damageSources().genericKill());
                assertEnded(context, fixture);
                context.succeed();
            }
        });
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = HumanGameTestTicks.DEFAULT_TIMEOUT)
    public void learnedWarmupReleasesForegroundAndClearingTheEffectEndsPractice(GameTestHelper context) {
        // 同一次登录先验未学拒绝，再让原版清掉已经由身体挂上的无限效果。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            var player = fixture.player;
            var effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(ID)).orElseThrow();
            context.assertFalse(player.addEffect(new MobEffectInstance(effect)), "外部授效绕过了起式");
            var unknown = HumanGuardTestCatalog.start(player, ID);
            context.assertTrue(unknown.getFailure() == TechniqueFailure.NOT_KNOWN, "未学起式没有被拒");
            start(context, fixture);
            context.runAfterDelay(AFTER_WARMUP, () -> {
                try (fixture) {
                    var body = fixture.body();
                    var instance = player.getEffect(effect);
                    context.assertTrue(instance != null && instance.isInfiniteDuration() && instance.showIcon(),
                            "起式结束没有无限可见图标");
                    context.assertFalse(TechniqueState.read(body.snapshot()).hasActiveCast(), "起式仍占前台");
                    var active = TechniqueBreathingPractice.active(body.snapshot()).orElseThrow();
                    TechniqueBreathingStatuses.end(player, ID, "earlier-cast");
                    context.assertTrue(player.hasEffect(effect), "迟到的收势关掉了新运功");
                    var guard = HumanGuardTestCatalog.start(player);
                    context.assertTrue(guard.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                            "运功阻止了其他式起手");
                    context.assertTrue(player.removeAllEffects(), "原版未清除图标");
                    assertEnded(context, fixture);
                    TechniqueBreathingStatuses.end(player, ID, active.getCastId());
                    assertEnded(context, fixture);
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = HumanGameTestTicks.DEFAULT_TIMEOUT)
    public void hardControlEndsMaintainedBreathingImmediately(GameTestHelper context) {
        // 硬控与普通受击区分；即使前台已空，也要同步收掉后台的功法。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            start(context, fixture);
            context.runAfterDelay(AFTER_WARMUP, () -> {
                try (fixture) {
                    var service = TechniqueFabric.service();
                    var uuid = fixture.player.getUUID();
                    var body = fixture.body();
                    context.assertTrue(TechniqueBreathingPractice.active(body.snapshot()).isPresent(),
                            "未进入运功");
                    var stagger = new TechniqueService.Stagger(CONTROL_MILLIS, 0, TechniqueService.StaggerKind.HOLD);
                    service.stagger(uuid, stagger);
                    assertEnded(context, fixture);
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void start(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 新生未开脉的正常身体只经公开学习与起式入口。
        var player = fixture.player;
        TechniqueFabric.service().learn(player.getUUID(), ID);
        var started = HumanGuardTestCatalog.start(player, ID);
        context.assertTrue(started.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "未开脉人体起式被拒：" + started);
        context.assertTrue(TechniqueBreathingPractice.active(fixture.body().snapshot()).isEmpty(),
                "起手尚未结束已受益");
    }

    private static void assertEnded(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 在同一次游戏回调内读取身体和原版效果，不等下一 tick。
        var body = fixture.body().snapshot();
        var effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(ID)).orElseThrow();
        context.assertTrue(TechniqueBreathingPractice.active(body).isEmpty(), "收功后仍会计费");
        context.assertTrue(BreathingModifiers.NORMAL.equals(BreathingModifiers.read(body)),
                "收功后仍有呼吸收益");
        context.assertFalse(fixture.player.hasEffect(effect), "收功后残留图标");
    }
}
