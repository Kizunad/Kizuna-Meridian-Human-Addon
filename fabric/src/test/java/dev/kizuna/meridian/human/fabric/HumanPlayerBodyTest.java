package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodyActions;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.ActionProtobuf;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.*;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** 整体回归：Minecraft 运动编排走人体注册内容与框架权威命令链。 */
public class HumanPlayerBodyTest {
    @Test
    public void debugInjectionUsesRealDiffusionAndFillPreservesOpeningState() {
        // 调试直接增加入口真元，随后与普通摄入产物共享真实事件扩散。
        HumanPlayerBody body = born();
        double before = body.snapshot().totalQi();
        String entry = body.diffusionEntry();
        var inject = dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi.newBuilder()
                .setMode(dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode.INJECT_FOR_DIFFUSION)
                .setMeridianId(entry).setAmount(.2).build();
        assertEquals(FailureCode.FAILURE_CODE_UNSPECIFIED, body.debugQi(0, inject));
        assertEquals(before + .2, body.snapshot().totalQi(), 1e-12);
        body.observe(0, HumanPlayerBody.Motion.IDLE);
        assertFalse(body.snapshot().transits().isEmpty());
        body.settle(120_000);
        assertEquals(before + .2, body.snapshot().totalQi(), 1e-12);
        assertTrue(body.snapshot().meridianStates().stream().allMatch(state ->
                state.opening().stage() == dev.kizuna.meridian.core.MeridianOpening.Stage.UNOPENED));
        assertNull(body.snapshot().openingTask());
        assertEquals(FailureCode.FAILURE_CODE_UNSPECIFIED, body.debugQi(120_000,
                inject.toBuilder().setMode(dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode.FILL_LUMENS)
                        .clearMeridianId().clearAmount().build()));
        assertEquals(20, body.snapshot().lumenQiTotal(), 1e-9);
        assertNull(body.snapshot().openingTask());
    }
    @Test
    public void openingCommandsRejectWithoutLockingHostAndDisconnectReleasesTheirAction() {
        // 准备不足属于玩家可调整的业务拒绝，拒绝后运动和时间结算仍能继续。
        HumanPlayerBody initial = born();
        MeridianOpeningRequest initialRequest = openingRequest(initial, "initial-opening");
        assertEquals(FailureCode.INSUFFICIENT_QI, initial.open(0, initialRequest).getFailure().getCode());
        assertNull(initial.snapshot().openingTask());
        initial.observe(1000, HumanPlayerBody.Motion.WALK);
        initial.observe(1000, HumanPlayerBody.Motion.IDLE);

        // 可信存档预置满储量以隔离操作接线；运行仍走真实命令与通路。
        var message = BodyProtobuf.toMessage(initial.snapshot()).toBuilder();
        for (int index = 0; index < message.getMeridianStatesCount(); index++) {
            var state = message.getMeridianStates(index);
            double capacity = message.getStructure().getMeridiansList().stream()
                    .filter(meridian -> meridian.getId().equals(state.getId())).findFirst().orElseThrow().getLumenCapacity();
            message.setMeridianStates(index, state.toBuilder().setLumenQi(BodyMessages.QiInventory.newBuilder()
                    .addEntries(BodyMessages.Qi.newBuilder().setPhase(BodyMessages.QiPhase.FREE)
                            .setAttribute("meridian:none").setAmount(capacity))));
        }
        HumanPlayerBody body = new HumanPlayerBody(BodyProtobuf.fromMessage(message.build()), List.of(), QiInventory.empty());
        double initialQi = body.snapshot().totalQi();
        body.observe(1000, HumanPlayerBody.Motion.WALK);
        MeridianOpeningRequest request = openingRequest(body, "start-opening");
        var started = body.open(1500, request);
        assertFalse(started.hasFailure());
        assertEquals(1500, body.snapshot().settledAt().elapsedMillis());

        // 同连接重试不重复结时或扣费，过期版本也不能借宿主刷新版本后生效。
        var afterStart = body.snapshot();
        assertEquals(started, body.open(1600, request));
        assertEquals(afterStart, body.snapshot());
        MeridianOpeningRequest stalePause = request.toBuilder().setRequestId("stale-pause")
                .setPause(PauseMeridianOpening.newBuilder().setMeridianId("left_lung_meridian")).build();
        assertEquals(FailureCode.REVISION_CONFLICT, body.open(1600, stalePause).getFailure().getCode());
        assertEquals(afterStart, body.snapshot());

        // 开脉会先结身体时钟，运动仍按自己的已计费边界补齐整个行走区间。
        body.observe(2000, HumanPlayerBody.Motion.IDLE);
        assertEquals(1.0 / 3, fatigue(body, "left_rectus_femoris"), 1e-9);
        double brainFatigue = body.snapshot().organStates().stream()
                .filter(organ -> organ.id().value().equals("brain")).findFirst().orElseThrow().fatigueJoules();
        assertEquals(0.5, brainFatigue, 1e-9);
        assertTrue(body.snapshot().openingTask().active());
        assertTrue(HumanOpeningNumbers.summary(body.snapshot()).contains("开"));
        body.disconnectGathering(2000);
        assertEquals(dev.kizuna.meridian.core.OpeningTask.Status.PAUSED, body.snapshot().openingTask().status());
        assertTrue(body.snapshot().actions().isEmpty());
        assertEquals(initialQi, body.snapshot().totalQi() + body.releasedQi().totalQi(), 1e-9);
        assertEquals(FailureCode.OPENING_NOT_ACTIVE, body.pauseOpening(2000));
    }

    private static MeridianOpeningRequest openingRequest(HumanPlayerBody body, String requestId) {
        // 使用界面同一契约，客户端只选规则并携带看到的身体版本。
        return MeridianOpeningRequest.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setBodyId(body.snapshot().id().value()).setExpectedRevision(body.snapshot().revision())
                .setStart(StartMeridianOpening.newBuilder().setRuleId("human:open/left_lung_meridian")
                        .setRuleVersion(dev.kizuna.meridian.human.server.HumanOpening.RULE_VERSION)).build();
    }

    @Test
    public void playerBreathingSharesMovementEnvironmentAndSavedRecovery() {
        // 真实玩家会话与运动使用同一身体和区域；合法动作更新不能使加工误报版本冲突。
        var store = new InMemoryBodyStore(List.of(), List.of(), QiInventory.empty());
        String epoch = UUID.randomUUID().toString();
        HumanPlayerBody body = HumanPlayerBody.born(UUID.randomUUID(), epoch, 0, store);
        HumanPlayerBody neighbor = HumanPlayerBody.born(UUID.randomUUID(), epoch, 0, store);
        var origin = BodyProtobuf.toTime(body.snapshot().settledAt());
        var inventory = BodyMessages.QiInventory.newBuilder();
        for (String attribute : HumanContent.INPUT_ATTRIBUTES) {
            inventory.addEntries(BodyMessages.Qi.newBuilder().setPhase(BodyMessages.QiPhase.FREE)
                        .setAttribute(attribute).setAmount(20));
        }
        store.registerEnvironmentSource(EnvironmentAbsorption.initialize(EnvironmentSpace.newBuilder()
                        .setProtocolVersion(1).setSourceId("test:shared").setKind(EnvironmentSpaceKind.REGION_BACKGROUND)
                        .setVolumeBlocks(100).build(), BodyProtobuf.fromInventory(inventory.build()), origin,
                origin.toBuilder().setElapsedMillis(120_000).build(), new EnvironmentQiRules(EnvironmentConfigLoader.defaults())));
        body.selectEnvironment("test:shared", 0);
        neighbor.selectEnvironment("test:shared", 0);
        body.connectGathering(0, response -> { /* 状态推送不承担任何结算。 */ });
        neighbor.connectGathering(0, response -> { /* 第二个身体只读取同一个区域。 */ });
        double initialTotal = body.snapshot().totalQi() + neighbor.snapshot().totalQi() + 100;
        double initialLumen = body.snapshot().lumenQiTotal();
        GatheringView view = breath(body, 0, 1, BreathAction.BEGIN_BREATHING).getView();

        for (int step = 0; step < 640; step++) {
            long millis = step * 50L;
            body.advanceGathering(millis);
            body.observe(millis, HumanPlayerBody.Motion.WALK);
            BreathAction action = step % 160 < 80 ? BreathAction.INHALE : BreathAction.EXHALE;
            GatheringResponse response = breath(body, millis, step + 2, action);
            assertTrue(response.hasView());
            view = response.getView();
            assertNotEquals(GatheringStage.FAILED, view.getProgress().getStage());
            // 配送暂时占满吞吐或存储时仍保持吐纳；身份、环境和内部错误不能混作正常等待。
            assertTrue(view.getBreathing().getActive());
            FailureCode feedback = view.getBreathing().getFailure();
            assertTrue(feedback.toString(), feedback == FailureCode.FAILURE_CODE_UNSPECIFIED
                    || feedback == FailureCode.THROUGHPUT_EXCEEDED || feedback == FailureCode.CAPACITY_EXCEEDED);
        }
        assertTrue(view.getBreathing().getAbsorbedQi() > 0);
        assertTrue(body.snapshot().lumenQiTotal() > initialLumen);
        // 原料与产物分别统计；只有实际到货才累计纳入真元，不能把五倍原料显示成余额。
        assertTrue(view.getBreathing().getStoredQi() > 0);
        assertTrue(view.getBreathing().getStoredQi() <= view.getBreathing().getAbsorbedQi() * .2 + 1e-9);
        double pendingBodyQi = body.snapshot().totalQi() - initialLumen - view.getBreathing().getStoredQi();
        assertTrue(pendingBodyQi >= -1e-9);
        assertTrue(fatigue(body, "diaphragm") > 0);
        assertTrue(fatigue(body, "attention_network") > 0);
        assertTrue(fatigue(body, "left_rectus_femoris") > 0);
        assertEquals(0, fatigue(body, "left_biceps_brachii"), 0);
        var inspect = GatheringRequest.newBuilder().setProtocolVersion(1).setRequestId("neighbor-inspect")
                .setBodyId(neighbor.snapshot().id().value()).setInspect(InspectGathering.getDefaultInstance());
        assertEquals(store.environmentSource("test:shared").orElseThrow().getInventory(),
                neighbor.gather(31_950, inspect.build()).getView().getEnvironment().getQi());
        assertEquals(FailureCode.ACCESS_DENIED, body.gather(31_950, inspect.build()).getFailure().getCode());
        assertEquals(initialTotal, body.snapshot().totalQi() + neighbor.snapshot().totalQi()
                + store.environmentQi().totalQi()
                + BodyProtobuf.fromInventory(store.environmentSource("test:shared").orElseThrow().getInventory()).totalQi(), 1e-9);

        // 保存身体、共享来源及回执后重连；只解除本会话占用，离线不恢复，在线休息才指数恢复。
        var saved = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body.snapshot()));
        double savedFatigue = fatigue(body, "diaphragm");
        var restoredStore = new InMemoryBodyStore(List.of(saved, neighbor.snapshot()), store.receipts(),
                store.environmentQi(), store.environmentSources());
        HumanPlayerBody restored = new HumanPlayerBody(saved, restoredStore, body.breathingActions());
        restored.resume(62_000);
        assertTrue(restored.snapshot().actions().isEmpty());
        assertEquals(saved.totalQi(), restored.snapshot().totalQi(), 1e-9);
        assertEquals(savedFatigue, fatigue(restored, "diaphragm"), 0);
        restored.observe(92_000, HumanPlayerBody.Motion.IDLE);
        assertEquals(savedFatigue / 2, fatigue(restored, "diaphragm"), 1e-9);
    }

    private static GatheringResponse breath(HumanPlayerBody body, long millis, long sequence, BreathAction action) {
        // 只上传公开控制意图，身体身份与时间分别由测试宿主绑定，不自报摄入结果。
        return body.gather(millis, GatheringRequest.newBuilder().setProtocolVersion(1)
                .setRequestId("breath-" + sequence).setBodyId(body.snapshot().id().value())
                .setBreath(BreathControl.newBuilder().setSequence(sequence).setAction(action)
                        .setAimX(.5).setAimY(.4).setAimZ(.5)).build());
    }

    @Test
    public void walkSprintAndRestSettleOnlyTheirOwnIntervals() {
        // 动作切换先结清旧负载；同一时刻查询不能再次消耗，空闲后才开始指数恢复。
        HumanPlayerBody body = born();
        body.observe(0, HumanPlayerBody.Motion.WALK);
        assertFalse(body.observe(50, HumanPlayerBody.Motion.WALK));
        body.observe(10_000, HumanPlayerBody.Motion.SPRINT);
        assertEquals(100.0 / 30, fatigue(body, "left_rectus_femoris"), 1e-9);
        assertEquals(0, fatigue(body, "left_biceps_brachii"), 0);
        body.observe(20_000, HumanPlayerBody.Motion.IDLE);
        double tired = fatigue(body, "left_rectus_femoris");
        assertEquals(100.0 / 6, tired, 1e-9);
        long revision = body.snapshot().revision();
        body.settle(20_000);
        assertEquals(revision, body.snapshot().revision());
        assertTrue(body.snapshot().actions().isEmpty());
        body.observe(50_000, HumanPlayerBody.Motion.IDLE);
        assertEquals(tired / 2, fatigue(body, "left_rectus_femoris"), 1e-9);
        assertTrue(HumanNumbers.tissue(body.snapshot(), "left_rectus_femoris").contains("空闲"));
    }

    @Test
    public void reconnectPreservesFatigueAndOtherActionOccupancy() {
        // 吐纳与走路可以同时占用不同组织；保存恢复只解除宿主残留的移动实例。
        HumanPlayerBody initial = born();
        var breathing = HumanContent.bundle().getActionsList().stream()
                .filter(action -> action.getId().equals("human:breathing")).findFirst().orElseThrow();
        var active = BodyActions.start(initial.snapshot(), "other.breath", ActionProtobuf.fromDefinition(breathing));
        HumanPlayerBody body = new HumanPlayerBody(active, List.of(), initial.releasedQi());
        body.observe(0, HumanPlayerBody.Motion.SPRINT);
        body.settle(15_000);
        double savedFatigue = fatigue(body, "left_rectus_femoris");
        var decoded = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body.snapshot()));
        HumanPlayerBody restored = new HumanPlayerBody(decoded, body.receipts(), body.releasedQi());
        restored.resume(75_000);
        assertEquals(savedFatigue, fatigue(restored, "left_rectus_femoris"), 0);
        assertEquals(1, restored.snapshot().actions().size());
        assertEquals("other.breath", restored.snapshot().actions().get(0).instanceId());
        restored.observe(105_000, HumanPlayerBody.Motion.IDLE);
        assertEquals(savedFatigue / 2, fatigue(restored, "left_rectus_femoris"), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> restored.settle(104_000));
    }

    @Test
    public void exhaustionCapsWorkAndRestStillRecovers() {
        // 长时间运动不能负耐力或让浮点误差暂停宿主；恢复完毕后空闲不再每秒提交。
        HumanPlayerBody body = born();
        body.observe(0, HumanPlayerBody.Motion.SPRINT);
        for (long millis = 1000; millis <= 100_000; millis += 1000) {
            body.observe(millis, HumanPlayerBody.Motion.SPRINT);
        }
        double tired = fatigue(body, "left_rectus_femoris");
        assertTrue(tired > 95 && tired <= 100);
        body.observe(100_000, HumanPlayerBody.Motion.IDLE);
        body.observe(130_000, HumanPlayerBody.Motion.IDLE);
        assertEquals(tired / 2, fatigue(body, "left_rectus_femoris"), 1e-9);
        body.observe(1_000_000, HumanPlayerBody.Motion.IDLE);
        assertEquals(0, fatigue(body, "left_rectus_femoris"), 0);
        assertFalse(body.observe(1_001_000, HumanPlayerBody.Motion.IDLE));
    }

    private static HumanPlayerBody born() {
        // 每个用例隔离玩家身份和时钟，避免共享存档或执行顺序影响结果。
        return HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
    }

    private static double fatigue(HumanPlayerBody body, String tissueId) {
        // 读取权威状态作为断言，不重复实现疲劳或恢复公式。
        return body.snapshot().tissueStates().stream().filter(tissue -> tissue.id().value().equals(tissueId))
                .findFirst().orElseThrow().fatigue();
    }
}
