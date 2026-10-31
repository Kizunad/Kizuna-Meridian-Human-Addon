package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** 调试用途，不进正式玩法：调试附属改写玩家真元库存的入口；权限检查留在调用方命令层。 */
public final class HumanDebugHost {
    private static Delegate delegate;

    private HumanDebugHost() {
        // 只提供静态入口，世界绑定由主入口安装。
    }

    static void bind(Delegate host) {
        // 入口不持有第二套身体；每次调用都进入当前世界的宿主。
        delegate = host;
    }

    public static FailureCode debugQi(ServerPlayer player, DebugQi request) {
        // 模组加载阶段尚无世界时按无身体拒绝，不提前创建玩家身体。
        Objects.requireNonNull(player);
        Objects.requireNonNull(request);
        if (delegate == null) {
            return FailureCode.BODY_NOT_FOUND;
        }
        return delegate.debugQi(player, request);
    }

    interface Delegate {
        FailureCode debugQi(ServerPlayer player, DebugQi request);
    }
}
