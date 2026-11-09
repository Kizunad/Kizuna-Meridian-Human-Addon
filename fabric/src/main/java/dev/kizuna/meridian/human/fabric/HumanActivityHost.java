package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.ActivityHostProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.server.BodyActivityHost;

/** 玩家身体的可信活动入口；使用与健康宿主相同的世界绑定。 */
public final class HumanActivityHost implements BodyActivityHost {
    private static BodyActivityHost delegate;

    static void bind(BodyActivityHost host) {
        // 只持有世界接线，不保存身体或能力副本。
        delegate = host;
    }

    @Override
    public byte[] exchange(String callerId, byte[] payload) {
        // 世界尚未绑定时返回明确路由事实，运行中的拒绝由唯一宿主处理。
        if (delegate == null) {
            var request = ActivityHostProtobuf.decodeRequest(payload);
            var response = HealthHostResponse.newBuilder()
                    .setProtocolVersion(MeridianProtocol.VERSION)
                    .setRequestId(request.getRequestId())
                    .setUnbound(true /* unbound */)
                    .build();
            return HealthHostProtobuf.encodeResponse(response);
        }
        return delegate.exchange(callerId, payload);
    }
}
