package dev.kizuna.meridian.human;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.human.server.*;
import dev.kizuna.meridian.human.pb.CultivationMessages.*;
import dev.kizuna.meridian.protocol.*;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.*;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.*;

/** 整体回归：真实原子入口的闭环、提纯守恒、脾加工、持久化与突破成败。 */
public class HumanCultivationTest {
    @Test
    public void automaticNeedsNoPracticePaysOnlyBrainAndResumesAfterStaminaRecovery() {
        // 真实权威执行到停止线，检查等待状态、空闲恢复、自动续跑和显式取消。
        Host host = new Host();
        host.open();
        var initial = HumanCultivation.state(host.body());
        assertEquals(HumanCultivationNumbers.AUTOMATIC_MIN_STAMINA_RATIO,
                initial.getAutomaticMinimumStaminaRatio(), 0);
        assertEquals(HumanCultivationNumbers.AUTOMATIC_EFFICIENCY, initial.getAutomaticEfficiency(), 0);
        assertEquals(HumanCultivationNumbers.BREATH_CYCLE_MILLIS, initial.getBreathCycleMillis());
        assertEquals(HumanCultivationNumbers.EXHALE_START_MILLIS, initial.getExhaleStartMillis());
        assertEquals(HumanCultivationNumbers.GATE_RETRY_MILLIS, initial.getGateRetryMillis());
        assertTrue(initial.getBrainStaminaRatio() > .99);
        host.debug(false, 100, .01);
        host.input(CultivationInput.Operation.START_AUTOMATIC);
        assertTrue(HumanCultivation.state(host.body()).getRunning());
        assertEquals(0, HumanCultivation.state(host.body()).getPracticedGates());
        assertTrue(host.body().actions().stream().allMatch(action -> action.definition().tissueIds().isEmpty()));
        int ticks = 0;
        while (HumanCultivation.state(host.body()).getRunning() && ticks++ < 480) {
            host.tick();
        }
        assertTrue(HumanCultivation.state(host.body()).getRunning());
        // 停止边界同时到来的启动不能忽略本区间即将扣除的费用。
        host.now += 250;
        host.commit(BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        host.input(CultivationInput.Operation.START_AUTOMATIC);
        var waiting = HumanCultivation.state(host.body());
        assertFalse(waiting.getRunning());
        assertTrue(waiting.getAutomatic());
        assertTrue(waiting.getAutomaticWaiting());
        assertTrue(HumanCultivation.needsAdvance(host.body()));
        assertTrue(HumanCultivation.state(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(host.body())))
                .getAutomaticWaiting());
        double fatigue = host.body().organStates().stream().filter(organ -> organ.id().value().equals("brain"))
                .findFirst().orElseThrow().fatigueJoules();
        assertEquals(120.25, fatigue, 1e-8);
        assertEquals(0, host.body().tissueStates().stream().filter(tissue -> tissue.id().value().equals("attention_network"))
                .findFirst().orElseThrow().fatigue(), 0);
        assertTrue(host.body().actions().isEmpty());
        assertTrue(host.body().transits().stream().noneMatch(transit -> transit.circuit() != null));
        int recoveryTicks = 0;
        while (HumanCultivation.state(host.body()).getAutomaticWaiting() && recoveryTicks++ < 100) {
            host.tick();
        }
        var resumed = HumanCultivation.state(host.body());
        assertTrue(resumed.getRunning());
        assertTrue(resumed.getAutomatic());
        assertFalse(resumed.getAutomaticWaiting());
        assertTrue(resumed.getBrainStaminaRatio() >= HumanCultivationNumbers.AUTOMATIC_MIN_STAMINA_RATIO);
        assertEquals(0, host.body().tissueStates().stream().filter(tissue -> tissue.id().value().equals("attention_network"))
                .findFirst().orElseThrow().fatigue(), 0);

        // 自动续跑后再次到达停止线，显式 STOP 必须撤销等待意图。
        int waitAgainTicks = 0;
        while (!HumanCultivation.state(host.body()).getAutomaticWaiting() && waitAgainTicks++ < 10) {
            host.tick();
        }
        assertTrue(HumanCultivation.state(host.body()).getAutomaticWaiting());
        host.input(CultivationInput.Operation.STOP);
        var stoppedByPlayer = HumanCultivation.state(host.body());
        assertFalse(stoppedByPlayer.getAutomatic());
        assertFalse(stoppedByPlayer.getAutomaticWaiting());
        var cargo = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 10, .01)));
        assertTrue(HumanCultivation.purify(cargo, HumanCultivationNumbers.AUTOMATIC_EFFICIENCY).entries().get(0).density()
                < HumanCultivation.purify(cargo, 1).entries().get(0).density());
    }

    @Test
    public void completeIsIdempotentAndCircuitPaysBatchThroughputOnce() {
        // 调试跳过等待，扩容仍经过正常完成算子；重试不能再次加体积。
        Host host = new Host();
        host.open();
        assertEquals(15000, host.body().structure().lumenVolume(), 1e-8);
        host.open();
        assertEquals(15000, host.body().structure().lumenVolume(), 1e-8);
        host.debug(false, 150, .01);
        host.input(CultivationInput.Operation.START_MANUAL);
        host.tick();
        QiTransit transit = host.circuit();
        assertEquals(19000, transit.circuit().getDurationMillis(), 1e-8);
        assertEquals(10, transit.cargo().totalQi(), 1e-8);
        // all 的140元按实际可用体积分配，不能挤占这10元闭环货物的返回预约。
        host.debug(false, 140, .01);
        assertEquals(150, host.body().meridianStates().stream().mapToDouble(item -> item.lumenQi().totalQi()).sum()
                + host.circuit().cargo().totalQi(), 1e-8);
        double total = host.body().totalQi();
        host.input(CultivationInput.Operation.STOP);
        assertTrue(host.body().transits().isEmpty());
        assertEquals(total, host.body().totalQi(), 1e-8);
        assertEquals(host.body(), BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(host.body())));
    }

    @Test
    public void manualGatesPurifyAndClampUsingActualGain() {
        // 真实按键与推力运行一圈，随后验证封顶处损耗只取实际提升。
        Host host = new Host();
        host.open();
        host.debug(false, 150, .01);
        double total = host.body().totalQi() + host.store.environmentQi().totalQi();
        host.input(CultivationInput.Operation.START_MANUAL);
        for (int index = 0; index < 1000 && HumanCultivation.state(host.body()).getLaps() == 0; index++) {
            var state = HumanCultivation.state(host.body());
            host.input(state.getThrust() < .95 ? CultivationInput.Operation.PUSH_ON : CultivationInput.Operation.PUSH_OFF);
            if (state.getGateRetryUntil() > 0 && state.getExhaling()
                    && Math.abs(host.now - state.getGateRetryUntil()) <= 400) {
                host.input(CultivationInput.Operation.STRIKE_GATE);
            }
            host.tick();
        }
        assertEquals(1, HumanCultivation.state(host.body()).getLaps());
        assertEquals(4, HumanCultivation.state(host.body()).getPracticedGates());
        assertTrue(host.circuit().cargo().entries().get(0).density() > .01);
        assertEquals(total, host.body().totalQi() + host.store.environmentQi().totalQi(), 1e-7);
        // 已排出的杂气必须随存档恢复；省略环境账不能静默丢失损耗。
        assertThrows(IllegalArgumentException.class, () -> new InMemoryBodyStore(List.of(host.body()), host.store.receipts()));
        var restored = new InMemoryBodyStore(List.of(host.body()), host.store.receipts(), host.store.environmentQi());
        assertEquals(total, restored.find(host.id).orElseThrow().totalQi() + restored.environmentQi().totalQi(), 1e-7);
        var nearCap = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 10, .0989)));
        QiInventory capped = HumanCultivation.purify(nearCap, 1);
        assertEquals(.099, capped.entries().get(0).density(), 1e-12);
        assertEquals(10 * Math.pow(.099 / .0989, -.66), capped.totalQi(), 1e-10);
        assertEquals(capped, HumanCultivation.purify(capped, 1));
    }

    @Test
    public void spleenProcessingDepositsThenMobilizesWithoutChangingDensity() {
        // 高占用触发真实器官配方，输出不提前入壁；动员只搬运已有储备。
        Host host = new Host();
        host.open();
        host.debug(false, 1480, .099);
        double total = host.body().totalQi();
        for (int index = 0; index < 16; index++) {
            host.tick();
        }
        assertTrue(host.body().wallQiTotal() > 0);
        assertEquals(.099, host.body().meridianStates().stream().flatMap(item -> item.wallQi().entries().stream())
                .findFirst().orElseThrow().density(), 1e-10);
        assertEquals(total, host.body().totalQi() + host.store.environmentQi().totalQi(), 1e-6);
        host.debug(false, 60, .099);
        double wall = host.body().wallQiTotal();
        host.input(CultivationInput.Operation.MOBILIZE_ON);
        host.tick();
        assertTrue(host.body().wallQiTotal() < wall);
        assertTrue(host.body().organStates().stream().filter(item -> item.id().value().equals("spleen"))
                .findFirst().orElseThrow().fatigueJoules() > 0);
    }

    @Test
    public void breakthroughIncludesTransitConsumesSixtyThenChangesPhaseAndFailureDoesNotRefund() {
        // 候选含这次周天在途；启动后不再要求外壁每秒仍有60元。
        Host host = preparedBreakthrough();
        assertTrue(host.body().lumenQiTotal() < 50);
        assertTrue(host.body().lumenQiTotal() + host.circuit().cargo().totalQi() >= 50);
        host.input(CultivationInput.Operation.START_BREAKTHROUGH);
        for (int index = 0; index < 240; index++) {
            host.tick();
        }
        assertEquals(Realm.TRUE_QI, host.body().realm());
        assertEquals(0, host.body().wallQiTotal(), 1e-7);
        assertTrue(host.body().meridianStates().stream().flatMap(item -> item.lumenQi().entries().stream())
                .anyMatch(qi -> qi.density() == .1));
        assertEquals(60, HumanCultivation.state(host.body()).getBreakthroughSeconds(), 0);

        Host failed = preparedBreakthrough();
        failed.input(CultivationInput.Operation.START_BREAKTHROUGH);
        for (int index = 0; index < 8; index++) {
            failed.tick();
        }
        failed.input(CultivationInput.Operation.STOP);
        assertEquals(Realm.WANDERING_QI, failed.body().realm());
        assertEquals(58, failed.body().wallQiTotal(), 1e-7);
        assertEquals(0, HumanCultivation.state(failed.body()).getBreakthroughSeconds(), 0);
    }

    @Test
    public void reserveRoundoffRemainsInSourceWhileUsableQiStillTransfers() {
        // 复现实机外壁残量：同种内腔已有余额，加上尾差不能触发 NUMERIC_LIMIT。
        Host fixture = new Host();
        fixture.open();
        fixture.debug(false, 50, .01);
        var saved = BodyProtobuf.toMessage(fixture.body()).toBuilder();
        double residual = 2.914335e-16;
        saved.setMeridianStates(0, saved.getMeridianStates(0).toBuilder().setWallQi(BodyProtobuf.toInventory(
                new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, residual, .01),
                        new Qi(Qi.QiAttribute.FIRE, .02, .01))))));
        Host host = new Host(BodyProtobuf.fromMessage(saved.build()));
        double total = host.body().totalQi();
        assertTrue(HumanCultivation.needsAdvance(host.body()));
        for (int tick = 0; tick < 8; tick++) {
            host.tick();
        }
        assertEquals(residual, host.body().meridianStates().get(0).wallQi().entries().stream()
                .filter(qi -> qi.attribute().equals(Qi.QiAttribute.NONE)).findFirst().orElseThrow().amount(), 0);
        QiInventory remainingWall = host.body().meridianStates().get(0).wallQi();
        assertTrue(remainingWall.totalQi() < HumanCultivationNumbers.MIN_TRANSFER_QI);
        assertFalse(HumanCultivation.needsAdvance(host.body()));
        assertEquals(total, host.body().totalQi(), 1e-12);

        // 主动动员仍按既有规则耗费资源，但不能搬走不可计量的尾差或崩溃。
        host.input(CultivationInput.Operation.MOBILIZE_ON);
        host.tick();
        assertEquals(remainingWall, host.body().meridianStates().get(0).wallQi());
        assertEquals(total, host.body().totalQi(), 1e-12);
    }

    @Test
    public void ordinaryEntryRejectsActivityAndForgedConservationDoesNotCommit() {
        // 通用网络不能上传可信计划；可信误用也不能造元或留下部分修改。
        Host host = new Host();
        var plan = BodyActivity.newBuilder().addCompletions(CompleteMeridian.newBuilder()
                .setMeridianId(HumanCultivation.MERIDIANS.get(0)).setAddedVolume(1625)).build();
        var request = host.request(BodyCommand.newBuilder().setExecuteActivity(plan));
        assertEquals(FailureCode.ACCESS_DENIED, MeridianProtocol.decodeResponse(host.handler.handle("test", request.toByteArray())).getFailure().getCode());
        BodySnapshot before = host.body();
        var state = before.meridianStates().get(0);
        var forged = BodyActivity.newBuilder().addAccounts(QiAccountChange.newBuilder().setLumenId(state.id().value())
                .setExpected(BodyProtobuf.toInventory(state.lumenQi()))
                .setReplacement(BodyProtobuf.toInventory(new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, .2, .01)))))).build();
        var response = MeridianProtocol.decodeResponse(host.handler.handleActivity("test",
                host.request(BodyCommand.newBuilder().setExecuteActivity(forged)).toByteArray()));
        assertTrue(response.hasFailure());
        assertEquals(before, host.body());
    }

    @Test
    public void overdriveUsesHealthAndReserveExhaustionStopsWithoutRetroactiveWork() {
        // 推力超限造成真实经脉伤势，唯一货物归还，不能只给客户端闪红。
        Host force = new Host();
        force.open();
        force.debug(false, 100, .01);
        force.input(CultivationInput.Operation.START_MANUAL);
        force.tick();
        double total = force.body().totalQi();
        force.input(CultivationInput.Operation.PUSH_ON);
        for (int tick = 0; tick < 8 && HumanCultivation.state(force.body()).getRunning(); tick++) {
            force.tick();
        }
        assertFalse(HumanCultivation.state(force.body()).getRunning());
        assertTrue(force.body().transits().isEmpty());
        assertTrue(force.body().meridianStates().stream().anyMatch(item -> item.condition() == MeridianState.Condition.LEAKING));
        assertEquals(total, force.body().totalQi(), 1e-8);

        // 长时间空闲后第一次按动员不补扣过去的秒数；耗尽脾耐力后伤脾并停机。
        Host reserve = new Host();
        reserve.now = 600_000;
        reserve.commit(BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        reserve.debug(true, 10, .01);
        reserve.input(CultivationInput.Operation.MOBILIZE_ON);
        var spleen = reserve.body().organStates().stream().filter(item -> item.id().value().equals("spleen")).findFirst().orElseThrow();
        assertEquals(0, spleen.fatigueJoules(), 0);
        assertEquals(10, reserve.body().wallQiTotal(), 1e-8);
        reserve.apply(BodyActivity.newBuilder().addEfforts(ActivityEffort.newBuilder()
                .setOrganId("spleen").setWatts(20).setSeconds(30)).build());
        reserve.tick();
        assertFalse(HumanCultivation.state(reserve.body()).getMobilizing());
        assertEquals(spleen.hp() - .1, reserve.body().organStates().stream().filter(item -> item.id().equals(spleen.id()))
                .findFirst().orElseThrow().hp(), 1e-9);
    }

    @Test
    public void fuelShortageAndCandidateLossResetProgressWithoutRefund() {
        // 启动达标不代表后续可以空烧；耗掉的燃料永久记入环境账。
        Host emptyFuel = preparedBreakthrough();
        emptyFuel.input(CultivationInput.Operation.START_BREAKTHROUGH);
        emptyFuel.debug(true, 1, .099);
        for (int tick = 0; tick < 8; tick++) {
            emptyFuel.tick();
        }
        assertFalse(HumanCultivation.state(emptyFuel.body()).getBreakthrough());
        assertEquals(0, HumanCultivation.state(emptyFuel.body()).getBreakthroughSeconds(), 0);
        assertEquals(0, emptyFuel.body().wallQiTotal(), 1e-8);
        assertEquals(Realm.WANDERING_QI, emptyFuel.body().realm());

        Host missingCandidate = preparedBreakthrough();
        missingCandidate.input(CultivationInput.Operation.START_BREAKTHROUGH);
        for (int tick = 0; tick < 4; tick++) {
            missingCandidate.tick();
        }
        missingCandidate.debug(false, 0, .01);
        missingCandidate.tick();
        assertFalse(HumanCultivation.state(missingCandidate.body()).getBreakthrough());
        assertEquals(0, HumanCultivation.state(missingCandidate.body()).getBreakthroughSeconds(), 0);
        assertTrue(missingCandidate.body().wallQiTotal() <= 59);
        assertEquals(Realm.WANDERING_QI, missingCandidate.body().realm());
    }

    private static Host preparedBreakthrough() {
        // 调试只跳攒量，运行时仍走真实候选、燃料和60秒判断。
        Host host = new Host();
        host.open();
        host.debug(false, 55, .099);
        host.input(CultivationInput.Operation.START_AUTOMATIC);
        host.tick();
        host.debug(true, 60, .099);
        return host;
    }

    private static final class Host {
        final InMemoryBodyStore store;
        final BodyCommandHandler<String> handler;
        final BodyId id = new BodyId("cultivation-test");
        long now;
        long sequence;

        Host() {
            // 出生与恢复夹具共用同一个权威执行入口。
            this(BodyProtobuf.decodeSnapshot(HumanBirth.candidate(HumanContent.bundle().getTemplates(0), "cultivation-test",
                    BodyMessages.TimeCursor.newBuilder().setDomain("test:world").setEpoch("epoch").setElapsedMillis(0).build())));
        }

        Host(BodySnapshot body) {
            // 从合法快照恢复后继续通过真实 handler 验证转移与总账守恒。
            store = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty());
            handler = new BodyCommandHandler<>((caller, command) -> Optional.of(caller), (caller, command, snapshot) -> true,
                    (caller, snapshot) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(snapshot.settledAt()).toBuilder().setElapsedMillis(now))
                            .setBiologicalSecondsPerSimulationSecond(1).setMode(TimeAdvanceMode.ACTIVE).build(),
                    store, (caller, receipt, before, after) -> {}, new OrganRecipeCatalog(List.of()), (caller, snapshot, plan) -> Optional.empty(),
                    new ActionCatalog(List.of()), new MeridianOpeningCatalog(List.of()), caller -> true);
        }

        BodySnapshot body() {
            // 每次读取最新事务结果，测试不保留第二份可写状态。
            return store.find(id).orElseThrow();
        }

        QiTransit circuit() {
            // 周天只能有一个指定货物账户。
            return body().transits().stream().filter(item -> item.id().equals(HumanCultivation.CIRCUIT)).findFirst().orElseThrow();
        }

        RequestEnvelope request(BodyCommand.Builder command) {
            // 请求身份独立于输入序号，重试测试可以显式复用同一封包。
            return RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(java.util.UUID.randomUUID().toString())
                    .setBodyCommand(command.setBodyId(id.value()).setExpectedRevision(body().revision())).build();
        }

        void apply(BodyActivity plan) {
            // 对外协议编码也会检查新增可缺省字段，覆盖本次实际回退缺陷。
            var response = MeridianProtocol.decodeResponse(handler.handleActivity("test", request(BodyCommand.newBuilder().setExecuteActivity(plan)).toByteArray()));
            assertTrue(response.toString(), response.hasCommandCommitted());
        }

        void input(CultivationInput.Operation operation) {
            // 按键只带操作与当前服务器 tick，不能传成功结果或资源数值。
            apply(HumanCultivation.update(body(), CultivationInput.newBuilder().setSequence(++sequence)
                    .setOperation(operation).setKeyTick(now / 50).build()));
        }

        void tick() {
            // 生理边界先推进框架的加工和运输，再执行这一段权威周天操作。
            now += 250;
            commit(BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
            apply(HumanCultivation.update(body(), null));
        }

        void commit(BodyCommand.Builder command) {
            // 所有通用命令都通过授权和唯一 Store。
            var response = MeridianProtocol.decodeResponse(handler.handle("test", request(command).toByteArray()));
            assertTrue(response.toString(), response.hasCommandCommitted());
        }

        void open() {
            // 只跳过本境八脉的等待，其他脉保留未开状态。
            var plan = BodyActivity.newBuilder();
            HumanCultivation.MERIDIANS.forEach(id -> plan.addCompletions(CompleteMeridian.newBuilder().setMeridianId(id).setAddedVolume(1625)));
            apply(plan.build());
        }

        void debug(boolean wall, double amount, double density) {
            // all 数值是整个账户类别的总量，不是每条经脉各得一份。
            commit(BodyCommand.newBuilder().setDebugQi(DebugQi.newBuilder().setMode(DebugQiMode.SET_INVENTORY)
                    .setWall(wall).setAmount(amount).setDensity(density)));
        }
    }
}
