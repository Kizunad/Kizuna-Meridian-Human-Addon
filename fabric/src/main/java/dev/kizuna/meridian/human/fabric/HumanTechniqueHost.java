package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 玩家功法宿主；身体与释放仍由 Human 的唯一世界 Store 管理。 */
public final class HumanTechniqueHost implements TechniqueHostEntrypoint {
    private static final long MILLIS_PER_TICK = 50;
    private static final Logger LOG = LoggerFactory.getLogger(HumanTechniqueHost.class);
    private static Binding binding;
    private static final HumanTechniqueEffects effects = new HumanTechniqueEffects();
    private static final Set<UUID> ready = new HashSet<>();

    public static boolean isCasting(UUID playerId) {
        // 原版攻击入口只查询实体效果锁，不创建或修改身体。
        return effects.isCasting(playerId);
    }

    public static boolean blocksUse(UUID playerId) {
        // 原版右键入口同样只查询实体效果锁；目录声明放行右键的式不拦。
        return effects.blocksUse(playerId);
    }

    static void bind(MinecraftServer server, HumanWorldState state, Predicate<UUID> failed,
                     Consumer<UUID> changed) {
        // 每个服务器绑定自己的世界与通知入口，不跨服务器保留实体效果回执。
        binding = new Binding(server, state, failed, changed);
        effects.clear();
        ready.clear();
    }

    static void unbind() {
        // 停服取消完成后释放世界引用，加载阶段的探测明确返回未认领。
        binding = null;
        effects.clear();
        ready.clear();
    }

    static HumanWorldState state() {
        // 世界状态不再挂在原版存档数据里，同包读取只能经过当前绑定。
        return binding.state();
    }

    static void reconnect(ServerPlayer player) {
        // 恢复残留施放后才对外认领；没有可靠效果游标时不补发离线效果。
        Binding current = binding;
        HumanPlayerBody body = current.state().find(player.getUUID());
        body.settle(nowMillis(current));
        TechniqueFabric.service().recoverOnLoad(player.getUUID(), recovery(player, body));
        ready.add(player.getUUID());
    }

    static void beforeDeath(ServerPlayer player) {
        // 外部死亡先尝试正常取消；身体已经致死时改由可信恢复入口清理进度。
        TechniqueFabric.service().cancelOnUnload(player.getUUID());
        Binding current = binding;
        HumanPlayerBody body = current.state().find(player.getUUID());
        if (body != null) {
            body.settle(nowMillis(current));
            TechniqueFabric.service().recoverOnLoad(player.getUUID(), recovery(player, body));
            current.state().setDirty();
            current.changed().accept(player.getUUID());
        }
    }

    static void recoverIfDead(ServerPlayer player) {
        // Framework 死亡已清空动作，附属施放进度需要通过可信入口单独结束。
        Binding current = binding;
        HumanPlayerBody body = current.state().find(player.getUUID());
        if (body != null && body.snapshot().vitality().dead()) {
            body.settle(nowMillis(current));
            TechniqueFabric.service().recoverOnLoad(player.getUUID(), recovery(player, body));
        }
    }

    static HumanTechniqueRecovery recovery(ServerPlayer player, HumanPlayerBody body) {
        // 恢复持有实体上下文，认领前也可执行 STOP/UNLOCK，不经字节入口重路由。
        return new HumanTechniqueRecovery(player, body, effects);
    }

    private static long nowMillis(Binding current) {
        // 所有功法路径共用 Human 主世界累计 tick 时钟。
        return Math.multiplyExact(current.server().overworld().getGameTime(), MILLIS_PER_TICK);
    }

    static void disconnect(UUID playerId) {
        // 功法库取消回调执行完毕后才撤销认领并释放连接内效果回执。
        ready.remove(playerId);
        effects.forget(playerId);
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 共用功法库的有界协议校验，实体归属只从服务端在线列表读取。
        TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
        var response = TechniqueHostResponse.newBuilder()
                .setRequestId(request.getRequestId());
        Binding current = binding;
        if (current == null) {
            return encode(response.setUnbound(true /* unbound */));
        }
        if (!current.server().isSameThread()) {
            return failure(response, FailureCode.ACCESS_DENIED);
        }
        UUID playerId = UUID.fromString(request.getEntityUuid());
        ServerPlayer player = current.server().getPlayerList().getPlayer(playerId);
        if (player == null) {
            return encode(response.setUnbound(true /* unbound */));
        }
        if (current.failed().test(playerId)) {
            return failure(response, FailureCode.BODY_PAUSED);
        }
        if (current.state() == null) {
            return failure(response, FailureCode.INTERNAL_ERROR);
        }
        if (!ready.contains(playerId)) {
            return encode(response.setUnbound(true /* unbound */));
        }
        HumanPlayerBody body = current.state().find(playerId);
        if (body == null) {
            return encode(response.setUnbound(true /* unbound */));
        }
        return execute(current, player, body, request);
    }

    private byte[] execute(Binding current, ServerPlayer player, HumanPlayerBody body,
                           TechniqueHostRequest request) {
        // 所有分支在服务端主线程串行执行，时间结算的变化也必须保存并投影。
        var response = TechniqueHostResponse.newBuilder()
                .setRequestId(request.getRequestId());
        long nowMillis = nowMillis(current);
        try {
            if (request.hasActivity()) {
                return activity(body, nowMillis, request);
            }
            if (request.hasInspect()) {
                body.settle(nowMillis);
                recoverIfDead(player);
                response.setBody(BodyProtobuf.toMessage(body.snapshot()));
                response.setHeldItems(HumanTechniqueHeldItems.inspect(player));
            } else if (request.hasEffect()) {
                effects.submit(player, new TechniqueHost.EffectSubmission(request.getRequestId(), request.getEffect()));
                response.setEffectAccepted(true /* effectAccepted */);
            } else {
                throw HumanErrors.invalid(HumanErrors.INVALID_TECHNIQUE_OPERATION);
            }
            return encode(response);
        } catch (HumanErrors.HostRejected rejected) {
            return failure(response, rejected.code());
        } catch (TechniqueErrors.HostRejected rejected) {
            return failure(response, rejected.failure());
        } catch (RuntimeException failure) {
            LOG.error(HumanErrors.TECHNIQUE_HOST_FAILED, failure);
            return failure(response, FailureCode.INTERNAL_ERROR);
        } finally {
            current.state().setDirty();
            current.changed().accept(player.getUUID());
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
