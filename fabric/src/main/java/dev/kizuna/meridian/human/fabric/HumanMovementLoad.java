package dev.kizuna.meridian.human.fabric;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** 服务端行走负荷提供者：按玩家当前负担放大行走与疾跑的目标做功；倍率不写入身体。 */
public interface HumanMovementLoad {
    /** 当前负荷倍率，必须是不小于 1 的有限数；只在逻辑服务器主线程调用，不得修改玩家或身体。 */
    double multiplier(ServerPlayer player);

    /** `/meridian body status` 追加的一行说明；没有可说明的负荷时返回空。 */
    Optional<String> status(ServerPlayer player);
}
