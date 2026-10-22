package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.MeridianOpening;
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
