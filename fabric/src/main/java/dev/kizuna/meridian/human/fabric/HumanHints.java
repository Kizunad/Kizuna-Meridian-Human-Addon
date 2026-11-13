package dev.kizuna.meridian.human.fabric;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 每名玩家只说一次的提示。说过就在玩家身上记一个原版实体标签：标签存进玩家档，重生、换维度都带着，
 * 用 {@code /tag <玩家> remove <标签>} 可以让它再说一遍。
 */
final class HumanHints {
    static final String WELCOME_TAG = "meridian.hint.human.welcome";
    static final String REVIVAL_TAG = "meridian.hint.human.revival";
    static final String WELCOME_NOTES = "message.meridian_human_host.welcome.notes";
    static final String WELCOME_TECHNIQUES = "message.meridian_human_host.welcome.techniques";
    static final String REVIVAL_EXPLAINED = "message.meridian_human_host.revival.explained";
    /** 打开修炼页的按键登记在库存界面框架里，这里只引用键名，显示玩家实际绑定的键。 */
    private static final String WORKSPACE_KEY = "key.kizuna_inventory_ui.workspace";

    private HumanHints() {
        // 只有静态入口。
    }

    static void welcome(ServerPlayer player) {
        // 第一次进世界：修炼从哪一卷开始、去哪找，功法卷轴从谁身上来。
        if (!player.addTag(WELCOME_TAG)) {
            return;
        }
        player.sendSystemMessage(Component.translatable(WELCOME_NOTES, Component.keybind(WORKSPACE_KEY)));
        player.sendSystemMessage(Component.translatable(WELCOME_TECHNIQUES));
    }

    static void revived(ServerPlayer player) {
        // 第一次死后重生：伤、真元、筋肉各自怎么恢复；动作栏那句折损寿元每次照发。
        if (!player.addTag(REVIVAL_TAG)) {
            return;
        }
        player.sendSystemMessage(Component.translatable(REVIVAL_EXPLAINED));
    }
}
