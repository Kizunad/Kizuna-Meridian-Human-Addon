package dev.kizuna.meridian.human.fabric;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** 行走负荷提供者的唯一注册点；没有提供者时行走与疾跑按原目标功率做功。 */
public final class HumanMovementLoads {
    /** 无负荷倍率：目标功率保持 Human 定义的走 300 秒、跑 75 秒耗尽耐力。 */
    static final double UNLOADED = 1;
    private static volatile HumanMovementLoad provider;

    private HumanMovementLoads() {
        // 只保存一个服务端提供者，不持有玩家或身体状态。
    }

    /** 在模组初始化阶段注册一次；重复注册说明两个附属争用同一规则，直接拒绝。 */
    public static synchronized void register(HumanMovementLoad load) {
        Objects.requireNonNull(load, "行走负荷提供者不能为空");
        if (provider != null) {
            throw new IllegalStateException("行走负荷提供者只能注册一次");
        }
        provider = load;
    }

    static double multiplier(ServerPlayer player) {
        // 提供者返回的倍率直接乘到目标功率上，小于 1 或非有限数会让做功失真，按接线错误拒绝。
        HumanMovementLoad load = provider;
        if (load == null) {
            return UNLOADED;
        }
        double multiplier = load.multiplier(player);
        if (!Double.isFinite(multiplier) || multiplier < UNLOADED) {
            throw new IllegalStateException("行走负荷倍率必须是不小于 1 的有限数：" + multiplier);
        }
        return multiplier;
    }

    static Optional<String> status(ServerPlayer player) {
        // 没有提供者时状态命令保持原样，不显示默认倍率。
        HumanMovementLoad load = provider;
        if (load == null) {
            return Optional.empty();
        }
        return load.status(player);
    }
}
