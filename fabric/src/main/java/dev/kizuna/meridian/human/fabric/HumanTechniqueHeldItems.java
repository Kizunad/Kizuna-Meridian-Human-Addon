package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import net.minecraft.server.level.ServerPlayer;

/** 双手事实只从服务端实体和物品数据采集，不接受客户端声明。 */
final class HumanTechniqueHeldItems {
    private HumanTechniqueHeldItems() {
        // 采集器不保存物品副本或施放选择。
    }

    static HeldItems inspect(ServerPlayer player) {
        // 空手也显式提供消息，主手侧独立于当前持物内容。
        return HumanProjectileItems.inspect(player);
    }
}
