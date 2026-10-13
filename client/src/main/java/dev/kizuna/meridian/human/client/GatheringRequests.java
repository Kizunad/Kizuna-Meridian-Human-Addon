package dev.kizuna.meridian.human.client;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganRecipe;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

/** 简易聚气操作的 Protobuf 请求构造器；每一步都使用宿主返回的最新身体版本。 */
public final class GatheringRequests {
    private GatheringRequests() {
        // 操作端只描述意图，不持有可写真元账本或自行推进时钟。
    }

    public static byte[] intake(BodySnapshot body, String requestId, String organId, QiInventory incoming) {
        // 剂量是申请值，实际来源、可用时间与共享预算仍由权威处理器核验。
        var command = command(body).setIntakeQi(IntakeQi.newBuilder().setOrganId(organId).setQi(incoming));
        return envelope(requestId, command);
    }

    public static byte[] refine(BodySnapshot body, String requestId, String organId, OrganRecipe recipe) {
        // 只提交已选择的配方身份；客户端改写输入、产出或时长不会随请求传给核心。
        var command = command(body).setStartOrganRecipe(StartOrganRecipe.newBuilder().setOrganId(organId)
                .setRecipeId(recipe.getId()).setRecipeVersion(recipe.getVersion()));
        return envelope(requestId, command);
    }

    public static byte[] store(BodySnapshot body, String requestId, String connectionId,
                               QiInventory cargo, double throughputPerSecond) {
        // 运输使用模板中的实际连接；完成前货物归在途，不能提前增加经脉余额。
        var command = command(body).setTransportQi(TransportQi.newBuilder().setConnectionId(connectionId)
                .setCargo(cargo).setThroughputPerSecond(throughputPerSecond));
        return envelope(requestId, command);
    }

    public static byte[] settle(BodySnapshot body, String requestId) {
        // 等待后仅请求结算，模拟时间与老化倍率不由操作端上传。
        return envelope(requestId, command(body).setAdvanceTime(AdvanceTime.getDefaultInstance()));
    }

    private static BodyCommand.Builder command(BodySnapshot body) {
        // 用观测版本请求变更；遇到冲突应重新查询，不覆盖服务端的新状态。
        return BodyCommand.newBuilder().setBodyId(body.getId()).setExpectedRevision(body.getRevision());
    }

    private static byte[] envelope(String requestId, BodyCommand.Builder command) {
        // 交换框架 protoc 生成的原始消息；重试必须重发同一 ID 与同一命令。
        return RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setBodyCommand(command).build().toByteArray();
    }
}
