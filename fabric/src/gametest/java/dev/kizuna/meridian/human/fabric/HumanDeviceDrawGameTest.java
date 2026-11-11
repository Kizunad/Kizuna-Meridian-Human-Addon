package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.DrawResult;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionDraw;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionQuery;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 装置取用的整体回归：按比例扣区域、速率封顶、下个窗口放回、去重与重载。 */
public final class HumanDeviceDrawGameTest {
    private static final long WINDOW_MILLIS = 30_000;
    private static final int REGION_BLOCK = 4_000;
    private static final double SMALL_DRAW_QI = 0.1;
    private static final double GREEDY_DRAW_QI = 1_000_000;
    private static final long WINDOW_TAIL_MILLIS = 200;
    private static final double RELATIVE_TOLERANCE = 1e-6;
    private static final double ABSOLUTE_TOLERANCE = 1e-9;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void deviceDrawTakesFromRegionAndReturnsNextWindow(GameTestHelper context) {
        // 取 0.1 元：区域按各属性比例少掉这些，默认账户多出这些，到窗尾整批放回原区域。
        ServerLevel world = context.getLevel();
        var state = new HumanWorldState();
        RegionView original = state.query(world, REGION_BLOCK, REGION_BLOCK, 0);
        QiInventory originalStock = BodyProtobuf.fromInventory(original.getInventory());
        DrawResult result = state.draw(draw("gametest:draw-small", world, SMALL_DRAW_QI), world, 1_000);
        QiInventory drawn = BodyProtobuf.fromInventory(result.getDrawnQi());
        context.assertTrue(result.getSourceId().equals(original.getSourceId()), "取用来源不是所在区域");
        context.assertTrue(!result.getDuplicate() && !result.getPersisted(), "首次取用被当成重复或已写盘");
        context.assertTrue(close(drawn.totalQi(), SMALL_DRAW_QI), "没取到想要的量：" + drawn.totalQi());
        double fraction = SMALL_DRAW_QI / originalStock.totalQi();
        for (Qi entry : drawn.entries()) {
            Qi before = amountOf(originalStock, entry);
            context.assertTrue(close(entry.amount(), before.amount() * fraction), "没按属性比例分摊：" + entry);
        }

        // 区域、默认账户和待放回队列三处对得上，总账不变。
        RegionView afterDraw = state.query(world, REGION_BLOCK, REGION_BLOCK, 1_000);
        CompoundTag saved = state.save(new CompoundTag());
        QiInventory released = defaultReleased(saved);
        context.assertTrue(afterDraw.getSourceRevision() == original.getSourceRevision() + 1, "取用没有推进区域版本");
        context.assertTrue(released.equals(drawn), "取出量没有进默认释放账户");
        context.assertTrue(saved.getListOrEmpty("pending_credits").size() == 1, "取出量没有排队放回");
        double totalAfter = BodyProtobuf.fromInventory(afterDraw.getInventory()).totalQi() + released.totalQi();
        context.assertTrue(close(totalAfter, originalStock.totalQi()), "取用后总账不守恒");

        // 窗口结束前不放回；到窗尾续窗时整批放回，默认账户清空。
        state.renewEnvironment(WINDOW_MILLIS - 1);
        context.assertTrue(defaultReleased(state.save(new CompoundTag())).equals(drawn), "窗口没结束就放回了");
        state.renewEnvironment(WINDOW_MILLIS);
        RegionView returned = state.query(world, REGION_BLOCK, REGION_BLOCK, WINDOW_MILLIS);
        CompoundTag afterReturn = state.save(new CompoundTag());
        context.assertTrue(defaultReleased(afterReturn).entries().isEmpty(), "放回后默认账户还有余额");
        context.assertTrue(afterReturn.getListOrEmpty("pending_credits").isEmpty(), "放回后队列没清");
        context.assertTrue(close(BodyProtobuf.fromInventory(returned.getInventory()).totalQi(), originalStock.totalQi()),
                "放回原区域后总量不等于取用前");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void deviceDrawIsCappedBySharedRateAndInventory(GameTestHelper context) {
        // 想取很多时只拿到这一秒的窗口速率；同一秒再取拿到零，下一秒又能取。
        ServerLevel world = context.getLevel();
        var state = new HumanWorldState();
        RegionView original = state.query(world, REGION_BLOCK, -REGION_BLOCK, 0);
        double ratePerSecond = source(state, original.getSourceId()).getMaxAbsorptionPerSecond();
        context.assertTrue(ratePerSecond > 0 && ratePerSecond < BodyProtobuf.fromInventory(original.getInventory()).totalQi(),
                "夹具区域的速率不适合本测试：" + ratePerSecond);
        double spareBefore = state.query(world, REGION_BLOCK, -REGION_BLOCK, 1_000).getSpareAbsorptionPerSecond();
        double first = drawnQi(state.draw(draw("gametest:greedy-1", world, GREEDY_DRAW_QI, -REGION_BLOCK), world, 1_000));
        double spareAfter = state.query(world, REGION_BLOCK, -REGION_BLOCK, 1_500).getSpareAbsorptionPerSecond();
        double sameSecond = drawnQi(state.draw(draw("gametest:greedy-2", world, GREEDY_DRAW_QI, -REGION_BLOCK), world, 1_000));
        double nextSecond = drawnQi(state.draw(draw("gametest:greedy-3", world, GREEDY_DRAW_QI, -REGION_BLOCK), world, 2_000));
        context.assertTrue(close(first, ratePerSecond), "取用没有封顶在一秒的窗口速率：" + first + " / " + ratePerSecond);
        context.assertTrue(sameSecond == 0, "同一秒的速率已占满却还取到了：" + sameSecond);
        context.assertTrue(close(nextSecond, ratePerSecond), "下一秒没恢复速率：" + nextSecond);

        // 查询报的剩余速率就是这一秒能取到的：取前等于取到的量，占满后是零。
        context.assertTrue(close(spareBefore, first), "取前查到的剩余速率和实际取到的不一样：" + spareBefore + " / " + first);
        context.assertTrue(spareAfter == 0, "速率占满后查询还报有剩余：" + spareAfter);

        // 窗口只剩 0.2 秒时截到窗尾；窗口到期还没续窗时，取用先结清再按新窗口取。
        double tail = drawnQi(state.draw(draw("gametest:tail", world, GREEDY_DRAW_QI, -REGION_BLOCK), world,
                WINDOW_MILLIS - WINDOW_TAIL_MILLIS));
        context.assertTrue(close(tail, ratePerSecond * WINDOW_TAIL_MILLIS / 1000.0), "窗尾区间没截短：" + tail);
        DrawResult renewed = state.draw(draw("gametest:renewed", world, SMALL_DRAW_QI, -REGION_BLOCK), world, WINDOW_MILLIS);
        context.assertTrue(close(drawnQi(renewed), SMALL_DRAW_QI), "到期窗口没先续上就取用");

        // 非主世界没有区域：返回不带来源的零结果，不算失败。
        ServerLevel nether = world.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        context.assertTrue(nether != null, "测试服务器未加载下界");
        DrawResult none = state.draw(draw("gametest:nether", nether, SMALL_DRAW_QI, 0), nether, 1_000);
        context.assertTrue(!none.hasSourceId() && none.getDrawnQi().getEntriesCount() == 0, "下界取到了真元");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void deviceDrawDeduplicatesAcrossReloadAndRejectsConflicts(GameTestHelper context) throws Exception {
        // 同 ID 重发拿到首次结果、不再扣区域；写盘后从真实文件重载仍然去重，并确认持久化。
        ServerLevel world = context.getLevel();
        var state = new HumanWorldState();
        state.query(world, -REGION_BLOCK, REGION_BLOCK, 0); // 在 0 时刻建好区域，后面的时刻都落在这个窗口里
        RegionDraw request = draw("gametest:draw-dedup", world, SMALL_DRAW_QI, -REGION_BLOCK, REGION_BLOCK);
        DrawResult first = state.draw(request, world, 1_000);
        RegionView afterFirst = state.query(world, -REGION_BLOCK, REGION_BLOCK, 1_000);
        DrawResult again = state.draw(request, world, 1_500);
        context.assertTrue(again.getDuplicate() && !again.getPersisted(), "内存里的重复取用被当成已写盘");
        context.assertTrue(again.getDrawnQi().equals(first.getDrawnQi()), "重复取用的结果和首次不同");
        context.assertTrue(state.query(world, -REGION_BLOCK, REGION_BLOCK, 1_500).equals(afterFirst), "重复取用又扣了区域");

        Path directory = Files.createTempDirectory(Path.of("."), "device-draw-");
        Path file = directory.resolve("human.dat");
        state.flushHandoffs(file);
        HumanWorldState reloaded = HumanWorldState.load(file);
        DrawResult afterReload = reloaded.draw(request, world, 2_000);
        context.assertTrue(afterReload.getDuplicate() && afterReload.getPersisted(), "重载后没有去重或没确认写盘");
        context.assertTrue(afterReload.getDrawnQi().equals(first.getDrawnQi()), "重载后重复取用的结果变了");
        // 剩余速率随查询时刻变化，只比库存和修订号。
        RegionView regionReloaded = reloaded.query(world, -REGION_BLOCK, REGION_BLOCK, 2_000);
        context.assertTrue(regionReloaded.getInventory().equals(afterFirst.getInventory())
                && regionReloaded.getSourceRevision() == afterFirst.getSourceRevision(), "重载后重复取用又扣了区域");
        context.assertTrue(defaultReleased(reloaded.save(new CompoundTag())).equals(BodyProtobuf.fromInventory(first.getDrawnQi())),
                "重载后待放回的量变了");

        // 同 ID 改数量或位置必须拒绝；非法数量不占 ID。
        assertRejected(context, reloaded, request.toBuilder().setAmount(SMALL_DRAW_QI * 2).build(), world,
                FailureCode.REQUEST_ID_CONFLICT);
        assertRejected(context, reloaded, request.toBuilder().setPosition(request.getPosition().toBuilder().setBlockX(0)).build(),
                world, FailureCode.REQUEST_ID_CONFLICT);
        for (double amount : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertRejected(context, reloaded, request.toBuilder().setDrawId("gametest:bad-" + amount).setAmount(amount).build(),
                    world, FailureCode.INVALID_REQUEST);
        }

        // 两个窗口后续窗时清掉去重记录；放回早已在第一个窗尾完成。
        reloaded.renewEnvironment(WINDOW_MILLIS);
        reloaded.renewEnvironment(2 * WINDOW_MILLIS + 1_000);
        ListTag ids = reloaded.save(new CompoundTag()).getListOrEmpty("draw_ids");
        context.assertTrue(ids.isEmpty(), "两窗之后取用记录还在：" + ids.size());
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT + HumanWorldState.DRAW_SAVE_INTERVAL_TICKS)
    public void boundDrawsWaitForTheSaveIntervalWhileReleasesSaveAtOnce(GameTestHelper context) throws Exception {
        // 绑了服务端时钟：取用攒到距上次保存满间隔才写盘，期间重发仍报未写盘；释放不等间隔，下一 tick 就写。
        ServerLevel world = context.getLevel();
        var state = new HumanWorldState();
        state.bind(world.getServer());
        Path file = Files.createTempDirectory(Path.of("."), "device-draw-interval-").resolve("human.dat");
        state.query(world, REGION_BLOCK, 2 * REGION_BLOCK, 0);
        // 先做一次保存，当作刚过的世界保存，间隔从这一 tick 起算。
        state.setDirty();
        state.save(file.toFile());
        context.assertTrue(Files.exists(file), "起算用的保存没有写出文件");
        RegionDraw request = draw("gametest:draw-interval", world, SMALL_DRAW_QI, REGION_BLOCK, 2 * REGION_BLOCK);
        int clearlyEarly = HumanWorldState.DRAW_SAVE_INTERVAL_TICKS / 2;
        int clearlyDue = HumanWorldState.DRAW_SAVE_INTERVAL_TICKS + 5;
        context.runAfterDelay(1, () -> {
            state.draw(request, world, 1_000);
            state.flushHandoffs(file);
            context.assertTrue(!state.draw(request, world, 1_000).getPersisted(), "刚受理的取用当 tick 就写盘了");
        });
        context.runAfterDelay(clearlyEarly, () -> {
            state.flushHandoffs(file);
            context.assertTrue(!state.draw(request, world, 1_000).getPersisted(), "没到保存间隔就把取用写盘了");
        });
        context.runAfterDelay(clearlyDue, () -> {
            state.flushHandoffs(file);
            context.assertTrue(state.draw(request, world, 1_000).getPersisted(), "满了保存间隔还没把取用写盘");
        });
        context.runAfterDelay(clearlyDue + 1, () -> {
            // 上一 tick 刚存过盘，释放照样当 tick 写，不等取用的间隔。
            var release = RegionRelease.newBuilder()
                    .setPosition(request.getPosition())
                    .setReleaseId("gametest:release-after-draw")
                    .setReleasedQi(BodyProtobuf.toInventory(new QiInventory(List.of(
                            new Qi(Qi.QiAttribute.WOOD, SMALL_DRAW_QI, 0.01)))))
                    .build();
            state.release(release, world, 1_000);
            state.flushHandoffs(file);
            context.assertTrue(state.release(release, world, 1_000).getPersisted(), "释放等了取用的保存间隔");
            context.succeed();
        });
    }

    private static RegionDraw draw(String id, ServerLevel world, double amountQi) {
        // 默认取用点在第一象限测试区域里。
        return draw(id, world, amountQi, REGION_BLOCK, REGION_BLOCK);
    }

    private static RegionDraw draw(String id, ServerLevel world, double amountQi, int blockZ) {
        // 速率测试用单独一块区域，避免和其他测试共用曲线。
        return draw(id, world, amountQi, REGION_BLOCK, blockZ);
    }

    private static RegionDraw draw(String id, ServerLevel world, double amountQi, int blockX, int blockZ) {
        // 位置写真实维度 ID 和方块坐标，和 Brewing 釜的取用请求同形。
        var position = RegionQuery.newBuilder().setDimensionId(world.dimension().identifier().toString())
                .setBlockX(blockX).setBlockZ(blockZ).build();
        return RegionDraw.newBuilder().setDrawId(id).setPosition(position).setAmount(amountQi).build();
    }

    private static double drawnQi(DrawResult result) {
        // 只看实际取到的总元数。
        return BodyProtobuf.fromInventory(result.getDrawnQi()).totalQi();
    }

    private static Qi amountOf(QiInventory stock, Qi kind) {
        // 找同属性同相态的库存行，用来核对分摊比例。
        return stock.entries().stream()
                .filter(entry -> entry.attribute().equals(kind.attribute()) && entry.phase() == kind.phase())
                .findFirst().orElseThrow();
    }

    private static EnvironmentQiSource source(HumanWorldState state, String sourceId) {
        // RegionView 不带窗口速率；从存档字节读出完整来源。
        ListTag sources = state.save(new CompoundTag()).getListOrEmpty("environment_sources");
        for (int index = 0; index < sources.size(); index++) {
            byte[] bytes = ((net.minecraft.nbt.ByteArrayTag) sources.get(index)).getAsByteArray();
            try {
                EnvironmentQiSource source = EnvironmentQiSource.parseFrom(bytes);
                if (source.getSpace().getSourceId().equals(sourceId)) {
                    return source;
                }
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(invalid);
            }
        }
        throw new IllegalStateException("存档里没有区域：" + sourceId);
    }

    private static QiInventory defaultReleased(CompoundTag saved) {
        // 默认释放账户只存在世界级字段里。
        try {
            return BodyProtobuf.fromInventory(BodyMessages.QiInventory.parseFrom(saved.getByteArray("released_qi").orElseThrow()));
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static void assertRejected(GameTestHelper context, HumanWorldState state, RegionDraw request,
                                       ServerLevel world, FailureCode expected) {
        // 拒绝必须给出协议失败码，而不是内部错误。
        FailureCode actual = null;
        try {
            state.draw(request, world, 3_000);
        } catch (HumanErrors.HostRejected rejected) {
            actual = rejected.code();
        }
        context.assertTrue(actual == expected, "期望 " + expected + "，实际 " + actual + "：" + request.getDrawId());
    }

    private static boolean close(double actual, double expected) {
        // 按比例分摊和速率余量会带来极小的相对误差。
        return Math.abs(actual - expected) <= Math.max(ABSOLUTE_TOLERANCE, Math.abs(expected) * RELATIVE_TOLERANCE);
    }
}
