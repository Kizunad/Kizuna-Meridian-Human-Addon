package dev.kizuna.meridian.human.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 只说一次的提示：第一次进世界的引导、第一次重生后的说明。 */
public final class HumanHintsGameTest {
    private static final int SETTLE_TICKS = 2;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void welcomeIsSaidOnFirstLoginOnly(GameTestHelper context) {
        // 同一名玩家退出再进：标签随玩家档保存，第二次登录不再说。
        UUID playerId = UUID.randomUUID();
        try (var first = new HumanTechniqueTestPlayer(context, playerId)) {
            context.assertTrue(chats(first, HumanHints.WELCOME_NOTES).size() == 1, "第一次登录没有说去哪找札记");
            context.assertTrue(chats(first, HumanHints.WELCOME_TECHNIQUES).size() == 1, "第一次登录没有说功法从哪来");
            Object key = chats(first, HumanHints.WELCOME_NOTES).get(0).getArgs()[0];
            context.assertTrue(key instanceof net.minecraft.network.chat.Component component
                    && component.getContents() instanceof KeybindContents, "修炼页的键名没有按玩家的绑定显示：" + key);
            context.assertTrue(first.player.entityTags().contains(HumanHints.WELCOME_TAG), "说过以后没有记在玩家身上");
        }
        try (var second = new HumanTechniqueTestPlayer(context, playerId)) {
            context.assertTrue(second.player.entityTags().contains(HumanHints.WELCOME_TAG), "重新登录后标签丢了");
            context.assertTrue(chats(second, HumanHints.WELCOME_NOTES).isEmpty()
                    && chats(second, HumanHints.WELCOME_TECHNIQUES).isEmpty(), "第二次登录又说了一遍引导");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void revivalIsExplainedAfterTheFirstDeathOnly(GameTestHelper context) {
        // 死两次：聊天里的说明只在第一次重生后出现，标签跟到重生后的新实体上。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            fixture.player.setGameMode(GameType.SURVIVAL);
            ServerPlayer revived = HumanRevivalGameTest.killAndRespawn(context, fixture);
            context.assertTrue(chats(fixture, HumanHints.REVIVAL_EXPLAINED).size() == 1, "第一次重生后没有说明怎么恢复");
            context.assertTrue(revived.entityTags().contains(HumanHints.REVIVAL_TAG), "标签没有跟到重生后的实体上");
            context.runAfterDelay(SETTLE_TICKS, () -> {
                // 重生当 tick 新实体的血量还没投影上来，等两 tick 再死第二次。
                HumanRevivalGameTest.killAndRespawn(context, fixture, revived);
                context.assertTrue(chats(fixture, HumanHints.REVIVAL_EXPLAINED).size() == 1, "第二次重生又说明了一遍");
                fixture.close();
                context.succeed();
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static List<TranslatableContents> chats(HumanTechniqueTestPlayer fixture, String key) {
        // 只数聊天栏里的系统消息，动作栏那句不算。
        return fixture.connection.packets().stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && !chat.overlay())
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getContents())
                .filter(contents -> contents instanceof TranslatableContents translated && translated.getKey().equals(key))
                .map(contents -> (TranslatableContents) contents)
                .toList();
    }
}
