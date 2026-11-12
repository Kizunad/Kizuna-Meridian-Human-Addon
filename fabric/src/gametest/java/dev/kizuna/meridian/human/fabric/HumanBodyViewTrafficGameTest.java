package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewRequest;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewUpdate;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.Deflater;

/** 量一名玩家每秒收到多少字节的身体视图：静止、被功法宿主轮询、行走、自动吐纳，订阅和不订阅内观，各量十秒。 */
public final class HumanBodyViewTrafficGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_human_traffic");
    private static final int WARMUP_TICKS = 20;
    private static final int WINDOW_TICKS = 200;
    private static final int TICKS_PER_SECOND = 20;
    private static final int WINDOW_SECONDS = WINDOW_TICKS / TICKS_PER_SECOND;
    private static final int TIMEOUT = WARMUP_TICKS + WINDOW_TICKS + 20;
    /** Combat-UI 每 5 tick 经功法宿主 inspect 一次在线玩家。 */
    private static final int COMBAT_UI_INSPECT_TICKS = 5;
    /** 施放中 TechniqueService 每 tick 至少 inspect 三次。 */
    private static final int CASTING_INSPECTS_PER_TICK = 3;
    /** 每 tick 前进的格数，和原版行走速度相近。 */
    private static final double WALK_STEP = 0.2;
    /** 身体视图每秒最多推几次。 */
    private static final int MAX_VIEWS_PER_SECOND = 4;
    private static final int MAX_VIEWS = MAX_VIEWS_PER_SECOND * WINDOW_SECONDS;
    private static final long MILLIS_PER_TICK = 50;
    /** deflate 输出缓冲比输入多留的字节，放得下不可压缩数据的块头。 */
    private static final int DEFLATE_SLACK = 64;

    @GameTest(maxTicks = TIMEOUT)
    public void idleBodyViewTraffic(GameTestHelper context) {
        // 没人读身体、也没在动的玩家。
        measure(context, "静止", Pacing.TICK_ONLY, fixture -> { }, (fixture, tick) -> { });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void polledBodyViewTraffic(GameTestHelper context) {
        // 和 Combat-UI 一样每 5 tick 经功法宿主 inspect 一次：每次都结清时间，身体版本跟着变。
        measure(context, "每 5 tick 被 inspect 一次", Pacing.TICK_ONLY, fixture -> { }, (fixture, tick) -> {
            if (tick % COMBAT_UI_INSPECT_TICKS == 0) {
                inspect(fixture);
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void castingBodyViewTraffic(GameTestHelper context) {
        // 施放中的玩家每 tick 被 inspect 三次，同一 tick 里后两次不再推进时间。
        measure(context, "每 tick 被 inspect 三次", Pacing.TICK_ONLY, fixture -> { }, (fixture, tick) -> {
            for (int count = 0; count < CASTING_INSPECTS_PER_TICK; count++) {
                inspect(fixture);
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void walkingBodyViewTraffic(GameTestHelper context) {
        // 着地、每 tick 前进 0.2 格，Human 的移动采样按行走结算。
        measure(context, "行走", Pacing.TICK_ONLY, fixture -> { }, (fixture, tick) -> {
            fixture.player.setOnGround(true);
            fixture.player.setPos(fixture.player.position().add(WALK_STEP, 0, 0));
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void introspectingIdleBodyViewTraffic(GameTestHelper context) {
        // 读过全部卷、开全脉、订阅了内观的玩家站着不动：每份视图带开脉规则和操作卡。
        measure(context, "内观订阅中静止", Pacing.WITH_GATHERING, HumanBodyViewTrafficGameTest::introspect,
                (fixture, tick) -> { });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void introspectingCastingBodyViewTraffic(GameTestHelper context) {
        // 同上，再像施放中一样每 tick 被 inspect 三次。
        measure(context, "内观订阅中每 tick 被 inspect 三次", Pacing.WITH_GATHERING,
                HumanBodyViewTrafficGameTest::introspect, (fixture, tick) -> {
                    for (int count = 0; count < CASTING_INSPECTS_PER_TICK; count++) {
                        inspect(fixture);
                    }
                });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void breathingBodyViewTraffic(GameTestHelper context) {
        // 读过全部卷、开全脉、订阅了内观的玩家开自动吐纳：吐纳回执和身体推送都带整个身体，内观还带开脉规则和操作卡。
        measure(context, "内观订阅中自动吐纳", Pacing.WITH_GATHERING, fixture -> {
            introspect(fixture);
            breathe(fixture);
        }, (fixture, tick) -> { });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void breathingWithoutIntrospectionBodyViewTraffic(GameTestHelper context) {
        // 读过全部卷、开全脉但没订阅内观的玩家开自动吐纳：身体推送不必跟着吐纳回执走。
        measure(context, "未订阅内观自动吐纳", Pacing.TICK_ONLY, fixture -> {
            unlock(fixture);
            breathe(fixture);
        }, (fixture, tick) -> { });
    }

    private static void introspect(HumanTechniqueTestPlayer fixture) {
        // 读完全部卷、开全脉，再和 UI 一样申请内观视图。
        unlock(fixture);
        subscribe(fixture);
    }

    private static void unlock(HumanTechniqueTestPlayer fixture) {
        // 走正式命令读完全部卷、开全脉。
        command(fixture, "meridian progression unlock");
        command(fixture, "meridian opening complete @s all");
    }

    private static void measure(GameTestHelper context, String scenario, Pacing pacing,
                                Consumer<HumanTechniqueTestPlayer> setup, TickAction everyTick) {
        // 先等一秒让登录和首推过去，再清空记录量十秒；每 tick 的动作贯穿两段。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            setup.accept(fixture);
            int[] tick = {0};
            context.onEachTick(() -> everyTick.run(fixture, tick[0]++));
            context.runAfterDelay(WARMUP_TICKS, () -> fixture.connection.packets().clear());
            context.runAfterDelay(WARMUP_TICKS + WINDOW_TICKS, () -> {
                try (fixture) {
                    Traffic traffic = Traffic.of(fixture.connection.packets());
                    LOG.info("身体视图流量 {}：{}；动作 {}", scenario, traffic.describe(), fixture.body().motion());
                    int views = traffic.views().packets();
                    int allowed = MAX_VIEWS + pacing.following(traffic.gatherings().packets());
                    context.assertTrue(views <= allowed,
                            scenario + "：十秒推了 " + views + " 次身体视图，超过上限 " + allowed + " 次");
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void inspect(HumanTechniqueTestPlayer fixture) {
        // 走正式功法宿主入口，和 Combat-UI、TechniqueService 的 inspect 相同。
        var request = TechniqueHostRequest.newBuilder()
                .setEntityUuid(fixture.player.getStringUUID())
                .setRequestId(UUID.randomUUID().toString())
                .setInspect(true /* inspect */)
                .build();
        new HumanTechniqueHost().exchange(TechniqueProtocol.encodeHost(request));
    }

    private static void subscribe(HumanTechniqueTestPlayer fixture) {
        // 和 UI 收到首个身体后一样申请内观视图。
        var request = BodyViewRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(fixture.body().snapshot().id().value())
                .setIntrospection(true /* introspection */)
                .build();
        var payload = MeridianChannels.BODY_VIEW_REQUEST.payload(request.toByteArray());
        fixture.player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
    }

    private static void breathe(HumanTechniqueTestPlayer fixture) {
        // 和吐纳窗口点开自动吐纳一样走正式吐纳请求；这是这名玩家的第一条吐纳控制。
        var control = BreathControl.newBuilder()
                .setAction(BreathAction.BEGIN_BREATHING)
                .setAutomatic(true /* automatic */)
                .setSequence(1)
                .build();
        var request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(fixture.player.getStringUUID())
                .setBreath(control)
                .build();
        long now = fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        var response = fixture.body().gather(now, request);
        if (response.hasFailure()) {
            throw new IllegalStateException("自动吐纳没有开始：" + response.getFailure());
        }
    }

    private static void command(HumanTechniqueTestPlayer fixture, String command) {
        // 走正式命令树解锁进度。
        var source = fixture.player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
        fixture.player.level().getServer().getCommands().performPrefixedCommand(source, command);
    }

    /** 身体视图除了每秒 4 次的推送，还能不能每个吐纳回执跟一份：订阅了内观的操作卡要和回执里的身体同一版本。 */
    private enum Pacing {
        TICK_ONLY,
        WITH_GATHERING;

        int following(int gatherings) {
            return this == WITH_GATHERING ? gatherings : 0;
        }
    }

    @FunctionalInterface
    private interface TickAction {
        void run(HumanTechniqueTestPlayer fixture, int tick);
    }

    /** 一条通道十秒里收到的包数、字节数和按原版网络压缩估的字节数。 */
    private record Channel(int packets, long bytes, long compressed) {
        static final Channel EMPTY = new Channel(0, 0, 0);

        Channel add(byte[] payload) {
            // 原版网络层对超过阈值的包整体 deflate，按包分别压缩估算线上字节。
            return new Channel(packets + 1, bytes + payload.length, compressed + compressedSize(payload));
        }

        String describe(String name) {
            // 每秒字节按十秒平均。
            return name + " " + packets + " 包 " + bytes / WINDOW_SECONDS + " 字节/秒（压缩后约 "
                    + compressed / WINDOW_SECONDS + "）";
        }
    }

    /** 十秒里两条带身体的通道各收到多少，以及最后一个身体视图的组成。 */
    private record Traffic(Channel views, Channel gatherings, byte[] lastView) {
        static Traffic of(List<?> packets) {
            // 只数服务端发给这名玩家的身体视图和吐纳回执；订阅回执不带身体，同样计入身体视图通道。
            Channel views = Channel.EMPTY;
            Channel gatherings = Channel.EMPTY;
            byte[] lastView = new byte[0];
            for (Object packet : packets) {
                if (!(packet instanceof ClientboundCustomPayloadPacket custom)
                        || !(custom.payload() instanceof ProtobufChannel.Payload payload)) {
                    continue;
                }
                if (payload.type().equals(MeridianChannels.BODY_VIEW.type())) {
                    views = views.add(payload.bytes());
                    lastView = payload.bytes();
                } else if (payload.type().equals(MeridianChannels.GATHERING_RESPONSE.type())) {
                    gatherings = gatherings.add(payload.bytes());
                }
            }
            return new Traffic(views, gatherings, lastView);
        }

        String describe() {
            // 合计两条通道；单包组成取最后一个身体视图。
            long total = (views.bytes() + gatherings.bytes()) / WINDOW_SECONDS;
            long totalCompressed = (views.compressed() + gatherings.compressed()) / WINDOW_SECONDS;
            String text = views.describe("身体视图") + "，" + gatherings.describe("吐纳回执") + "，合计 " + total
                    + " 字节/秒（压缩后约 " + totalCompressed + "）";
            if (lastView.length == 0) {
                return text;
            }
            try {
                BodyViewUpdate update = BodyViewUpdate.parseFrom(lastView);
                int operations = update.getOperationsList().stream().mapToInt(item -> item.getSerializedSize()).sum();
                return text + "；末包 " + lastView.length + " 字节：身体 " + update.getBody().getSerializedSize()
                        + "，其中构型 " + update.getBody().getStructure().getSerializedSize()
                        + "；开脉规则 " + update.getOpening().getSerializedSize() + "，操作卡 " + operations;
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(invalid);
            }
        }
    }

    private static int compressedSize(byte[] bytes) {
        // zlib 默认级别，和原版网络压缩相同。
        var deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        byte[] buffer = new byte[bytes.length + DEFLATE_SLACK];
        int size = deflater.deflate(buffer);
        deflater.end();
        return size;
    }
}
