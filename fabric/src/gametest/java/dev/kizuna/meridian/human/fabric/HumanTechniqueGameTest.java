package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 真实玩家宿主的协议、施放、世界效果、归位及断线整体回归。 */
public final class HumanTechniqueGameTest implements FabricGameTest {
    private static final long MILLIS_PER_TICK = 50;
    private static final long RELEASE_WINDOW_MILLIS = 30_000;
    private static final int FINISH_TICKS = 10;
    private static final double EPSILON = 1e-7;
    private static final double EXPECTED_SPEED_PER_TICK = 0.5;
    private static final double DIAGONAL_COMPONENT = Math.sqrt(0.5);
    private static final int PACKET_SPEED_SCALE = 8000;
    private static final String OPEN_MERIDIAN = "left_large_intestine_meridian";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void legacyBodyLoadsMigratesAndCastsWithHandAndFootBindings(TestContext context) {
        // 启动前准备的真实旧文件由正式宿主加载；登录、命令学习和施放不替换任何服务。
        try (var fixture = new HumanTechniqueTestPlayer(context, HumanLegacyBodyTestBootstrap.PLAYER_ID)) {
            var body = fixture.body();
            context.assertTrue(HumanModuleTagMigration.missingTags(body.snapshot()).getModulesCount() == 0,
                    "旧身体仍然缺少人体标签");
            context.assertTrue(body.snapshot().revision() > HumanLegacyBodyTestBootstrap.legacyBody.revision(),
                    "加载旧身体未经过标签补齐命令");
            var persisted = HumanWorldState.read(fixture.state().writeNbt(new NbtCompound()));
            context.assertTrue(persisted.find(fixture.player.getUuid()).snapshot().equals(body.snapshot()),
                    "标签迁移未持久保存或重载重复增加版本");
            context.assertTrue(body.completeOpening(now(fixture), OPEN_MERIDIAN)
                    == FailureCode.FAILURE_CODE_UNSPECIFIED, "迁移后开脉失败");
            learn(context, fixture);
            var outcome = cast(fixture, "legacy_tags");
            context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "迁移后手足经绑定施放失败：" + outcome);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void claimsOnlyOnlinePlayersAndSettlesClock(TestContext context) {
        // 真实入口只认领完成登录的在线玩家，其他实体和离线 UUID 均明确未认领。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            var player = fixture.player;
            var inspected = exchange(inspect(player.getUuid()));
            context.assertTrue(inspected.hasBody(), "在线玩家没有被认领");
            context.assertTrue(inspected.getBody().getId().equals(player.getUuidAsString()), "身体身份错误");
            var body = BodyProtobuf.fromMessage(inspected.getBody());
            context.assertTrue(body.settledAt().elapsedMillis() == now(fixture), "inspect 没有结清到主世界时钟");
            context.assertTrue(exchange(inspect(UUID.randomUUID())).getUnbound(), "离线 UUID 被认领");
            var villager = context.spawnEntity(EntityType.VILLAGER, 0, 1, 0);
            context.assertTrue(exchange(inspect(villager.getUuid())).getUnbound(), "非玩家实体被认领");
            var denied = CompletableFuture.supplyAsync(() -> exchange(inspect(player.getUuid()))).join();
            context.assertTrue(denied.getFailure().getCode() == FailureCode.ACCESS_DENIED, "跨线程调用未拒绝");
            fixture.close();
            context.assertTrue(exchange(inspect(player.getUuid())).getUnbound(), "断线玩家仍被认领");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void activityPreservesRevisionFailureAndDurableReplay(TestContext context) {
        // 同一活动走 Framework 的持久回执，过期版本与不同内容不能覆盖权威状态。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            exchange(inspect(fixture.player.getUuid()));
            var activity = BodyActivity.newBuilder()
                    .putProgress("human_test:probe", Any.pack(StringValue.of("first")))
                    .build();
            var request = base(fixture.player.getUuid(), "human_test_activity")
                    .setExpectedRevision(fixture.body().snapshot().revision())
                    .setActivity(activity)
                    .build();
            var stale = request.toBuilder().setRequestId("human_test_stale").setExpectedRevision(0).build();
            context.assertTrue(exchange(stale).getFailure().getCode() == FailureCode.REVISION_CONFLICT,
                    "expected_revision 冲突未保留原码");
            var accepted = exchange(request);
            context.assertTrue(accepted.hasActivity(), "正式活动未提交：" + accepted);
            context.assertTrue(exchange(request).equals(accepted), "活动重放产生新提交");
            var restored = HumanWorldState.read(fixture.state().writeNbt(new NbtCompound()));
            var replay = restored.find(fixture.player.getUuid()).applyTechnique(now(fixture), request);
            context.assertTrue(replay.getCommandCommitted().getRevision() == accepted.getActivity().getRevision(),
                    "存档恢复丢失功法调用身份下的活动回执");
            var changed = activity.toBuilder()
                    .putProgress("human_test:probe", Any.pack(StringValue.of("changed")))
                    .build();
            context.assertTrue(exchange(request.toBuilder().setActivity(changed).build()).getFailure().getCode()
                    == FailureCode.REQUEST_ID_CONFLICT, "不同内容复用 ID 没有冲突");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void impulseConvertsSpeedSynchronizesAndDeduplicates(TestContext context) {
        // 服务端速度、同步标记、实际本人和观察者速度包必须沿原版跟踪器一致生效。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            var request = impulse(fixture.player.getUuid(), "human_test_impulse", ImpulseDirection.INPUT);
            context.assertTrue(exchange(request).getEffectAccepted(), "冲量未受理");
            assertVelocity(context, fixture, new Vec3d(EXPECTED_SPEED_PER_TICK, 0, 0));
            context.assertTrue(fixture.player.velocityModified, "未标记客户端同步");
            var observers = new ArrayList<Packet<?>>();
            var tracker = new EntityTrackerEntry(context.getWorld(), fixture.player, 1,
                    false /* alwaysUpdateVelocity */, observers::add);
            tracker.tick();
            context.assertTrue(observers.stream().anyMatch(HumanTechniqueGameTest::hasExpectedPacketVelocity),
                    "旁观者未收到速度包");
            context.assertTrue(fixture.connection.packets.stream()
                    .anyMatch(HumanTechniqueGameTest::hasExpectedPacketVelocity), "玩家本人未收到正确速度包");
            context.assertTrue(!fixture.player.velocityModified, "跟踪器未消费同步标记");
            fixture.player.setVelocity(Vec3d.ZERO);
            context.assertTrue(exchange(request).getEffectAccepted(), "同效果重放未确认");
            assertVelocity(context, fixture, Vec3d.ZERO);
            var changed = impulse(fixture.player.getUuid(), request.getRequestId(), ImpulseDirection.UP);
            context.assertTrue(exchange(changed).getFailure().getCode() == FailureCode.REQUEST_ID_CONFLICT,
                    "效果 ID 内容冲突未拒绝");
            verifyDirections(context, fixture);
            var effect = unknownVisual();
            context.assertTrue(exchange(base(fixture.player.getUuid(), "human_test_visual")
                    .setEffect(effect).build()).getEffectAccepted(), "未知表现不应失败");
        }
        context.complete();
    }

    private static EffectIntent unknownVisual() {
        // 未识别的表现仍是合法意图，宿主应接受而不调用动画系统。
        var visual = PresentationIntent.newBuilder()
                .setId("human_test:unknown_visual")
                .setAction(PresentationAction.PLAY_ONCE)
                .build();
        return EffectIntent.newBuilder().setCastId("visual").setPresentation(visual).build();
    }

    private static boolean hasExpectedPacketVelocity(Packet<?> packet) {
        // 对原版量化后的真实包校验三个分量，不能只凭包类型判定同步正确。
        return packet instanceof EntityVelocityUpdateS2CPacket velocity
                && velocity.getVelocityX() == EXPECTED_SPEED_PER_TICK * PACKET_SPEED_SCALE
                && velocity.getVelocityY() == 0 && velocity.getVelocityZ() == 0;
    }

    private static void verifyDirections(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 朝向、垂直、极大输入及同世界目标都采用归一化方向，失败不改变速度。
        fixture.player.setYaw(0);
        exchange(impulse(fixture.player.getUuid(), "human_test_forward", ImpulseDirection.FORWARD));
        assertVelocity(context, fixture, new Vec3d(0, 0, EXPECTED_SPEED_PER_TICK));
        exchange(impulse(fixture.player.getUuid(), "human_test_up", ImpulseDirection.UP));
        assertVelocity(context, fixture, new Vec3d(0, EXPECTED_SPEED_PER_TICK, 0));
        var request = impulse(fixture.player.getUuid(), "human_test_large_input", ImpulseDirection.INPUT);
        var diagonal = HorizontalDirection.newBuilder().setX(Double.MAX_VALUE).setZ(Double.MAX_VALUE).build();
        var diagonalEffect = request.getEffect().toBuilder().setDirection(diagonal).build();
        exchange(request.toBuilder().setEffect(diagonalEffect).build());
        double component = EXPECTED_SPEED_PER_TICK * DIAGONAL_COMPONENT;
        assertVelocity(context, fixture, new Vec3d(component, 0, component));
        var target = context.spawnEntity(EntityType.VILLAGER, 0, 1, 0);
        target.setPosition(fixture.player.getPos().add(0, 1, 0));
        var toward = impulse(fixture.player.getUuid(), "human_test_target", ImpulseDirection.TOWARD_TARGET);
        var towardEffect = toward.getEffect().toBuilder().setTargetId(target.getUuidAsString()).build();
        context.assertTrue(exchange(toward.toBuilder().setEffect(towardEffect).build()).getEffectAccepted(),
                "同世界目标未定位");
        assertVelocity(context, fixture, new Vec3d(0, EXPECTED_SPEED_PER_TICK, 0));
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void commandLearnCastFinishAndReleasedQiReturnsOnce(TestContext context) throws Exception {
        // 管理命令、真实服务 tick、身体投影和默认释放账户贯穿一次完整施放。
        var fixture = prepared(context);
        var body = fixture.body();
        double before = body.snapshot().totalQi();
        double releasedBefore = released(fixture.state().writeNbt(new NbtCompound()));
        var outcome = cast(fixture, "complete");
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "起手失败：" + outcome);
        double paid = before - body.snapshot().totalQi();
        context.assertTrue(Math.abs(paid - HumanTechniqueTestCatalog.COST_QI) < EPSILON, "起手费用不符");
        var saved = fixture.state().writeNbt(new NbtCompound());
        context.assertTrue(Math.abs(released(saved) - releasedBefore - paid) < EPSILON, "释放未进入默认账户");
        verifyReleasedReturn(context, fixture, saved);
        fixture.connection.packets.clear();
        context.waitAndRun(FINISH_TICKS, () -> {
            try (fixture) {
                var progress = TechniqueState.read(body.snapshot());
                context.assertTrue(!progress.hasActiveCast(), "真实 tick 未完成收势");
                context.assertTrue(body.snapshot().actions().isEmpty(), "收势后遗留动作");
                context.assertTrue(progress.getKnownCount() == 1 && progress.getCooldownsCount() == 1,
                        "收势丢失学会记录或冷却");
                context.assertTrue(fixture.connection.packets.stream().anyMatch(HumanTechniqueGameTest::isBodyView),
                        "成功后未按原规则推送身体视图");
                context.complete();
            }
        });
    }

    private static void verifyReleasedReturn(TestContext context, HumanTechniqueTestPlayer fixture,
                                             NbtCompound saved) {
        // 独立恢复同一快照后推进供给窗，不能让在线世界的时钟倒退或提前归位。
        var restored = HumanWorldState.read(saved);
        var player = fixture.player;
        var position = player.getBlockPos();
        var original = restored.query(player.getServerWorld(), position.getX(), position.getZ(), now(fixture));
        double released = pendingForSource(saved, original.getSourceId());
        double originalQi = BodyProtobuf.fromInventory(original.getInventory()).totalQi();
        long boundary = now(fixture) + RELEASE_WINDOW_MILLIS;
        restored.renewEnvironment(boundary);
        double regionQi = BodyProtobuf.fromInventory(restored.query(player.getServerWorld(),
                position.getX(), position.getZ(), boundary).getInventory()).totalQi();
        context.assertTrue(Math.abs(regionQi - originalQi - released) < EPSILON, "施放释放归位不守恒");
        var reloaded = HumanWorldState.read(restored.writeNbt(new NbtCompound()));
        NbtCompound returned = reloaded.writeNbt(new NbtCompound());
        // 共享账户还可能包含其他区域的待归位项，只核对目标区域已扣转并持久删除队列。
        context.assertTrue(pendingForSource(returned, original.getSourceId()) == 0, "目标区域归位队列未清除");
        context.assertTrue(released(returned) <= released(saved) - released + EPSILON, "归位未扣默认账户");
        long nextBoundary = boundary + RELEASE_WINDOW_MILLIS;
        reloaded.renewEnvironment(nextBoundary);
        double afterReload = BodyProtobuf.fromInventory(reloaded.query(player.getServerWorld(),
                position.getX(), position.getZ(), nextBoundary).getInventory()).totalQi();
        context.assertTrue(Math.abs(afterReload - regionQi) < EPSILON, "重载重复归位");
    }

    private static double pendingForSource(NbtCompound saved, String sourceId) {
        // 并行 GameTest 共享世界；只比较目标区域的待归位项，避免混入其他玩家的区域。
        double total = 0;
        var pending = saved.getList("pending_credits", NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < pending.size(); index++) {
            var entry = pending.getCompound(index);
            if (entry.getString("source_id").equals(sourceId) && entry.getString("kind").equals("default")) {
                total += inventoryQi(entry.getByteArray("qi"));
            }
        }
        return total;
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void disconnectCancelsBeforeUnbindWithoutRefund(TestContext context) {
        // 真实断线事件必须在身体访问撤销前清除施放，保留冷却和已经扣除的费用。
        try (var fixture = prepared(context)) {
            context.assertTrue(cast(fixture, "disconnect").getPhase() == CastPhase.WARMUP, "没有开始施放");
            var body = fixture.body();
            double paidBalance = body.snapshot().totalQi();
            var cooldowns = TechniqueState.read(body.snapshot()).getCooldownsMap();
            fixture.close();
            var progress = TechniqueState.read(body.snapshot());
            context.assertTrue(!progress.hasActiveCast() && body.snapshot().actions().isEmpty(), "断线未取消");
            context.assertTrue(Math.abs(body.snapshot().totalQi() - paidBalance) < EPSILON, "断线错误退费");
            context.assertTrue(progress.getCooldownsMap().equals(cooldowns), "断线清除了冷却");
            var restored = HumanWorldState.read(fixture.state().writeNbt(new NbtCompound()));
            context.assertTrue(!TechniqueState.read(restored.find(fixture.player.getUuid()).snapshot())
                    .hasActiveCast(), "保存重载恢复了已取消施放");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void abandonedSavedCastCancelsBeforeReclaimWithoutEffects(TestContext context) {
        // 异常退出没有断线回调；重新认领前必须清理保存中的施放且不能补发冲量。
        try (var fixture = prepared(context)) {
            context.assertTrue(cast(fixture, "abandoned").getPhase() == CastPhase.WARMUP, "没有开始施放");
            var body = fixture.body();
            double balance = body.snapshot().totalQi();
            var saved = fixture.state().writeNbt(new NbtCompound());
            var restored = HumanWorldState.read(saved).find(fixture.player.getUuid());
            context.assertTrue(TechniqueState.read(restored.snapshot()).hasActiveCast(), "存档未保存当前施放");
            var cooldowns = TechniqueState.read(restored.snapshot()).getCooldownsMap();
            TechniqueFabric.service().recoverOnLoad(fixture.player.getUuid(), restored);
            context.assertTrue(!TechniqueState.read(restored.snapshot()).hasActiveCast(),
                    "真实读档身体未清除残留施放");
            context.assertTrue(restored.snapshot().actions().isEmpty(), "读档恢复遗留动作");
            HumanTechniqueHost.disconnect(fixture.player.getUuid());
            context.assertTrue(exchange(inspect(fixture.player.getUuid())).getUnbound(), "恢复前仍被认领");
            fixture.player.setVelocity(Vec3d.ZERO);
            HumanTechniqueHost.reconnect(fixture.player);
            context.assertTrue(exchange(inspect(fixture.player.getUuid())).hasBody(), "恢复后未重新认领");
            var progress = TechniqueState.read(body.snapshot());
            context.assertTrue(!progress.hasActiveCast() && body.snapshot().actions().isEmpty(), "残留施放未取消");
            context.assertTrue(progress.getCooldownsMap().equals(cooldowns), "恢复取消清除了冷却");
            context.assertTrue(Math.abs(body.snapshot().totalQi() - balance) < EPSILON, "恢复取消错误退费");
            assertVelocity(context, fixture, Vec3d.ZERO);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void nativeDeathCancelsBeforeBodyDeath(TestContext context) {
        // 原版死亡前先取消存活身体的施放，随后死亡确认不会留下 active_cast。
        try (var fixture = prepared(context)) {
            context.assertTrue(cast(fixture, "native_death").getPhase() == CastPhase.WARMUP, "没有开始施放");
            var paid = TechniqueState.read(fixture.body().snapshot());
            fixture.player.onDeath(fixture.player.getDamageSources().genericKill());
            context.assertTrue(!TechniqueState.read(fixture.body().snapshot()).hasActiveCast(), "原版死亡遗留施放");
            context.assertTrue(fixture.body().snapshot().actions().isEmpty(), "死亡遗留动作占用");
            assertStoppedProgress(context, fixture.body(), paid);
            context.assertTrue(TechniqueFabric.service().cancelOnUnload(fixture.player.getUuid()).isEmpty(),
                    "死亡后仍保留会话");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void healthDeathClearsCastWithoutRewritingDamageRevision(TestContext context) {
        // 健康命令先按原版本致死，再用可信进度清理；不能为了取消而改写伤害版本。
        try (var fixture = prepared(context)) {
            context.assertTrue(cast(fixture, "health_death").getPhase() == CastPhase.WARMUP, "没有开始施放");
            var paid = TechniqueState.read(fixture.body().snapshot());
            RequestEnvelope death = death(fixture.body());
            var request = HealthHostRequest.newBuilder()
                    .setProtocolVersion(MeridianProtocol.VERSION)
                    .setRequestId(death.getRequestId())
                    .setSubjectId(fixture.player.getUuidAsString())
                    .setEffect(death)
                    .build();
            byte[] payload = HealthHostProtobuf.encodeRequest(request);
            var host = new HumanHealthHost();
            var response = HealthHostProtobuf.decodeResponse(host.exchange(payload));
            context.assertTrue(response.hasBody() && response.hasHealth(), "致死健康命令失败：" + response);
            context.assertTrue(fixture.body().snapshot().vitality().dead(), "健康命令未确认死亡");
            assertStoppedProgress(context, fixture.body(), paid);
            var recovered = fixture.body().snapshot();
            var replay = HealthHostProtobuf.decodeResponse(host.exchange(payload));
            context.assertTrue(replay.hasBody() && replay.getHealth().equals(response.getHealth()),
                    "致死请求重试丢失固定回执");
            context.assertTrue(recovered.equals(fixture.body().snapshot()), "致死重试重复清理或扣费");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void deadSavedResidualLoadsAndReconnectsWithoutPausing(TestContext context) {
        // 保存真实的死体残留，读档和在线重新认领均使用服务统一入口，不把 BODY_DEAD 当恢复失败。
        try (var fixture = prepared(context)) {
            context.assertTrue(cast(fixture, "dead_saved").getPhase() == CastPhase.WARMUP, "没有开始施放");
            var body = fixture.body();
            var paid = TechniqueState.read(body.snapshot());
            context.assertTrue(body.applyHealth(now(fixture), death(body)).hasCommandCommitted(), "未生成死亡旧档");
            context.assertTrue(TechniqueState.read(body.snapshot()).hasActiveCast(), "没有形成残留施放");
            var restoredState = HumanWorldState.read(fixture.state().writeNbt(new NbtCompound()));
            var restored = restoredState.join(fixture.player, now(fixture));
            TechniqueFabric.service().recoverOnLoad(fixture.player.getUuid(), restored);
            assertStoppedProgress(context, restored, paid);
            var reloaded = HumanWorldState.read(restoredState.writeNbt(new NbtCompound()));
            context.assertTrue(!TechniqueState.read(reloaded.find(fixture.player.getUuid()).snapshot())
                    .hasActiveCast(), "恢复结果没有保存");
            HumanTechniqueHost.disconnect(fixture.player.getUuid());
            HumanTechniqueHost.reconnect(fixture.player);
            context.assertTrue(exchange(inspect(fixture.player.getUuid())).hasBody(), "死体恢复后身体被暂停");
            assertStoppedProgress(context, body, paid);
            context.assertTrue(fixture.player.networkHandler.isConnectionOpen(), "残留施放导致断线");
            context.assertTrue(cast(fixture, "after_death").getFailure() != TechniqueFailure.ALREADY_CASTING,
                    "死亡后施放仍被旧 active_cast 卡住");
        }
        context.complete();
    }

    private static RequestEnvelope death(HumanPlayerBody body) {
        // 通过正式死亡命令生成 Framework 清动作但保留进度的状态。
        var effect = ConfirmBodyDeath.newBuilder().setSourceId("human_test:death").build();
        var command = BodyCommand.newBuilder()
                .setBodyId(body.snapshot().id().value())
                .setExpectedRevision(body.snapshot().revision())
                .setConfirmBodyDeath(effect)
                .build();
        return RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId("human_test_death_" + UUID.randomUUID())
                .setBodyCommand(command)
                .build();
    }

    private static void assertStoppedProgress(TestContext context, HumanPlayerBody body, TechniqueProgress paid) {
        // 取消或死亡恢复只清理当前施放，不改变可信知识、冷却和其他功法进度。
        context.assertTrue(TechniqueState.read(body.snapshot()).equals(paid.toBuilder().clearActiveCast().build()),
                "施放未清除或费用冷却事实被修改");
        context.assertTrue(body.snapshot().actions().isEmpty(), "清理后仍有动作占用");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void breathingAndCastShareTissueUntilTheirOwnStop(TestContext context) {
        // Framework 按实例保留组织占用；功法收势只能停止自己的实例。
        var fixture = prepared(context);
        var body = fixture.body();
        body.writeProgress(body.progress().toBuilder()
                .setQiNotesRead(true)
                .setGatheringScrollRead(true)
                .build());
        context.assertTrue(!breath(fixture, 1, BreathAction.BEGIN_BREATHING).hasFailure(), "吐纳未开始");
        context.assertTrue(!breath(fixture, 2, BreathAction.INHALE).hasFailure(), "吸气未开始");
        var breathingIds = body.snapshot().actions().stream().map(action -> action.instanceId()).toList();
        context.assertTrue(!breathingIds.isEmpty(), "吐纳未建立真实组织占用");
        context.assertTrue(cast(fixture, "shared_tissue").getPhase() == CastPhase.WARMUP, "共享组织施放被拒绝");
        context.assertTrue(body.snapshot().actions().size() > breathingIds.size(), "功法未建立自己的占用");
        context.waitAndRun(FINISH_TICKS, () -> {
            try (fixture) {
                context.assertTrue(!TechniqueState.read(body.snapshot()).hasActiveCast(), "功法未完成");
                var retained = body.snapshot().actions().stream().map(action -> action.instanceId()).toList();
                context.assertTrue(retained.containsAll(breathingIds), "收势释放了吐纳的占用");
                context.complete();
            }
        });
    }

    private static dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse breath(
            HumanTechniqueTestPlayer fixture, long sequence, BreathAction action) {
        // 输入按正式吐纳会话递增，瞄准只决定气流方向而不指定结果。
        var control = BreathControl.newBuilder().setAction(action).setSequence(sequence).build();
        var request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("human_test_breath_" + sequence)
                .setBodyId(fixture.player.getUuidAsString())
                .setBreath(control)
                .build();
        return fixture.body().gather(now(fixture), request);
    }

    private static HumanTechniqueTestPlayer prepared(TestContext context) {
        // 只通过可信开脉入口与正式学习命令准备，费用从出生实际库存扣除。
        var fixture = new HumanTechniqueTestPlayer(context);
        context.assertTrue(fixture.body().completeOpening(now(fixture), OPEN_MERIDIAN)
                == FailureCode.FAILURE_CODE_UNSPECIFIED, "可信开脉失败");
        learn(context, fixture);
        return fixture;
    }

    private static void learn(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 旧身体与新身体都通过同一公开命令写入可信学习记录。
        var server = fixture.player.getServer();
        int learned = server.getCommandManager().executeWithPrefix(server.getCommandSource(),
                "meridian_technique learn " + fixture.player.getUuidAsString() + " "
                        + HumanTechniqueTestCatalog.TECHNIQUE_ID);
        context.assertTrue(learned == 1 && TechniqueFabric.service().known(fixture.player.getUuid()).size() == 1,
                "学会命令未写入可信知识");
    }

    private static CastOutcome cast(HumanTechniqueTestPlayer fixture, String castId) {
        // 施放请求只引用目录版本和输入，不携带费用与效果数值。
        return TechniqueFabric.service().cast(fixture.player.getUuid(), castRequest(fixture, castId));
    }

    private static CastRequest castRequest(HumanTechniqueTestPlayer fixture, String castId) {
        // 所有玩家夹具都使用同一正式施放请求，迁移不改变绑定规则。
        var reference = TechniqueReference.newBuilder()
                .setId(HumanTechniqueTestCatalog.TECHNIQUE_ID)
                .setVersion(1)
                .build();
        var direction = HorizontalDirection.newBuilder().setX(1).build();
        return CastRequest.newBuilder()
                .setBodyId(fixture.player.getUuidAsString())
                .setTechnique(reference)
                .setCastId(castId)
                .setDirection(direction)
                .build();
    }

    private static TechniqueHostRequest impulse(UUID playerId, String requestId, ImpulseDirection direction) {
        // 固定测试剂量为每秒十米，所有分支共享同一有单位的效果构造。
        var speed = HumanTechniqueTestCatalog.quantity(HumanTechniqueTestCatalog.SPEED_METERS_PER_SECOND,
                Unit.METERS_PER_SECOND);
        var impulse = Impulse.newBuilder().setDirection(direction).setSpeed(speed).build();
        var input = HorizontalDirection.newBuilder().setX(1).build();
        var effect = EffectIntent.newBuilder()
                .setCastId("impulse")
                .setDirection(input)
                .setImpulse(impulse)
                .build();
        return base(playerId, requestId).setEffect(effect).build();
    }

    private static TechniqueHostRequest inspect(UUID playerId) {
        // 探测也经过正式 Protobuf 编解码与 entrypoint。
        return base(playerId, "human_test_inspect").setInspect(true).build();
    }

    private static TechniqueHostRequest.Builder base(UUID playerId, String requestId) {
        // 身份只来自测试玩家实体，宿主仍须独立检查在线列表。
        return TechniqueHostRequest.newBuilder().setEntityUuid(playerId.toString()).setRequestId(requestId);
    }

    private static TechniqueHostResponse exchange(TechniqueHostRequest request) {
        // 从加载器取得正式宿主入口，不用测试宿主覆盖生产接线。
        var host = FabricLoader.getInstance().getEntrypoints(TechniqueHostEntrypoint.ENTRYPOINT_ID,
                TechniqueHostEntrypoint.class).stream().filter(HumanTechniqueHost.class::isInstance)
                .findFirst().orElseThrow();
        return TechniqueProtocol.decodeHostResponse(host.exchange(TechniqueProtocol.encodeHost(request)));
    }

    private static void assertVelocity(TestContext context, HumanTechniqueTestPlayer fixture, Vec3d expected) {
        // 比较真实实体速度，允许方向归一化产生的浮点舍入。
        context.assertTrue(fixture.player.getVelocity().distanceTo(expected) < EPSILON,
                "速度换算错误：" + fixture.player.getVelocity() + "，期望 " + expected);
    }

    private static long now(HumanTechniqueTestPlayer fixture) {
        // 与 Human 的主世界累计 tick 时钟完全相同。
        return fixture.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
    }

    private static double released(NbtCompound saved) {
        // 读取实际 Store 的序列化默认账户，不用独立测试余额替代。
        return inventoryQi(saved.getByteArray("released_qi"));
    }

    private static double inventoryQi(byte[] payload) {
        // 库存只由 Framework 生成消息解析，损坏数据必须使回归失败。
        try {
            return BodyProtobuf.fromInventory(QiInventory.parseFrom(payload)).totalQi();
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static boolean isBodyView(Packet<?> packet) {
        // 身体视图沿既有通道推送，功法宿主无需增设第二个状态频道。
        return packet instanceof CustomPayloadS2CPacket custom
                && custom.getChannel().equals(new Identifier("meridian:body_view"));
    }
}
