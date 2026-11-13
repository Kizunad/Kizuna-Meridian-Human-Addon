package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.world.level.GameType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 换维度、重生时宿主新建吐纳会话，新会话的序号从头算。回执必须换成新的会话标识，
 * 客户端才知道不该拿旧会话的序号比较；客户端接着往上加的控制序号新会话照收。
 * 死在吐纳产物还在经脉途中、又是在两 tick 之间（摔死、被玩家打死、/kill 都在包处理里）时，宿主下一 tick 推进吐纳配送，
 * 时刻已经变了；配送不能去给死体推进时间、抛错让宿主暂停身体，否则重生后根本不建新会话。
 * 重置后发结束吐纳来验序号：刚复活耐力为 0，开始吐纳会按规则被拒，和序号无关；结束在空闲时也照常记下序号。
 */
public final class HumanBreathingSessionGameTest {
    private static final long MILLIS_PER_TICK = 50;
    /** 换维度前先自动吐纳一秒，和玩家吐纳中途换维度一样。 */
    private static final int BREATHING_TICKS = 20;
    /** 被打死前最多等自动吐纳这么久，等它把肺里的产物派上经脉。 */
    private static final int MAX_TRANSIT_WAIT_TICKS = 100;
    /** 等在途货物时每隔这么多 tick 看一次。 */
    private static final int POLL_TICKS = 1;
    /** 死后停这么多 tick 再重生，和玩家停在死亡画面一样；这段时间宿主照常每 tick 推进这具死体的吐纳会话。 */
    private static final int DEATH_TICKS = 20;
    private static final int TIMEOUT_SLACK_TICKS = 20;
    private static final int TIMEOUT = MAX_TRANSIT_WAIT_TICKS + DEATH_TICKS + TIMEOUT_SLACK_TICKS;
    /** 原版致死伤害每次只伤一个部位，最多打这么多次等人体判死。 */
    private static final int MAX_LETHAL_HITS = 128;
    /** 这名玩家的第一条吐纳控制。 */
    private static final long FIRST_SEQUENCE = 1;

    @GameTest(maxTicks = TIMEOUT)
    public void dimensionChangeStartsNewBreathingSession(GameTestHelper context) {
        // 自动吐纳中走原版传送换维度。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            HumanBodyViewTrafficGameTest.unlock(fixture);
            BreathingView before = beginAutomatic(context, fixture, FIRST_SEQUENCE).getBreathing();
            context.runAfterDelay(BREATHING_TICKS, () -> {
                try (fixture) {
                    fixture.connection.packets().clear();
                    HumanBodyViewTrafficGameTest.travelToNether(context, fixture);
                    verifyNewSession(context, fixture, before);
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void respawnStartsNewBreathingSession(GameTestHelper context) {
        // 自动吐纳派出的货物还在经脉途中时被打死，停在死亡画面一秒，再走客户端重生指令。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            fixture.player.setGameMode(GameType.SURVIVAL);
            HumanBodyViewTrafficGameTest.unlock(fixture);
            GatheringView begun = beginAutomatic(context, fixture, FIRST_SEQUENCE);
            Set<String> outlets = breathingOutlets(begun);
            BreathingView before = begun.getBreathing();
            whenDispatched(context, fixture, outlets, 0, () -> killThenRespawn(context, fixture, before));
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void whenDispatched(GameTestHelper context, HumanTechniqueTestPlayer fixture, Set<String> outlets,
                                       int waitedTicks, Runnable then) {
        // 下一 tick 看一次：吐纳器官出口上有了在途货物才往下走，死时宿主的吐纳配送才记着在途。
        context.runAfterDelay(POLL_TICKS, () -> {
            boolean dispatched = fixture.body().snapshot().transits().stream()
                    .anyMatch(transit -> outlets.contains(transit.connectionId().value()));
            if (dispatched) {
                then.run();
                return;
            }
            int waited = waitedTicks + POLL_TICKS;
            boolean waitedTooLong = waited >= MAX_TRANSIT_WAIT_TICKS;
            if (waitedTooLong) {
                fixture.close();
            }
            context.assertTrue(!waitedTooLong, "自动吐纳一直没派出在途货物，测不到死体配送结算");
            whenDispatched(context, fixture, outlets, waited, then);
        });
    }

    private static Set<String> breathingOutlets(GatheringView view) {
        // 吐纳配送只走吐纳器官的出口连接；人体只给经脉之间的连接开被动扩散，出口上的在途都是吐纳派出去的。
        List<String> organs = view.getBreathing().getOrganIdsList();
        return view.getChoicesList().stream()
                .filter(choice -> organs.contains(choice.getOrganId()))
                .map(choice -> choice.getConnectionId())
                .collect(Collectors.toSet());
    }

    private static void killThenRespawn(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                        BreathingView before) {
        // 排进两 tick 之间的服务端任务里打死，和包处理里的死亡一样；停在死亡画面这段时间宿主照常每 tick 推进配送。
        MinecraftServer server = fixture.player.level().getServer();
        fixture.connection.packets().clear();
        server.schedule(new TickTask(server.getTickCount(), () -> kill(fixture)));
        context.runAfterDelay(DEATH_TICKS, () -> {
            try (fixture) {
                context.assertTrue(!fixture.player.isAlive(), "原版致死伤害没有让玩家死亡");
                respawn(fixture);
                verifyNewSession(context, fixture, before);
                context.succeed();
            }
        });
    }

    private static void verifyNewSession(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                         BreathingView before) {
        // 重置后推来的回执属于新会话、吐纳已停；接着往上加的序号新会话照收，会话标识在会话内不变。
        BreathingView pushed = lastReceipt(context, fixture.connection.packets());
        context.assertTrue(!pushed.getSessionId().isEmpty(), "回执没带吐纳会话标识");
        context.assertTrue(!pushed.getSessionId().equals(before.getSessionId()),
                "重置后回执仍是旧吐纳会话 " + before.getSessionId());
        context.assertTrue(!pushed.getActive(), "重置后吐纳没有停");
        long nextSequence = before.getSequence() + 1;
        BreathingView resumed = end(context, fixture, nextSequence);
        context.assertTrue(resumed.getSessionId().equals(pushed.getSessionId()),
                "同一吐纳会话内会话标识变了：" + pushed.getSessionId() + " → " + resumed.getSessionId());
        context.assertTrue(resumed.getSequence() == nextSequence, "新会话没记下客户端的序号：" + resumed.getSequence());
    }

    private static void kill(HumanTechniqueTestPlayer fixture) {
        // 吐纳中身体版本一直在变，不按预期版本提交伤害；重复原版致死伤害到人体判死，死没死由重生前检查。
        var player = fixture.player;
        for (int hit = 0; hit < MAX_LETHAL_HITS && player.isAlive(); hit++) {
            player.invulnerableTime = 0;
            player.kill(player.level());
        }
    }

    private static void respawn(HumanTechniqueTestPlayer fixture) {
        // 和客户端点「重生」一样发重生指令。
        fixture.player.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    private static GatheringView beginAutomatic(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                                long sequence) {
        // 和吐纳窗口勾上自动再点开始一样。
        var control = BreathControl.newBuilder()
                .setAction(BreathAction.BEGIN_BREATHING)
                .setAutomatic(true /* automatic */)
                .setSequence(sequence)
                .build();
        return send(context, fixture, control);
    }

    private static BreathingView end(GameTestHelper context, HumanTechniqueTestPlayer fixture, long sequence) {
        // 和关吐纳窗时一样发结束吐纳。
        var control = BreathControl.newBuilder()
                .setAction(BreathAction.END_BREATHING)
                .setSequence(sequence)
                .build();
        return send(context, fixture, control).getBreathing();
    }

    private static GatheringView send(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                      BreathControl control) {
        // 走正式吐纳请求，返回宿主确认的采集视图（含吐纳状态）。
        var request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(fixture.player.getStringUUID())
                .setBreath(control)
                .build();
        long now = fixture.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        GatheringResponse response = fixture.body().gather(now, request);
        context.assertTrue(response.hasView(), "吐纳控制被拒：" + response.getFailure());
        return response.getView();
    }

    private static BreathingView lastReceipt(GameTestHelper context, List<?> packets) {
        // 服务端发给这名玩家的最后一份带吐纳状态的回执。
        List<GatheringResponse> receipts = packets.stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(ClientboundCustomPayloadPacket.class::cast)
                .filter(packet -> packet.payload().type().equals(MeridianChannels.GATHERING_RESPONSE.type()))
                .map(packet -> ((ProtobufChannel.Payload) packet.payload()).bytes())
                .map(HumanBodyViewTrafficGameTest::decodeGathering)
                .filter(response -> response.hasView() && response.getView().hasBreathing())
                .toList();
        context.assertTrue(!receipts.isEmpty(), "重置后没有推吐纳回执");
        return receipts.getLast().getView().getBreathing();
    }
}
