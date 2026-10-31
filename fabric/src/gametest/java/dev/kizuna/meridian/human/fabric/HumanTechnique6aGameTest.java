package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueScheduler;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 真实玩家的双手事实、移动属性、表现意图及未认领恢复回归。 */
public final class HumanTechnique6aGameTest {
    private static final long MILLIS_PER_TICK = 50;
    private static final int FINISH_TICKS = 10;
    private static final double EPSILON = 1e-7;
    private static final long STAGGER_MILLIS = 200;
    private static final double STAGGER_MOVEMENT = 0.5;
    private static final double SPEED_PER_TICK = 0.5;
    private static final double TARGET_HEIGHT_DIFFERENCE = 4;
    private static final double TARGET_HORIZONTAL_DISTANCE = 3;
    private static final String CAST_ID = "locked_cast";
    private static final Map<UUID, Presentations> observations = new HashMap<>();

    static {
        HumanTechniquePresentation.EVENT.register((player, requestId, effect) -> {
            // 只记录当前测试玩家，关闭夹具后不保留实体或身体引用。
            Presentations observer = observations.get(player.getUUID());
            if (observer != null) {
                observer.accept(effect);
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void inspectIncludesEmptyHandsOffhandAndContainerContents(GameTestHelper context) {
        // 事实经过正式字节入口，同时核对空消息、物品标签及显式布尔字段。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            HeldItems empty = inspect(fixture).getHeldItems();
            context.assertTrue(empty.hasMainHand() && empty.hasOffHand(), "空手消息缺失");
            context.assertTrue(empty.getMainHand().equals(HeldItem.getDefaultInstance())
                    && empty.getOffHand().equals(HeldItem.getDefaultInstance()), "空手带有物品事实");
            context.assertTrue(empty.getMainHandSide() == HandSide.RIGHT, "主手侧错误");
            var sword = new ItemStack(Items.IRON_SWORD);
            sword.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
            fixture.player.setItemInHand(InteractionHand.OFF_HAND, sword);
            HeldItem off = inspect(fixture).getHeldItems().getOffHand();
            context.assertTrue(off.getItemId().equals("minecraft:iron_sword")
                    && off.getTagsList().contains("minecraft:swords"), "副手 ID 或标签缺失");
            context.assertTrue(off.hasDamageable() && off.getDamageable()
                    && off.hasContainsItems() && !off.getContainsItems(), "副手耐久或装物事实错误");
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, container(Items.SHULKER_BOX));
            context.assertTrue(inspect(fixture).getHeldItems().getMainHand().getContainsItems(),
                    "潜影盒装物未识别");
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, container(Items.BUNDLE));
            context.assertTrue(inspect(fixture).getHeldItems().getMainHand().getContainsItems(),
                    "收纳袋装物未识别");
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHULKER_BOX));
            HeldItem box = inspect(fixture).getHeldItems().getMainHand();
            context.assertTrue(box.hasContainsItems() && !box.getContainsItems()
                    && box.hasDamageable() && !box.getDamageable(), "空容器事实错误");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void castSlowsSynchronizesAllowsMovementAndImpulseThenFinishes(GameTestHelper context) {
        // 起手立即同步限速，主动移动与冲量仍可执行，收势恢复属性并停止表现。
        var fixture = prepared(context);
        var observer = new Presentations(fixture);
        double originalSpeed = speed(fixture);
        try {
            fixture.connection.packets().clear();
            start(context, fixture);
            assertSlow(context, fixture, originalSpeed);
            context.assertTrue(attributePackets(fixture) > 0, "限速未同步本人属性");
            Vec3 before = fixture.player.position();
            fixture.player.setSpeed((float) speed(fixture));
            fixture.player.travel(new Vec3(0, 0, 1));
            context.assertTrue(fixture.player.position().distanceToSqr(before) > EPSILON,
                    "限速期间主动移动没有产生位移");
            observer.assertAction(context, PresentationAction.START);
            context.assertTrue(observer.events.get(0).effect().getPresentation().getStartMillis()
                    == HumanTechniqueTestCatalog.PRESENTATION_START_MILLIS, "起播时刻丢失");
            submitImpulse(context, fixture, ImpulseDirection.BACKWARD);
            assertSlow(context, fixture, originalSpeed);
            context.assertTrue(fixture.player.hurtMarked, "冲量未同步");
        } catch (RuntimeException failure) {
            observer.close();
            fixture.close();
            throw failure;
        }
        context.runAfterDelay(FINISH_TICKS, () -> {
            try (fixture; observer) {
                context.assertTrue(Math.abs(speed(fixture) - originalSpeed) < EPSILON, "收势未恢复移速");
                context.assertTrue(attributePackets(fixture) >= 2, "解锁未同步本人属性");
                context.assertTrue(!TechniqueState.read(fixture.body().snapshot()).hasActiveCast(),
                        "收势进度残留");
                observer.assertAction(context, PresentationAction.STOP);
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void staggerAndDisconnectUnlockAndStop(GameTestHelper context) {
        // 僵直打断和正式断线回调都在宿主撤销认领前完成实体清理；打断后只剩僵直减速。
        try (var fixture = prepared(context); var observer = new Presentations(fixture)) {
            double originalSpeed = speed(fixture);
            start(context, fixture);
            var stagger = new TechniqueService.Stagger(STAGGER_MILLIS, STAGGER_MOVEMENT);
            TechniqueFabric.service().stagger(fixture.player.getUUID(), stagger);
            double staggeredSpeed = originalSpeed * STAGGER_MOVEMENT;
            context.assertTrue(Math.abs(speed(fixture) - staggeredSpeed) < EPSILON, "打断未解锁或僵直未减速");
            observer.assertAction(context, PresentationAction.STOP);
        }
        try (var fixture = prepared(context); var observer = new Presentations(fixture)) {
            double originalSpeed = speed(fixture);
            start(context, fixture);
            fixture.close();
            context.assertTrue(Math.abs(speed(fixture) - originalSpeed) < EPSILON, "断线未解锁");
            observer.assertAction(context, PresentationAction.STOP);
            context.assertTrue(!TechniqueState.read(fixture.body().snapshot()).hasActiveCast(), "断线残留施放");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void backwardAndZeroInputDefaultWorkWhileSlowed(GameTestHelper context) {
        // 零输入默认向后，显式向后与非零世界输入分别核对，限速不缩放冲量。
        try (var fixture = prepared(context)) {
            double originalSpeed = speed(fixture);
            start(context, fixture);
            submitImpulse(context, fixture, ImpulseDirection.INPUT);
            submitImpulse(context, fixture, ImpulseDirection.BACKWARD);
            var direction = HorizontalDirection.newBuilder().setX(1).build();
            EffectIntent input = impulse(ImpulseDirection.INPUT).toBuilder()
                    .setEffectId("input_nonzero")
                    .setDirection(direction)
                    .build();
            context.assertTrue(exchange(fixture, "input_nonzero", input).getEffectAccepted(), "非零输入被拒绝");
            context.assertTrue(fixture.player.getDeltaMovement().distanceTo(new Vec3(SPEED_PER_TICK, 0, 0))
                    < EPSILON, "输入被朝向替代");
            assertSlow(context, fixture, originalSpeed);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void orthogonalImpulsesCombineInEitherOrderAndRetryIsIdempotent(GameTestHelper context) {
        // 同刻水平与竖直冲量各自保留正交分量，重放成功效果不能改写外部速度。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            var direction = HorizontalDirection.newBuilder().setX(1).build();
            var input = impulse(ImpulseDirection.INPUT).toBuilder()
                    .setCastId("orthogonal_forward")
                    .setEffectId("horizontal")
                    .setDirection(direction)
                    .build();
            var up = impulse(ImpulseDirection.UP).toBuilder()
                    .setCastId("orthogonal_forward")
                    .setEffectId("vertical")
                    .build();
            context.assertTrue(exchange(fixture, "orthogonal_horizontal", input).getEffectAccepted(),
                    "水平冲量提交失败");
            context.assertTrue(exchange(fixture, "orthogonal_vertical", up).getEffectAccepted(),
                    "竖直冲量提交失败");
            assertVelocity(context, fixture, new Vec3(SPEED_PER_TICK, SPEED_PER_TICK, 0));

            fixture.player.setDeltaMovement(Vec3.ZERO);
            var reverseUp = up.toBuilder()
                    .setCastId("orthogonal_reverse")
                    .setEffectId("vertical")
                    .build();
            var reverseInput = input.toBuilder()
                    .setCastId("orthogonal_reverse")
                    .setEffectId("horizontal")
                    .build();
            context.assertTrue(exchange(fixture, "reverse_vertical", reverseUp).getEffectAccepted(),
                    "反向竖直冲量提交失败");
            context.assertTrue(exchange(fixture, "reverse_horizontal", reverseInput).getEffectAccepted(),
                    "反向水平冲量提交失败");
            assertVelocity(context, fixture, new Vec3(SPEED_PER_TICK, SPEED_PER_TICK, 0));

            fixture.player.setDeltaMovement(Vec3.ZERO);
            context.assertTrue(exchange(fixture, "reverse_horizontal", reverseInput).getEffectAccepted(),
                    "重试未确认");
            assertVelocity(context, fixture, Vec3.ZERO);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void elevatedTargetKeepsFullHorizontalImpulseAndVerticalSpeed(GameTestHelper context) {
        // 错层目标只决定水平方向，不能把声明速度耗在随后被丢弃的竖直分量上。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            var target = context.spawn(EntityTypes.COW, 1, 1, 1);
            try {
                Vec3 position = fixture.player.position();
                target.setPos(position.add(0, TARGET_HEIGHT_DIFFERENCE, TARGET_HORIZONTAL_DISTANCE));
                fixture.player.setDeltaMovement(0, SPEED_PER_TICK, 0);
                var toward = impulse(ImpulseDirection.TOWARD_TARGET).toBuilder()
                        .setTargetId(target.getStringUUID())
                        .build();
                context.assertTrue(exchange(fixture, "elevated_target", toward).getEffectAccepted(),
                        "错层目标冲量被拒绝");
                assertVelocity(context, fixture, new Vec3(0, SPEED_PER_TICK, SPEED_PER_TICK));
                target.setPos(position.add(0, TARGET_HEIGHT_DIFFERENCE, 0));
                var overhead = toward.toBuilder().setEffectId("overhead").build();
                context.assertTrue(exchange(fixture, "overhead", overhead).getFailure().getCode()
                        == FailureCode.INVALID_REQUEST, "纯竖直目标没有拒绝零水平向量");
            } finally {
                target.discard();
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void movementIdentityAndPresentationRetriesAreIdempotent(GameTestHelper context) {
        // 多个施放的属性身份独立，效果身份去重覆盖不同请求号，冲突保留原始失败码。
        try (var fixture = new HumanTechniqueTestPlayer(context); var observer = new Presentations(fixture)) {
            double originalSpeed = speed(fixture);
            EffectIntent firstLock = movement("first", MovementAction.LOCK);
            EffectIntent secondLock = movement("second", MovementAction.LOCK);
            context.assertTrue(exchange(fixture, "first_lock", firstLock).getEffectAccepted(), "第一把锁失败");
            context.assertTrue(exchange(fixture, "second_lock", secondLock).getEffectAccepted(), "第二把锁失败");
            exchange(fixture, "first_unlock", movement("first", MovementAction.UNLOCK));
            assertSlow(context, fixture, originalSpeed);
            exchange(fixture, "second_unlock", movement("second", MovementAction.UNLOCK));
            context.assertTrue(Math.abs(speed(fixture) - originalSpeed) < EPSILON, "未恢复移速");
            EffectIntent once = presentation(PresentationAction.PLAY_ONCE);
            exchange(fixture, "once", once);
            exchange(fixture, "once", once);
            exchange(fixture, "once_alias", once);
            context.assertTrue(observer.events.size() == 1, "表现重复执行");
            context.assertTrue(observer.events.get(0).effect().equals(once), "表现意图未完整传递");
            var changed = once.toBuilder().setTargetId(UUID.randomUUID().toString()).build();
            context.assertTrue(exchange(fixture, "once_alias", changed).getFailure().getCode()
                    == FailureCode.REQUEST_ID_CONFLICT, "身份内容冲突被接受");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void startCallbackCancelsAfterDeliveryWithoutRecursion(GameTestHelper context) {
        // 同步 START 取消必须先退出回调，再交付唯一 STOP 和解锁。
        try (var fixture = prepared(context); var observer = new Presentations(fixture)) {
            double originalSpeed = speed(fixture);
            observer.callback = effect -> {
                if (effect.getPresentation().getAction() == PresentationAction.START) {
                    context.assertTrue(observer.events.size() == 1, "START 同步递归");
                    TechniqueFabric.service().cancelOnUnload(fixture.player.getUUID());
                    context.assertTrue(TechniqueState.read(fixture.body().snapshot()).hasActiveCast(),
                            "取消在 START 回调退出前执行");
                }
            };
            start(context, fixture);
            context.assertTrue(observer.events.size() == 2, "START 或 STOP 重复、缺失");
            observer.assertAction(context, PresentationAction.STOP);
            context.assertTrue(!TechniqueState.read(fixture.body().snapshot()).hasActiveCast(),
                    "回调取消残留施放");
            context.assertTrue(Math.abs(speed(fixture) - originalSpeed) < EPSILON, "回调取消未解锁");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void synchronousResubmissionDistinguishesExecutingFromSucceeded(GameTestHelper context) {
        // 执行中不返回成功，冲突仍拒绝；外层失败后同身份必须可以重新执行。
        try (var fixture = new HumanTechniqueTestPlayer(context); var observer = new Presentations(fixture)) {
            EffectIntent effect = presentation(PresentationAction.PLAY_ONCE);
            observer.callback = submitted -> {
                context.assertTrue(observer.events.size() == 1, "同身份同步重入了副作用");
                context.assertTrue(exchange(fixture, "reentrant", submitted).getFailure().getCode()
                        == FailureCode.INTERNAL_ERROR, "执行中请求被提前确认");
                context.assertTrue(exchange(fixture, "reentrant_alias", submitted).getFailure().getCode()
                        == FailureCode.INTERNAL_ERROR, "执行中效果别名被提前确认");
                EffectIntent changed = submitted.toBuilder().setTargetId(UUID.randomUUID().toString()).build();
                context.assertTrue(exchange(fixture, "reentrant", changed).getFailure().getCode()
                        == FailureCode.REQUEST_ID_CONFLICT, "执行中请求冲突未拒绝");
                context.assertTrue(exchange(fixture, "reentrant_alias", changed).getFailure().getCode()
                        == FailureCode.REQUEST_ID_CONFLICT, "执行中效果冲突未拒绝");
                throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
            };
            context.assertTrue(exchange(fixture, "reentrant", effect).getFailure().getCode()
                    == FailureCode.MODULE_UNAVAILABLE, "外层失败被吞掉");
            observer.callback = submitted -> { /* 允许失败后的同身份重试。 */ };
            context.assertTrue(exchange(fixture, "reentrant_alias", effect).getEffectAccepted(),
                    "失败留下成功回执");
            context.assertTrue(observer.events.size() == 2, "失败后没有重新执行");
            context.assertTrue(exchange(fixture, "reentrant", effect).getEffectAccepted(), "成功回执未复用");
            context.assertTrue(observer.events.size() == 2, "已成功效果重复执行");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void disappearedTargetCancelsAndUnlocksRealPlayer(GameTestHelper context) {
        // 起手后销毁真实目标，冲量拒绝仍须完成身体、移动属性和表现清理。
        var fixture = prepared(context, HumanTechniqueTestCatalog.TARGETED_TECHNIQUE_ID);
        var observer = new Presentations(fixture);
        double originalSpeed = speed(fixture);
        var target = context.spawn(EntityTypes.COW, 1, 2, 1);
        start(context, fixture, HumanTechniqueTestCatalog.TARGETED_TECHNIQUE_ID, target.getStringUUID());
        target.discard();
        context.runAfterDelay(FINISH_TICKS, () -> {
            try (fixture; observer) {
                context.assertTrue(!TechniqueState.read(fixture.body().snapshot()).hasActiveCast(),
                        "目标消失残留施放");
                context.assertTrue(TechniqueState.read(fixture.body().snapshot()).getLastCast().getEnding()
                        == CastPhase.CANCELLED, "目标消失没有取消");
                context.assertTrue(Math.abs(speed(fixture) - originalSpeed) < EPSILON, "目标消失未解锁");
                observer.assertAction(context, PresentationAction.STOP);
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unclaimedRecoveryRetriesStopBeforeClearingProgress(GameTestHelper context) {
        // 模拟无断线回调的残留，未认领时失败不清进度，重试先清实体再提交身体。
        try (var fixture = prepared(context); var observer = new Presentations(fixture)) {
            double originalSpeed = speed(fixture);
            start(context, fixture);
            UUID playerId = fixture.player.getUUID();
            HumanPlayerBody body = fixture.body();
            HumanTechniqueHost.disconnect(playerId);
            context.assertTrue(inspect(fixture).getUnbound(), "恢复前仍被认领");
            var recovery = HumanTechniqueHost.recovery(fixture.player, body);
            List<EffectIntent> cleanup = cleanup(fixture);
            observer.rejectStops();
            try {
                TechniqueFabric.service().recoverOnLoad(playerId, recovery);
                context.fail("清理失败被当作成功");
            } catch (TechniqueErrors.HostRejected rejected) {
                context.assertTrue(rejected.failure() == FailureCode.MODULE_UNAVAILABLE, "失败码被改写");
            }
            context.assertTrue(TechniqueState.read(body.snapshot()).hasActiveCast()
                    && speed(fixture) > 0 && speed(fixture) < originalSpeed, "失败清除了进度或限速");
            observer.allowStops();
            TechniqueFabric.service().recoverOnLoad(playerId, recovery);
            context.assertTrue(!TechniqueState.read(body.snapshot()).hasActiveCast(), "恢复未清进度");
            context.assertTrue(Math.abs(speed(fixture) - originalSpeed) < EPSILON, "恢复未解锁");
            Observed stop = observer.last();
            context.assertTrue(TechniqueState.read(stop.body()).hasActiveCast(), "进度清理早于实体清理");
            int applied = observer.events.size();
            TechniqueFabric.service().recoverOnLoad(playerId, recovery);
            replayCleanup(context, recovery, cleanup);
            context.assertTrue(observer.events.size() == applied, "恢复重复停止表现");
            HumanTechniqueHost.reconnect(fixture.player);
            context.assertTrue(inspect(fixture).hasBody(), "清理后未重新认领");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void deadRecoveryCleansEntityAndRejectsStartOrImpulse(GameTestHelper context) {
        // 死体进度通过可信入口清除，实体适配器仍执行停止与解锁，禁止恢复开始效果。
        try (var fixture = prepared(context); var observer = new Presentations(fixture)) {
            double originalSpeed = speed(fixture);
            start(context, fixture);
            killBody(context, fixture);
            UUID playerId = fixture.player.getUUID();
            HumanPlayerBody body = fixture.body();
            HumanTechniqueHost.disconnect(playerId);
            var recovery = HumanTechniqueHost.recovery(fixture.player, body);
            List<EffectIntent> cleanup = cleanup(fixture);
            assertRecoveryDenied(context, recovery, presentation(PresentationAction.START));
            assertRecoveryDenied(context, recovery, impulse(ImpulseDirection.BACKWARD));
            TechniqueFabric.service().recoverOnLoad(playerId, recovery);
            BodySnapshot recovered = body.snapshot();
            context.assertTrue(recovered.vitality().dead(), "恢复复活了身体");
            context.assertTrue(!TechniqueState.read(recovered).hasActiveCast(), "死体残留施放");
            context.assertTrue(Math.abs(speed(fixture) - originalSpeed) < EPSILON, "死体恢复未解锁");
            observer.assertAction(context, PresentationAction.STOP);
            replayCleanup(context, recovery, cleanup);
        }
        context.succeed();
    }

    private static List<EffectIntent> cleanup(HumanTechniqueTestPlayer fixture) {
        // 重放库实际生成的完整清理意图，保留原始身份和调度时刻。
        var body = fixture.body().snapshot();
        long now = body.settledAt().elapsedMillis();
        return TechniqueScheduler.stopEffects(TechniqueState.read(body).getActiveCast(), now, now);
    }

    private static void replayCleanup(GameTestHelper context, HumanTechniqueRecovery recovery,
                                      List<EffectIntent> cleanup) {
        // 同请求和新请求号都重试同一效果身份，仍应回显原请求且不重做清理。
        for (EffectIntent effect : cleanup) {
            String requestId = "cleanup_alias_" + effect.getEffectId();
            var submission = new TechniqueHost.EffectSubmission(requestId, effect);
            context.assertTrue(recovery.submitEffect(submission).requestId().equals(requestId), "回执身份错误");
            recovery.submitEffect(submission);
        }
    }

    private static void assertRecoveryDenied(GameTestHelper context, HumanTechniqueRecovery recovery,
                                              EffectIntent effect) {
        // 恢复权限校验在执行器前拒绝离线副作用，原业务失败码直接传播。
        try {
            recovery.submitEffect(new TechniqueHost.EffectSubmission("forbidden", effect));
            context.fail("恢复接受了开始效果");
        } catch (TechniqueErrors.HostRejected rejected) {
            context.assertTrue(rejected.failure() == FailureCode.ACCESS_DENIED, "恢复拒绝码错误");
        }
    }

    private static ItemStack container(Item item) {
        // 使用原版库存组件，覆盖潜影盒（容器组件）和收纳袋（收纳袋组件）各自的存储位置。
        var stack = new ItemStack(item);
        if (item == Items.SHULKER_BOX) {
            stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
        } else {
            stack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStackTemplate(Items.STONE))));
        }
        return stack;
    }

    private static HumanTechniqueTestPlayer prepared(GameTestHelper context) {
        // 只通过真实身体开脉和管理命令学习，不伪造进度或免除费用。
        return prepared(context, HumanTechniqueTestCatalog.LOCKED_TECHNIQUE_ID);
    }

    private static HumanTechniqueTestPlayer prepared(GameTestHelper context, String techniqueId) {
        // 针对不同拒绝路径选择目录中的测试式，身体初始化保持一致。
        var fixture = new HumanTechniqueTestPlayer(context);
        var server = fixture.player.level().getServer();
        long now = server.overworld().getGameTime() * MILLIS_PER_TICK;
        context.assertTrue(fixture.body().completeOpening(now, "left_large_intestine_meridian")
                == FailureCode.FAILURE_CODE_UNSPECIFIED, "开脉失败");
        int learned = HumanTestCommands.run(server.createCommandSourceStack(),
                "meridian technique learn " + fixture.player.getStringUUID() + " "
                        + techniqueId);
        context.assertTrue(learned == 1, "学习失败");
        return fixture;
    }

    private static void start(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 无方向输入由目录声明回到向后，起手锁与表现使用实际服务调度。
        start(context, fixture, HumanTechniqueTestCatalog.LOCKED_TECHNIQUE_ID, "");
    }

    private static void start(GameTestHelper context, HumanTechniqueTestPlayer fixture, String techniqueId,
                              String targetId) {
        // 目标由真实服务器实体身份提供，施放仍走公开服务。
        var reference = TechniqueReference.newBuilder()
                .setId(techniqueId)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(fixture.player.getStringUUID())
                .setTechnique(reference)
                .setCastId(CAST_ID)
                .setTargetId(targetId)
                .build();
        var outcome = TechniqueFabric.service().cast(fixture.player.getUUID(), request);
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "起手失败：" + outcome);
    }

    private static void submitImpulse(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                      ImpulseDirection direction) {
        // 当前服务端朝向决定后方，不受移动属性锁影响。
        EffectIntent effect = impulse(direction);
        context.assertTrue(exchange(fixture, "impulse_" + direction, effect).getEffectAccepted(), "冲量被拒绝");
        Vec3 expected = Vec3.directionFromRotation(0, fixture.player.getYRot()).scale(-SPEED_PER_TICK);
        context.assertTrue(fixture.player.getDeltaMovement().distanceTo(expected) < EPSILON, "无输入未向后");
    }

    private static EffectIntent impulse(ImpulseDirection direction) {
        // 输入式显式声明向后默认值，其他方向不携带该字段。
        var impulse = Impulse.newBuilder()
                .setDirection(direction)
                .setSpeed(HumanTechniqueTestCatalog.quantity(
                        HumanTechniqueTestCatalog.SPEED_METERS_PER_SECOND, Unit.METERS_PER_SECOND));
        if (direction == ImpulseDirection.INPUT) {
            impulse.setInputDefaultDirection(ImpulseDirection.BACKWARD);
        }
        return EffectIntent.newBuilder()
                .setCastId(CAST_ID)
                .setEffectId("impulse_" + direction.name().toLowerCase(Locale.ROOT))
                .setImpulse(impulse.build())
                .build();
    }

    private static EffectIntent movement(String castId, MovementAction action) {
        // 测试使用与库相同的施放内稳定移动身份。
        var movement = MovementIntent.newBuilder().setAction(action);
        if (action == MovementAction.LOCK) {
            movement.setMovementSpeedMultiplier(HumanTechniqueTestCatalog.MOVEMENT_MULTIPLIER);
        }
        return EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId("movement_" + action.name().toLowerCase(Locale.ROOT))
                .setMovement(movement)
                .build();
    }

    private static EffectIntent presentation(PresentationAction action) {
        // 一次性和持续表现都传递原始剪辑起播时刻。
        var presentation = PresentationIntent.newBuilder()
                .setId("human_test:animation")
                .setAction(action)
                .setStartMillis(HumanTechniqueTestCatalog.PRESENTATION_START_MILLIS)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(CAST_ID)
                .setEffectId("presentation")
                .setPresentation(presentation)
                .build();
    }

    private static TechniqueHostResponse inspect(HumanTechniqueTestPlayer fixture) {
        // 从正式协议读取身体与同次采集的手部事实。
        var request = TechniqueHostRequest.newBuilder()
                .setEntityUuid(fixture.player.getStringUUID())
                .setRequestId("inspect_hands")
                .setInspect(true /* inspect */)
                .build();
        return TechniqueProtocol.decodeHostResponse(new HumanTechniqueHost().exchange(
                TechniqueProtocol.encodeHost(request)));
    }

    private static TechniqueHostResponse exchange(HumanTechniqueTestPlayer fixture, String id, EffectIntent effect) {
        // 效果也经过正式认证、协议校验和结构化失败回执。
        var request = TechniqueHostRequest.newBuilder()
                .setEntityUuid(fixture.player.getStringUUID())
                .setRequestId(id)
                .setEffect(effect)
                .build();
        return TechniqueProtocol.decodeHostResponse(new HumanTechniqueHost().exchange(
                TechniqueProtocol.encodeHost(request)));
    }

    private static double speed(HumanTechniqueTestPlayer fixture) {
        // 读取原版实际属性值，不能用宿主内存标志替代。
        return fixture.player.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    private static void assertSlow(GameTestHelper context, HumanTechniqueTestPlayer fixture, double originalSpeed) {
        // 检查实际原版移动属性仍为正数，并精确等于施放前速度的目录倍率。
        double expected = originalSpeed * HumanTechniqueTestCatalog.MOVEMENT_MULTIPLIER;
        double actual = speed(fixture);
        context.assertTrue(actual > 0 && Math.abs(actual - expected) < EPSILON, "施放未保持 0.4 倍移速");
    }

    private static void assertVelocity(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                       Vec3 expected) {
        // 直接读取实体速度，区分同刻分量合成与重复效果重写。
        context.assertTrue(fixture.player.getDeltaMovement().distanceTo(expected) < EPSILON,
                "实体速度不符合预期：" + fixture.player.getDeltaMovement());
    }

    private static long attributePackets(HumanTechniqueTestPlayer fixture) {
        // 连接记录真实属性同步包，旁观者同步继续由原版实体跟踪器负责。
        return fixture.connection.packets().stream()
                .filter(ClientboundUpdateAttributesPacket.class::isInstance).count();
    }

    private static void killBody(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 直接提交 Framework 死亡形成死体残留，不经提前取消的健康宿主路径。
        var body = fixture.body();
        String subjectId = fixture.player.getStringUUID();
        var death = ConfirmBodyDeath.newBuilder().setSourceId("human_test:death").build();
        var command = BodyCommand.newBuilder()
                .setBodyId(subjectId)
                .setExpectedRevision(body.snapshot().revision())
                .setConfirmBodyDeath(death)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId("death_" + subjectId)
                .setBodyCommand(command)
                .build();
        long now = fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        context.assertTrue(body.applyHealth(now, request).hasCommandCommitted(), "死体准备失败");
    }

    private record Observed(EffectIntent effect, BodySnapshot body) { }

    private static final class Presentations implements AutoCloseable {
        private final HumanTechniqueTestPlayer fixture;
        private final List<Observed> events = new ArrayList<>();
        private boolean rejectStops;
        private Consumer<EffectIntent> callback = effect -> { /* 默认只记录表现。 */ };

        Presentations(HumanTechniqueTestPlayer fixture) {
            // 观察器只活到本次夹具关闭，事件注册本身不持有玩家引用。
            this.fixture = fixture;
            observations.put(fixture.player.getUUID(), this);
        }

        void accept(EffectIntent effect) {
            // 在回调时保存权威快照，用于核对 STOP 先于进度清理。
            if (rejectStops && effect.getPresentation().getAction() == PresentationAction.STOP) {
                throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
            }
            events.add(new Observed(effect, fixture.body().snapshot()));
            callback.accept(effect);
        }

        void rejectStops() {
            // 模拟表现通道暂时拒绝，执行器必须允许同身份重试。
            rejectStops = true;
        }

        void allowStops() {
            // 恢复通道后继续使用原施放身份。
            rejectStops = false;
        }

        Observed last() {
            // 恢复测试只在已收到停止事件后读取最后一条。
            return events.get(events.size() - 1);
        }

        void assertAction(GameTestHelper context, PresentationAction action) {
            // 按结构化动作判断开始或停止，资源 ID 不改变行为。
            boolean received = events.stream()
                    .anyMatch(event -> event.effect().getPresentation().getAction() == action);
            context.assertTrue(received, "缺少表现动作：" + action);
        }

        @Override
        public void close() {
            // 即使断言失败，也释放测试对玩家的观察引用。
            observations.remove(fixture.player.getUUID());
        }
    }
}
