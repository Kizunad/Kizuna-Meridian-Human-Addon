package dev.kizuna.meridian.human.fabric;

import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 同一调度点选择人体全身动作，动画模组负责起点、追踪补发与跨端同步。 */
final class HumanPlayerAnimation {
    private final Map<UUID, String> playing = new HashMap<>();

    void synchronize(ServerPlayerEntity player, HumanPlayerBody.Motion motion, boolean breathing) {
        // 运动前已正式结束吐纳；这里仅选择外观，不能替代会话结算。
        String desired = select(motion, breathing);
        String previous = playing.getOrDefault(player.getUuid(), "");
        if (desired.equals(previous)) {
            return;
        }
        stop(player);
        if (!desired.isEmpty()) {
            // API 返回的请求 ID 供动画侧诊断；身体授权不依赖客户端回执。
            ServerPlayerAnimations.play(player, desired);
            playing.put(player.getUuid(), desired);
        }
    }

    static String select(HumanPlayerBody.Motion motion, boolean breathing) {
        // 吐纳由权威会话决定，其余只在已识别的地面运动中播放。
        if (breathing) {
            return "meditate";
        }
        return switch (motion) {
            case IDLE -> "";
            case WALK -> "walk";
            case SPRINT -> "run";
        };
    }

    void stop(ServerPlayerEntity player) {
        // 只停止本宿主管理的一个全身动作，不停止功法附属的其他动画。
        String previous = playing.get(player.getUuid());
        if (previous == null) {
            return;
        }
        ServerPlayerAnimations.stop(player, previous);
        playing.remove(player.getUuid());
    }

    void clear() {
        // 服务器结束后不保留上一个世界的玩家身份。
        playing.clear();
    }
}
