package dev.kizuna.meridian.human.demo;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.human.client.GatheringRequests;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 可复现的内存宿主演示；使用真实请求和事务，模拟时间只在事件边界推进。 */
public final class GatheringDemo {
    private GatheringDemo() {
        // 演示只在显式执行 Gradle demo 时运行，不随附属加载自动创建玩家。
    }

    public static void main(String[] arguments) {
        // 宿主先提供能力画像并注册内容，再以自己的身份与时钟首次创建人体。
        var profile = AddonHostProfile.newBuilder().setHostId("meridian:java_reference").setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES).addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE).addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY);
        var registration = AddonRegistration.newBuilder().setProtocolVersion(1).setHost(profile)
                .addBundles(AddonProtobuf.decodeBundle(HumanContent.payload())).build();
        AddonRegistry registry = AddonRegistry.load(registration);
        var template = registry.findTemplate(HumanContent.TEMPLATE_ID, 1).orElseThrow();
        BodySnapshot initial = BodyProtobuf.decodeSnapshot(HumanBirth.candidate(template, "demo-human", time(0)));
        var recipe = registry.recipes().find("human:lung_refine_wood", 1).orElseThrow();

        // 100 格空间、100 元木气，按框架浓度规则供给 1 元/秒；只创建一次真实库存。
        var rawQi = BodyMessages.QiInventory.newBuilder().addEntries(BodyMessages.Qi.newBuilder()
                .setPhase(BodyMessages.QiPhase.FREE).setAttribute("meridian:wood").setAmount(100)).build();
        var space = EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId("demo-region")
                .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(100).build();
        var source = EnvironmentAbsorption.initialize(space, BodyProtobuf.fromInventory(rawQi), time(0), time(5000),
                new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty(), List.of(source));
        var clock = new DemoClock();
        var handler = new BodyCommandHandler<String>((caller, command) -> {
            // 这个内存宿主只授权本次演示身份和身体，不提供匿名全权操作入口。
            if (caller.equals("demo-owner") && command.getBodyId().equals("demo-human")) {
                return Optional.of(caller);
            }
            return Optional.empty();
        }, (caller, command, body) -> true,
                (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(time(clock.millis))
                        .setBiologicalSecondsPerSimulationSecond(1).setMode(TimeAdvanceMode.ACTIVE).build(),
                store, (caller, receipt, before, after) -> { /* 审计不承担资源转移。 */ },
                registry.recipes(), (caller, body, plan) -> Optional.of("demo-region"), registry.actions());

        // 一轮由五个显式请求组成；等待时间取权威事件，不创建逐帧轮询或额外运行账本。
        show("出生", store);
        clock.millis = 1000;
        submit(handler, GatheringRequests.intake(message(store), "intake", "left_lung", recipe.getInput()));
        show("摄入 1 元木气", store);
        submit(handler, GatheringRequests.refine(message(store), "refine", "left_lung", recipe));
        BodyMessages.OrganState lung = message(store).getOrganStatesList().stream()
                .filter(organ -> organ.getId().equals("left_lung")).findFirst().orElseThrow();
        clock.millis = lung.getProcess().getCompletionMillis();
        submit(handler, GatheringRequests.settle(message(store), "complete"));
        show("炼化完成", store);
        submit(handler, GatheringRequests.store(message(store), "store", "left_lung.to.meridian", recipe.getOutput(), 1));
        show("运往左肺经", store);
        clock.millis = message(store).getTransits(0).getArrivalMillis();
        submit(handler, GatheringRequests.settle(message(store), "arrive"));
        show("到达并储存", store);
        System.out.println("肺经仍未开拓；本轮仅聚气和储存。环境释放账尚待真实宿主按位置归入区域。");
    }

    private static BodyMessages.TimeCursor time(long millis) {
        // 世界时钟由演示宿主控制，客户端请求无法注入时间。
        return BodyMessages.TimeCursor.newBuilder().setDomain("demo:world").setEpoch("demo-1").setElapsedMillis(millis).build();
    }

    private static BodyMessages.BodySnapshot message(InMemoryBodyStore store) {
        // 每条后续请求先读取最新 revision，避免把出生快照当作长期可写副本。
        return BodyProtobuf.toMessage(store.find(new BodyId("demo-human")).orElseThrow());
    }

    private static void submit(BodyCommandHandler<String> handler, byte[] request) {
        // 失败时展示框架原始失败码并停止演示，不能继续假定上一步已经入账。
        var response = MeridianProtocol.decodeResponse(handler.handle("demo-owner", request));
        if (!response.hasCommandCommitted()) {
            throw HumanErrors.demoRejected(response.getFailure().getCode());
        }
    }

    private static void show(String stage, InMemoryBodyStore store) {
        // 所有展示值从权威状态派生；环境释放账与区域库存分别展示以免重复计量。
        BodySnapshot body = store.find(new BodyId("demo-human")).orElseThrow();
        var lungChannel = body.meridianStates().stream()
                .filter(meridian -> meridian.id().value().equals("left_lung_meridian")).findFirst().orElseThrow();
        double environmentQi = BodyProtobuf.fromInventory(store.environmentSource("demo-region").orElseThrow().getInventory()).totalQi();
        System.out.printf(Locale.ROOT,
                "%s | t=%.1fs | 环境 %.2f | 器官含加工 %.2f | 在途 %.2f | 左肺经 %.2f | 环境释放 %.2f%n",
                stage, body.settledAt().elapsedMillis() / 1000.0, environmentQi, body.organQiTotal(),
                body.transitQiTotal(), lungChannel.lumenQi().totalQi(), store.environmentQi().totalQi());
    }

    private static final class DemoClock {
        private long millis;
    }
}
