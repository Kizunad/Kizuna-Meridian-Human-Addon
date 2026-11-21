package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanAgingRules;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.MeridianOpeningRequest;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.PauseMeridianOpening;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewRequest;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewUpdate;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.Deflater;

/**
 * 量一名玩家每秒收到多少字节的身体视图：静止、被功法宿主轮询、行走、自动吐纳，订阅和不订阅内观，各量十秒。
 * 另验构型变了会重发一次，开脉请求版本冲突时视图不等限频，换维度后仍推内观视图。
 */
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
    /** 吐纳回执每秒最多几份：粒子每 250 毫秒走一步就立刻发，只是余量在变的每 5 tick 最多一份。 */
    private static final int MAX_RECEIPTS_PER_SECOND = 8;
    private static final int MAX_RECEIPTS = MAX_RECEIPTS_PER_SECOND * WINDOW_SECONDS;
    /** 自动吐纳十秒里至少每秒一份回执：粒子每 250 毫秒走一步就发，少于这个数就是吐纳中途停了。 */
    private static final int MIN_BREATHING_RECEIPTS = WINDOW_SECONDS;
    /** 不吐纳的场景对回执数没有下限。 */
    private static final int NO_RECEIPTS_REQUIRED = 0;
    /** 改了构型或换了维度之后量几 tick：每 tick inspect 一次，身体视图每 5 tick 推一份，够推好几份。 */
    private static final int FOLLOW_UP_TICKS = 20;
    private static final long MILLIS_PER_TICK = 50;
    private static final int DIMENSION_AGE_WAIT_TICKS = 10;
    private static final double AGE_EPSILON = 1e-6;
    private static final double OVERWORLD_LIFESPAN_REMAINING_SECONDS = 7_200;
    private static final double NETHER_LIFESPAN_REMAINING_SECONDS = 14_400;
    private static final long LIFESPAN_BOUNDARY_MILLIS = 1_000;
    private static final long ONE_MILLISECOND = 1;
    /** deflate 输出缓冲比输入多留的字节，放得下不可压缩数据的块头。 */
    private static final int DEFLATE_SLACK = 64;

    @GameTest(maxTicks = TIMEOUT)
    public void idleBodyViewTraffic(GameTestHelper context) {
        // 没人读身体、也没在动的玩家。
        measure(context, "静止", NO_RECEIPTS_REQUIRED, fixture -> { }, (fixture, tick) -> { });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void polledBodyViewTraffic(GameTestHelper context) {
        // 和 Combat-UI 一样每 5 tick 经功法宿主 inspect 一次：每次都结清时间，身体版本跟着变。
        measure(context, "每 5 tick 被 inspect 一次", NO_RECEIPTS_REQUIRED, fixture -> { }, (fixture, tick) -> {
            if (tick % COMBAT_UI_INSPECT_TICKS == 0) {
                inspect(fixture);
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void castingBodyViewTraffic(GameTestHelper context) {
        // 施放中的玩家每 tick 被 inspect 三次，同一 tick 里后两次不再推进时间。
        measure(context, "每 tick 被 inspect 三次", NO_RECEIPTS_REQUIRED, fixture -> { }, (fixture, tick) -> {
            for (int count = 0; count < CASTING_INSPECTS_PER_TICK; count++) {
                inspect(fixture);
            }
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void walkingBodyViewTraffic(GameTestHelper context) {
        // 着地、每 tick 前进 0.2 格，Human 的移动采样按行走结算。
        measure(context, "行走", NO_RECEIPTS_REQUIRED, fixture -> { }, (fixture, tick) -> {
            fixture.player.setOnGround(true);
            fixture.player.setPos(fixture.player.position().add(WALK_STEP, 0, 0));
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void introspectingIdleBodyViewTraffic(GameTestHelper context) {
        // 读过全部卷、开全脉、订阅了内观的玩家站着不动：每份视图带开脉规则和操作卡。
        measure(context, "内观订阅中静止", NO_RECEIPTS_REQUIRED, HumanBodyViewTrafficGameTest::introspect,
                (fixture, tick) -> { });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void introspectingCastingBodyViewTraffic(GameTestHelper context) {
        // 同上，再像施放中一样每 tick 被 inspect 三次。
        measure(context, "内观订阅中每 tick 被 inspect 三次", NO_RECEIPTS_REQUIRED,
                HumanBodyViewTrafficGameTest::introspect, (fixture, tick) -> {
                    for (int count = 0; count < CASTING_INSPECTS_PER_TICK; count++) {
                        inspect(fixture);
                    }
                });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void breathingBodyViewTraffic(GameTestHelper context) {
        // 读过全部卷、开全脉、订阅了内观的玩家开自动吐纳：身体推送带开脉规则和操作卡，吐纳回执不带身体。
        measure(context, "内观订阅中自动吐纳", MIN_BREATHING_RECEIPTS, fixture -> {
            introspect(fixture);
            breathe(fixture);
        }, (fixture, tick) -> { });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void breathingWithoutIntrospectionBodyViewTraffic(GameTestHelper context) {
        // 读过全部卷、开全脉但没订阅内观的玩家开自动吐纳，和关着经脉窗吐纳一样。
        measure(context, "未订阅内观自动吐纳", MIN_BREATHING_RECEIPTS, fixture -> {
            unlock(fixture);
            breathe(fixture);
        }, (fixture, tick) -> { });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void changedStructureIsSentAgain(GameTestHelper context) {
        // 开全脉改了各脉内腔体积，构型变了：下一份身体视图重带新构型，之后又省略。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            command(fixture, "meridian progression unlock");
            BodyStructure[] before = {null};
            context.onEachTick(() -> inspect(fixture));
            context.runAfterDelay(WARMUP_TICKS, () -> {
                before[0] = structure(fixture);
                fixture.connection.packets().clear();
                command(fixture, "meridian opening complete @s all");
            });
            context.runAfterDelay(WARMUP_TICKS + FOLLOW_UP_TICKS, () -> {
                try (fixture) {
                    verifyStructureChange(context, fixture, before[0]);
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void staleOpeningRequestGetsCurrentViewAtTickEnd(GameTestHelper context) {
        // 身体每 tick 改版时，客户端开脉请求的版本常是旧的：回冲突的这一 tick 末就推当前视图。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            unlock(fixture);
            subscribe(fixture);
            var probe = new StaleOpeningProbe(context, fixture);
            context.onEachTick(probe::tick);
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void dimensionChangeKeepsIntrospection(GameTestHelper context) {
        // 换维度和重生走同一段重置：内观订阅留着，之后推的仍是带操作卡的内观视图。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            introspect(fixture);
            context.onEachTick(() -> inspect(fixture));
            context.runAfterDelay(WARMUP_TICKS, () -> {
                fixture.connection.packets().clear();
                travelToNether(context, fixture);
            });
            context.runAfterDelay(WARMUP_TICKS + FOLLOW_UP_TICKS, () -> {
                try (fixture) {
                    verifyIntrospectionKept(context, fixture);
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void realDimensionTransferSplitsNaturalAge(GameTestHelper context) {
        // 原版 ServerPlayer.teleportTo 触发真实换维回调，年龄按传送前后的实际世界时钟分段。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            context.runAfterDelay(WARMUP_TICKS, () -> {
                try {
                    HumanPlayerBody body = fixture.body();
                    long overworldStart = HumanTechniqueHost.nowMillis();
                    body.settle(overworldStart);
                    double startAge = body.snapshot().vitality().ageSeconds();
                    context.runAfterDelay(DIMENSION_AGE_WAIT_TICKS,
                            () -> transferPlayerAndAssertAge(context, fixture, body, overworldStart, startAge));
                } catch (Throwable failure) {
                    fixture.close();
                    throw failure;
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void transferPlayerAndAssertAge(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                                    HumanPlayerBody body, long overworldStart,
                                                    double startAge) {
        try {
            long transferMillis = HumanTechniqueHost.nowMillis();
            body.settle(transferMillis);
            double transferAge = body.snapshot().vitality().ageSeconds();
            assertAgeDelta(context, "主世界", transferAge - startAge,
                    transferMillis - overworldStart, HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER);
            travelToNether(context, fixture);
            context.runAfterDelay(DIMENSION_AGE_WAIT_TICKS, () -> {
                try {
                    long netherEnd = HumanTechniqueHost.nowMillis();
                    body.settle(netherEnd);
                    double endAge = body.snapshot().vitality().ageSeconds();
                    assertAgeDelta(context, "下界", endAge - transferAge,
                            netherEnd - transferMillis, HumanAgingRules.DEFAULT_NETHER_MULTIPLIER);
                    context.succeed();
                } finally {
                    fixture.close();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @GameTest(maxTicks = TIMEOUT)
    public void lifespanBoundaryUsesDimensionAgeRate(GameTestHelper context) {
        assertLifespanBoundary(context, "minecraft:overworld", OVERWORLD_LIFESPAN_REMAINING_SECONDS);
        assertLifespanBoundary(context, "minecraft:the_nether", NETHER_LIFESPAN_REMAINING_SECONDS);
        context.succeed();
    }

    private static void assertLifespanBoundary(GameTestHelper context, String dimensionId,
                                               double remainingSeconds) {
        long startMillis = HumanTechniqueHost.nowMillis();
        var born = HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), startMillis);
        BodyMessages.BodySnapshot saved = BodyProtobuf.toMessage(born.snapshot());
        var vitality = saved.getVitality();
        double lifespanLimit = vitality.getAgeSeconds() + vitality.getSpentLifespanSeconds()
                + remainingSeconds;
        var shortenedVitality = vitality.toBuilder()
                .setLifespanLimitSeconds(lifespanLimit)
                .build();
        var shortened = saved.toBuilder()
                .setVitality(shortenedVitality)
                .build();
        var boundaryBody = new HumanPlayerBody(BodyProtobuf.fromMessage(shortened), born.receipts(), born.releasedQi());
        boundaryBody.selectEnvironment("test:" + dimensionId, dimensionId, startMillis);
        double agingRate = HumanAgingRules.configured().agingRate(dimensionId);
        long boundaryMillis = BodyTimeAdvancement.nextLifespanBoundary(boundaryBody.snapshot(), agingRate)
                .orElseThrow()
                .elapsedMillis();

        context.assertTrue(boundaryMillis - startMillis == LIFESPAN_BOUNDARY_MILLIS,
                dimensionId + "寿尽用时不符合新年龄流速");
        context.assertTrue(boundaryBody.bodyStamp(boundaryMillis - ONE_MILLISECOND).isPresent(),
                dimensionId + "寿尽前一毫秒未保持存活");
        context.assertTrue(boundaryBody.bodyStamp(boundaryMillis).isEmpty(),
                dimensionId + "边界当刻仍提供身体戳");
        boundaryBody.settle(boundaryMillis);
        context.assertTrue(boundaryBody.snapshot().vitality().dead(), dimensionId + "寿尽边界身体未死亡");
    }

    private static void assertAgeDelta(GameTestHelper context, String segment, double actual,
                                       long elapsedMillis, double dimensionMultiplier) {
        double biologicalRate = HumanAgingRules.GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND
                * dimensionMultiplier;
        double expected = elapsedMillis / 1000.0 * biologicalRate;
        context.assertTrue(Math.abs(actual - expected) < AGE_EPSILON,
                segment + "真实传送段年龄错误，预期 " + expected + "，实际 " + actual);
    }

    /** 等身体视图刚推过的下一 tick 发一个旧版本的开脉请求，再下一 tick 看冲突后有没有立刻补推视图。 */
    private static final class StaleOpeningProbe {
        private final GameTestHelper context;
        private final HumanTechniqueTestPlayer fixture;
        private int ticks;
        private int views;
        private String requestId = "";
        private boolean finished;

        StaleOpeningProbe(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
            this.context = context;
            this.fixture = fixture;
        }

        void tick() {
            // 每 tick inspect 一次让身体改版，视图因此每 5 tick 推一份。
            if (finished) {
                return;
            }
            inspect(fixture);
            int sent = bodyViews(fixture.connection.packets()).size();
            if (!requestId.isEmpty()) {
                finished = true;
                try (fixture) {
                    verifyConflictRefresh(sent);
                    context.succeed();
                }
                return;
            }
            boolean justSent = sent > views;
            views = sent;
            ticks++;
            if (ticks > WARMUP_TICKS && justSent) {
                fixture.connection.packets().clear();
                requestId = requestStaleOpening(fixture);
            }
        }

        private void verifyConflictRefresh(int sent) {
            // 上一份视图才推过一 tick，不补推的话要再等四 tick。
            ResponseEnvelope response = openingResponse(fixture.connection.packets(), requestId);
            context.assertTrue(response.getFailure().getCode() == FailureCode.REVISION_CONFLICT,
                    "旧版本的开脉请求没有回版本冲突：" + response);
            context.assertTrue(sent > 0, "开脉请求版本冲突后，这一 tick 末没有推当前身体视图");
        }
    }

    private static String requestStaleOpening(HumanTechniqueTestPlayer fixture) {
        // 和 UI 一样走正式开脉通道，只是带上一版的身体版本。
        String meridianId = HumanContent.bundle().getOpeningRules(0).getTargetMeridianId();
        var pause = PauseMeridianOpening.newBuilder()
                .setMeridianId(meridianId)
                .build();
        var request = MeridianOpeningRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(fixture.body().snapshot().id().value())
                .setExpectedRevision(fixture.body().snapshot().revision() - 1)
                .setPause(pause)
                .build();
        var payload = MeridianChannels.OPENING_REQUEST.payload(request.toByteArray());
        fixture.player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
        return request.getRequestId();
    }

    private static ResponseEnvelope openingResponse(List<?> packets, String requestId) {
        // 读服务端真正发回的开脉回执。
        return packets.stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(ClientboundCustomPayloadPacket.class::cast)
                .filter(packet -> packet.payload().type().equals(MeridianChannels.OPENING_RESPONSE.type()))
                .map(packet -> decodeResponse(((ProtobufChannel.Payload) packet.payload()).bytes()))
                .filter(response -> response.getRequestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少开脉回执"));
    }

    private static void verifyStructureChange(GameTestHelper context, HumanTechniqueTestPlayer fixture,
                                              BodyStructure before) {
        // 只看带身体的视图：第一份带新构型，之后的都省略。
        BodyStructure after = structure(fixture);
        context.assertTrue(!after.equals(before), "开全脉没有改变构型，测不到重发");
        List<BodyViewUpdate> views = bodyViews(fixture.connection.packets());
        context.assertTrue(views.size() >= 2, "改构型后只推了 " + views.size() + " 份身体视图");
        BodyViewUpdate first = views.get(0);
        context.assertTrue(!first.getStructureOmitted() && first.getBody().getStructure().equals(after),
                "构型变了，下一份身体视图没有带上新构型");
        context.assertTrue(views.stream().skip(1).allMatch(BodyViewUpdate::getStructureOmitted),
                "新构型发过之后又重带了构型");
    }

    static void travelToNether(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 原版传送换维度，触发 Fabric 的换维度回调。
        var player = fixture.player;
        var nether = player.level().getServer().getLevel(Level.NETHER);
        player.teleportTo(nether, player.getX(), player.getY(), player.getZ(), Set.of(), player.getYRot(),
                player.getXRot(), true /* resetCamera */);
        context.assertTrue(player.level() == nether, "玩家没有实际换维度");
    }

    private static void verifyIntrospectionKept(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 换维度后推的每份身体视图都还是内观视图，带着操作卡。
        List<BodyViewUpdate> views = bodyViews(fixture.connection.packets());
        context.assertTrue(!views.isEmpty(), "换维度后没有推身体视图");
        context.assertTrue(views.stream().allMatch(view -> view.getIntrospection() && view.getOperationsCount() > 0),
                "换维度后内观订阅丢了，推的是不带操作卡的外部视图");
    }

    private static BodyStructure structure(HumanTechniqueTestPlayer fixture) {
        // 和宿主推送的是同一份投影。
        return HumanCultivation.bodyView(fixture.body().snapshot()).getStructure();
    }

    private static List<BodyViewUpdate> bodyViews(List<?> packets) {
        // 服务端发给这名玩家的身体视图，去掉不带身体的订阅回执。
        return packets.stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(ClientboundCustomPayloadPacket.class::cast)
                .filter(packet -> packet.payload().type().equals(MeridianChannels.BODY_VIEW.type()))
                .map(packet -> decodeView(((ProtobufChannel.Payload) packet.payload()).bytes()))
                .filter(BodyViewUpdate::hasBody)
                .toList();
    }

    private static void introspect(HumanTechniqueTestPlayer fixture) {
        // 读完全部卷、开全脉，再和 UI 一样申请内观视图。
        unlock(fixture);
        subscribe(fixture);
    }

    static void unlock(HumanTechniqueTestPlayer fixture) {
        // 走正式命令读完全部卷、开全脉。
        command(fixture, "meridian progression unlock");
        command(fixture, "meridian opening complete @s all");
    }

    private static void measure(GameTestHelper context, String scenario, int minReceipts,
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
                    verify(context, scenario, minReceipts, traffic);
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void verify(GameTestHelper context, String scenario, int minReceipts, Traffic traffic) {
        // 两条通道都限频，吐纳场景的回执也不能停；构型在窗口前已经发过，窗口里不该重带；回执的身体走身体视图通道。
        int views = traffic.views().packets();
        context.assertTrue(views <= MAX_VIEWS,
                scenario + "：十秒推了 " + views + " 次身体视图，超过上限 " + MAX_VIEWS + " 次");
        int receipts = traffic.gatherings().packets();
        context.assertTrue(receipts <= MAX_RECEIPTS,
                scenario + "：十秒发了 " + receipts + " 份吐纳回执，超过上限 " + MAX_RECEIPTS + " 份");
        context.assertTrue(receipts >= minReceipts,
                scenario + "：十秒只发了 " + receipts + " 份吐纳回执，少于 " + minReceipts + " 份");
        context.assertTrue(traffic.structuredViews() == 0,
                scenario + "：构型没变，十秒里却有 " + traffic.structuredViews() + " 份身体视图重带构型");
        context.assertTrue(traffic.bodiedGatherings() == 0,
                scenario + "：" + traffic.bodiedGatherings() + " 份吐纳回执带了身体");
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
        // 和 UI 打开经脉窗时一样申请内观视图。
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

    /** 十秒里两条带身体的通道各收到多少、几份带了构型或身体，以及最后一个身体视图和吐纳回执的组成。 */
    private record Traffic(Channel views, Channel gatherings, int structuredViews, int bodiedGatherings,
                           byte[] lastView, byte[] lastGathering) {
        static Traffic of(List<?> packets) {
            // 只数服务端发给这名玩家的身体视图和吐纳回执；订阅回执不带身体，同样计入身体视图通道。
            Channel views = Channel.EMPTY;
            Channel gatherings = Channel.EMPTY;
            int structuredViews = 0;
            int bodiedGatherings = 0;
            byte[] lastView = new byte[0];
            byte[] lastGathering = new byte[0];
            for (Object packet : packets) {
                if (!(packet instanceof ClientboundCustomPayloadPacket custom)
                        || !(custom.payload() instanceof ProtobufChannel.Payload payload)) {
                    continue;
                }
                if (payload.type().equals(MeridianChannels.BODY_VIEW.type())) {
                    views = views.add(payload.bytes());
                    lastView = payload.bytes();
                    if (decodeView(payload.bytes()).getBody().hasStructure()) {
                        structuredViews++;
                    }
                } else if (payload.type().equals(MeridianChannels.GATHERING_RESPONSE.type())) {
                    gatherings = gatherings.add(payload.bytes());
                    lastGathering = payload.bytes();
                    if (decodeGathering(payload.bytes()).getView().hasBody()) {
                        bodiedGatherings++;
                    }
                }
            }
            return new Traffic(views, gatherings, structuredViews, bodiedGatherings, lastView, lastGathering);
        }

        String describe() {
            // 合计两条通道；单包组成取最后一个身体视图和最后一份吐纳回执。
            long total = (views.bytes() + gatherings.bytes()) / WINDOW_SECONDS;
            long totalCompressed = (views.compressed() + gatherings.compressed()) / WINDOW_SECONDS;
            String text = views.describe("身体视图") + "，" + gatherings.describe("吐纳回执") + "，合计 " + total
                    + " 字节/秒（压缩后约 " + totalCompressed + "）；带构型的身体视图 " + structuredViews
                    + " 份，带身体的吐纳回执 " + bodiedGatherings + " 份";
            if (lastView.length != 0) {
                BodyViewUpdate update = decodeView(lastView);
                int operations = update.getOperationsList().stream().mapToInt(item -> item.getSerializedSize()).sum();
                text += "；视图末包 " + lastView.length + " 字节：身体 " + update.getBody().getSerializedSize()
                        + "，其中构型 " + update.getBody().getStructure().getSerializedSize()
                        + "；开脉规则 " + update.getOpening().getSerializedSize() + "，操作卡 " + operations;
            }
            if (lastGathering.length != 0) {
                GatheringView view = decodeGathering(lastGathering).getView();
                int choices = view.getChoicesList().stream().mapToInt(item -> item.getSerializedSize()).sum();
                text += "；回执末包 " + lastGathering.length + " 字节：身体 " + view.getBody().getSerializedSize()
                        + "，其中构型 " + view.getBody().getStructure().getSerializedSize()
                        + "；吐纳 " + view.getBreathing().getSerializedSize() + "，可选配方 " + choices
                        + "，环境 " + view.getEnvironment().getSerializedSize();
            }
            return text;
        }
    }

    private static BodyViewUpdate decodeView(byte[] bytes) {
        // 测试只读服务端发出的字节，解不开就是协议坏了。
        try {
            return BodyViewUpdate.parseFrom(bytes);
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static ResponseEnvelope decodeResponse(byte[] bytes) {
        // 同上，开脉回执通道。
        try {
            return ResponseEnvelope.parseFrom(bytes);
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    static GatheringResponse decodeGathering(byte[] bytes) {
        // 同上，吐纳回执通道。
        try {
            return GatheringResponse.parseFrom(bytes);
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(invalid);
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
