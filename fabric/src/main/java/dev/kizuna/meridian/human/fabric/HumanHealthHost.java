package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.server.BodyHealthHost;

/** Fabric 的 meridian_health_host 服务；实例由加载器发现，世界绑定由主入口安装。 */
public final class HumanHealthHost implements BodyHealthHost {
    private static BodyHealthHost delegate;

    static void bind(BodyHealthHost host) {
        // 服务对象不持有第二套身体；每次调用都进入当前世界的宿主。
        delegate = host;
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 模组加载阶段尚无世界时明确未绑定，不提前创建玩家或默认人体。
        if (delegate == null) {
            var request = HealthHostProtobuf.decodeRequest(payload);
            return HealthHostProtobuf.encodeResponse(HealthHostResponse.newBuilder().setProtocolVersion(1)
                    .setRequestId(request.getRequestId()).setUnbound(true).build());
        }
        return delegate.exchange(payload);
    }
}
