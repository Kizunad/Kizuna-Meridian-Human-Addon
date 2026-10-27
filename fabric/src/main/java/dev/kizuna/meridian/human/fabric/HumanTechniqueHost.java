package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 玩家功法宿主；身体与释放仍由 Human 的唯一世界 Store 管理。 */
public final class HumanTechniqueHost implements TechniqueHostEntrypoint {
    private static final double TICKS_PER_SECOND = 20;
    private static final long MILLIS_PER_TICK = 50;
    private static final Logger LOG = LoggerFactory.getLogger(HumanTechniqueHost.class);
    private static Binding binding;
    private static final Map<UUID, Map<String, EffectIntent>> effectReceipts = new HashMap<>();
    private static final Set<UUID> ready = new HashSet<>();

    static void bind(MinecraftServer server, HumanWorldState state, Predicate<UUID> failed,
                     Consumer<UUID> changed) {
        // 每个服务器绑定自己的世界与通知入口，不跨服务器保留实体效果回执。
        binding = new Binding(server, state, failed, changed);
        effectReceipts.clear();
        ready.clear();
    }

    static void unbind() {
        // 停服取消完成后释放世界引用，加载阶段的探测明确返回未认领。
        binding = null;
        effectReceipts.clear();
        ready.clear();
    }

    static void reconnect(ServerPlayerEntity player) {
        // 恢复残留施放后才对外认领；没有可靠效果游标时不补发离线效果。
        Binding current = binding;
        HumanPlayerBody body = current.state().find(player.getUuid());
        TechniqueFabric.service().recoverOnLoad(player.getUuid(), body);
        ready.add(player.getUuid());
    }

    static void beforeDeath(ServerPlayerEntity player) {
        // 外部死亡先尝试正常取消；身体已经致死时改由可信恢复入口清理进度。
        TechniqueFabric.service().cancelOnUnload(player.getUuid());
        Binding current = binding;
        HumanPlayerBody body = current.state().find(player.getUuid());
        if (body != null) {
            TechniqueFabric.service().recoverOnLoad(player.getUuid(), body);
            current.state().markDirty();
            current.changed().accept(player.getUuid());
        }
    }

    static void recoverIfDead(ServerPlayerEntity player) {
        // Framework 死亡已清空动作，附属施放进度需要通过可信入口单独结束。
        Binding current = binding;
        HumanPlayerBody body = current.state().find(player.getUuid());
        if (body != null && body.snapshot().vitality().dead()) {
            TechniqueFabric.service().recoverOnLoad(player.getUuid(), body);
        }
    }

    static void disconnect(UUID playerId) {
        // 功法库取消回调执行完毕后才撤销认领并释放连接内效果回执。
        ready.remove(playerId);
        effectReceipts.remove(playerId);
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 共用功法库的有界协议校验，实体归属只从服务端在线列表读取。
        TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
        var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
        Binding current = binding;
        if (current == null) {
            return encode(response.setUnbound(true));
        }
        if (!current.server().isOnThread()) {
            return failure(response, FailureCode.ACCESS_DENIED);
        }
        UUID playerId = UUID.fromString(request.getEntityUuid());
        ServerPlayerEntity player = current.server().getPlayerManager().getPlayer(playerId);
        if (player == null) {
            return encode(response.setUnbound(true));
        }
        if (current.state() == null || current.failed().test(playerId)) {
            return failure(response, FailureCode.INTERNAL_ERROR);
        }
        if (!ready.contains(playerId)) {
            return encode(response.setUnbound(true));
        }
        HumanPlayerBody body = current.state().find(playerId);
        if (body == null) {
            return encode(response.setUnbound(true));
        }
        return execute(current, player, body, request);
    }

    private byte[] execute(Binding current, ServerPlayerEntity player, HumanPlayerBody body,
                           TechniqueHostRequest request) {
        // 所有分支在服务端主线程串行执行，时间结算的变化也必须保存并投影。
        var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
        long nowMillis = Math.multiplyExact(current.server().getOverworld().getTime(), MILLIS_PER_TICK);
        try {
            if (request.hasActivity()) {
                return activity(body, nowMillis, request);
            }
            if (request.hasInspect()) {
                body.settle(nowMillis);
                recoverIfDead(player);
                response.setBody(BodyProtobuf.toMessage(body.snapshot()));
            } else if (request.hasEffect()) {
                applyEffect(player, request);
                response.setEffectAccepted(true);
            } else {
                throw HumanErrors.invalid(HumanErrors.INVALID_TECHNIQUE_OPERATION);
            }
            return encode(response);
        } catch (HumanErrors.HostRejected rejected) {
            return failure(response, rejected.code());
        } catch (RuntimeException failure) {
            LOG.error(HumanErrors.TECHNIQUE_HOST_FAILED, failure);
            return failure(response, FailureCode.INTERNAL_ERROR);
        } finally {
            current.state().markDirty();
            current.changed().accept(player.getUuid());
        }
    }

    private byte[] activity(HumanPlayerBody body, long nowMillis, TechniqueHostRequest request) {
        // 只将 Framework 的实际提交版本和原活动一起返回，业务失败保持原始失败码。
        var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
        var result = body.applyTechnique(nowMillis, request);
        if (result.hasFailure()) {
            return failure(response, result.getFailure().getCode());
        }
        TechniqueActivityCommit commit = TechniqueActivityCommit.newBuilder()
                .setRevision(result.getCommandCommitted().getRevision())
                .setActivity(request.getActivity())
                .build();
        return encode(response.setActivity(commit));
    }

    private void applyEffect(ServerPlayerEntity player, TechniqueHostRequest request) {
        // 同一连接内重复效果返回原接受结果，内容冲突不能重新施加世界副作用。
        Map<String, EffectIntent> receipts = effectReceipts.computeIfAbsent(player.getUuid(), id -> new HashMap<>());
        EffectIntent effect = request.getEffect();
        EffectIntent previous = receipts.get(request.getRequestId());
        if (previous != null) {
            if (!previous.equals(effect)) {
                throw HumanErrors.hostRejected(FailureCode.REQUEST_ID_CONFLICT);
            }
            return;
        }
        if (effect.hasImpulse()) {
            Vec3d direction = impulseDirection(player, effect);
            double speedPerTick = effect.getImpulse().getSpeed().getValue() / TICKS_PER_SECOND;
            player.setVelocity(direction.multiply(speedPerTick));
            // Yarn 实体跟踪器用此标记向旁观者和玩家本人发送 EntityVelocityUpdateS2CPacket。
            player.velocityModified = true;
        }
        // 未识别的表现 ID 只确认接受，不创建动画或额外客户端通道。
        receipts.put(request.getRequestId(), effect);
    }

    private Vec3d impulseDirection(ServerPlayerEntity player, EffectIntent effect) {
        // 朝前与输入方向均在水平面，朝目标使用同一世界实体的实际三维位置。
        return switch (effect.getImpulse().getDirection()) {
            case UP -> new Vec3d(0, 1, 0);
            case FORWARD -> Vec3d.fromPolar(0, player.getYaw());
            case INPUT -> inputDirection(player, effect.getDirection());
            case TOWARD_TARGET -> targetDirection(player, effect.getTargetId());
            default -> throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        };
    }

    private Vec3d inputDirection(ServerPlayerEntity player, HorizontalDirection input) {
        // 先按最大分量缩放，有限但极大的输入也不会在平方时溢出。
        double scale = Math.max(Math.abs(input.getX()), Math.abs(input.getZ()));
        if (scale == 0) {
            return Vec3d.fromPolar(0, player.getYaw());
        }
        return new Vec3d(input.getX() / scale, 0, input.getZ() / scale).normalize();
    }

    private Vec3d targetDirection(ServerPlayerEntity player, String targetId) {
        // 目标必须是同世界仍存在的实体，无法定位时拒绝且不写效果回执以便重试。
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetId);
        } catch (IllegalArgumentException invalid) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        Entity target = player.getServerWorld().getEntity(targetUuid);
        if (target == null || target.isRemoved()) {
            throw HumanErrors.hostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        Vec3d direction = target.getPos().subtract(player.getPos());
        if (direction.lengthSquared() == 0) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        return direction.normalize();
    }

    private static byte[] failure(TechniqueHostResponse.Builder response, FailureCode code) {
        // 保留 Framework 原始失败码，不把版本冲突或业务拒绝改写成宿主故障。
        RequestFailure failure = RequestFailure.newBuilder().setCode(code).build();
        return encode(response.setFailure(failure));
    }

    private static byte[] encode(TechniqueHostResponse.Builder response) {
        // 所有响应共用库的结果类型与结构校验。
        return TechniqueProtocol.encodeHostResponse(response.build());
    }

    private record Binding(MinecraftServer server, HumanWorldState state, Predicate<UUID> failed,
                           Consumer<UUID> changed) { }
}
