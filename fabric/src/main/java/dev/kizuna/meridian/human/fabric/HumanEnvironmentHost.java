package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostRequest;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostResponse;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import java.util.function.Function;

/** 可信附属通过反射调用 exchange，仅交换 Protobuf 字节。 */
public final class HumanEnvironmentHost {
    private static volatile Function<byte[], byte[]> delegate;

    static void bind(Function<byte[], byte[]> host) {
        // 绑定当前宿主处理器，不向调用方暴露人体 Java 接口。
        delegate = host;
    }

    public byte[] exchange(byte[] payload) {
        // 加载阶段先解码并校验请求，再明确返回尚未绑定世界。
        Function<byte[], byte[]> host = delegate;
        if (host == null) {
            var response = EnvironmentHostResponse.newBuilder().setProtocolVersion(1);
            if (payload == null || payload.length == 0 || payload.length > MeridianProtocol.MAX_PAYLOAD_BYTES) {
                return response.setFailure(RequestFailure.newBuilder().setCode(FailureCode.INVALID_REQUEST))
                        .build().toByteArray();
            }
            try {
                var request = EnvironmentHostRequest.parseFrom(payload);
                response.setRequestId(request.getRequestId());
                FailureCode failure = null;
                if (request.getProtocolVersion() != 1) {
                    failure = FailureCode.UNSUPPORTED_VERSION;
                } else if (request.getRequestId().isBlank()
                        || request.getOperationCase() == EnvironmentHostRequest.OperationCase.OPERATION_NOT_SET
                        || (request.hasClock() && !request.getClock())) {
                    failure = FailureCode.INVALID_REQUEST;
                }
                if (failure == null) {
                    response.setUnbound(true);
                } else {
                    response.setFailure(RequestFailure.newBuilder().setCode(failure));
                }
                return response.build().toByteArray();
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                return response.setFailure(RequestFailure.newBuilder().setCode(FailureCode.INVALID_REQUEST))
                        .build().toByteArray();
            }
        }
        return host.apply(payload);
    }
}
