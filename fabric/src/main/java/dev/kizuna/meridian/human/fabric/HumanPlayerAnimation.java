package dev.kizuna.meridian.human.fabric;

import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** 同一调度点选择人体全身动作，动画模组负责起点、追踪补发与跨端同步。 */
final class HumanPlayerAnimation {
    private final Map<UUID, String> playing = new HashMap<>();

    void synchronize(ServerPlayer player, HumanPlayerBody.Motion motion, boolean breathing, boolean automatic) {
        // 手动吐纳保持盘坐；自动吐纳允许走跑，动画优先反映玩家当前移动。
        String desired = select(motion, breathing, automatic);
        String previous = playing.getOrDefault(player.getUUID(), "");
        if (desired.equals(previous)) {
            return;
        }
        stop(player);
        if (!desired.isEmpty()) {
            // API 返回的请求 ID 供动画侧诊断；身体授权不依赖客户端回执。
            ServerPlayerAnimations.play(player, desired);
            playing.put(player.getUUID(), desired);
        }
    }

    static String select(HumanPlayerBody.Motion motion, boolean breathing, boolean automatic) {
        // 静止吐纳或手动吐纳显示盘坐；自动吐纳移动时显示实际步态。
        if (breathing && (!automatic || motion == HumanPlayerBody.Motion.IDLE)) {
            return "meditate";
        }
        return switch (motion) {
            case IDLE -> "";
            case WALK -> "walk";
            case SPRINT -> "run";
        };
    }

    void stop(ServerPlayer player) {
        // 只停止本宿主管理的一个全身动作，不停止功法附属的其他动画。
        String previous = playing.get(player.getUUID());
        if (previous == null) {
            return;
        }
        ServerPlayerAnimations.stop(player, previous);
        playing.remove(player.getUUID());
    }

    void clear() {
        // 服务器结束后不保留上一个世界的玩家身份。
        playing.clear();
    }
}
