package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.server.TechniqueBodyStamp;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 玩家宿主的身体戳：答得出时与 inspect 结清的结果相同；inspect 答不出身体时读数也不给。 */
public final class HumanTechniqueStampGameTest {
    private static final long LOGIN_SETTLE_TICKS = 2;
    private static final long PAUSE_OBSERVATION_TICKS = 3;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void stampMatchesInspectedBody(GameTestHelper context) {
        // 读数报读数时已提交的版本；同一刻 inspect 结清后的 settled_at 就是读数的时钟，之后的读数跟上新版本。
        var fixture = new HumanTechniqueTestPlayer(context);
        context.runAfterDelay(LOGIN_SETTLE_TICKS, () -> {
            try (fixture) {
                var host = new HumanTechniqueHost();
                UUID id = fixture.player.getUUID();
                long revision = fixture.body().snapshot().revision();
                TechniqueBodyStamp stamp = host.bodyStamp(id).orElseThrow();
                context.assertTrue(stamp.revision() == revision, "读数的版本不是已提交的版本");
                var inspected = inspect(host, fixture).getBody();
                context.assertTrue(inspected.getSettledAt().getElapsedMillis() == stamp.clockMillis(),
                        "读数的时钟与 inspect 结清的结果不同");
                TechniqueBodyStamp after = host.bodyStamp(id).orElseThrow();
                context.assertTrue(after.revision() == inspected.getRevision(), "inspect 之后的读数不是刚提交的版本");
                context.assertTrue(after.clockMillis() == stamp.clockMillis(), "同一 tick 内读数的时钟变了");
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void stampIsWithheldOffTheServerThread(GameTestHelper context) {
        // 与 exchange 一样只在服务端主线程作答，别的线程看到的身体可能正被结清。
        var fixture = new HumanTechniqueTestPlayer(context);
        context.runAfterDelay(LOGIN_SETTLE_TICKS, () -> {
            try (fixture) {
                var host = new HumanTechniqueHost();
                UUID id = fixture.player.getUUID();
                Optional<TechniqueBodyStamp> elsewhere = CompletableFuture.supplyAsync(() -> host.bodyStamp(id)).join();
                context.assertTrue(elsewhere.isEmpty(), "非主线程也报出了读数");
                context.assertTrue(host.bodyStamp(id).isPresent(), "主线程上答不出读数");
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void pausedBodyHasNoStamp(GameTestHelper context) {
        // 客户端撤销必装的身体视图通道，正式 tick 暂停这具身体：inspect 回 BODY_PAUSED，读数也答不出。
        var fixture = new HumanTechniqueTestPlayer(context);
        var host = new HumanTechniqueHost();
        UUID id = fixture.player.getUUID();
        context.runAfterDelay(LOGIN_SETTLE_TICKS, () -> {
            context.assertTrue(host.bodyStamp(id).isPresent(), "暂停之前就答不出读数");
            var unregister = new RegistrationPayload(RegistrationPayload.UNREGISTER,
                    List.of(MeridianChannels.BODY_VIEW.type().id()));
            fixture.player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(unregister));
            context.runAfterDelay(PAUSE_OBSERVATION_TICKS, () -> {
                try (fixture) {
                    FailureCode code = inspect(host, fixture).getFailure().getCode();
                    context.assertTrue(code == FailureCode.BODY_PAUSED, "暂停的身体 inspect 没有回 BODY_PAUSED");
                    context.assertTrue(host.bodyStamp(id).isEmpty(), "暂停的身体仍报出读数");
                }
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void offlinePlayerHasNoStamp(GameTestHelper context) {
        // 断线后宿主不再认领这名玩家，inspect 回未认领，读数也不给。
        var fixture = new HumanTechniqueTestPlayer(context);
        var host = new HumanTechniqueHost();
        UUID id = fixture.player.getUUID();
        context.runAfterDelay(LOGIN_SETTLE_TICKS, () -> {
            context.assertTrue(host.bodyStamp(id).isPresent(), "在线时答不出读数");
            fixture.close();
            context.assertTrue(inspect(host, fixture).getUnbound(), "断线后 inspect 仍认领");
            context.assertTrue(host.bodyStamp(id).isEmpty(), "断线后仍报出读数");
            context.succeed();
        });
    }

    private static TechniqueHostResponse inspect(HumanTechniqueHost host, HumanTechniqueTestPlayer fixture) {
        // 走功法库同一条字节入口，暂停和认领判定与正式施放一致。
        var request = TechniqueHostRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setEntityUuid(fixture.player.getStringUUID())
                .setInspect(true /* inspect */)
                .build();
        return TechniqueProtocol.decodeHostResponse(host.exchange(TechniqueProtocol.encodeHost(request)));
    }
}
