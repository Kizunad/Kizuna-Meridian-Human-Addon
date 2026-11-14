package dev.kizuna.meridian.human.fabric;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.human.server.HumanCultivation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;

/**
 * 同一毫秒里结算两次，第二次不能再改身体版本。
 * 原版伤害先读一次身体、记下版本，Healing 再读几次，最后按记下的版本提交；每次读都会结算。
 * 储备回补内腔是在推进时间之后做的，回补完经脉之间有了落差，被动扩散此刻就能发运；
 * 这一批要是留给下一次结算，版本就在「读」和「提交」之间变了，伤害报 REVISION_CONFLICT。
 */
public final class HumanSameMillisSettleGameTest {
    private static final long MILLIS_PER_TICK = 50;
    /** 只有这一条经脉的外壁有储备，回补只进它的内腔。 */
    private static final String RESERVE_MERIDIAN = "left_large_intestine_meridian";
    private static final String RESERVE_QI = "2";
    private static final String RESERVE_DENSITY = "0.099";
    /** 摆好身体后过这么多 tick 再动手：没到 250 毫秒的修炼步长，宿主自己还没结算过，这一次结算要补 100 毫秒。 */
    private static final int WAIT_TICKS = 2;
    private static final int VERIFY_TICKS = WAIT_TICKS + 2;
    /** 原版致死伤害每次只伤一个部位，最多打这么多次等人体判死。 */
    private static final int MAX_LETHAL_HITS = 128;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void reserveRefillLeavesNothingDueInTheSameMillisecond(GameTestHelper context) {
        // 第一次结算把储备回补进内腔，还得把回补引出的扩散当场发掉：结算完没有此刻到期的事件，再结算一次版本不变。
        var fixture = refillingPlayer(context);
        String[] failure = {"两 tick 之间的任务没有执行"};
        betweenTicks(context, fixture, () -> {
            HumanPlayerBody body = fixture.body();
            long now = now(fixture.player);
            body.settle(now);
            BodySnapshot settled = body.snapshot();
            boolean refilled = settled.meridianStates().stream().anyMatch(item -> item.lumenQi().volume() > 0);
            boolean stillDue = BodyTimeAdvancement.nextEventBoundary(settled, HumanPlayerBody.AGING_RATE,
                    BodyTimeAdvancement.Mode.ACTIVE).filter(boundary -> boundary.elapsedMillis() <= now).isPresent();
            body.settle(now);
            long again = body.snapshot().revision();
            failure[0] = null;
            if (!refilled) {
                failure[0] = "储备没有回补进内腔，场景没摆出来";
            } else if (settled.transits().isEmpty()) {
                failure[0] = "回补后没有扩散发运，场景没摆出来";
            } else if (stillDue) {
                failure[0] = "结算完还有此刻到期的事件";
            } else if (again != settled.revision()) {
                failure[0] = "同一毫秒第二次结算又改了身体版本：" + settled.revision() + " → " + again;
            }
        });
        context.runAfterDelay(VERIFY_TICKS, () -> {
            try (fixture) {
                context.assertTrue(failure[0] == null, String.valueOf(failure[0]));
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void vanillaKillBetweenTicksDuringReserveRefillReachesTheBody(GameTestHelper context) {
        // 同样的身体，在两 tick 之间走原版 kill（和 /kill、包处理里的死亡一样）：伤害要提交得上，人体判死。
        var fixture = refillingPlayer(context);
        fixture.player.setGameMode(GameType.SURVIVAL);
        String[] failure = {"两 tick 之间的任务没有执行"};
        betweenTicks(context, fixture, () -> {
            ServerPlayer player = fixture.player;
            failure[0] = null;
            try {
                for (int hit = 0; hit < MAX_LETHAL_HITS && player.isAlive(); hit++) {
                    player.invulnerableTime = 0;
                    player.kill(player.level());
                }
            } catch (RuntimeException rejected) {
                failure[0] = "原版致死伤害没能提交到身体：" + rejected;
            }
        });
        context.runAfterDelay(VERIFY_TICKS, () -> {
            try (fixture) {
                context.assertTrue(failure[0] == null, String.valueOf(failure[0]));
                context.assertTrue(!fixture.player.isAlive() && fixture.body().snapshot().vitality().dead(),
                        "原版致死伤害后人体没有判死");
            }
            context.succeed();
        });
    }

    private static HumanTechniqueTestPlayer refillingPlayer(GameTestHelper context) {
        // 八脉全开、真炁境、内腔全空，只有一条经脉的外壁有一点储备：占用低于回补线，储备会往这一条的内腔回补。
        var fixture = new HumanTechniqueTestPlayer(context);
        CommandSourceStack op = fixture.player.createCommandSourceStack()
                .withPermission(LevelBasedPermissionSet.OWNER);
        String name = fixture.player.getScoreboardName();
        for (String meridian : HumanCultivation.MERIDIANS) {
            context.assertTrue(HumanTestCommands.run(op, "meridian opening complete " + name + " " + meridian) == 1,
                    "开脉命令失败：" + meridian);
        }
        context.assertTrue(HumanTestCommands.run(op, "meridian body realm true_qi") == 1, "改境界命令失败");
        context.assertTrue(HumanTestCommands.run(op, "meridian qi set " + name + " " + RESERVE_QI + " "
                + RESERVE_MERIDIAN + " wall " + RESERVE_DENSITY) == 1, "灌外壁储备的命令失败");
        context.assertTrue(HumanCultivation.needsAdvance(fixture.body().snapshot()), "储备没有等着回补");
        return fixture;
    }

    private static void betweenTicks(GameTestHelper context, HumanTechniqueTestPlayer fixture, Runnable task) {
        // 排进两 tick 之间的服务端任务：这时宿主这一 tick 的观察已经跑完，身体还停在上一次结算的时刻。
        MinecraftServer server = fixture.player.level().getServer();
        context.runAfterDelay(WAIT_TICKS, () -> server.schedule(new TickTask(server.getTickCount(), task)));
    }

    private static long now(ServerPlayer player) {
        return Math.multiplyExact(player.level().getServer().overworld().getGameTime(), MILLIS_PER_TICK);
    }
}
