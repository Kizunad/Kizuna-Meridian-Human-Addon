package dev.kizuna.meridian.human;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.human.client.GatheringRequests;
import dev.kizuna.meridian.human.client.HumanPresentation;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.human.server.HumanOpeningPolicy;
import dev.kizuna.meridian.human.server.HumanOpeningViews;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.*;
import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.EnvironmentProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.GatheringSession;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;
import static dev.kizuna.meridian.protocol.pb.OperationUiMessages.OperationMeterState.*;

/** 整体回归：独立附属内容经过公开协议安装，并用真实权威处理器完成聚气。 */
public class HumanGatheringTest {
    @Test
    public void realmLayersProtectRequestsAndShareTheHumanCapacityBudget() {
        // 绕过界面直接请求尚未解锁的正经，必须原子拒绝，不能只靠模型隐藏保护。
        var seed = new Host(source(100), null);
        var initial = fullMeridians(seed.message());
        var host = new Host(source(100), initial);
        var locked = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("locked-opening")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                        .setStartMeridianOpening(dev.kizuna.meridian.protocol.pb.OpeningMessages.StartMeridianOpening.newBuilder()
                                .setRuleId("human:open/left_heart_meridian").setRuleVersion(HumanOpening.RULE_VERSION)))
                .build().toByteArray();
        host.rejectUnchanged(locked, FailureCode.OPENING_REQUIREMENTS_UNMET);

        // 同一规则在满足境界后可启动；运行中失去层级不再累计适应时间。
        var higherBody = initial.toBuilder().setRealm(BodyMessages.Realm.QI_GUIDANCE).build();
        var higher = new Host(source(100), higherBody);
        higher.commit(locked);
        var rule = HumanContent.bundle().getOpeningRulesList().stream()
                .filter(item -> item.getTargetMeridianId().equals("left_heart_meridian")).findFirst().orElseThrow();
        var check = dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.OpeningPolicyRequest.newBuilder()
                .setProtocolVersion(1).setBody(higher.message().toBuilder().setRealm(BodyMessages.Realm.AWAKENING))
                .setRule(rule).setPhase(dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.OpeningPolicyPhase.CHECK_OPENING)
                .setIntervalMillis(1000).build();
        try {
            var decision = dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.OpeningPolicyDecision.parseFrom(
                    new dev.kizuna.meridian.human.server.HumanOpeningPolicy().evaluate(check.toByteArray()));
            assertFalse(decision.getReady());
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new AssertionError(invalid);
        }

        // 每个阶段都是累计层级；醒灵的八条增量加上全身出生容量，恰好为 150 元。
        var rules = HumanContent.bundle().getOpeningRulesList();
        int[] expectedCounts = {8, 16, 24, 32, 36, 36};
        for (int realm = 1; realm <= expectedCounts.length; realm++) {
            int stage = realm;
            assertEquals(expectedCounts[realm - 1], rules.stream().filter(item ->
                    dev.kizuna.meridian.human.server.HumanOpeningPolicy.conditions(item).getMinimumRealmValue() <= stage).count());
        }
        double initialCapacity = initial.getStructure().getMeridiansList().stream()
                .mapToDouble(BodyMessages.MeridianDefinition::getLumenCapacity).sum();
        double awakeningGain = rules.stream().filter(item ->
                        HumanOpening.minimumRealm(item.getTargetMeridianId()) == BodyMessages.Realm.AWAKENING)
                .mapToDouble(BodyMessages.MeridianOpeningRule::getLumenCapacityGain).sum();
        assertEquals(150, initialCapacity + awakeningGain, 0);

        // 各条都通过真实启动请求固定要求，保护递增公式、临时额度与展示使用同一份计划。
        double[] expectedGroupQi = {22, 39.75, 58.5, 78.25, 99, 120.75, 143.5, 167.25};
        var awakeningRules = rules.stream().filter(item ->
                HumanOpening.minimumRealm(item.getTargetMeridianId()) == BodyMessages.Realm.AWAKENING).toList();

        // 醒灵必须同时覆盖双臂与双腿，不能在满足八条数量时又退回到只有手经的分组。
        Set<String> awakeningIds = awakeningRules.stream()
                .map(BodyMessages.MeridianOpeningRule::getTargetMeridianId).collect(Collectors.toSet());
        assertEquals(Set.of("left_lung_meridian", "right_lung_meridian",
                "left_large_intestine_meridian", "right_large_intestine_meridian",
                "left_spleen_meridian", "right_spleen_meridian",
                "left_stomach_meridian", "right_stomach_meridian"), awakeningIds);
        Set<String> coveredParts = initial.getStructure().getMeridiansList().stream()
                .filter(meridian -> awakeningIds.contains(meridian.getId()))
                .flatMap(meridian -> meridian.getPartIdsList().stream()).collect(Collectors.toSet());
        assertTrue(coveredParts.containsAll(Set.of("left_upper_arm", "right_upper_arm",
                "left_forearm", "right_forearm", "left_hand", "right_hand",
                "left_thigh", "right_thigh", "left_lower_leg", "right_lower_leg",
                "left_foot", "right_foot")));

        var progressedBody = initial.toBuilder();
        for (int index = 0; index < awakeningRules.size(); index++) {
            var openingRule = awakeningRules.get(index);
            var attempt = new Host(source(100), fullMeridians(progressedBody.build()));
            attempt.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("progressive-opening-" + index)
                    .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(attempt.body().revision())
                            .setStartMeridianOpening(dev.kizuna.meridian.protocol.pb.OpeningMessages.StartMeridianOpening.newBuilder()
                                    .setRuleId(openingRule.getId()).setRuleVersion(HumanOpening.RULE_VERSION)))
                    .build().toByteArray());
            var task = attempt.body().openingTask();
            assertEquals(expectedGroupQi[index], task.participants().stream().mapToDouble(item -> item.qi()).sum(), 1e-9);
            assertEquals(10000L * (index + 1), task.requiredMillis());
            assertEquals(22, task.participants().stream()
                    .filter(item -> item.meridianId().value().equals(openingRule.getTargetMeridianId()))
                    .findFirst().orElseThrow().qi(), 0);

            // 下一轮初值声明此前目标已完成及相应容量成长；完成过程由下方真实肺加工回归保护。
            for (int meridianIndex = 0; meridianIndex < progressedBody.getMeridianStatesCount(); meridianIndex++) {
                var state = progressedBody.getMeridianStates(meridianIndex);
                if (state.getId().equals(openingRule.getTargetMeridianId())) {
                    progressedBody.setMeridianStates(meridianIndex, state.toBuilder().setOpening(
                            BodyMessages.MeridianOpening.newBuilder().setStage(BodyMessages.MeridianOpeningStage.OPENED).setProgress(1)));
                }
            }
            var structure = progressedBody.getStructureBuilder();
            for (int meridianIndex = 0; meridianIndex < structure.getMeridiansCount(); meridianIndex++) {
                var meridian = structure.getMeridians(meridianIndex);
                if (meridian.getId().equals(openingRule.getTargetMeridianId())) {
                    structure.setMeridians(meridianIndex, meridian.toBuilder()
                            .setLumenCapacity(meridian.getLumenCapacity() + openingRule.getLumenCapacityGain()));
                }
            }
        }
    }

    @Test
    public void registeredOpeningUsesWholeHumanTopologyAndLungProcessing() throws IOException {
        // 预置已聚满的真实经脉账户，只缩短准备时间；开脉仍走规则目录和权威请求。
        var seed = new Host(source(100), null);
        var environment = source(100).toBuilder().setValidUntil(time(600000)).build();
        var host = new Host(environment, fullMeridians(seed.message()));
        double initialTotal = host.body().totalQi() + host.sourceTotal();
        var rule = host.registry.openings().find("human:open/left_lung_meridian", HumanOpening.RULE_VERSION).orElseThrow();
        assertEquals(36, HumanContent.bundle().getOpeningRulesCount());
        assertEquals(22, rule.getMinimumLumenQi().getEntries(0).getAmount(), 0);
        var conditions = dev.kizuna.meridian.human.server.HumanOpeningPolicy.conditions(rule);
        assertFalse(conditions.getStrictLumenThreshold());
        assertEquals(.8, conditions.getMinimumBodyLumenFraction(), 0);
        assertEquals(16.75, conditions.getGroupPressureGrowth().getBaseIncrementQi(), 0);
        assertEquals(1, conditions.getGroupPressureGrowth().getStepIncrementQi(), 0);
        for (GroupPressureGrowth invalid : List.of(
                GroupPressureGrowth.newBuilder().setBaseIncrementQi(16.75).setStepIncrementQi(-1).build(),
                GroupPressureGrowth.newBuilder().setBaseIncrementQi(Double.MAX_VALUE).setStepIncrementQi(1).build())) {
            // 非法递增与最大全组溢出在条件解码时拒绝，不能进入任务或产生临时容量。
            var malformed = rule.toBuilder().setPolicyConfig(com.google.protobuf.Any.pack(
                    conditions.toBuilder().setGroupPressureGrowth(invalid).build())).build();
            assertThrows(IllegalArgumentException.class, () -> HumanOpeningPolicy.conditions(malformed));
        }
        var initialCards = dev.kizuna.meridian.human.server.HumanOpeningViews.update(host.message(), HumanContent.bundle().getOpeningRulesList());
        assertEquals(host.body().revision(), initialCards.getBodyRevision());
        assertEquals(36, initialCards.getOperationsCount());
        assertEquals(8, initialCards.getOperationsList().stream().filter(card -> card.getEnabled()).count());
        assertEquals(.1, rule.getHoldQiPerSecond(), 0);
        assertEquals(.2, rule.getGuidanceBufferQi(), 0);
        host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("opening-start")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                        .setStartMeridianOpening(dev.kizuna.meridian.protocol.pb.OpeningMessages.StartMeridianOpening.newBuilder()
                                .setRuleId(rule.getId()).setRuleVersion(HumanOpening.RULE_VERSION))).build().toByteArray());
        var recipes = HumanContent.bundle().getOrganRecipesList().stream()
                .filter(item -> item.getId().startsWith("human:lung_refine_")).toList();
        var gathering = host.session();
        int round = 0;
        boolean observedAdapting = false;
        while (host.body().openingTask().active() && round < 150) {
            // 双肺模拟实际吐纳接触；即时炼化和出口运输仍由权威核心按真实账户执行。
            host.now += 1000;
            host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("opening-tick-" + round)
                    .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                            .setAdvanceTime(AdvanceTime.getDefaultInstance())).build().toByteArray());
            if (!host.body().openingTask().active()) {
                break;
            }
            var recipe = recipes.get(round % recipes.size());
            for (String organId : List.of("left_lung", "right_lung")) {
                var organ = host.body().organStates().stream().filter(item -> item.id().value().equals(organId))
                        .findFirst().orElseThrow();
                if (organ.buffer().totalQi() > 0) {
                    // 出口与开脉共享吞吐；满载或仍有在途时保留产物，等待下一个真实状态边界。
                    var choice = gathering.view().getChoicesList().stream()
                            .filter(item -> item.getOrganId().equals(organId) && item.getRecipe().equals(recipe))
                            .findFirst().orElseThrow();
                    var connection = new dev.kizuna.meridian.core.Anatomy.ConnectionId(choice.getConnectionId());
                    double rate = dev.kizuna.meridian.core.QiTransport.availableThroughputPerSecond(host.body(), connection);
                    if (rate <= 0 || choice.getTargetRemainingQi() < organ.buffer().totalQi()) {
                        continue;
                    }
                    host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("opening-send-" + round + organId)
                            .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                                    .setTransportQi(TransportQi.newBuilder().setConnectionId(choice.getConnectionId())
                                            .setCargo(BodyProtobuf.toInventory(organ.buffer())).setThroughputPerSecond(rate)))
                            .build().toByteArray());
                }
                // 可信宿主按当前浓度采样一元原料；比例损耗回归同一环境来源，不能再重复启动固定批次。
                double rawQi = recipe.getInput().getEntriesList().stream().mapToDouble(BodyMessages.Qi::getAmount).sum();
                double concentration = host.sourceTotal() / environment.getSpace().getVolumeBlocks();
                host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("opening-intake-" + round + organId)
                        .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                                .setCaptureQi(CaptureQi.newBuilder().setOrganId(organId).setRecipeId(recipe.getId())
                                        .setRecipeVersion(recipe.getVersion()).setSampleVolumeBlocks(rawQi / concentration)))
                        .build().toByteArray());
            }
            if (round == 0) {
                // 新摄入已经满足，但目标尚未汇满；适应条必须等待，不能把准备误画成计时。
                var card = HumanOpeningViews.update(host.message(), List.of(rule)).getOperations(0);
                assertEquals(dev.kizuna.meridian.core.OpeningTask.Status.PREPARING, host.body().openingTask().status());
                assertEquals(0, host.body().openingTask().heldMillis());
                assertEquals(METER_READY, card.getMetersList().stream()
                        .filter(meter -> meter.getId().equals("intake")).findFirst().orElseThrow().getState());
                assertEquals(METER_WAITING, card.getMetersList().stream()
                        .filter(meter -> meter.getId().equals("target")).findFirst().orElseThrow().getState());
                assertEquals(METER_WAITING, card.getMetersList().stream()
                        .filter(meter -> meter.getId().equals("hold")).findFirst().orElseThrow().getState());

                // 只给策略提供边界候选，不提交账户变更；恰好 22 元应满足新的凝聚门槛。
                var boundaryBody = host.message().toBuilder();
                for (int index = 0; index < boundaryBody.getMeridianStatesCount(); index++) {
                    var meridian = boundaryBody.getMeridianStates(index);
                    if (meridian.getId().equals(rule.getTargetMeridianId())) {
                        boundaryBody.setMeridianStates(index, meridian.toBuilder().setLumenQi(rule.getMinimumLumenQi()));
                    }
                }
                var decision = OpeningPolicyDecision.parseFrom(new HumanOpeningPolicy().evaluate(
                        OpeningPolicyRequest.newBuilder().setProtocolVersion(1).setPhase(OpeningPolicyPhase.CHECK_OPENING)
                                .setBody(boundaryBody).setRule(rule).build().toByteArray()));
                assertTrue(decision.getReady());
            }
            if (!observedAdapting && host.body().openingTask().status() == dev.kizuna.meridian.core.OpeningTask.Status.HOLDING
                    && host.body().openingTask().heldMillis() > 0) {
                // 真实入账并开始维持后，通用卡片才切换到运行态。
                var card = HumanOpeningViews.update(host.message(), List.of(rule)).getOperations(0);
                assertEquals(METER_RUNNING, card.getMetersList().stream()
                        .filter(meter -> meter.getId().equals("hold")).findFirst().orElseThrow().getState());
                observedAdapting = true;
            }
            if (round == 2) {
                gathering.close();
                host.restore();
                gathering = host.session();
            }
            round++;
        }
        gathering.close();
        assertEquals(host.body().openingTask().toString(), dev.kizuna.meridian.core.OpeningTask.Status.COMPLETED,
                host.body().openingTask().status());
        assertTrue("必须实际经过连续适应阶段", observedAdapting);
        var completedCard = HumanOpeningViews.update(host.message(), List.of(rule)).getOperations(0);
        assertEquals(METER_READY, completedCard.getMetersList().stream()
                .filter(meter -> meter.getId().equals("hold")).findFirst().orElseThrow().getState());
        assertEquals(36.25, host.body().structure().meridians().stream().mapToDouble(item -> item.lumenCapacity()).sum(), 0);
        assertEquals(1, host.body().meridianStates().stream()
                .filter(item -> item.opening().stage() == dev.kizuna.meridian.core.MeridianOpening.Stage.OPENED).count());
        assertEquals(initialTotal, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-8);

        // 第二条比首条增加 16.75＋1 元，已开脉保留 17.75 元，目标维持 22 元。
        var readyForSecond = new Host(environment, fullMeridians(host.message()));
        readyForSecond.now = host.now;
        readyForSecond.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("opening-second")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(readyForSecond.body().revision())
                        .setStartMeridianOpening(dev.kizuna.meridian.protocol.pb.OpeningMessages.StartMeridianOpening.newBuilder()
                                .setRuleId("human:open/right_lung_meridian").setRuleVersion(HumanOpening.RULE_VERSION))).build().toByteArray());
        var second = readyForSecond.body().openingTask();
        assertEquals(20000, second.requiredMillis());
        assertEquals(39.75, second.participants().stream().mapToDouble(item -> item.qi()).sum(), 0);
        assertEquals(22, second.rule().minimumLumenQi().totalQi(), 0);
        assertEquals(17.75, second.participants().stream()
                .filter(item -> item.meridianId().value().equals("left_lung_meridian")).findFirst().orElseThrow().qi(), 0);
        readyForSecond.restore();
        assertEquals(second, readyForSecond.body().openingTask());
        var cards = dev.kizuna.meridian.human.server.HumanOpeningViews.update(readyForSecond.message(), HumanContent.bundle().getOpeningRulesList());
        var card = cards.getOperationsList().stream().filter(item -> item.getMeridianId().equals("right_lung_meridian")).findFirst().orElseThrow();
        assertEquals("pause", card.getActionId());
        double actualGroupQi = readyForSecond.body().meridianStates().stream()
                .filter(state -> second.participants().stream().anyMatch(item -> item.meridianId().equals(state.id())))
                .mapToDouble(state -> state.lumenQi().totalQi()).sum();
        assertEquals(actualGroupQi / 39.75, card.getMetersList().stream()
                .filter(meter -> meter.getId().equals("group")).findFirst().orElseThrow().getFraction(), 1e-9);

        // 旧规则的 Any 未声明递增，重新读取时仍使用逐脉固定阈值。
        var secondRule = HumanContent.bundle().getOpeningRulesList().stream()
                .filter(item -> item.getTargetMeridianId().equals("right_lung_meridian")).findFirst().orElseThrow();
        var legacyRule = secondRule.toBuilder().setVersion(3).setPolicyConfig(com.google.protobuf.Any.pack(
                HumanOpeningPolicy.conditions(secondRule).toBuilder().clearGroupPressureGrowth().build())).build();
        var legacyPlan = OpeningPolicyDecision.parseFrom(new HumanOpeningPolicy().evaluate(
                OpeningPolicyRequest.newBuilder().setProtocolVersion(1).setPhase(OpeningPolicyPhase.PREPARE_OPENING)
                        .setBody(readyForSecond.message()).setRule(legacyRule).build().toByteArray()));
        assertEquals(44, legacyPlan.getParticipantsList().stream().mapToDouble(BodyMessages.OpeningPressureLimit::getQi).sum(), 0);
    }

    @Test
    public void openingGuidanceLeavesThroughputForBufferedBreathingProducts() throws IOException {
        // 复现满身真元后开拓大肠经：双肺已有产物，引导不能反复抢满肺经而饿死摄入。
        var seed = new Host(source(100), null);
        var initial = fullMeridians(seed.message()).toBuilder();
        for (int index = 0; index < initial.getOrganStatesCount(); index++) {
            var organ = initial.getOrganStates(index);
            if (organ.getId().equals("left_lung") || organ.getId().equals("right_lung")) {
                initial.setOrganStates(index, organ.toBuilder().setBuffer(inventory("meridian:none", 2)));
            }
        }
        var host = new Host(source(100).toBuilder().setValidUntil(time(600000)).build(), initial.build());
        double initialTotal = host.body().totalQi() + host.sourceTotal();
        var session = host.session();
        session.enableBreathing(HumanContent.breathing());
        breath(session, 1, BreathAction.BEGIN_BREATHING, .5, .4);
        session.advance();
        assertTrue(session.view().getBreathing().getActive());
        assertEquals(FailureCode.CAPACITY_EXCEEDED, session.view().getBreathing().getFailure());
        assertEquals(0, session.view().getBreathing().getStoredQi(), 0);

        // 经脉满载是合法等待；开拓腾出空间后，同一吐纳会话应自行恢复配送。
        host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("buffered-opening")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                        .setStartMeridianOpening(dev.kizuna.meridian.protocol.pb.OpeningMessages.StartMeridianOpening.newBuilder()
                                .setRuleId("human:open/left_large_intestine_meridian")
                                .setRuleVersion(HumanOpening.RULE_VERSION))).build().toByteArray());
        session.synchronizeBodyRevision();

        // 不依赖随机粒子命中；只续租停气，让真实缓存、到货和引导竞争同一权威吞吐。
        for (int step = 0; step < 40; step++) {
            breath(session, step + 2, BreathAction.HOLD, .5, .4);
            host.now += 250;
            session.advance();
            for (var meridian : host.body().structure().meridians()) {
                var ports = meridian.ports().stream().map(port -> port.id()).toList();
                double usedRate = host.body().transits().stream()
                        .filter(transit -> transit.status() == dev.kizuna.meridian.core.QiTransit.Status.MOVING)
                        .filter(transit -> host.body().structure().connections().stream().anyMatch(connection ->
                                connection.id().equals(transit.connectionId())
                                        && (ports.contains(connection.from()) || ports.contains(connection.to()))))
                        .mapToDouble(transit -> transit.throughputPerSecond()).sum();
                assertTrue(meridian.id() + " 吞吐超限：" + usedRate,
                        usedRate <= meridian.throughputPerSecond() + 1e-9);
            }
        }
        assertTrue("肺部缓存必须能送进经脉", session.view().getBreathing().getStoredQi() > 0);
        double remainingBuffer = host.body().organStates().stream()
                .filter(organ -> organ.id().value().equals("left_lung") || organ.id().value().equals("right_lung"))
                .mapToDouble(organ -> organ.buffer().totalQi()).sum();
        assertTrue("器官待配送产物应减少", remainingBuffer < 4);
        assertEquals(initialTotal, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-8);
        session.close();
    }

    @Test
    public void manualBreathingAndOpeningChargeSharedAttentionTogether() throws IOException {
        // 新小游戏和开脉共用宿主，输入捕获、加工、引导及费用不得互相覆盖。
        var seed = new Host(source(100), null);
        var host = new Host(source(100).toBuilder().setValidUntil(time(600000)).build(), fullMeridians(seed.message()));
        host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("manual-opening-start")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(0)
                        .setStartMeridianOpening(dev.kizuna.meridian.protocol.pb.OpeningMessages.StartMeridianOpening.newBuilder()
                                .setRuleId("human:open/left_lung_meridian").setRuleVersion(HumanOpening.RULE_VERSION))).build().toByteArray());
        var session = host.session();
        session.enableBreathing(HumanContent.breathing());
        // 肺出口能使用开脉的临时增压空间，避免断言依赖某个旧版本的压力数值。
        double stableLungCapacity = host.body().structure().meridians().stream()
                .filter(meridian -> meridian.id().value().equals("left_lung_meridian"))
                .findFirst().orElseThrow().lumenCapacity();
        assertTrue(session.view().getChoicesList().stream()
                .anyMatch(choice -> choice.getTargetMeridianId().equals("left_lung_meridian")
                        && choice.getTargetRemainingQi() > stableLungCapacity));
        long sequence = 1;
        breath(session, sequence++, BreathAction.BEGIN_BREATHING, .5, .4);
        double breathingSeconds = 0;
        for (int step = 0; step < 36; step++) {
            var particle = session.view().getBreathing().getParticlesList().stream()
                    .min(java.util.Comparator.comparingDouble(value -> Math.pow(value.getX() - .5, 2)
                            + Math.pow(value.getY() - .4, 2) + Math.pow(value.getZ() - .5, 2))).orElseThrow();
            breath(session, sequence++, step % 32 < 16 ? BreathAction.INHALE : BreathAction.EXHALE,
                    particle.getX(), particle.getY(), particle.getZ());
            double previousAir = session.view().getBreathing().getAirLiters();
            host.now += 250;
            session.advance();
            breathingSeconds += Math.abs(session.view().getBreathing().getAirLiters() - previousAir) / 1.25;
            assertTrue(session.view().getBreathing().getFailure().toString(),
                    session.view().getBreathing().getActive());
        }
        // 以真实空气变化统计有效吸呼时间；命中摄入和未命中做功都须保留开脉费用。
        double expectedAttentionWork = breathingSeconds * 4 + 9 * .25;
        assertTrue(host.body().openingTask().active());
        assertEquals(expectedAttentionWork / 300 * 100, tissueFatigue(host.body(), "attention_network"), 1e-9);
        assertEquals(9, host.body().organStates().stream().filter(organ -> organ.id().value().equals("brain"))
                .findFirst().orElseThrow().fatigueJoules(), 1e-9);
        assertEquals(120, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-8);
        session.close();
    }

    @Test
    public void manualBreathingIsRepeatableBoundedAndConservesRealResources() throws IOException {
        // 人体内容经真实会话完成两次吸呼；输入没有剂量字段，服务端粒子命中才会摄入。
        var mixedSource = source(100).toBuilder();
        mixedSource.setInventory(mixedSource.getInventory().toBuilder()
                .addAllEntries(inventory("meridian:none", 900).getEntriesList()));
        var host = new Host(mixedSource.build(), null);
        var session = host.session();
        session.enableBreathing(HumanContent.breathing());
        long sequence = 1;
        breath(session, sequence++, BreathAction.BEGIN_BREATHING, .5, .4);
        // 停气也有三轴游离运动，移动机会标记不能免费增加资源或扣除组织耐力。
        var beforeDrift = session.view().getBreathing();
        breath(session, sequence++, BreathAction.HOLD, .5, .4);
        host.now += 250;
        session.advance();
        var afterDrift = session.view().getBreathing();
        assertNotEquals(beforeDrift.getParticlesList(), afterDrift.getParticlesList());
        assertNotEquals(beforeDrift.getParticles(0).getZ(), afterDrift.getParticles(0).getZ(), 0);
        assertEquals(beforeDrift.getAbsorbedQi(), afterDrift.getAbsorbedQi(), 0);
        assertEquals(beforeDrift.getStaminaRatio(), afterDrift.getStaminaRatio(), 0);
        assertEquals(FailureCode.INVALID_REQUEST, GatheringResponse.parseFrom(session.handle(
                startRequest(host, "bypass").toByteArray())).getFailure().getCode());
        for (int step = 0; step < 36; step++) {
            var particle = session.view().getBreathing().getParticlesList().stream()
                    .min(java.util.Comparator.comparingDouble(value -> Math.pow(value.getX() - .5, 2)
                            + Math.pow(value.getY() - .4, 2) + Math.pow(value.getZ() - .5, 2)))
                    .orElseThrow();
            BreathAction action = step < 18 ? BreathAction.INHALE : BreathAction.EXHALE;
            breath(session, sequence++, action, particle.getX(), particle.getY(), particle.getZ());
            host.now += 250;
            session.advance();
            var view = session.view().getBreathing();
            assertEquals(36, view.getParticlesCount());
            assertTrue(view.getParticlesList().stream()
                    .allMatch(value -> HumanContent.INPUT_ATTRIBUTES.contains(value.getAttribute())));
            assertTrue(view.getAirLiters() >= 0 && view.getAirLiters() <= view.getCapacityLiters());
            if (step == 17) {
                assertEquals(5, view.getAirLiters(), 1e-9);
            }
        }
        var finished = session.view().getBreathing();
        assertEquals(0, finished.getAirLiters(), 1e-9);
        assertTrue(finished.getAbsorbedQi() > 0);
        assertTrue(finished.getAttentionRatio() < 1 && finished.getStaminaRatio() < 1);
        assertEquals(1000.36, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-9);

        // 同时刻停止、重开不会额外恢复；同请求重放也不能再次消费或领取。
        breath(session, sequence++, BreathAction.END_BREATHING, .5, .4);
        BodySnapshot stopped = host.body();
        session.close();
        var reopened = host.session();
        reopened.enableBreathing(HumanContent.breathing());
        assertEquals(finished.getAttentionRatio(), reopened.view().getBreathing().getAttentionRatio(), 0);
        breath(reopened, 1, BreathAction.BEGIN_BREATHING, .5, .4);
        assertEquals(stopped.tissueStates(), host.body().tissueStates());
        BodySnapshot restarted = host.body();
        breath(reopened, 1, BreathAction.BEGIN_BREATHING, .5, .4);
        assertEquals(restarted, host.body());
        double sourceBeforeIdle = host.sourceTotal();
        host.now += 1000;
        reopened.advance();
        assertFalse(reopened.view().getBreathing().getActive());
        // 输入失联会停气，但真实在途仍可送达；不能用整份快照不变来要求冻结身体。
        assertEquals(sourceBeforeIdle, host.sourceTotal(), 0);
        assertTrue(host.body().actions().isEmpty());
        for (var tissue : host.body().tissueStates()) {
            var prior = restarted.tissueStates().stream().filter(candidate -> candidate.id().equals(tissue.id()))
                    .findFirst().orElseThrow();
            assertTrue(tissue.fatigue() <= prior.fatigue() + 1e-9);
        }
        assertEquals(1000.36, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-9);
    }

    @Test
    public void registeredActionsBlockOnlyTheirTissuesAndBreathingReleasesItsOwnUse() throws IOException {
        // 行走与吐纳由人体包登记，实际动作和疲劳统一留在框架快照。
        var host = new Host(source(100), null);
        assertTrue(host.registry.actions().find("human:run", 1).isPresent());
        host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("walk")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                        .setStartAction(StartAction.newBuilder().setInstanceId("walking").setActionId("human:walk")
                                .setActionVersion(1))).build().toByteArray());
        var session = host.session();
        session.enableBreathing(HumanContent.breathing());
        breath(session, 1, BreathAction.BEGIN_BREATHING, 0, 0);
        breath(session, 2, BreathAction.INHALE, 0, 0);
        assertEquals(2, host.body().actions().size());
        host.now = 250;
        session.advance();
        breath(session, 3, BreathAction.HOLD, 0, 0);
        assertEquals("human:walk", host.body().actions().get(0).definition().id().value());
        assertEquals(1, host.body().actions().size());
        double fatigue = tissueFatigue(host.body(), "diaphragm");
        assertTrue(fatigue > 0);
        host.now = 500;
        session.advance();
        assertEquals(fatigue * Math.exp(-Math.log(2) * .25 / 30), tissueFatigue(host.body(), "diaphragm"), 1e-10);

        // 外部动作令原编排版本失效时，关闭仍能解除吐纳；行走占用不会被一并删除。
        breath(session, 4, BreathAction.INHALE, 0, 0);
        host.commit(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("run")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(host.body().revision())
                        .setStartAction(StartAction.newBuilder().setInstanceId("running").setActionId("human:run")
                                .setActionVersion(1))).build().toByteArray());
        session.close();
        assertEquals(List.of("human:walk", "human:run"), host.body().actions().stream()
                .map(action -> action.definition().id().value()).toList());
    }

    private static double tissueFatigue(BodySnapshot body, String tissueId) {
        // 断言实际组织的唯一疲劳状态，避免只核验可能过期的界面比例。
        return body.tissueStates().stream().filter(tissue -> tissue.id().value().equals(tissueId))
                .findFirst().orElseThrow().fatigue();
    }

    @Test
    public void intakeCostsAreAtomicAndExhaustionStopsTheFirstResource() throws IOException {
        // 控制组织已耗尽时，带负载的摄入必须同时保留环境、身体和回执。
        var host = new Host(source(100), null);
        var loads = ExertTissues.newBuilder()
                .addLoads(ExertTissue.newBuilder().setTissueId("diaphragm").setPowerWatts(30).setDurationSeconds(1))
                .addLoads(ExertTissue.newBuilder().setTissueId("attention_network").setPowerWatts(400).setDurationSeconds(1));
        host.now = 250;
        RequestEnvelope request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("expensive-intake")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId("test-human").setExpectedRevision(0)
                        .setIntakeQi(IntakeQi.newBuilder().setOrganId("left_lung").setQi(inventory("meridian:wood", .05))
                                .addAllLoads(loads.getLoadsList()))).build();
        host.rejectUnchanged(request.toByteArray(), FailureCode.TISSUE_LOAD_EXCEEDED);

        var session = host.session();
        session.enableBreathing(HumanContent.breathing().toBuilder().setAttentionWatts(100).build());
        breath(session, 1, BreathAction.BEGIN_BREATHING, 0, 0);
        for (int step = 0; step < 32 && session.view().getBreathing().getActive(); step++) {
            breath(session, step + 2, step < 16 ? BreathAction.INHALE : BreathAction.EXHALE, 0, 0);
            host.now += 250;
            session.advance();
        }
        assertFalse(session.view().getBreathing().getActive());
        assertEquals(0, session.view().getBreathing().getAttentionRatio(), .00001);
        assertTrue(session.view().getBreathing().getStaminaRatio() > .9);
    }

    private static void breath(GatheringSession<String> session, long sequence, BreathAction action, double x, double y)
            throws IOException {
        // 未指定深度时按公开默认入口平面处理，保留普通开始与结束操作的简明调用。
        breath(session, sequence, action, x, y, .5);
    }

    private static void breath(GatheringSession<String> session, long sequence, BreathAction action, double x, double y, double z)
            throws IOException {
        // 用公开 Protobuf 入口而非内部函数，连同序号校验和业务回执一起覆盖。
        var request = GatheringRequest.newBuilder().setProtocolVersion(1).setBodyId("test-human")
                .setRequestId("breath-" + sequence).setBreath(BreathControl.newBuilder()
                        .setAction(action).setSequence(sequence).setAimX(x).setAimY(y).setAimZ(z)).build();
        var response = GatheringResponse.parseFrom(session.handle(request.toByteArray()));
        assertTrue(response.toString(), response.hasView());
    }

    @Test
    public void oneClickGatheringUsesBoundariesAndRejectsDuplicatesAndFullTargets() throws IOException {
        // 从公开聚气协议驱动整个链路，等待中的刷新与提前唤醒都不能提交身体变化。
        var host = new Host(source(100), null);
        var session = host.session();
        BodySnapshot initial = host.body();
        assertEquals(20, session.view().getChoicesCount());
        assertEquals(initial, host.body());
        host.now = 2000;
        GatheringRequest first = startRequest(host, "one-click");
        assertEquals(GatheringStage.INTAKING, GatheringResponse.parseFrom(session.handle(first.toByteArray())).getView().getProgress().getStage());
        assertEquals(3000, session.nextBoundaryMillis());
        BodySnapshot waiting = host.body();
        for (int i = 0; i < 100; i++) {
            boolean advanced = session.advance();
            assertFalse(session.view().getProgress().toString(), advanced);
            session.view();
        }
        session.handle(first.toByteArray());
        assertEquals(waiting, host.body());
        assertEquals(100, host.sourceTotal(), 0);
        assertEquals(FailureCode.GATHERING_BUSY, GatheringResponse.parseFrom(session.handle(startRequest(host, "second").toByteArray()))
                .getFailure().getCode());

        finishRound(host, session);
        assertEquals(GatheringStage.COMPLETED, session.view().getProgress().getStage());
        assertEquals(0.2, session.view().getProgress().getStoredQi(), 0);
        assertEquals(initial.totalQi() + 0.2, host.body().totalQi(), 1e-12);
        assertEquals(initial.totalQi() + 100, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-12);
        long revision = host.body().revision();
        session.handle(first.toByteArray());
        assertEquals(revision, host.body().revision());

        session.handle(startRequest(host, "second-round").toByteArray());
        finishRound(host, session);
        session.handle(startRequest(host, "third-round").toByteArray());
        finishRound(host, session);
        assertEquals(initial.totalQi() + .6, host.body().totalQi(), 1e-12);
        assertEquals(97, host.sourceTotal(), 0);
        assertEquals(FailureCode.REVISION_CONFLICT, GatheringResponse.parseFrom(session.handle(first.toByteArray())).getFailure().getCode());

        // 扩散会腾出肺经空间；真正满载的身体仍在开始前拒绝，不能靠一次按钮挤占容量。
        var fullHost = new Host(source(100), fullMeridians(BodyProtobuf.toMessage(initial)));
        var fullSession = fullHost.session();
        BodySnapshot full = fullHost.body();
        assertEquals(FailureCode.CAPACITY_EXCEEDED, GatheringResponse.parseFrom(fullSession.handle(
                startRequest(fullHost, "full-body").toByteArray())).getFailure().getCode());
        assertEquals(full, fullHost.body());

        // 未开拓经脉可以接收上述真元，但不能借普通发运请求绕过首脉开拓。
        var route = host.message().getStructure().getConnectionsList().stream()
                .filter(connection -> connection.getFrom().equals("left_lung_meridian.out")).findFirst().orElseThrow();
        host.rejectUnchanged(GatheringRequests.store(host.message(), "unopened-send", route.getId(),
                inventory("meridian:none", 0.1), 1), FailureCode.MERIDIAN_UNOPENED);
    }

    @Test
    public void gatheringFailureAndDisconnectKeepAlreadyCommittedResources() throws IOException {
        // 新的外部身体变更使自动计划失效；已经炼化的输入仍归身体，不会静默退款或重做。
        var host = new Host(source(100), null);
        var session = host.session();
        GatheringResponse started = GatheringResponse.parseFrom(session.handle(startRequest(host, "start").toByteArray()));
        assertTrue(started.toString(), started.hasView());
        host.now = session.nextBoundaryMillis();
        session.advance();
        assertEquals(GatheringStage.REFINING, session.view().getProgress().getStage());
        host.now += 100;
        host.commit(GatheringRequests.settle(host.message(), "another-operation"));
        session.advance();
        assertEquals(FailureCode.REVISION_CONFLICT, session.view().getProgress().getFailure());
        assertEquals(1, host.body().organQiTotal(), 0);
        assertEquals(99, host.sourceTotal(), 0);
        session.close();
        host.now = 2000;
        host.commit(GatheringRequests.settle(host.message(), "normal-host-settlement"));
        assertEquals(0.2, host.body().organQiTotal(), 1e-12);
        assertFalse(session.advance());

        // 撤销读权限后不返回快照，已完成的事务保持原样。
        var deniedHost = new Host(source(100), null);
        var denied = deniedHost.session();
        deniedHost.readable = false;
        var result = GatheringResponse.parseFrom(denied.handle(startRequest(deniedHost, "denied").toByteArray()));
        assertEquals(FailureCode.ACCESS_DENIED, result.getFailure().getCode());
        assertFalse(result.hasView());
        assertEquals(0, deniedHost.body().revision());
    }

    private static GatheringRequest startRequest(Host host, String id) {
        // 请求只选择人体内容身份，所有数值仍从服务端内容目录解析。
        return GatheringRequest.newBuilder().setProtocolVersion(1).setBodyId("test-human").setRequestId(id)
                .setStart(StartGathering.newBuilder().setExpectedRevision(host.body().revision()).setOrganId("left_lung")
                        .setRecipeId("human:lung_refine_wood").setRecipeVersion(1).setConnectionId("left_lung.to.meridian")).build();
    }

    private static void finishRound(Host host, GatheringSession<String> session) {
        // 只按实际事件唤醒；自然扩散占用吞吐时，产物可等待下一次到达后再发运。
        for (int step = 0; step < 100 && session.nextBoundaryMillis() != 0; step++) {
            host.now = session.nextBoundaryMillis();
            assertTrue(session.view().getProgress().toString(), session.advance());
        }
        assertEquals(GatheringStage.COMPLETED, session.view().getProgress().getStage());
    }
    @Test
    public void packagedContentHasExplicitDependencyAndNoEmbeddedFramework() throws IOException {
        // 检查真正的交付 JAR，防止源码测试通过却遗漏内容、许可或偷偷打入框架实现。
        var registration = registration();
        AddonRegistry registry = AddonRegistry.load(registration);
        var template = registry.findTemplate(HumanContent.TEMPLATE_ID, 1).orElseThrow();
        assertEquals(17, template.getStructure().getPartsCount());
        assertEquals(15, template.getStructure().getOrgansCount());
        assertEquals(36, template.getStructure().getMeridiansCount());
        assertEquals(20, template.getStructure().getMeridiansList().stream().mapToDouble(BodyMessages.MeridianDefinition::getLumenCapacity).sum(), 0);
        assertEquals(20, template.getStructure().getMeridiansList().stream().mapToDouble(BodyMessages.MeridianDefinition::getWallCapacity).sum(), 0);
        assertEquals(0.01, BodyProtobuf.fromMessage(template).structure().capacityRatio(), 0);
        try (var jar = new ZipFile(System.getProperty("human.serverJar"))) {
            var resource = jar.getEntry(HumanContent.BUNDLE_RESOURCE);
            assertNotNull(resource);
            try (var input = jar.getInputStream(resource)) {
                assertEquals(HumanContent.bundle(), AddonProtobuf.decodeBundle(input.readAllBytes()));
            }
            try (var input = jar.getInputStream(jar.getEntry(HumanContent.BREATHING_RESOURCE))) {
                assertEquals(HumanContent.breathing(), dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingProfile.parseFrom(input));
            }
            assertNotNull(jar.getEntry("LICENSE"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("dev/kizuna/meridian/core/")
                    || entry.getName().startsWith("dev/kizuna/meridian/protocol/")
                    || entry.getName().startsWith("dev/kizuna/meridian/human/demo/")
                    || entry.getName().startsWith("dev/kizuna/meridian/human/tooling/")));
        }
        try (var jar = new ZipFile(System.getProperty("human.clientJar"))) {
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("dev/kizuna/meridian/human/server/")
                    || entry.getName().startsWith("dev/kizuna/meridian/protocol/")));
            assertNotNull(jar.getEntry("fabric.mod.json"));
            var visualResource = jar.getEntry(HumanPresentation.RESOURCE);
            assertNotNull(visualResource);
            try (var input = jar.getInputStream(visualResource)) {
                BodyPresentation presentation = BodyPresentation.parseFrom(input.readAllBytes());
                assertEquals(HumanPresentation.create(), presentation);
                assertEquals(HumanContent.bundle().getTemplates(0).getReference(), presentation.getTemplate());
                assertEquals(5, presentation.getLayersCount());
                assertEquals("surface", presentation.getDefaultLayerId());
                // 对实际打包资源检查完整覆盖，模型坐标表不能悄悄引用过期或拼错的身体 ID。
                var structure = HumanContent.bundle().getTemplates(0).getStructure();
                for (BodyVisual node : presentation.getVisualsList()) {
                    String id = node.getTargetId();
                    boolean exists = switch (node.getTargetKind()) {
                        case PART -> structure.getPartsList().stream().anyMatch(value -> value.getId().equals(id));
                        case TISSUE -> structure.getTissuesList().stream().anyMatch(value -> value.getId().equals(id));
                        case ORGAN -> structure.getOrgansList().stream().anyMatch(value -> value.getId().equals(id));
                        case MERIDIAN -> structure.getMeridiansList().stream().anyMatch(value -> value.getId().equals(id));
                        default -> false;
                    };
                    assertTrue(node.getId(), exists);
                    if (node.getTargetKind() == BodyTargetKind.MERIDIAN) {
                        // 成品展示的门槛必须与服务端条件一致，防止客户端出现可见却不可用的层级漂移。
                        var rule = HumanContent.bundle().getOpeningRulesList().stream()
                                .filter(item -> item.getTargetMeridianId().equals(id)).findFirst().orElseThrow();
                        assertEquals(dev.kizuna.meridian.human.server.HumanOpeningPolicy.conditions(rule).getMinimumRealm(),
                                node.getMinimumRealm());
                    }
                    // 每层共用相机范围，资源越界会导致切层后裁切或选择错位。
                    for (ModelPoint vertex : node.getVerticesList()) {
                        assertTrue(Float.isFinite(vertex.getX()) && Float.isFinite(vertex.getY()) && Float.isFinite(vertex.getZ()));
                        assertTrue(vertex.getX() >= presentation.getMinimum().getX() && vertex.getX() <= presentation.getMaximum().getX());
                        assertTrue(vertex.getY() >= presentation.getMinimum().getY() && vertex.getY() <= presentation.getMaximum().getY());
                        assertTrue(vertex.getZ() >= presentation.getMinimum().getZ() && vertex.getZ() <= presentation.getMaximum().getZ());
                    }
                    assertEquals(0, node.getTrianglesCount() % 3);
                    for (int index : node.getTrianglesList()) {
                        assertTrue(index >= 0 && index < node.getVerticesCount());
                    }
                    // UV、实际 PNG 和身份绑定一起验证，防止模型有形状却丢失贴图或独立眼球状态。
                    if (!node.getTextureResource().isEmpty()) {
                        assertEquals(node.getVerticesCount(), node.getTextureUvCount());
                        if (node.hasPlayerSkin()) {
                            assertEquals("surface", node.getLayerId());
                            assertEquals("minecraft:textures/entity/player/wide/steve.png", node.getTextureResource());
                            assertEquals(node.getVerticesCount(), node.getPlayerSkin().getSlimVerticesCount());
                            assertEquals(node.getVerticesCount(), node.getPlayerSkin().getSlimTextureUvCount());
                            for (ModelUv uv : node.getPlayerSkin().getSlimTextureUvList()) {
                                assertTrue(uv.getU() >= 0 && uv.getU() <= 1 && uv.getV() >= 0 && uv.getV() <= 1);
                            }
                        } else {
                            String[] resource = node.getTextureResource().split(":", 2);
                            assertNotNull(jar.getEntry("assets/" + resource[0] + "/" + resource[1]));
                        }
                        for (ModelUv uv : node.getTextureUvList()) {
                            assertTrue(uv.getU() >= 0 && uv.getU() <= 1 && uv.getV() >= 0 && uv.getV() <= 1);
                        }
                    }
                }
                assertEquals(17, presentation.getVisualsList().stream().filter(BodyVisual::hasPlayerSkin).count());
                assertEquals(19, presentation.getVisualsList().stream().filter(node -> node.getTargetKind() == BodyTargetKind.TISSUE).count());
                assertEquals(15, presentation.getVisualsList().stream().filter(node -> node.getTargetKind() == BodyTargetKind.ORGAN).count());
                assertTrue(presentation.getVisualsList().stream().anyMatch(node -> node.getTargetId().equals("left_eye")));
                assertTrue(presentation.getVisualsList().stream().anyMatch(node -> node.getTargetId().equals("right_eye")));
                assertEquals(36, presentation.getVisualsList().stream().filter(node -> node.getTargetKind() == BodyTargetKind.MERIDIAN).count());
            }
        }

        // 下游功法包直接依赖 human 并引用 lung 类型，缺失或版本不符时必须明确拒绝。
        var recipe = HumanContent.bundle().getOrganRecipes(0).toBuilder().setId("practice:breath").build();
        var practice = AddonBundle.newBuilder().setSchemaVersion(1)
                .setManifest(AddonManifest.newBuilder().setNamespace("practice").setContentVersion(1)
                        .setApiVersions(ContentVersionRange.newBuilder().setMinimum(1).setMaximum(1))
                        .addSupportedHosts("meridian:java_reference")
                        .addDependencies(AddonDependency.newBuilder().setNamespace("human")
                                .setVersions(ContentVersionRange.newBuilder().setMinimum(1).setMaximum(1))))
                .addOrganRecipes(recipe).build();
        var withPractice = registration.toBuilder().addBundles(practice).build();
        assertTrue(AddonRegistry.load(withPractice).recipes().find("practice:breath", 1).isPresent());
        rejectedRegistration(withPractice.toBuilder().clearBundles().addBundles(practice).build(), AddonIssueCode.DEPENDENCY_MISSING);
        rejectedRegistration(withPractice.toBuilder().setBundles(0, HumanContent.bundle().toBuilder()
                .setManifest(HumanContent.bundle().getManifest().toBuilder().setContentVersion(2))).build(),
                AddonIssueCode.DEPENDENCY_VERSION_MISMATCH);
        rejectedRegistration(registration.toBuilder().setHost(registration.getHost().toBuilder().clearCapabilities()
                .addCapabilities(AddonRegistry.BODY_TEMPLATES).addCapabilities(AddonRegistry.ORGAN_RECIPES)).build(),
                AddonIssueCode.CAPABILITY_MISSING);
        assertTrue(AddonRegistry.load(registration.toBuilder().clearBundles().build()).findTemplate(HumanContent.TEMPLATE_ID, 1).isEmpty());
    }

    @Test
    public void eachElementCanGatherIntoEitherUnopenedLungMeridianWithoutDuplication() {
        // 五行逐个验证，避免仅在木气样例中正确；左右实例必须互不覆盖。
        for (String side : List.of("left", "right")) {
            for (BodyMessages.OrganRecipe recipe : HumanContent.bundle().getOrganRecipesList().stream()
                    .filter(value -> value.getId().startsWith("human:lung_refine_")).toList()) {
                var host = new Host(source(100), null);
                double initialTotal = host.body().totalQi();
                host.now = 1000;
                byte[] intake = GatheringRequests.intake(host.message(), "intake", side + "_lung", recipe.getInput());
                host.commit(intake);
                assertEquals(99, host.sourceTotal(), 1e-12);
                assertEquals(initialTotal + 1, host.body().totalQi(), 1e-12);
                host.commit(intake);
                assertEquals(1, host.body().revision());
                assertEquals(99, host.sourceTotal(), 1e-12);

                // 客户端传入改过产出的展示配方，也只能发送 ID；权威配方始终产出 0.2 元。
                var forgedDisplay = recipe.toBuilder().setOutput(inventory("meridian:fire", 99)).build();
                host.commit(GatheringRequests.refine(host.message(), "refine", side + "_lung", forgedDisplay));
                assertEquals(initialTotal + 1, host.body().totalQi(), 1e-12);
                host.now = 2000;
                host.commit(GatheringRequests.settle(host.message(), "complete"));
                assertEquals(0.8, host.store.environmentQi().totalQi(), 1e-12);
                host.commit(GatheringRequests.store(host.message(), "store", side + "_lung.to.meridian", recipe.getOutput(), 1));
                assertEquals(initialTotal + .2, host.body().lumenQiTotal() + host.body().transitQiTotal(), 1e-12);
                var lungTransit = host.body().transits().stream()
                        .filter(transit -> transit.connectionId().value().equals(side + "_lung.to.meridian"))
                        .findFirst().orElseThrow();
                assertEquals(2300, lungTransit.arrivalMillis());
                host.now = 2300;
                host.commit(GatheringRequests.settle(host.message(), "arrive"));

                assertEquals(0, host.body().organQiTotal(), 0);
                assertTrue(host.body().transitQiTotal() > 0);
                assertEquals(0, host.body().wallQiTotal(), 0);
                var channel = host.message().getMeridianStatesList().stream()
                        .filter(meridian -> meridian.getId().equals(side + "_lung_meridian")).findFirst().orElseThrow();
                assertTrue(channel.getLumenQi().getEntries(0).getAmount() < .21);
                assertEquals("meridian:none", channel.getLumenQi().getEntries(0).getAttribute());
                assertEquals(BodyMessages.QiPhase.FREE, channel.getLumenQi().getEntries(0).getPhase());
                assertTrue(host.message().getMeridianStatesList().stream().allMatch(meridian ->
                        meridian.getOpening().getStage() == BodyMessages.MeridianOpeningStage.UNOPENED
                                && meridian.getOpening().getProgress() == 0));
                assertEquals(initialTotal + 100, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-12);
                assertEquals(HumanBirth.INITIAL_AGE_YEARS * HumanBirth.SECONDS_PER_YEAR + 2.3, host.body().vitality().ageSeconds(), 1e-6);
                assertEquals(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(host.body())), host.body());

                // 后续无需主动指令，按同一事件算子自然经过全身通路；开拓状态保持独立。
                host.now = 120_000;
                host.commit(GatheringRequests.settle(host.message(), "diffuse"));
                assertEquals(initialTotal + .2, host.body().lumenQiTotal(), 1e-12);
                assertEquals(0, host.body().transitQiTotal(), 0);
                assertTrue(host.body().meridianStates().stream().allMatch(meridian ->
                        meridian.lumenQi().totalQi() > HumanBirth.INITIAL_QI_PER_MERIDIAN));
            }
        }
    }

    @Test
    public void blockedIntakeAndFullMeridianPreserveActualOwnership() {
        // 人体能力与环境浓度分别约束摄入，拒绝不能消费时间、库存或成功回执。
        var recipe = HumanContent.bundle().getOrganRecipes(0);
        var slow = new Host(source(200), null);
        slow.now = 1000;
        slow.rejectUnchanged(GatheringRequests.intake(slow.message(), "thin", "left_lung", recipe.getInput()),
                FailureCode.ENVIRONMENT_BUDGET_EXCEEDED);
        slow.now = 2000;
        slow.commit(GatheringRequests.intake(slow.message(), "enough-time", "left_lung", recipe.getInput()));
        var normal = new Host(source(100), null);
        normal.now = 1000;
        normal.rejectUnchanged(GatheringRequests.intake(normal.message(), "over-rate", "left_lung",
                inventory("meridian:metal", 2)), FailureCode.THROUGHPUT_EXCEEDED);
        normal.rejectUnchanged(GatheringRequests.intake(normal.message(), "wrong-organ", "stomach", recipe.getInput()),
                FailureCode.INTAKE_UNSUPPORTED);
        BodySnapshot initial = normal.body();
        byte[] denied = GatheringRequests.intake(normal.message(), "denied", "left_lung", recipe.getInput());
        assertEquals(FailureCode.ACCESS_DENIED, MeridianProtocol.decodeResponse(normal.handler().handle("intruder", denied)).getFailure().getCode());
        assertEquals(initial, normal.body());

        // 连续三轮产物可自然离开肺经；局部与在途汇总仍是实际净产量。
        for (int round = 0; round < 3; round++) {
            String prefix = "r" + round;
            normal.now = normal.body().settledAt().elapsedMillis() + 1000;
            normal.commit(GatheringRequests.intake(normal.message(), prefix + "-intake", "left_lung", recipe.getInput()));
            normal.commit(GatheringRequests.refine(normal.message(), prefix + "-refine", "left_lung", recipe));
            normal.now += 1000;
            normal.commit(GatheringRequests.settle(normal.message(), prefix + "-complete"));
            // 显式发运由宿主按共享空闲量申请，不能要求被动批次让出已预约的吞吐。
            double rate = dev.kizuna.meridian.core.QiTransport.availableThroughputPerSecond(normal.body(),
                    new dev.kizuna.meridian.core.Anatomy.ConnectionId("left_lung.to.meridian"));
            int wait = 0;
            while (rate <= 0 && wait < 100) {
                normal.now = dev.kizuna.meridian.core.QiTransport.nextArrivalBoundary(normal.body()).orElseThrow().elapsedMillis();
                normal.commit(GatheringRequests.settle(normal.message(), prefix + "-wait-" + wait++));
                rate = dev.kizuna.meridian.core.QiTransport.availableThroughputPerSecond(normal.body(),
                        new dev.kizuna.meridian.core.Anatomy.ConnectionId("left_lung.to.meridian"));
            }
            assertTrue(rate > 0);
            byte[] transport = GatheringRequests.store(normal.message(), prefix + "-store", "left_lung.to.meridian", recipe.getOutput(), rate);
            normal.commit(transport);
            normal.now = normal.body().transits().stream()
                    .filter(transit -> transit.connectionId().value().equals("left_lung.to.meridian"))
                    .findFirst().orElseThrow().arrivalMillis();
            normal.commit(GatheringRequests.settle(normal.message(), prefix + "-arrive"));
        }
        assertEquals(0, normal.body().organQiTotal(), 0);
        assertEquals(initial.totalQi() + .6, normal.body().lumenQiTotal() + normal.body().transitQiTotal(), 1e-12);
        assertEquals(initial.totalQi() + 100, normal.body().totalQi() + normal.sourceTotal() + normal.store.environmentQi().totalQi(), 1e-12);

        // 全身经脉真实满载时，炼化产物仍归肺缓存，失败发运不退款或丢弃产物。
        var full = new Host(source(100), fullMeridians(BodyProtobuf.toMessage(initial)));
        full.now = 1000;
        full.commit(GatheringRequests.intake(full.message(), "full-intake", "left_lung", recipe.getInput()));
        full.commit(GatheringRequests.refine(full.message(), "full-refine", "left_lung", recipe));
        full.now = 2000;
        full.commit(GatheringRequests.settle(full.message(), "full-complete"));
        full.rejectUnchanged(GatheringRequests.store(full.message(), "full-store", "left_lung.to.meridian",
                recipe.getOutput(), 1), FailureCode.CAPACITY_EXCEEDED);
        assertEquals(.2, full.body().organQiTotal(), 1e-12);

        // 肺或胸部失能都由框架拒绝，不允许附属通过另一个入口绕过人体伤势。
        var initialMessage = BodyProtobuf.toMessage(initial);
        int leftLungIndex = java.util.stream.IntStream.range(0, initialMessage.getOrganStatesCount())
                .filter(index -> initialMessage.getOrganStates(index).getId().equals("left_lung"))
                .findFirst().orElseThrow();
        var hurtLung = initialMessage.toBuilder().setOrganStates(leftLungIndex,
                initialMessage.getOrganStates(leftLungIndex).toBuilder().setHp(0)).build();
        var injured = new Host(source(100), hurtLung);
        injured.now = 1000;
        injured.rejectUnchanged(GatheringRequests.intake(injured.message(), "injured", "left_lung", recipe.getInput()),
                FailureCode.MODULE_UNAVAILABLE);
        var hurtChest = initialMessage.toBuilder().setPartStates(2, initialMessage.getPartStates(2).toBuilder().setHp(0)).build();
        var chest = new Host(source(100), hurtChest);
        chest.now = 1000;
        chest.rejectUnchanged(GatheringRequests.intake(chest.message(), "chest", "left_lung", recipe.getInput()),
                FailureCode.MODULE_UNAVAILABLE);
    }

    @Test
    public void savedProcessingAndTransitResumeWithoutRestartingHumanOrRefillingQi() {
        // 在加工与运输两个事件边界前分别保存，恢复后只结算原来拥有的资源。
        var host = new Host(source(100), null);
        var recipe = HumanContent.bundle().getOrganRecipes(0);
        host.now = 1000;
        host.commit(GatheringRequests.intake(host.message(), "intake", "left_lung", recipe.getInput()));
        byte[] refine = GatheringRequests.refine(host.message(), "refine", "left_lung", recipe);
        host.commit(refine);
        host.restore();
        host.commit(refine);
        assertEquals(2, host.body().revision());
        host.now = 2000;
        host.commit(GatheringRequests.settle(host.message(), "complete"));
        byte[] transport = GatheringRequests.store(host.message(), "store", "left_lung.to.meridian", recipe.getOutput(), 1);
        host.commit(transport);
        host.restore();
        host.commit(transport);
        assertEquals(4, host.body().revision());
        host.now = 2300;
        host.commit(GatheringRequests.settle(host.message(), "arrive"));
        assertEquals(0.56, host.body().totalQi(), 1e-12);
        assertEquals(0.8, host.store.environmentQi().totalQi(), 1e-12);
        assertEquals(99, host.sourceTotal(), 1e-12);
        assertEquals(5, host.receipts.size());
    }

    private static void rejectedRegistration(AddonRegistration registration, AddonIssueCode code) {
        // 对外固定诊断比中文异常文本稳定，附属依赖失败必须给出可识别类别。
        var failure = assertThrows(MeridianErrors.AddonRejected.class, () -> AddonRegistry.load(registration));
        assertEquals(code, failure.issue().getCode());
    }

    private static AddonRegistration registration() {
        // 宿主画像由可信测试宿主提供，不能借 Human 自己声称有能力跳过校验。
        var host = AddonHostProfile.newBuilder().setHostId("meridian:java_reference").setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES).addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE).addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY).addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS).addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE);
        return AddonRegistration.newBuilder().setProtocolVersion(1).setHost(host)
                .addBundles(AddonProtobuf.decodeBundle(HumanContent.payload())).build();
    }

    private static BodyMessages.TimeCursor time(long millis) {
        // 测试时钟由宿主推进；操作端请求中不携带这个值。
        return BodyMessages.TimeCursor.newBuilder().setDomain("test:world").setEpoch("session-1").setElapsedMillis(millis).build();
    }

    private static BodyMessages.QiInventory inventory(String attribute, double amount) {
        // 构造明确的原始相态与属性，用于验证服务端从未把五行当作无属性入账。
        return BodyMessages.QiInventory.newBuilder().addEntries(BodyMessages.Qi.newBuilder()
                .setPhase(BodyMessages.QiPhase.FREE).setAttribute(attribute).setAmount(amount)).build();
    }

    private static BodyMessages.BodySnapshot fullMeridians(BodyMessages.BodySnapshot original) {
        // 仅构造可信测试初值；每脉按真实构型容量装满，不能只装满肺经后假定不会扩散。
        var result = original.toBuilder();
        for (int index = 0; index < original.getMeridianStatesCount(); index++) {
            String id = original.getMeridianStates(index).getId();
            double capacity = original.getStructure().getMeridiansList().stream()
                    .filter(meridian -> meridian.getId().equals(id)).findFirst().orElseThrow().getLumenCapacity();
            result.setMeridianStates(index, original.getMeridianStates(index).toBuilder()
                    .setLumenQi(inventory("meridian:none", capacity)));
        }
        return result.build();
    }

    private static EnvironmentQiSource source(double volumeBlocks) {
        // 同样 100 元库存，100 格空间供给 1 元/秒，200 格空间供给 0.5 元/秒。
        var inventory = BodyMessages.QiInventory.newBuilder();
        for (String attribute : HumanContent.INPUT_ATTRIBUTES) {
            inventory.addAllEntries(inventory(attribute, 20).getEntriesList());
        }
        var space = EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId("test-region")
                .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(volumeBlocks).build();
        return EnvironmentAbsorption.initialize(space, BodyProtobuf.fromInventory(inventory.build()), time(0), time(10000),
                new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
    }

    private static final class Host {
        private InMemoryBodyStore store;
        private final AddonRegistry registry = AddonRegistry.load(registration());
        private final List<CommandReceipt> receipts = new ArrayList<>();
        private long now;
        private boolean readable = true;

        private GatheringSession<String> session() {
            // 模拟可信宿主把同一时钟、权限、来源和核心交给可选操作编排。
            return new GatheringSession<>("owner", new BodyId("test-human"), handler(), store,
                    (context, id) -> readable && context.equals("owner") && id.value().equals("test-human"),
                    (context, body) -> TimeAdvancePlan.newBuilder().setTarget(time(now))
                            .setBiologicalSecondsPerSimulationSecond(1).setMode(TimeAdvanceMode.ACTIVE).build(),
                    body -> store.environmentSource("test-region"), registry.recipes());
        }

        private Host(EnvironmentQiSource source, BodyMessages.BodySnapshot initial) {
            // 仅可信宿主负责首次安装候选；测试变体用于验证损伤，不是客户端写账入口。
            BodySnapshot body;
            if (initial == null) {
                var template = registry.findTemplate(HumanContent.TEMPLATE_ID, 1).orElseThrow();
                body = BodyProtobuf.decodeSnapshot(HumanBirth.candidate(template, "test-human", time(0)));
            } else {
                body = BodyProtobuf.fromMessage(initial);
            }
            store = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty(), List.of(source));
        }

        private BodyCommandHandler<String> handler() {
            // 同一真实处理器负责授权、去重、环境事务和时间事件；附属只提供配方目录。
            return new BodyCommandHandler<>((context, command) -> {
                if (context.equals("owner") && command.getBodyId().equals("test-human")) {
                    return Optional.of(context);
                }
                return Optional.empty();
            }, (context, command, body) -> true,
                    (context, body) -> TimeAdvancePlan.newBuilder().setTarget(time(now))
                            .setBiologicalSecondsPerSimulationSecond(1).setMode(TimeAdvanceMode.ACTIVE).build(),
                    store, (context, receipt, before, after) -> receipts.add(receipt), registry.recipes(),
                    (context, body, plan) -> Optional.of("test-region"), registry.actions(), registry.openings());
        }

        private BodySnapshot body() {
            // 读取唯一权威快照，不在测试宿主另存一个可漂移的人体余额。
            return store.find(new BodyId("test-human")).orElseThrow();
        }

        private BodyMessages.BodySnapshot message() {
            // 模拟客户端收到快照；操作构造器只依赖公开 Protobuf 消息。
            return BodyProtobuf.toMessage(body());
        }

        private double sourceTotal() {
            // 环境库存、释放账与身体总量分别汇总，避免隐藏重复入账。
            return BodyProtobuf.fromInventory(store.environmentSource("test-region").orElseThrow().getInventory()).totalQi();
        }

        private void commit(byte[] request) {
            // 模拟宿主已确认的灵气接触走可信入口；普通命令仍通过公开请求入口校验。
            boolean captured = MeridianProtocol.decodeRequest(request).getBodyCommand().hasCaptureQi();
            byte[] reply = captured ? handler().handleCapturedQi("owner", request) : handler().handle("owner", request);
            var response = MeridianProtocol.decodeResponse(reply);
            assertTrue(response.toString(), response.hasCommandCommitted());
        }

        private void rejectUnchanged(byte[] request, FailureCode expected) {
            // 整笔拒绝既不消耗资源，也不推进寿命和游标或创建成功回执。
            BodySnapshot before = body();
            var source = store.environmentSource("test-region").orElseThrow();
            QiInventory released = store.environmentQi();
            int receiptCount = receipts.size();
            var response = MeridianProtocol.decodeResponse(handler().handle("owner", request));
            assertEquals(expected, response.getFailure().getCode());
            assertEquals(before, body());
            assertEquals(source, store.environmentSource("test-region").orElseThrow());
            assertEquals(released, store.environmentQi());
            assertEquals(receiptCount, receipts.size());
        }

        private void restore() {
            // 恢复完整身体、来源和回执，不重新调用出生规则或初始化环境库存。
            BodySnapshot savedBody = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body()));
            var source = store.environmentSource("test-region").orElseThrow();
            var savedSource = EnvironmentProtobuf.decodeSource(EnvironmentProtobuf.encodeSource(source));
            var savedReceipts = receipts.stream().map(receipt ->
                    MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt))).toList();
            store = new InMemoryBodyStore(List.of(savedBody), savedReceipts, store.environmentQi(), List.of(savedSource));
        }
    }
}
