package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 损毁下肢只拒绝运动动作，宿主真正暂停时可以重连恢复同一身体。 */
public final class HumanMovementRecoveryGameTest implements FabricGameTest {
    private static final String LOWER_LEG = "left_lower_leg";
    private static final double LOWER_LEG_HP = 18;
    private static final long MILLIS_PER_TICK = 50;
    private static final long PAUSE_OBSERVATION_TICKS = 3;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void destroyedLegRejectsWalkingAndSprintingWithoutPausingBody(TestContext context) {
        // 生存玩家部位归零后直接走试玩失败的 observe 路径，健康与功法宿主仍须可用。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            fixture.player.changeGameMode(GameMode.SURVIVAL);
            var hit = HealthTarget.newBuilder().setPartId(LOWER_LEG).build();
            damage(context, fixture, hit, LOWER_LEG_HP);
            verifyRejectedMovement(context, fixture);
            context.assertTrue(inspect(fixture).hasBody(), "黑腿错误暂停了健康宿主");
            context.assertTrue(technique(fixture).hasBody(), "黑腿错误暂停了功法宿主");
            context.assertTrue(partHp(fixture) == 0, "移动不能恢复黑腿 HP");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void destroyedLegTissueAlsoRejectsOnlyMovement(TestContext context) {
        // 组织账户可独立损毁；部位仍有血时也不能建立需要该组织的运动占用。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            var tissue = fixture.body().snapshot().structure().tissues().stream()
                    .filter(item -> item.partId().value().equals(LOWER_LEG))
                    .filter(item -> item.id().value().contains("gastrocnemius"))
                    .findFirst().orElseThrow();
            var hit = HealthTarget.newBuilder().setTissueId(tissue.id().value()).build();
            damage(context, fixture, hit, tissue.maxHp());
            verifyRejectedMovement(context, fixture);
            context.assertTrue(partHp(fixture) == LOWER_LEG_HP, "组织伤害意外扣了部位 HP");
            context.assertTrue(inspect(fixture).hasBody(), "组织损毁错误暂停身体");
        }
        context.complete();
    }

    private static void verifyRejectedMovement(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 同一时刻切换动作不会扣费，被拒动作也不能留下占用。
        var body = fixture.body();
        long now = fixture.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
        var before = body.snapshot().tissueStates();
        body.observe(now, HumanPlayerBody.Motion.WALK);
        body.observe(now, HumanPlayerBody.Motion.SPRINT);
        body.observe(now, HumanPlayerBody.Motion.IDLE);
        context.assertTrue(body.snapshot().actions().isEmpty(), "被拒运动仍建立占用");
        context.assertTrue(body.snapshot().tissueStates().equals(before), "被拒运动仍扣组织疲劳");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void missingRequiredChannelPausesOnceAndReconnectPreservesBody(TestContext context) {
        // 缺少必装 UI 接线是真实宿主故障，模拟客户端撤销通道触发正式 tick 的暂停路径。
        var fixture = new HumanTechniqueTestPlayer(context);
        damage(context, fixture, HealthTarget.newBuilder().setPartId(LOWER_LEG).build(), 1);
        var buffer = PacketByteBufs.create();
        buffer.writeBytes("meridian:body_view".getBytes(StandardCharsets.UTF_8));
        try {
            var packet = new CustomPayloadC2SPacket(new Identifier("minecraft:unregister"), buffer);
            fixture.player.networkHandler.onCustomPayload(packet);
        } finally {
            buffer.release();
        }
        context.waitAndRun(PAUSE_OBSERVATION_TICKS, () -> verifyPausedAndReconnect(context, fixture));
    }

    private static void verifyPausedAndReconnect(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 暂停保持已认领身份，提示只有一次；同一 UUID 重登沿用已有损伤和资源。
        UUID id = fixture.player.getUuid();
        double damagedHp = partHp(fixture);
        double qi = fixture.body().snapshot().totalQi();
        try (fixture) {
            context.assertTrue(inspect(fixture).getFailure().getCode() == FailureCode.BODY_PAUSED,
                    "健康宿主没有明确返回暂停");
            context.assertTrue(technique(fixture).getFailure().getCode() == FailureCode.BODY_PAUSED,
                    "功法宿主没有明确返回暂停");
            long hints = fixture.connection.packets.stream()
                    .filter(GameMessageS2CPacket.class::isInstance)
                    .map(GameMessageS2CPacket.class::cast)
                    .filter(packet -> packet.content().getString().contains("退出世界后重新加入"))
                    .count();
            context.assertTrue(hints == 1, "恢复提示未发送或每 tick 重复发送");
        }
        try (var restored = new HumanTechniqueTestPlayer(context, id)) {
            context.assertTrue(inspect(restored).hasBody(), "重新登录未恢复健康宿主");
            context.assertTrue(technique(restored).hasBody(), "重新登录未恢复功法宿主");
            context.assertTrue(partHp(restored) == damagedHp, "恢复重置了既有损伤");
            context.assertTrue(restored.body().snapshot().totalQi() == qi, "恢复重置了真元");
        }
        context.complete();
    }

    private static double partHp(HumanTechniqueTestPlayer fixture) {
        // 只读取权威部位账户，原版红心是投影而非身体恢复证据。
        return fixture.body().snapshot().partStates().stream()
                .filter(part -> part.id().value().equals(LOWER_LEG))
                .findFirst().orElseThrow().hp();
    }

    private static void damage(TestContext context, HumanTechniqueTestPlayer fixture, HealthTarget hit, double hp) {
        // 可信健康协议提交精确损伤，CUSTOM 避免附加局部组织分配干扰最小复现。
        var body = inspect(fixture).getBody();
        var damage = DamageBody.newBuilder()
                .setSourceId("human_test:movement")
                .setDamageType("human_test:movement")
                .setKind(DamageKind.CUSTOM)
                .setHit(hit)
                .setDamageHp(hp)
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(body.getId())
                .setExpectedRevision(body.getRevision())
                .setDamageBody(damage)
                .build();
        String id = UUID.randomUUID().toString();
        var effect = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        var request = request(fixture, id).setEffect(effect).build();
        var response = exchange(request);
        context.assertTrue(response.hasBody(), "最小损伤提交失败：" + response);
    }

    private static HealthHostResponse inspect(HumanTechniqueTestPlayer fixture) {
        // 与治疗附属相同的公开宿主查询，不绕过暂停状态。
        return exchange(request(fixture, UUID.randomUUID().toString()).setInspect(true /* inspect */).build());
    }

    private static HealthHostRequest.Builder request(HumanTechniqueTestPlayer fixture, String id) {
        // 每次请求绑定当前在线实体身份与独立请求 ID。
        return HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setSubjectId(fixture.player.getUuidAsString());
    }

    private static HealthHostResponse exchange(HealthHostRequest request) {
        // 正式 Protobuf 编解码和健康宿主共同验证请求契约。
        return HealthHostProtobuf.decodeResponse(new HumanHealthHost().exchange(
                HealthHostProtobuf.encodeRequest(request)));
    }

    private static TechniqueHostResponse technique(HumanTechniqueTestPlayer fixture) {
        // 独立功法宿主必须与健康宿主对同一玩家返回一致的暂停状态。
        var request = TechniqueHostRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setEntityUuid(fixture.player.getUuidAsString())
                .setInspect(true /* inspect */)
                .build();
        return TechniqueProtocol.decodeHostResponse(new HumanTechniqueHost().exchange(
                TechniqueProtocol.encodeHost(request)));
    }
}
