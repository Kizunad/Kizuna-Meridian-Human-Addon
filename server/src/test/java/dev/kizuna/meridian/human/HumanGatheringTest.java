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
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** 整体回归：独立附属内容经过公开协议安装，并用真实权威处理器完成聚气。 */
public class HumanGatheringTest {
    @Test
    public void manualBreathingIsRepeatableBoundedAndConservesRealResources() throws IOException {
        // 人体内容经真实会话完成两次吸呼；输入没有剂量字段，服务端粒子命中才会摄入。
        var host = new Host(source(100), null);
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
            assertTrue(view.getAirLiters() >= 0 && view.getAirLiters() <= view.getCapacityLiters());
            if (step == 17) {
                assertEquals(5, view.getAirLiters(), 1e-9);
            }
        }
        var finished = session.view().getBreathing();
        assertEquals(0, finished.getAirLiters(), 1e-9);
        assertTrue(finished.getAbsorbedQi() > 0);
        assertTrue(finished.getAttentionRatio() < 1 && finished.getStaminaRatio() < 1);
        assertEquals(100.36, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-9);

        // 停止、重开不会回填组织预算，同请求重放也不能再次消费或领取。
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
        host.now += 1000;
        reopened.advance();
        assertFalse(reopened.view().getBreathing().getActive());
        assertEquals(restarted, host.body());
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
        BodySnapshot full = host.body();
        assertEquals(FailureCode.CAPACITY_EXCEEDED, GatheringResponse.parseFrom(session.handle(startRequest(host, "third-round").toByteArray()))
                .getFailure().getCode());
        assertEquals(full, host.body());
        assertEquals(98, host.sourceTotal(), 0);
        assertEquals(FailureCode.REVISION_CONFLICT, GatheringResponse.parseFrom(session.handle(first.toByteArray())).getFailure().getCode());

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
        // 按框架返回的边界唤醒，分别执行摄入与启动、加工完成与发运、运输到达。
        for (int step = 0; step < 3; step++) {
            host.now = session.nextBoundaryMillis();
            assertTrue(session.advance());
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
                        String[] resource = node.getTextureResource().split(":", 2);
                        assertNotNull(jar.getEntry("assets/" + resource[0] + "/" + resource[1]));
                        for (ModelUv uv : node.getTextureUvList()) {
                            assertTrue(uv.getU() >= 0 && uv.getU() <= 1 && uv.getV() >= 0 && uv.getV() <= 1);
                        }
                    }
                }
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
                assertEquals(0.2, host.body().transitQiTotal(), 1e-12);
                assertEquals(initialTotal, host.body().lumenQiTotal(), 1e-12);
                assertEquals(2300, host.body().transits().get(0).arrivalMillis());
                host.now = 2300;
                host.commit(GatheringRequests.settle(host.message(), "arrive"));

                assertEquals(0, host.body().organQiTotal(), 0);
                assertEquals(0, host.body().transitQiTotal(), 0);
                assertEquals(0, host.body().wallQiTotal(), 0);
                var channel = host.message().getMeridianStatesList().stream()
                        .filter(meridian -> meridian.getId().equals(side + "_lung_meridian")).findFirst().orElseThrow();
                assertEquals(0.21, channel.getLumenQi().getEntries(0).getAmount(), 1e-12);
                assertEquals("meridian:none", channel.getLumenQi().getEntries(0).getAttribute());
                assertEquals(BodyMessages.QiPhase.FREE, channel.getLumenQi().getEntries(0).getPhase());
                assertTrue(host.message().getMeridianStatesList().stream().allMatch(meridian ->
                        meridian.getOpening().getStage() == BodyMessages.MeridianOpeningStage.UNOPENED
                                && meridian.getOpening().getProgress() == 0));
                assertEquals(initialTotal + 100, host.body().totalQi() + host.sourceTotal() + host.store.environmentQi().totalQi(), 1e-12);
                assertEquals(HumanBirth.INITIAL_AGE_YEARS * HumanBirth.SECONDS_PER_YEAR + 2.3, host.body().vitality().ageSeconds(), 1e-6);
                assertEquals(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(host.body())), host.body());
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

        // 真实运行两轮把肺经从 0.01 加到 0.41，第三轮产物留在肺内等待腾出空间。
        for (int round = 0; round < 3; round++) {
            String prefix = "r" + round;
            normal.now = normal.body().settledAt().elapsedMillis() + 1000;
            normal.commit(GatheringRequests.intake(normal.message(), prefix + "-intake", "left_lung", recipe.getInput()));
            normal.commit(GatheringRequests.refine(normal.message(), prefix + "-refine", "left_lung", recipe));
            normal.now += 1000;
            normal.commit(GatheringRequests.settle(normal.message(), prefix + "-complete"));
            byte[] transport = GatheringRequests.store(normal.message(), prefix + "-store", "left_lung.to.meridian", recipe.getOutput(), 1);
            if (round == 2) {
                normal.rejectUnchanged(transport, FailureCode.CAPACITY_EXCEEDED);
            } else {
                normal.commit(transport);
                normal.now += 300;
                normal.commit(GatheringRequests.settle(normal.message(), prefix + "-arrive"));
            }
        }
        assertEquals(0.2, normal.body().organQiTotal(), 1e-12);
        assertEquals(0, normal.body().transitQiTotal(), 0);
        assertEquals(initial.totalQi() + 100, normal.body().totalQi() + normal.sourceTotal() + normal.store.environmentQi().totalQi(), 1e-12);

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
                .addCapabilities(AddonRegistry.ORGAN_INTAKE);
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
                    (context, body, plan) -> Optional.of("test-region"));
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
            // 必须有成功回执，单靠运行无异常不足以证明请求执行。
            var response = MeridianProtocol.decodeResponse(handler().handle("owner", request));
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
