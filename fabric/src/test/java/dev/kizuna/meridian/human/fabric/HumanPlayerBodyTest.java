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
import dev.kizuna.meridian.protocol.pb.HealthMessages.ConfirmBodyDeath;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.*;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** 整体回归：Minecraft 运动编排走人体注册内容与框架权威命令链。 */
public class HumanPlayerBodyTest {
    @Test
    public void completedProcessingReleasesLossOnceWithoutReplayingHistory() {
        // 从可信加工中存档恢复，真实推进须把损耗交给宿主，并排除历史与重复结算。
        HumanPlayerBody initial = born();
        initial.settle(0);
        var recipe = HumanContent.bundle().getOrganRecipes(0);
        var message = BodyProtobuf.toMessage(initial.snapshot()).toBuilder();
        for (int index = 0; index < message.getOrganStatesCount(); index++) {
            var organ = message.getOrganStates(index);
            if (organ.getId().equals("left_lung")) {
                message.setOrganStates(index, organ.toBuilder().setProcess(BodyMessages.OrganProcess.newBuilder()
                        .setRecipe(recipe).setInput(recipe.getInput()).setCompletionMillis(1000).setBlocked(false)));
            }
        }
        QiInventory history = BodyProtobuf.fromInventory(recipe.getInput());
        HumanPlayerBody body = new HumanPlayerBody(BodyProtobuf.fromMessage(message.build()), List.of(), history);
        var releases = new java.util.ArrayList<QiInventory>();
        body.bindReleaseSink(releases::add);
        double before = body.snapshot().totalQi();
        body.settle(1000);
        assertEquals(1, releases.size());
        assertEquals(.8, releases.get(0).totalQi(), 1e-9);
        assertEquals(before, body.snapshot().totalQi() + releases.get(0).totalQi(), 1e-9);
        assertEquals(history.volume() + releases.get(0).volume(), body.releasedQi().volume(), 1e-9);
        body.settle(1000);
        assertEquals(1, releases.size());
    }

    @Test
    public void deathReleaseExcludesHistoryWithDifferentDensity() {
        // 历史释放与死亡释放同类但密度不同，回调仍须独立保留本次数量和体积。
        HumanPlayerBody initial = born();
        initial.settle(0);
        var saved = initial.snapshot();
        var expected = dev.kizuna.meridian.core.BodyDeath.release(
                saved.meridianStates(), saved.organStates(), saved.transits()).qi();
        var history = new QiInventory(expected.entries().stream().map(entry -> new dev.kizuna.meridian.core.Qi(
                entry.attribute(), entry.amount() * 2,
                dev.kizuna.meridian.protocol.QiDensity.clampToPhase(entry.density() * 1.5,
                        BodyMessages.QiPhase.valueOf(entry.phase().name())))).toList());
        assertNotEquals(expected.entries().get(0).density(), history.entries().get(0).density(), 0);
        HumanPlayerBody body = new HumanPlayerBody(saved, initial.receipts(), history);
        var releases = new java.util.ArrayList<QiInventory>();
        body.bindReleaseSink(releases::add);
        assertTrue(releases.isEmpty());
        var death = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("mixed-density-death")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(saved.id().value())
                        .setExpectedRevision(saved.revision()).setConfirmBodyDeath(
                                ConfirmBodyDeath.newBuilder().setSourceId("sample:vanilla"))).build();
        assertTrue(body.applyHealth(0, death).hasCommandCommitted());
        assertEquals(1, releases.size());
        assertEquals(expected.totalQi(), releases.get(0).totalQi(), 1e-9);
        assertEquals(expected.volume(), releases.get(0).volume(), 1e-9);
        assertTrue(body.applyHealth(0, death).hasCommandCommitted());
        assertEquals(1, releases.size());
    }

    @Test
    public void trustedDamageUsesCurrentRevisionAndDeathRetainsInjuriesAfterReload() {
        // 伤害附属先查询后提交，宿主保持原请求身份，重试不会重复损伤或释放。
        HumanPlayerBody body = born();
        body.settle(0);
        var releases = new java.util.ArrayList<QiInventory>();
        body.bindReleaseSink(releases::add);
        var original = body.snapshot();
        var effect = RequestEnvelope.newBuilder()
                .setProtocolVersion(1).setRequestId("vanilla-hit")
                .setBodyCommand(BodyCommand.newBuilder()
                        .setBodyId(original.id().value()).setExpectedRevision(original.revision())
                        .setDamageBody(DamageBody.newBuilder()
                                .setSourceId("sample:vanilla").setDamageType("minecraft:arrow")
                                .setKind(DamageKind.PIERCING)
                                .setDamageHp(2).setHit(BodyMessages.HealthTarget.newBuilder().setPartId("head")))).build();
        var result = body.applyHealth(0, effect);
        assertTrue(result.toString(), result.hasCommandCommitted());
        var hurt = body.snapshot();
        double originalHeadHp = original.partStates().stream()
                .filter(part -> part.id().value().equals("head")).findFirst().orElseThrow().hp();
        double hurtHeadHp = hurt.partStates().stream()
                .filter(part -> part.id().value().equals("head")).findFirst().orElseThrow().hp();
        assertEquals(originalHeadHp - 2, hurtHeadHp, 0);
        assertTrue(body.applyHealth(0, effect).hasCommandCommitted());
        assertEquals(hurt, body.snapshot());

        var death = effect.toBuilder().setRequestId("vanilla-death")
                .setBodyCommand(effect.getBodyCommand().toBuilder().setExpectedRevision(hurt.revision())
                        .setConfirmBodyDeath(ConfirmBodyDeath.newBuilder()
                                .setSourceId("sample:vanilla"))).build();
        assertTrue(body.applyHealth(0, death).hasCommandCommitted());
        assertEquals(1, releases.size());
        assertEquals(hurt.totalQi(), releases.get(0).totalQi(), 1e-9);
        assertTrue(body.applyHealth(0, death).hasCommandCommitted());
        assertEquals(1, releases.size());
        assertTrue(body.snapshot().vitality().dead());
        assertEquals(hurt.partStates(), body.snapshot().partStates());
        assertEquals(hurt.tissueStates(), body.snapshot().tissueStates());
        var restored = new HumanPlayerBody(body.snapshot(), body.receipts(), body.releasedQi());
        restored.bindReleaseSink(releases::add);
        assertTrue(restored.applyHealth(0, death).hasCommandCommitted());
        assertEquals(body.snapshot(), restored.snapshot());
        assertEquals(body.releasedQi(), restored.releasedQi());
        assertTrue(restored.applyHealth(0, effect.toBuilder().setRequestId("stale-hit").build()).hasFailure());
        assertEquals(1, releases.size());
    }

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
        unlock(initial);
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
                    .filter(meridian -> meridian.getId().equals(state.getId())).findFirst().orElseThrow().getLumenVolume();
            message.setMeridianStates(index, state.toBuilder().setLumenQi(BodyMessages.QiInventory.newBuilder()
                    .addEntries(BodyMessages.Qi.newBuilder().setDensity(.01)
                            .setAttribute("meridian:none").setAmount(capacity * .01))));
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
    public void playerBreathingSharesEnvironmentAndSavedRecovery() {
        // 真实静坐会话使用共享身体和区域；周期结算不能使加工误报版本冲突。
        var store = new InMemoryBodyStore(List.of(), List.of(), QiInventory.empty());
        String epoch = UUID.randomUUID().toString();
        HumanPlayerBody body = HumanPlayerBody.born(UUID.randomUUID(), epoch, 0, store);
        HumanPlayerBody neighbor = HumanPlayerBody.born(UUID.randomUUID(), epoch, 0, store);
        var releases = new java.util.ArrayList<QiInventory>();
        var neighborReleases = new java.util.ArrayList<QiInventory>();
        body.bindReleaseSink(releases::add);
        neighbor.bindReleaseSink(neighborReleases::add);
        unlock(body);
        unlock(neighbor);
        var origin = BodyProtobuf.toTime(body.snapshot().settledAt());
        var inventory = BodyMessages.QiInventory.newBuilder();
        for (String attribute : HumanContent.INPUT_ATTRIBUTES) {
            inventory.addEntries(BodyMessages.Qi.newBuilder().setDensity(.01)
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
            body.observe(millis, HumanPlayerBody.Motion.IDLE);
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
        assertEquals(0, fatigue(body, "left_rectus_femoris"), 0);
        assertEquals(0, fatigue(body, "left_biceps_brachii"), 0);
        var inspect = GatheringRequest.newBuilder().setProtocolVersion(1).setRequestId("neighbor-inspect")
                .setBodyId(neighbor.snapshot().id().value()).setInspect(InspectGathering.getDefaultInstance());
        assertEquals(store.environmentSource("test:shared").orElseThrow().getInventory(),
                neighbor.gather(31_950, inspect.build()).getView().getEnvironment().getQi());
        assertEquals(FailureCode.ACCESS_DENIED, body.gather(31_950, inspect.build()).getFailure().getCode());
        assertEquals(initialTotal, body.snapshot().totalQi() + neighbor.snapshot().totalQi()
                + store.environmentQi().totalQi()
                + BodyProtobuf.fromInventory(store.environmentSource("test:shared").orElseThrow().getInventory()).totalQi(), 1e-9);
        // 加工损耗逐笔归位，读取同一个共享账本的邻居不能重复领取释放。
        assertTrue(neighborReleases.isEmpty());
        assertEquals(store.environmentQi().totalQi(), releases.stream().mapToDouble(QiInventory::totalQi).sum(), 1e-9);
        assertEquals(store.environmentQi().volume(), releases.stream().mapToDouble(QiInventory::volume).sum(), 1e-9);
        assertTrue(releases.stream().flatMap(release -> release.entries().stream()).allMatch(entry -> entry.amount() > 0));

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

        // 撤权终止当前吐纳，邻居的独立进度保持不变；旧控制意图不能继续执行。
        var breathingInstances = body.breathingActions();
        assertFalse(breathingInstances.isEmpty());
        body.writeProgress(body.progress().toBuilder().setQiNotesRead(false).build());
        assertTrue(body.snapshot().actions().stream()
                .noneMatch(action -> breathingInstances.contains(action.instanceId())));
        assertTrue(body.snapshot().actions().isEmpty());
        assertTrue(neighbor.progress().getQiNotesRead());
        assertEquals(FailureCode.ACCESS_DENIED,
                breath(body, 31_950, 642, BreathAction.INHALE).getFailure().getCode());
    }

    private static GatheringResponse breath(HumanPlayerBody body, long millis, long sequence, BreathAction action) {
        // 默认请求明确保持手动模式，方便回归验证移动停止规则。
        return breath(body, millis, sequence, action, false);
    }

    private static GatheringResponse breath(HumanPlayerBody body, long millis, long sequence, BreathAction action,
            boolean automatic) {
        // 只上传公开控制意图，身体身份与时间分别由测试宿主绑定，不自报摄入结果。
        return body.gather(millis, GatheringRequest.newBuilder().setProtocolVersion(1)
                .setRequestId("breath-" + sequence).setBodyId(body.snapshot().id().value())
                .setBreath(BreathControl.newBuilder().setSequence(sequence).setAction(action)
                        .setAutomatic(automatic).setAimX(.5).setAimY(.4).setAimZ(.5)).build());
    }

    @Test
    public void movementEndsBreathingThroughTheAuthoritativeControlPath() {
        // 正式开始并占用膈肌；位置变化必须先结清旧输入，再交给运动动作。
        HumanPlayerBody body = born();
        unlock(body);
        var responses = new java.util.ArrayList<GatheringResponse>();
        body.connectGathering(0, responses::add);
        assertTrue(breath(body, 0, 1, BreathAction.BEGIN_BREATHING).getView().getBreathing().getActive());
        assertFalse(breath(body, 0, 2, BreathAction.INHALE).hasFailure());
        var breathingInstances = body.breathingActions();
        assertFalse(breathingInstances.isEmpty());
        assertEquals("meditate", HumanPlayerAnimation.select(body.motion(), body.breathingActive(), body.breathingAutomatic()));

        body.observe(100, HumanPlayerBody.Motion.WALK, true);
        assertFalse(body.breathingActive());
        assertEquals(BreathAction.END_BREATHING, responses.get(responses.size() - 1).getView().getBreathing().getAction());
        assertTrue(body.snapshot().actions().stream().noneMatch(action -> breathingInstances.contains(action.instanceId())));
        assertTrue(fatigue(body, "diaphragm") > 0);
        assertEquals("walk", HumanPlayerAnimation.select(body.motion(), body.breathingActive(), body.breathingAutomatic()));
        assertFalse(breath(body, 150, 4, BreathAction.INHALE).getView().getBreathing().getActive());
        body.observe(200, HumanPlayerBody.Motion.SPRINT, true);
        assertEquals("run", HumanPlayerAnimation.select(body.motion(), body.breathingActive(), body.breathingAutomatic()));
        body.observe(250, HumanPlayerBody.Motion.IDLE, false);
        assertEquals("", HumanPlayerAnimation.select(body.motion(), body.breathingActive(), body.breathingAutomatic()));

        // 跳跃等非地面位移也结束静坐，但不伪造 walk 动作。
        assertTrue(breath(body, 300, 5, BreathAction.BEGIN_BREATHING).getView().getBreathing().getActive());
        body.observe(350, HumanPlayerBody.Motion.IDLE, true);
        assertFalse(body.breathingActive());
        assertEquals("", HumanPlayerAnimation.select(body.motion(), body.breathingActive(), body.breathingAutomatic()));
    }

    @Test
    public void automaticBreathingContinuesDuringMovementWhileManualBreathingStillStops() {
        // 自动模式由服务端维持；移动只替换走跑动作，不结束吐纳占用或呼吸会话。
        HumanPlayerBody body = born();
        unlock(body);
        var responses = new java.util.ArrayList<GatheringResponse>();
        body.connectGathering(0, responses::add);
        GatheringResponse started = breath(body, 0, 1, BreathAction.BEGIN_BREATHING, true);
        assertTrue(started.getView().getBreathing().getActive());
        assertTrue(started.getView().getBreathing().getAutomatic());
        var breathingInstances = body.breathingActions();
        assertFalse(breathingInstances.isEmpty());

        body.observe(100, HumanPlayerBody.Motion.WALK, true);
        for (long millis = 250; millis <= 2_000; millis += 250) {
            body.advanceGathering(millis);
            body.observe(millis, HumanPlayerBody.Motion.WALK, true);
        }

        assertTrue(body.breathingActive());
        assertTrue(body.breathingAutomatic());
        assertTrue(body.snapshot().actions().stream().anyMatch(action -> breathingInstances.contains(action.instanceId())));
        assertTrue(fatigue(body, "diaphragm") > 0);
        assertEquals(0, fatigue(body, "attention_network"), 0);
        assertEquals("walk", HumanPlayerAnimation.select(body.motion(), body.breathingActive(), body.breathingAutomatic()));
        assertTrue(responses.stream().noneMatch(response -> response.hasView()
                && response.getView().getBreathing().getAction() == BreathAction.END_BREATHING));

        // 手动模式仍在首次真实位移时发送正式结束意图。
        HumanPlayerBody manual = born();
        unlock(manual);
        manual.connectGathering(0, response -> {});
        assertTrue(breath(manual, 0, 1, BreathAction.BEGIN_BREATHING).getView().getBreathing().getActive());
        manual.observe(100, HumanPlayerBody.Motion.WALK, true);
        assertFalse(manual.breathingActive());
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

    private static void unlock(HumanPlayerBody body) {
        // 行为回归显式建立已读条件，不能靠测试宿主绕过正式授权。
        body.writeProgress(body.progress().toBuilder().setQiNotesRead(true)
                .setGatheringScrollRead(true).setMiddleJiaoScrollRead(true).build());
    }

    @Test
    public void threeScrollsGateSeparateServerOperationsAndSurviveSave() {
        // 札记只授权修炼页，旧客户端直接发自动或手动意图仍被服务器拒绝。
        HumanPlayerBody body = born();
        body.writeProgress(body.progress().toBuilder().setQiNotesRead(true).build());
        body.connectGathering(0, response -> {});
        assertTrue(body.canIntrospect(body.snapshot().id().value()));
        assertEquals(FailureCode.ACCESS_DENIED, breath(body, 0, 1, BreathAction.BEGIN_BREATHING).getFailure().getCode());
        for (var operation : List.of(
                dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.START_MANUAL,
                dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.START_AUTOMATIC,
                dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.MOBILIZE_ON)) {
            assertEquals(FailureCode.ACCESS_DENIED, body.cultivate(0,
                    dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.newBuilder()
                            .setSequence(1).setOperation(operation).build()));
        }
        body.writeProgress(body.progress().toBuilder().setGatheringScrollRead(true).build());
        assertTrue(breath(body, 0, 2, BreathAction.BEGIN_BREATHING).hasView());
        assertFalse(body.progress().getMiddleJiaoScrollRead());
        body.writeProgress(body.progress().toBuilder().setMiddleJiaoScrollRead(true).build());
        assertEquals(FailureCode.FAILURE_CODE_UNSPECIFIED, body.cultivate(0,
                dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.newBuilder().setSequence(2)
                        .setOperation(dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation.MOBILIZE_ON).build()));
        body.writeProgress(body.progress().toBuilder().setGatheringScrollRead(false).build());
        assertFalse(body.breathingActive());
        var saved = new HumanPlayerBody(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body.snapshot())),
                body.receipts(), body.releasedQi());
        assertTrue(saved.progress().getQiNotesRead());
        assertFalse(saved.progress().getGatheringScrollRead());
        assertTrue(saved.progress().getMiddleJiaoScrollRead());
    }

    @Test
    public void unreadRequestsAreDeniedAndResetPreservesBodyFacts() {
        // 未读仍正常出生与运动；吐纳、开脉必须由身体进度授权。
        HumanPlayerBody body = born();
        assertFalse(body.progress().getQiNotesRead());
        assertFalse(body.canIntrospect(body.snapshot().id().value()));
        assertEquals(.36, body.snapshot().totalQi(), 1e-9);
        body.connectGathering(0, response -> { /* 不建立客户端影子账本。 */ });
        assertEquals(FailureCode.ACCESS_DENIED, breath(body, 0, 1, BreathAction.BEGIN_BREATHING).getFailure().getCode());
        assertEquals(FailureCode.ACCESS_DENIED, body.open(0, openingRequest(body, "locked")).getFailure().getCode());
        assertEquals(FailureCode.ACCESS_DENIED, body.startOpening(0, "left_lung_meridian"));
        unlock(body);
        assertTrue(body.canIntrospect(body.snapshot().id().value()));
        assertFalse(body.canIntrospect("another-player"));
        body.observe(1000, HumanPlayerBody.Motion.WALK);
        body.observe(2000, HumanPlayerBody.Motion.IDLE);
        assertTrue(body.progress().getQiNotesRead());
        var beforeReset = body.snapshot();
        body.writeProgress(body.progress().toBuilder().setQiNotesRead(false).build());
        assertFalse(body.canIntrospect(body.snapshot().id().value()));
        assertEquals(beforeReset.totalQi(), body.snapshot().totalQi(), 0);
        assertEquals(beforeReset.vitality(), body.snapshot().vitality());
        assertEquals(beforeReset.tissueStates(), body.snapshot().tissueStates());
        var restored = new HumanPlayerBody(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body.snapshot())),
                body.receipts(), body.releasedQi());
        assertFalse(restored.progress().getQiNotesRead());
        unlock(restored);
        var saved = new HumanPlayerBody(restored.snapshot(), restored.receipts(), restored.releasedQi());
        assertTrue(saved.progress().getQiNotesRead());
    }

    private static double fatigue(HumanPlayerBody body, String tissueId) {
        // 读取权威状态作为断言，不重复实现疲劳或恢复公式。
        return body.snapshot().tissueStates().stream().filter(tissue -> tissue.id().value().equals(tissueId))
                .findFirst().orElseThrow().fatigue();
    }
}
