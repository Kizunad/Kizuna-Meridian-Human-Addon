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
            for (BodyMessages.OrganRecipe recipe : HumanContent.bundle().getOrganRecipesList()) {
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
