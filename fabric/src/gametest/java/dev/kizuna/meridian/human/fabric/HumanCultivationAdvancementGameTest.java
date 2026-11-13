package dev.kizuna.meridian.human.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/**
 * 修炼线进度由 {@code meridian_human_host:cultivation_state} 推进：开第一条脉、八脉全开、突破真炁各完成一条，
 * 每一步之前后面那条都还没完成。
 */
public final class HumanCultivationAdvancementGameTest {
    private static final int STEP_TICKS = 30;
    private static final int SLACK_TICKS = 20;
    private static final int TIMEOUT = 4 * STEP_TICKS + SLACK_TICKS;
    private static final Identifier FIRST = Identifier.parse("meridian_progression:cultivation/first_meridian");
    private static final Identifier EIGHT = Identifier.parse("meridian_progression:cultivation/eight_meridians");
    private static final Identifier TRUE_QI = Identifier.parse("meridian_progression:cultivation/true_qi");

    @GameTest(maxTicks = TIMEOUT)
    public void openingAndBreakthroughCompleteCultivationLine(GameTestHelper context) {
        // 每步之间等一秒多，让每秒一次的现状判断跑过；身体只用正式命令改。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            String name = fixture.player.getScoreboardName();
            requireOpen(context, fixture, FIRST, "没开脉就完成了一脉初通");
            command(context, fixture, "meridian progression unlock");
            context.runAfterDelay(STEP_TICKS, () -> {
                requireOpen(context, fixture, FIRST, "进世界一秒后就完成了一脉初通");
                command(context, fixture, "meridian opening complete " + name + " left_lung_meridian");
            });
            context.runAfterDelay(2 * STEP_TICKS, () -> {
                requireDone(context, fixture, FIRST, "开了一条脉没完成一脉初通");
                requireOpen(context, fixture, EIGHT, "只开一条脉就完成了八脉皆通");
                command(context, fixture, "meridian opening complete " + name + " all");
            });
            context.runAfterDelay(3 * STEP_TICKS, () -> {
                requireDone(context, fixture, EIGHT, "全开以后没完成八脉皆通");
                requireOpen(context, fixture, TRUE_QI, "还是游炁就完成了炼炁成真");
                command(context, fixture, "meridian body realm " + name + " true_qi");
            });
            context.runAfterDelay(4 * STEP_TICKS, () -> {
                try (fixture) {
                    requireDone(context, fixture, TRUE_QI, "到了真炁没完成炼炁成真");
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void requireDone(GameTestHelper context, HumanTechniqueTestPlayer fixture, Identifier id,
                                    String message) {
        // 进度必须已完成。
        context.assertTrue(done(fixture, id), message);
    }

    private static void requireOpen(GameTestHelper context, HumanTechniqueTestPlayer fixture, Identifier id,
                                    String message) {
        // 进度必须还没完成。
        context.assertFalse(done(fixture, id), message);
    }

    private static boolean done(HumanTechniqueTestPlayer fixture, Identifier id) {
        // Progression 的进度文件必须已加载，缺了直接报错而不是当作未完成。
        var advancement = fixture.player.level().getServer().getAdvancements().get(id);
        if (advancement == null) {
            throw new AssertionError("进度没有加载：" + id);
        }
        return fixture.player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static void command(GameTestHelper context, HumanTechniqueTestPlayer fixture, String command) {
        // 走正式命令树，失败就是夹具没布置好。
        var source = fixture.player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER);
        context.assertTrue(HumanTestCommands.run(source, command) > 0, "命令失败：" + command);
    }
}
