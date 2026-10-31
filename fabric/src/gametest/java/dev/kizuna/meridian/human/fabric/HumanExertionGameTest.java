package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissues;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import java.util.UUID;

/** 玩家健康宿主使用真实在线绑定转发普通做功命令。 */
public final class HumanExertionGameTest implements FabricGameTest {
    private static final double EXCESSIVE_POWER = 1_000_000;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void exertionChargesOnceRejectsCapacityAndChecksBoundIdentity(GameTestHelper context) {
        // 原子做功、成功重试和能力不足都通过正式宿主，不替换玩家或核心账本。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            String subject = fixture.player.getStringUUID();
            var inspect = HealthHostRequest.newBuilder()
                    .setProtocolVersion(1)
                    .setRequestId(UUID.randomUUID().toString())
                    .setSubjectId(subject)
                    .setInspect(true /* inspect */)
                    .build();
            BodySnapshot initial = exchange(inspect).getBody();
            var request = request(subject, initial, 1);
            var first = exchange(request);
            context.assertTrue(first.hasBody(), "玩家宿主未接受普通做功：" + first);
            BodySnapshot exerted = first.getBody();
            context.assertTrue(exerted.getRevision() == initial.getRevision() + 1, "做功未提交一次");
            context.assertTrue(!first.hasHealth(), "做功不能伪造健康效果回执");
            context.assertTrue(!exerted.getTissueStatesList().equals(initial.getTissueStatesList()),
                    "做功未增加组织疲劳");
            context.assertTrue(exchange(request).getBody().equals(exerted), "重放做功重复扣疲劳");
            var rejected = exchange(request(subject, exerted, EXCESSIVE_POWER));
            context.assertTrue(rejected.getFailure().getCode() == FailureCode.TISSUE_LOAD_EXCEEDED,
                    "能力不足没有返回原始失败码");
            context.assertTrue(exchange(inspect).getBody().equals(exerted), "被拒做功修改了身体");
            var otherBody = exerted.toBuilder().setId(UUID.randomUUID().toString()).build();
            var denied = exchange(request(subject, otherBody, 1));
            context.assertTrue(denied.getFailure().getCode() == FailureCode.ACCESS_DENIED,
                    "玩家宿主允许做功请求操作其他身体");
        }
        context.succeed();
    }

    private static HealthHostRequest request(String subject, BodySnapshot body, double power) {
        // 身体版本和组织均来自刚查询的权威快照，调用方不能借宿主刷新版本。
        String tissue = body.getStructure().getTissuesList().stream()
                .filter(item -> item.getTagsList().contains("human:arm_muscle"))
                .findFirst().orElseThrow().getId();
        var load = ExertTissue.newBuilder()
                .setTissueId(tissue)
                .setForceNewtons(1)
                .setPowerWatts(power)
                .setDurationSeconds(1)
                .build();
        var loads = ExertTissues.newBuilder().addLoads(load).build();
        var command = BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setExertTissues(loads)
                .build();
        String id = UUID.randomUUID().toString();
        var envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        return HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setSubjectId(subject)
                .setExertion(envelope)
                .build();
    }

    private static HealthHostResponse exchange(HealthHostRequest request) {
        // 实际入口负责在线身份、线程和世界状态检查。
        return HealthHostProtobuf.decodeResponse(new HumanHealthHost().exchange(
                HealthHostProtobuf.encodeRequest(request)));
    }
}
