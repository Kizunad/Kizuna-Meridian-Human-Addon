package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.human.server.HumanCultivationNumbers;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;

/** /meridian body realm：OP 把游炁玩家推到真炁，候选真元按突破成功相变；往下走、普通玩家都不行，境界能补全。 */
public final class HumanRealmCommandGameTest {
    private static final int TIMEOUT = 100;
    private static final double CANDIDATE_QI = 100;
    private static final double TOLERANCE = 1e-6;

    @GameTest(maxTicks = TIMEOUT)
    public void opRaisesWanderingPlayerToTrueQiWithBreakthroughPhaseChange(GameTestHelper context) {
        // 内腔全是 0.09 候选：命令后境界为真炁、候选变 0.1、总量不变；再下一次同样命令仍算成功，往下走被拒。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            HumanPlayerBody body = fixture.body();
            long now = Math.multiplyExact(context.getLevel().getServer().overworld().getGameTime(), 50);
            for (String meridian : HumanCultivation.MERIDIANS) {
                body.completeOpening(now, meridian);
            }
            body.debugQi(now, DebugQi.newBuilder().setMode(DebugQiMode.SET_INVENTORY).setAmount(CANDIDATE_QI)
                    .setDensity(HumanCultivationNumbers.CANDIDATE_DENSITY).build());
            CommandSourceStack op = fixture.player.createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.OWNER);
            context.assertTrue(body.snapshot().realm() == Realm.WANDERING_QI, "新角色不是游炁");
            context.assertTrue(HumanTestCommands.run(op, "meridian body realm true_qi") == 1, "OP 改境界失败");
            context.assertTrue(body.snapshot().realm() == Realm.TRUE_QI, "命令后不是真炁");
            double gas = lumen(body, 0.1);
            context.assertTrue(Math.abs(gas - CANDIDATE_QI) < TOLERANCE, "候选没有相变为 0.1：" + gas);
            context.assertTrue(lumen(body, HumanCultivationNumbers.CANDIDATE_DENSITY) == 0, "仍有 0.09 候选");
            context.assertTrue(HumanTestCommands.run(op, "meridian body realm true_qi") == 1, "已是真炁应算成功");
            context.assertTrue(HumanTestCommands.run(op, "meridian body realm wandering_qi") == 0, "境界能往下走");
            context.assertTrue(body.snapshot().realm() == Realm.TRUE_QI, "被拒的命令改了境界");
            context.assertTrue(HumanTestCommands.run(op, "meridian body realm essence_transformation") == 0,
                    "人体没有的境界被接受");
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void onlyOpsCanUseItAndRealmsTabComplete(GameTestHelper context) {
        // 节点权限 2；普通玩家执行无效且境界不变；body 下补全 realm，参数补全两层境界和实体选择器。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            CommandSourceStack player = fixture.player.createCommandSourceStack()
                    .withPermission(PermissionSet.NO_PERMISSIONS);
            CommandSourceStack op = fixture.player.createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.OWNER);
            var dispatcher = op.getServer().getCommands().getDispatcher();
            var node = dispatcher.getRoot().getChild("meridian").getChild("body").getChild("realm");
            context.assertTrue(node != null && !node.canUse(player) && node.canUse(op), "realm 节点权限不是 OP");
            context.assertTrue(HumanTestCommands.run(player, "meridian body realm true_qi") == 0, "普通玩家能改境界");
            context.assertTrue(fixture.body().snapshot().realm() == Realm.WANDERING_QI, "普通玩家改动了境界");
            context.assertTrue(suggests(op, "meridian body re", "realm"), "body 下没有补全 realm");
            context.assertTrue(suggests(op, "meridian body realm t", "true_qi"), "没有补全 true_qi");
            context.assertTrue(suggests(op, "meridian body realm w", "wandering_qi"), "没有补全 wandering_qi");
            context.assertTrue(suggests(op, "meridian body realm @", "@s"), "没有实体选择器补全");
        }
        context.succeed();
    }

    private static double lumen(HumanPlayerBody body, double density) {
        // 合计全身经脉内腔里指定密度的真元。
        return body.snapshot().meridianStates().stream().flatMap(state -> state.lumenQi().entries().stream())
                .filter(qi -> Math.abs(qi.density() - density) < TOLERANCE).mapToDouble(Qi::amount).sum();
    }

    private static boolean suggests(CommandSourceStack source, String input, String expected) {
        // 用原版补全入口请求建议，和聊天栏按 Tab 相同。
        var dispatcher = source.getServer().getCommands().getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join().getList().stream()
                .anyMatch(item -> item.getText().equals(expected));
    }
}
