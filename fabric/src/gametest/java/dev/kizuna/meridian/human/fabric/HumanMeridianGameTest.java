package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.*;
import dev.kizuna.meridian.human.server.HumanErrors;
import net.minecraft.nbt.NbtCompound;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;

/** 整体回归：游戏服务端加载真实人体内容，并由玩家宿主执行新拓扑扩散和存档恢复。 */
public final class HumanMeridianGameTest implements FabricGameTest {
    private static final int RELEASE_COORDINATE = -1;
    private static final long RELEASE_WINDOW_MILLIS = 30_000;
    private static final double GAS_RELEASE_AMOUNT = 4;
    private static final double LIQUID_RELEASE_AMOUNT = 2;
    private static final double GAS_RELEASE_DENSITY = 0.01;
    private static final double LIQUID_RELEASE_DENSITY = 0.1;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void regionReleaseSurvivesSaveAndCreditsOnlyAtWindowBoundary(TestContext context) {
        // 真实世界首次查询生成区域，后续查询与排队释放均不得重抽库存或提前改版。
        var world = context.getWorld();
        var state = new HumanWorldState();
        var original = state.query(world, RELEASE_COORDINATE, RELEASE_COORDINATE, 0);
        context.assertTrue(original != null, "主世界区域未生成");
        context.assertTrue(original.equals(state.query(world, RELEASE_COORDINATE, RELEASE_COORDINATE, 1)),
                "重复查询重抽区域或改变版本");
        var qi = new QiInventory(List.of(new Qi(Qi.QiAttribute.of("qi_variants:wither"),
                GAS_RELEASE_AMOUNT, GAS_RELEASE_DENSITY), new Qi(Qi.QiAttribute.of("qi_variants:wither"),
                LIQUID_RELEASE_AMOUNT, LIQUID_RELEASE_DENSITY)));
        var position = RegionQuery.newBuilder()
                .setDimensionId(world.getRegistryKey().getValue().toString())
                .setBlockX(RELEASE_COORDINATE)
                .setBlockZ(RELEASE_COORDINATE)
                .build();
        var release = RegionRelease.newBuilder()
                .setPosition(position)
                .setReleaseId("gametest:queued-release")
                .setReleasedQi(BodyProtobuf.toInventory(qi))
                .build();
        context.assertTrue(!state.release(release, world, 1).getDuplicate(), "首次释放被判为重试");
        context.assertTrue(state.release(release, world, 1).getDuplicate(), "重复释放未去重");
        context.assertTrue(original.equals(state.query(world, RELEASE_COORDINATE, RELEASE_COORDINATE, 1)),
                "排队释放提前改变区域");
        verifySavedRelease(context, state, release, original, qi);
        context.complete();
    }

    private static void verifySavedRelease(TestContext context, HumanWorldState state, RegionRelease release,
                                           RegionView original, QiInventory qi) {
        // 排队状态经过 NBT 写读后仍保留去重键，跨供给窗续算只能入账一次。
        var restored = HumanWorldState.read(state.writeNbt(new NbtCompound()));
        var world = context.getWorld();
        context.assertTrue(restored.release(release, world, 1).getDuplicate(), "存档恢复丢失排队去重键");
        restored.renewEnvironment(RELEASE_WINDOW_MILLIS - 1);
        context.assertTrue(original.equals(restored.query(world, RELEASE_COORDINATE, RELEASE_COORDINATE,
                RELEASE_WINDOW_MILLIS - 1)), "窗口结束前已入账");
        restored.renewEnvironment(RELEASE_WINDOW_MILLIS);
        var credited = restored.query(world, RELEASE_COORDINATE, RELEASE_COORDINATE, RELEASE_WINDOW_MILLIS);
        var expected = dev.kizuna.meridian.core.QiAccounting.credit(
                BodyProtobuf.fromInventory(original.getInventory()), qi);
        context.assertTrue(BodyProtobuf.fromInventory(credited.getInventory()).equals(expected),
                "区域入账未保留自定义属性、数量、密度与相态");
        context.assertTrue(credited.getSourceRevision() > original.getSourceRevision(), "入账未更新区域版本");
        var savedAgain = HumanWorldState.read(restored.writeNbt(new NbtCompound()));
        context.assertTrue(savedAgain.release(release, world, RELEASE_WINDOW_MILLIS + 1).getDuplicate(),
                "已入账去重键未保存");
        long nextWindowMillis = RELEASE_WINDOW_MILLIS + RELEASE_WINDOW_MILLIS;
        savedAgain.renewEnvironment(nextWindowMillis);
        context.assertTrue(BodyProtobuf.fromInventory(savedAgain.query(world,
                RELEASE_COORDINATE, RELEASE_COORDINATE, nextWindowMillis)
                .getInventory()).equals(expected), "重启或续窗重复入账");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void defaultReleasedAccountReturnsWithoutCreatingQi(TestContext context) {
        // 真实玩家死亡先进入默认账户和位置队列，重启后在续窗从原账户扣转。
        var player = context.createMockCreativeServerPlayerInWorld();
        long now = player.getServer().getOverworld().getTime() * 50;
        var state = new HumanWorldState();
        var body = state.join(player, now);
        // 健康请求须先结算再读取版本，避免首次修炼进度初始化使请求过期。
        body.settle(now);
        var position = player.getBlockPos();
        var original = state.query(context.getWorld(), position.getX(), position.getZ(), now);
        double released = body.snapshot().totalQi();
        var death = dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1).setRequestId("gametest:player-death")
                .setBodyCommand(dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand.newBuilder()
                        .setBodyId(body.snapshot().id().value()).setExpectedRevision(body.snapshot().revision())
                        .setConfirmBodyDeath(dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath.newBuilder()
                                .setSourceId("gametest:death"))).build();
        context.assertTrue(body.applyHealth(now, death).hasCommandCommitted(), "死亡清账未提交");
        var saved = state.writeNbt(new NbtCompound());
        context.assertTrue(Math.abs(defaultReleased(saved) - released) < 1e-7, "死亡未进入唯一默认账户");
        context.assertTrue(saved.getList("pending_credits", 10).size() == 1, "玩家释放未按位置排队");
        context.assertTrue(original.equals(state.query(context.getWorld(), position.getX(), position.getZ(), now)), "玩家释放提前入账");
        // 余额暂时不一致时，失败项仍须能保存并在修复后重试，不能在加载阶段丢弃队列。
        byte[] savedReleased = saved.getByteArray("released_qi");
        saved.putByteArray("released_qi", BodyProtobuf.toInventory(QiInventory.empty()).toByteArray());
        var failedTransfer = HumanWorldState.read(saved);
        long boundary = now + 30_000;
        failedTransfer.renewEnvironment(boundary);
        var failedSave = failedTransfer.writeNbt(new NbtCompound());
        context.assertTrue(failedSave.getList("pending_credits", 10).size() == 1, "归位失败后队列被静默删除");
        failedSave.putByteArray("released_qi", savedReleased);
        var restored = HumanWorldState.read(failedSave);
        long retryBoundary = boundary + 30_000;
        restored.renewEnvironment(retryBoundary);
        var after = restored.writeNbt(new NbtCompound());
        context.assertTrue(defaultReleased(after) == 0, "默认释放账户归位后未扣账");
        double beforeTotal = BodyProtobuf.fromInventory(original.getInventory()).totalQi();
        double afterTotal = BodyProtobuf.fromInventory(restored.query(context.getWorld(), position.getX(), position.getZ(), retryBoundary)
                .getInventory()).totalQi();
        context.assertTrue(Math.abs(afterTotal - beforeTotal - released) < 1e-7, "默认归位不守恒");
        var reloaded = HumanWorldState.read(after);
        reloaded.renewEnvironment(boundary * 2);
        context.assertTrue(Math.abs(BodyProtobuf.fromInventory(reloaded.query(context.getWorld(), position.getX(), position.getZ(), boundary * 2)
                .getInventory()).totalQi() - afterTotal) < 1e-7, "默认余额恢复后重复归位");
        context.complete();
    }

    private static double defaultReleased(NbtCompound saved) {
        // 直接读取宿主保存的框架消息，避免另建测试账本掩盖实际扣账问题。
        try {
            return BodyProtobuf.fromInventory(dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory
                    .parseFrom(saved.getByteArray("released_qi"))).totalQi();
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new AssertionError("默认释放账户存档损坏", invalid);
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void nonOverworldReleaseStaysInDefaultAccountAndInvalidInventoryIsRejected(TestContext context) {
        // 存在的非主世界不生成区域，外部释放仍须排队并随同一存档恢复。
        var nether = context.getWorld().getServer().getWorld(net.minecraft.world.World.NETHER);
        context.assertTrue(nether != null, "测试服务器未加载下界");
        var state = new HumanWorldState();
        var position = RegionQuery.newBuilder().setDimensionId("minecraft:the_nether").build();
        context.assertTrue(state.query(nether, 0, 0, 0) == null, "下界意外生成区域");
        var qi = new QiInventory(List.of(new Qi(Qi.QiAttribute.of("qi_variants:wither"), 7, .01)));
        var release = RegionRelease.newBuilder().setReleaseId("gametest:nether-release")
                .setPosition(position).setReleasedQi(BodyProtobuf.toInventory(qi)).build();
        context.assertTrue(!state.release(release, nether, 1).hasSourceId(), "无区域释放返回了来源 ID");
        context.assertTrue(defaultReleased(state.writeNbt(new NbtCompound())) == 0, "非主世界释放跳过队列");
        state = HumanWorldState.read(state.writeNbt(new NbtCompound()));
        state.renewEnvironment(30_000);
        context.assertTrue(defaultReleased(state.writeNbt(new NbtCompound())) == 7, "非主世界释放未进入默认账户");
        context.assertTrue(state.release(release, nether, 30_001).getDuplicate(), "无区域释放重试未去重");

        // 非法明细不得占用释放 ID；修正同一请求后仍能首次受理。
        for (double amount : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var invalid = release.toBuilder().setReleaseId("gametest:invalid-" + amount)
                    .setReleasedQi(release.getReleasedQi().toBuilder().setEntries(0,
                            release.getReleasedQi().getEntries(0).toBuilder().setAmount(amount))).build();
            boolean rejected = false;
            try {
                state.release(invalid, nether, 30_001);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            context.assertTrue(rejected, "非法释放明细未拒绝");
            context.assertTrue(!state.release(invalid.toBuilder().setReleasedQi(release.getReleasedQi()).build(),
                    nether, 30_001).getDuplicate(), "非法请求占用了释放 ID");
        }
        // 玩家在下界死亡没有区域归位项，既有余额继续留在默认账户。
        var player = new net.minecraft.server.network.ServerPlayerEntity(nether.getServer(), nether,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "EnvironmentTest"));
        long deathMillis = 30_001;
        var body = state.join(player, deathMillis);
        // 与正式健康宿主保持相同读取顺序，结算完成后才能固定预期版本。
        body.settle(deathMillis);
        double bodyQi = body.snapshot().totalQi();
        var death = dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1).setRequestId("gametest:nether-death")
                .setBodyCommand(dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand.newBuilder()
                        .setBodyId(body.snapshot().id().value()).setExpectedRevision(body.snapshot().revision())
                        .setConfirmBodyDeath(dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath.newBuilder()
                                .setSourceId("gametest:death"))).build();
        context.assertTrue(body.applyHealth(deathMillis, death).hasCommandCommitted(), "下界玩家死亡未提交");
        state.renewEnvironment(60_000);
        var saved = state.writeNbt(new NbtCompound());
        context.assertTrue(Math.abs(defaultReleased(saved) - 35 - bodyQi) < 1e-7, "下界玩家释放丢失或被转出");
        context.assertTrue(saved.getList("pending_credits", 10).isEmpty(), "下界玩家生成了默认归位队列");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void environmentHostRejectsUnboundAndOffThreadCalls(TestContext context) throws Exception {
        // 暂时解除实际宿主后立即恢复，验证加载阶段以合法协议返回 unbound。
        var field = HumanEnvironmentHost.class.getDeclaredField("delegate");
        field.setAccessible(true);
        Object bound = field.get(null);
        context.assertTrue(bound != null, "正式环境宿主未绑定");
        byte[] request = EnvironmentHostRequest.newBuilder().setProtocolVersion(1)
                .setRequestId("gametest:environment-host").setClock(true).build().toByteArray();
        try {
            HumanEnvironmentHost.bind(null);
            context.assertTrue(EnvironmentHostResponse.parseFrom(new HumanEnvironmentHost().exchange(request)).getUnbound(),
                    "世界未加载时未返回 unbound");
        } finally {
            @SuppressWarnings("unchecked")
            var delegate = (java.util.function.Function<byte[], byte[]>) bound;
            HumanEnvironmentHost.bind(delegate);
        }
        var now = EnvironmentHostResponse.parseFrom(new HumanEnvironmentHost().exchange(request));
        context.assertTrue(now.hasNow() && now.getNow().getDomain().equals("human:minecraft")
                && !now.getNow().getEpoch().isBlank(), "主线程未返回 Human 世界时钟");
        var query = EnvironmentHostRequest.newBuilder().setProtocolVersion(1).setRequestId("gametest:nether-query")
                .setQuery(RegionQuery.newBuilder().setDimensionId("minecraft:the_nether")).build();
        context.assertTrue(EnvironmentHostResponse.parseFrom(new HumanEnvironmentHost().exchange(query.toByteArray()))
                .getResultCase() == EnvironmentHostResponse.ResultCase.RESULT_NOT_SET, "非主世界查询未返回空结果");
        var invalidClock = EnvironmentHostRequest.parseFrom(request).toBuilder().setClock(false).build();
        context.assertTrue(EnvironmentHostResponse.parseFrom(new HumanEnvironmentHost().exchange(invalidClock.toByteArray()))
                .getFailure().getCode() == FailureCode.INVALID_REQUEST, "clock=false 未拒绝");

        // 工作线程调用真实绑定处理器，合法协议请求也不得越过服务器线程边界。
        byte[] response = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> new HumanEnvironmentHost().exchange(request)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        context.assertTrue(EnvironmentHostResponse.parseFrom(response).getFailure().getCode() == FailureCode.ACCESS_DENIED,
                "非主线程环境请求未返回 ACCESS_DENIED");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void movementPacketGapsKeepAnimationUntilConfirmedStop(TestContext context) {
        // 真实网络会短暂缺少位置包，不能因此停止循环，再以新请求重新播放。
        var observer = new HumanMovementObserver();
        var origin = net.minecraft.util.math.Vec3d.ZERO;
        observer.sample(origin, "overworld", true, false, false);
        var walking = origin.add(.2, 0, 0);
        context.assertTrue(observer.sample(walking, "overworld", true, false, false)
                == HumanPlayerBody.Motion.WALK, "实际位移未开始行走");
        for (int gap = 0; gap < 2; gap++) {
            context.assertTrue(observer.sample(walking, "overworld", true, false, false)
                    == HumanPlayerBody.Motion.WALK, "位置包间隙重启动画");
            context.assertTrue(!observer.moved(), "缺包不能伪造实际位移并结束吐纳");
        }
        context.assertTrue(observer.sample(walking, "overworld", true, false, false)
                == HumanPlayerBody.Motion.IDLE, "确认停步后动作未结束");
        context.assertTrue(observer.sample(walking.add(.3, 0, 0), "overworld", true, true, false)
                == HumanPlayerBody.Motion.SPRINT, "实际疾跑未切换");
        context.assertTrue(observer.sample(walking.add(.6, 0, 0), "nether", true, true, false)
                == HumanPlayerBody.Motion.IDLE, "换维度继承旧运动");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void playerHostRunsCircuitAndCancelsOnReset(TestContext context) {
        // 游戏宿主完成开脉、手动推力和冲关；撤权必须归还在途并解除动作。
        var player = context.createMockSurvivalPlayer();
        HumanPlayerBody body = HumanPlayerBody.born(player.getUuid(), "circuit-gametest", 0);
        body.writeProgress(dev.kizuna.meridian.progression.pb.ProgressionMessages.PlayerProgress.newBuilder()
                .setSchemaVersion(1).setQiNotesRead(true).setGatheringScrollRead(true).setMiddleJiaoScrollRead(true).build());
        for (String id : dev.kizuna.meridian.human.server.HumanCultivation.MERIDIANS) {
            context.assertTrue(body.completeOpening(0, id) == FailureCode.FAILURE_CODE_UNSPECIFIED, "OP 完成失败");
        }
        context.assertTrue(body.snapshot().structure().lumenVolume() == 15000, "八脉扩容不符");
        body.debugQi(0, DebugQi.newBuilder().setMode(DebugQiMode.SET_INVENTORY).setAmount(150).setDensity(.01).build());
        long sequence = 1;
        body.cultivate(0, input(sequence++, 0, dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.START_MANUAL));
        long now = 0;
        while (now < 150_000 && dev.kizuna.meridian.human.server.HumanCultivation.state(body.snapshot()).getLaps() == 0) {
            var state = dev.kizuna.meridian.human.server.HumanCultivation.state(body.snapshot());
            var operation = state.getThrust() < .95
                    ? dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.PUSH_ON
                    : dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.PUSH_OFF;
            body.cultivate(now, input(sequence++, now, operation));
            if (state.getGateRetryUntil() > 0 && state.getExhaling() && Math.abs(now - state.getGateRetryUntil()) <= 400) {
                body.cultivate(now, input(sequence++, now,
                        dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.STRIKE_GATE));
            }
            now += 250;
            body.settle(now);
        }
        context.assertTrue(dev.kizuna.meridian.human.server.HumanCultivation.state(body.snapshot()).getLaps() == 1,
                "正式宿主未完成手动提纯");
        // 健康宿主在同tick先读后写，重复结清不能让伤害请求凭空遭遇版本冲突。
        long revision = body.snapshot().revision();
        body.settle(now);
        context.assertTrue(body.snapshot().revision() == revision, "同tick活动结算重复改版");
        double total = body.snapshot().totalQi();
        body.writeProgress(body.progress().toBuilder().setQiNotesRead(false).build());
        context.assertTrue(body.snapshot().transits().stream().noneMatch(item -> item.circuit() != null), "重置遗留闭环在途");
        context.assertTrue(Math.abs(total - body.snapshot().totalQi()) < 1e-7, "撤权丢失真元");
        context.assertTrue(!dev.kizuna.meridian.human.server.HumanCultivation.state(body.snapshot()).getRunning(), "撤权未停机");
        context.complete();
    }

    private static dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput input(long sequence, long now,
            dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation operation) {
        // 测试输入与正式客户端相同，不携带资源或成功判定。
        return dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.newBuilder()
                .setSequence(sequence).setOperation(operation).setKeyTick(now / 50).build();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void playerHostDiffusesThroughCrossedMiddleJiaoCycle(TestContext context) {
        // 使用游戏创建的玩家身份，身体与推进均走正式宿主及唯一权威账本。
        var player = context.createMockSurvivalPlayer();
        HumanPlayerBody body = HumanPlayerBody.born(player.getUuid(), "gametest", 0);
        List<String> cycle = List.of("left_lung_meridian", "left_large_intestine_meridian",
                "right_stomach_meridian", "right_spleen_meridian", "right_lung_meridian",
                "right_large_intestine_meridian", "left_stomach_meridian", "left_spleen_meridian");
        var structure = BodyProtobuf.toMessage(body.snapshot()).getStructure();
        for (int index = 0; index < cycle.size(); index++) {
            String from = cycle.get(index) + ".out";
            String to = cycle.get((index + 1) % cycle.size()) + ".in";
            context.assertTrue(structure.getConnectionsList().stream()
                    .anyMatch(edge -> edge.getFrom().equals(from) && edge.getTo().equals(to)), "中焦周天缺少连接：" + from);
        }
        double originalTotal = body.snapshot().totalQi();
        var result = body.debugQi(0, DebugQi.newBuilder().setMode(DebugQiMode.INJECT_FOR_DIFFUSION)
                .setMeridianId("left_lung_meridian").setAmount(.2).build());
        context.assertTrue(result == FailureCode.FAILURE_CODE_UNSPECIFIED, "可信注入应进入肺经");

        // 早期运输应从左大肠去右胃，不能沿旧的同侧连接；保存后仍沿原权威路线继续。
        body.observe(0, HumanPlayerBody.Motion.IDLE);
        boolean crossed = false;
        for (int millis = 50; millis <= 5000; millis += 50) {
            body.settle(millis);
            crossed |= body.snapshot().transits().stream().anyMatch(transit -> transit.connectionId().value()
                    .equals("left_large_intestine_meridian.to.right_stomach_meridian"));
        }
        context.assertTrue(crossed, "左肺产物必须沿跨侧连接扩散");
        var saved = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body.snapshot()));
        var restored = new HumanPlayerBody(saved, body.receipts(), body.releasedQi());
        restored.settle(120_000);
        context.assertTrue(Math.abs(restored.snapshot().totalQi() - originalTotal - .2) < 1e-12, "存档与扩散必须守恒");
        context.assertTrue(restored.snapshot().meridianStates().stream()
                .allMatch(state -> state.opening().stage() == MeridianOpening.Stage.UNOPENED), "被动扩散不能替代主动开脉");
        context.assertTrue(restored.snapshot().meridianStates().stream().filter(state -> cycle.contains(state.id().value()))
                .allMatch(state -> state.lumenQi().totalQi() > .01), "中焦八脉应被真实扩散滋养");
        context.complete();
    }
}
