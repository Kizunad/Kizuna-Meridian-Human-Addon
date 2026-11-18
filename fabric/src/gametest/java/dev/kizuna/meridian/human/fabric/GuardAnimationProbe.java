package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.playeranimation.network.AnimationPackets;
import dev.kizuna.playeranimation.protocol.pb.PlayerAnimationProtocol.PlayRequest;
import dev.kizuna.playeranimation.protocol.pb.PlayerAnimationProtocol.StopRequest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.HashSet;
import java.util.Set;

/** 解析实际 PA 输出，避免只测映射字符串而漏掉宿主接线。 */
final class GuardAnimationProbe {
    static final String HOLD = "guard_arms";
    static final String HIT = "guard_arms_hit";
    static final String LOWER = "guard_arms_lower";
    private static final double ORGAN_SHARE = 0.6;

    private GuardAnimationProbe() {
        // 测试助手不保留玩家或施放状态。
    }

    static List<String> events(HumanTechniqueTestPlayer fixture) {
        // 保留网络顺序，最后毁臂时能区分 hit→stop→lower 和错误的反向顺序。
        var events = new ArrayList<String>();
        var requests = new HashSet<String>();
        String playerId = fixture.player.getStringUUID();
        for (var packet : fixture.connection.packets()) {
            if (packet instanceof ClientboundRespawnPacket) {
                events.add("respawn");
            }
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof AnimationPackets.Payload payload) {
                read(events, requests, playerId, payload);
            }
        }
        return events;
    }

    private static void read(List<String> events, Set<String> requests, String playerId,
                             AnimationPackets.Payload payload) {
        // 连接也接收附近玩家的动画和未确认补发；只计本人的新请求身份。
        try {
            if (payload.type().equals(AnimationPackets.PLAY)) {
                var play = AnimationPackets.read(payload, PlayRequest.parser());
                if (play.getPlayerUuid().equals(playerId) && requests.add(play.getRequestId())) {
                    events.add("play:" + play.getAnimationId());
                }
            } else if (payload.type().equals(AnimationPackets.STOP)) {
                var stop = AnimationPackets.read(payload, StopRequest.parser());
                if (stop.getPlayerUuid().equals(playerId) && requests.add(stop.getRequestId())) {
                    events.add("stop:" + stop.getAnimationId());
                }
            }
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("动画消息非法", invalid);
        }
    }

    static void assertPlays(GameTestHelper context, HumanTechniqueTestPlayer fixture, String clip, int count) {
        // 次数精确匹配，重复播放也算失败。
        var events = events(fixture);
        context.assertTrue(events.stream().filter(event -> event.equals("play:" + clip)).count() == count,
                "动画次数不符：" + clip + " / " + events);
    }

    static void assertStopped(GameTestHelper context, HumanTechniqueTestPlayer fixture, String clip) {
        // 停止的身份必须是实际播出的剪辑。
        context.assertTrue(events(fixture).contains("stop:" + clip), "未停止动画：" + clip);
    }

    static void assertStoppedBeforeTransfer(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 客户端必须先停旧世界的保持，再处理换世界包；卸载后的补停已经晚了一步。
        var events = events(fixture);
        int stop = events.indexOf("stop:" + HOLD);
        int respawn = events.indexOf("respawn");
        context.assertTrue(stop >= 0 && respawn > stop, "换世界包之前未清旧动作：" + events);
    }

    static void assertLastArmOrder(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 保持和受击都在落手前停；落手之后不允许任何抱架或反馈重播。
        var events = events(fixture);
        int hit = events.indexOf("play:" + HIT);
        int stop = events.indexOf("stop:" + HIT);
        int holdStop = events.indexOf("stop:" + HOLD);
        int lower = events.indexOf("play:" + LOWER);
        context.assertTrue(hit >= 0 && stop > hit && holdStop >= 0 && lower > stop && lower > holdStop,
                "最后毁臂动作顺序错误：" + events);
        assertPlays(context, fixture, HIT, 1);
        assertPlays(context, fixture, LOWER, 1);
    }

    static void hit(ServerPlayer target, DamageSource source, String part, double hp) {
        // 可信直接肢击仍经过正式格挡判定及唯一健康结算。
        var result = VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(target, source, part, hp));
        if (result.isEmpty()) {
            throw new IllegalStateException("测试肢击未结算");
        }
    }

    static void penetrate(ServerPlayer target, DamageSource source, double hp) {
        // 显式透劲代表整击绕过格挡，不应产生受击抱架反馈。
        var extras = new VanillaDamageFabric.DirectExtras(OptionalDouble.of(ORGAN_SHARE), 1, List.of());
        var damage = new VanillaDamageFabric.DirectDamage(target, source, "head", hp, extras);
        if (VanillaDamageFabric.damageDirect(damage).isEmpty()) {
            throw new IllegalStateException("测试透劲未结算");
        }
    }
}
