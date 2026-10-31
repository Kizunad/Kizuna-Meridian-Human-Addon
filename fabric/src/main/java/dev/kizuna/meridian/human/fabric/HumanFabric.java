package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostRequest;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostResponse;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.human.server.HumanDamageRules;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.human.server.HumanRevival;
import dev.kizuna.meridian.progression.fabric.ProgressionFabric;
import dev.kizuna.meridian.progression.pb.ProgressionMessages.PlayerProgress;
import dev.kizuna.meridian.progression.server.ProgressionStore;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.GatheringProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.MeridianOpeningRequest;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewRequest;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewUpdate;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.MeridianOpeningOptions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fabric 宿主绑定；集成服务器与专服走相同的服务端权威路径。 */
public final class HumanFabric implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_human_host");
    private static final Identifier DISCONNECT_CLEANUP =
            Identifier.fromNamespaceAndPath("meridian_human", "disconnect_cleanup");
    private final Map<UUID, Integer> gatheringPackets = new HashMap<>();
    private final Map<UUID, HumanMovementObserver> observers = new HashMap<>();
    private final Map<UUID, Long> sentRevisions = new HashMap<>();
    private final Set<UUID> watching = new HashSet<>();
    private final Set<UUID> failed = new HashSet<>();
    private final Set<UUID> introspecting = new HashSet<>();
    private final HumanPlayerAnimation animations = new HumanPlayerAnimation();
    private HumanWorldState state;
    private volatile MinecraftServer activeServer;

    @Override
    public void onInitialize() {
        // 身体创建前固定模板伤害规则，玩家与类人共用同一服务端配置。
        HumanDamage.configure(FabricLoader.getInstance().getConfigDir()
                .resolve(HumanDamageRules.FILE_NAME));
        // 宿主生命周期独立于客户端窗口，关掉 UI 后玩家移动仍正常记入同一身体。
        HumanTechniqueEffects.initialize();
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (HumanTechniqueHost.isCasting(player.getUUID())) {
                return net.minecraft.world.InteractionResult.FAIL;
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, side) -> {
            if (HumanTechniqueHost.isCasting(player.getUUID())) {
                return net.minecraft.world.InteractionResult.FAIL;
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        HumanHealthHost.bind(this::health);
        HumanDebugHost.bind(this::debugQi);
        HumanEnvironmentHost.bind(this::environment);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> ProgressionFabric.installStore(server, new ProgressionStore() {
            @Override
            public PlayerProgress read(UUID playerId) {
                // 适配器属于这次服务器实例；身体必须由登录流程先加载。
                return requireBody(playerId).progress();
            }

            @Override
            public void write(UUID playerId, PlayerProgress progress) {
                // 同 tick 内先停主动修炼，再提交身体进度；无独立的玩家解锁旁账。
                HumanPlayerBody body = requireBody(playerId);
                body.settle(millis(server));
                body.writeProgress(progress);
                state.setDirty();
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    if (!progress.getQiNotesRead() || !progress.getInnerVisionScrollRead()) {
                        introspecting.remove(playerId);
                    }
                    if (!progress.getQiNotesRead()) {
                        animations.stop(player);
                        body.connectGathering(millis(server), response -> sendGathering(player, response));
                    }
                    sentRevisions.remove(playerId);
                    ProgressionFabric.publishProgress(player);
                }
            }
        }));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            // 加载失败时保持 null，不能新建身体把损坏存档覆盖掉。
            Path file = stateFile(server);
            if (Files.exists(file)) {
                state = loadState(file);
            } else {
                state = new HumanWorldState();
            }
            if (state != null) {
                state.bind(server);
            }
            HumanTechniqueHost.bind(server, state, failed::contains, sentRevisions::remove);
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
            // 跟随原版世界保存（含停服）；/save-off 后只在强制保存时写入，与原版主世界存档数据一致。
            if (state != null && (force || !server.overworld().noSave())) {
                state.save(stateFile(server).toFile());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> HumanTechniqueHost.unbind());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> join(handler.player, server));
        // 功法库在默认阶段取消当前施放，Human 随后才结束自己的动作并撤销认领。
        ServerPlayConnectionEvents.DISCONNECT.addPhaseOrdering(Event.DEFAULT_PHASE, DISCONNECT_CLEANUP);
        ServerPlayConnectionEvents.DISCONNECT.register(DISCONNECT_CLEANUP,
                (handler, server) -> leave(handler.player, server));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
                (player, origin, destination) -> resetGathering(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // 末地返回等存活换实体不触发复活，也不支出寿元。
            if (!alive) {
                revive(newPlayer);
            }
            resetGathering(newPlayer);
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            // 原版外部死亡先结束施放，允许后续死亡确认保留已经清理的功法进度。
            if (entity instanceof ServerPlayer player && state != null
                    && !failed.contains(player.getUUID())) {
                HumanTechniqueHost.beforeDeath(player);
            }
            return true;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerPlayNetworking.registerGlobalReceiver(MeridianChannels.BODY_VIEW_REQUEST.type(), (packet, context) -> {
            // 只有授权连接可订阅内部操作视图，拒绝结果仍沿同一 Protobuf 通道返回。
            ServerPlayer player = context.player();
            if (!acceptsInput(MeridianChannels.BODY_VIEW_REQUEST, packet, player)) {
                return;
            }
            try {
                BodyViewRequest request = BodyViewRequest.parseFrom(packet.bytes());
                if (request.getProtocolVersion() != 1 || request.getRequestId().isBlank()) {
                    return;
                }
                HumanPlayerBody body = requireBody(player.getUUID());
                boolean allowed = request.getBodyId().equals(body.snapshot().id().value());
                if (request.getIntrospection()) {
                    allowed = body.canIntrospect(request.getBodyId());
                }
                var update = BodyViewUpdate.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId());
                if (!allowed) {
                    update.setFailure(RequestFailure.newBuilder().setCode(FailureCode.ACCESS_DENIED));
                } else {
                    if (request.getIntrospection()) {
                        introspecting.add(player.getUUID());
                    } else {
                        introspecting.remove(player.getUUID());
                    }
                    update.setIntrospection(request.getIntrospection());
                    sentRevisions.remove(player.getUUID());
                }
                sendBodyView(player, update.build());
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                // 无效输入只拒绝本包，不损坏玩家的自然身体运行。
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(MeridianChannels.OPENING_REQUEST.type(), (packet, context) -> {
            // 与吐纳共享连接限流；收包回调已在服务端线程执行。
            ServerPlayer player = context.player();
            if (!acceptsInput(MeridianChannels.OPENING_REQUEST, packet, player)) {
                return;
            }
            MeridianOpeningRequest request;
            try {
                request = OpeningProtobuf.decodeRequest(packet.bytes());
            } catch (IllegalArgumentException invalid) {
                return;
            }
            try {
                HumanPlayerBody body = state.find(player.getUUID());
                if (body == null) {
                    return;
                }
                var response = body.open(millis(context.server()), request);
                state.setDirty();
                requireChannel(player, MeridianChannels.OPENING_RESPONSE);
                ServerPlayNetworking.send(player, MeridianChannels.OPENING_RESPONSE.payload(response.toByteArray()));
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(MeridianChannels.GATHERING_REQUEST.type(), (packet, context) -> {
            // 身体归属、限流和结算都在服务端线程执行。
            ServerPlayer player = context.player();
            if (!acceptsInput(MeridianChannels.GATHERING_REQUEST, packet, player)) {
                return;
            }
            GatheringRequest request;
            try {
                request = GatheringProtobuf.decodeRequest(packet.bytes());
            } catch (IllegalArgumentException invalid) {
                // 非法网络输入只拒绝该包，不暂停玩家的合法行走与恢复。
                return;
            }
            try {
                HumanPlayerBody body = state.find(player.getUUID());
                if (body == null) {
                    return;
                }
                sendGathering(player, body.gather(millis(context.server()), request));
                state.setDirty();
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(Event.DEFAULT_PHASE, DISCONNECT_CLEANUP);
        ServerLifecycleEvents.SERVER_STOPPING.register(DISCONNECT_CLEANUP, server -> {
            // 保存前结清仍在线玩家，停止移动占用；不能把断线后的整段时间当作跑步。
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                leave(player, server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            observers.clear();
            sentRevisions.clear();
            watching.clear();
            introspecting.clear();
            failed.clear();
            gatheringPackets.clear();
            animations.clear();
            state = null;
            activeServer = null;
        });
        registerCommands();
        ServerPlayNetworking.registerGlobalReceiver(HumanChannels.CULTIVATION.type(), (packet, context) -> {
            // 包只含控制边沿，限制体积和每秒输入次数；身份与时间取真实连接。
            ServerPlayer player = context.player();
            if (!acceptsInput(HumanChannels.CULTIVATION, packet, player)) {
                return;
            }
            try {
                var input = dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.parseFrom(packet.bytes());
                HumanPlayerBody body = state.find(player.getUUID());
                if (body != null) {
                    if (body.cultivate(millis(context.server()), input) == FailureCode.ACCESS_DENIED) {
                        player.sendOverlayMessage(Component.literal(HumanErrors.MIDDLE_JIAO_LOCKED));
                    }
                    state.setDirty();
                }
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                // 无效消息不能创建任何活动。
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        });
        HumanCultivationCommands.register(
                player -> state == null || failed.contains(player.getUUID()) ? null : state.find(player.getUUID()),
                player -> state.setDirty());
        HumanQiCommands.register(
                player -> state == null || failed.contains(player.getUUID()) ? null : state.find(player.getUUID()),
                player -> {
                    // 命令提交和身体推送沿用唯一世界存档，下一 tick 的版本推送会刷新全部页面。
                    state.setDirty();
                    state.find(player.getUUID()).publishGathering();
                });
    }

    private void join(ServerPlayer player, MinecraftServer server) {
        // 玩家身份来自服务端连接，客户端请求只能操作该连接绑定的身体。
        try {
            if (state == null) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            HumanPlayerBody body = state.join(player, millis(server));
            HumanTechniqueHost.reconnect(player);
            state.selectEnvironment(player, millis(server));
            body.connectGathering(millis(server), response -> sendGathering(player, response));
            observers.put(player.getUUID(), new HumanMovementObserver());
            sentRevisions.remove(player.getUUID());
            failed.remove(player.getUUID());
            ProgressionFabric.publishProgress(player);
            player.sendSystemMessage(Component.translatable(HumanLang.BODY_BOUND));
        } catch (RuntimeException failure) {
            fail(player, failure);
        }
    }

    private byte[] environment(byte[] payload) {
        // 先验证有界协议输入，再触碰当前服务器及唯一环境状态。
        var response = EnvironmentHostResponse.newBuilder().setProtocolVersion(1);
        if (payload == null || payload.length == 0 || payload.length > MeridianProtocol.MAX_PAYLOAD_BYTES) {
            return environmentFailure(response, FailureCode.INVALID_REQUEST);
        }
        EnvironmentHostRequest request;
        try {
            request = EnvironmentHostRequest.parseFrom(payload);
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            return environmentFailure(response, FailureCode.INVALID_REQUEST);
        }
        response.setRequestId(request.getRequestId());
        if (request.getProtocolVersion() != 1) {
            return environmentFailure(response, FailureCode.UNSUPPORTED_VERSION);
        }
        if (request.getRequestId().isBlank()
                || request.getOperationCase() == EnvironmentHostRequest.OperationCase.OPERATION_NOT_SET
                || (request.hasClock() && !request.getClock())) {
            return environmentFailure(response, FailureCode.INVALID_REQUEST);
        }
        MinecraftServer server = activeServer;
        if (server == null) {
            return response.setUnbound(true).build().toByteArray();
        }
        if (!server.isSameThread()) {
            return environmentFailure(response, FailureCode.ACCESS_DENIED);
        }
        if (state == null) {
            return environmentFailure(response, FailureCode.INTERNAL_ERROR);
        }
        return environmentResult(request, server, response);
    }

    private byte[] environmentResult(EnvironmentHostRequest request, MinecraftServer server,
                                     EnvironmentHostResponse.Builder response) {
        // 协议拒绝与磁盘失败分别保留原失败码和内部错误，失败时不产生持久化确认。
        try {
            return executeEnvironment(request, server, response);
        } catch (HumanErrors.HostRejected rejected) {
            return environmentFailure(response, rejected.code());
        } catch (dev.kizuna.meridian.error.MeridianErrors.IntakeRejected rejected) {
            return environmentFailure(response, rejected.code());
        } catch (dev.kizuna.meridian.error.MeridianErrors.GatheringRejected rejected) {
            return environmentFailure(response, rejected.code());
        } catch (IllegalArgumentException invalid) {
            return environmentFailure(response, FailureCode.INVALID_REQUEST);
        } catch (RuntimeException failure) {
            LOG.error(HumanErrors.ENVIRONMENT_HOST_FAILED, failure);
            return environmentFailure(response, FailureCode.INTERNAL_ERROR);
        }
    }

    private byte[] executeEnvironment(EnvironmentHostRequest request, MinecraftServer server,
                                      EnvironmentHostResponse.Builder response) {
        // 请求只受理和查询状态，写盘统一在 tick 末完成，避免逐笔阻塞主线程。
        long now = millis(server);
        if (request.hasClock()) {
            return response.setNow(state.time(now)).build().toByteArray();
        }
        if (request.hasForgetRelease()) {
            return response.setForgotten(state.forgetRelease(request.getForgetRelease())).build().toByteArray();
        }
        boolean queryingRegion = request.hasQuery();
        var query = request.getQuery();
        String dimension;
        if (queryingRegion) {
            dimension = query.getDimensionId();
        } else {
            dimension = request.getRelease().getPosition().getDimensionId();
        }
        var world = environmentWorld(server, dimension);
        if (queryingRegion) {
            var region = state.query(world, query.getBlockX(), query.getBlockZ(), now);
            if (region != null) {
                response.setRegion(region);
            }
            return response.build().toByteArray();
        }
        var released = state.release(request.getRelease(), world, now);
        return response.setReleased(released).build().toByteArray();
    }

    private static byte[] environmentFailure(EnvironmentHostResponse.Builder response, FailureCode code) {
        // 统一返回框架真实失败码，调用方无需解析异常文案。
        var failure = RequestFailure.newBuilder().setCode(code).build();
        return response.setFailure(failure).build().toByteArray();
    }

    private static net.minecraft.server.level.ServerLevel environmentWorld(MinecraftServer server, String dimension) {
        // 非法或不存在的维度必须明确拒绝，不能当作没有区域接收释放。
        Identifier id = Identifier.tryParse(dimension);
        if (id == null || !id.toString().equals(dimension)) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        var world = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (world == null) {
            throw HumanErrors.hostRejected(FailureCode.ENVIRONMENT_UNAVAILABLE);
        }
        return world;
    }

    private byte[] health(byte[] payload) {
        // 此进程内接口只供可信附属使用；实际玩家仍须在线并由当前宿主唯一管理。
        var request = HealthHostProtobuf.decodeRequest(payload);
        var response = HealthHostResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        if (activeServer == null) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
        }
        if (!activeServer.isSameThread()) {
            return healthFailure(response, FailureCode.ACCESS_DENIED);
        }
        UUID subjectId;
        try {
            subjectId = UUID.fromString(request.getSubjectId());
        } catch (IllegalArgumentException invalid) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
        }
        ServerPlayer player = activeServer.getPlayerList().getPlayer(subjectId);
        if (player == null) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
        }
        if (failed.contains(subjectId)) {
            return healthFailure(response, FailureCode.BODY_PAUSED);
        }
        if (state == null) {
            return healthFailure(response, FailureCode.INTERNAL_ERROR);
        }
        HumanPlayerBody body = state.find(subjectId);
        if (body == null) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true /* unbound */).build());
        }
        return applyHealthOperation(request, response, body, player);
    }

    private byte[] applyHealthOperation(HealthHostRequest request, HealthHostResponse.Builder response,
                                       HumanPlayerBody body, ServerPlayer player) {
        // 已绑定身份只能操作其实际身体；不能利用可信入口读取或伤害另一名玩家。
        RequestEnvelope operation;
        if (request.hasExertion()) {
            operation = request.getExertion();
        } else {
            operation = request.getEffect();
        }
        if (!request.hasInspect() && !operation.getBodyCommand().getBodyId().equals(body.snapshot().id().value())) {
            return healthFailure(response, FailureCode.ACCESS_DENIED);
        }
        try {
            long nowMillis = millis(activeServer);
            if (request.hasInspect()) {
                body.settle(nowMillis);
            } else {
                var result = submitHealthOperation(request, response, body, player, nowMillis);
                if (result.hasFailure()) {
                    return healthFailure(response, result.getFailure().getCode());
                }
            }
            HumanTechniqueHost.recoverIfDead(player);
            return HealthHostProtobuf.encodeResponse(response.setBody(BodyProtobuf.toMessage(body.snapshot())).build());
        } catch (RuntimeException failure) {
            fail(player, failure);
            return healthFailure(response, FailureCode.INTERNAL_ERROR);
        } finally {
            // 时间结算也可能产生持久化变化；业务拒绝不能丢掉已经推进的权威游标。
            state.setDirty();
        }
    }

    private ResponseEnvelope submitHealthOperation(
            HealthHostRequest request, HealthHostResponse.Builder response, HumanPlayerBody body,
            ServerPlayer player, long nowMillis) {
        // 普通做功不生成健康效果回执；健康效果成功后才发布固定回执与死亡动画状态。
        if (request.hasExertion()) {
            return body.applyExertion(nowMillis, request.getExertion());
        }
        var result = body.applyHealth(nowMillis, request.getEffect());
        if (!result.hasFailure()) {
            response.setHealth(body.healthReceipt(request.getRequestId()));
            if (body.snapshot().vitality().dead()) {
                animations.stop(player);
            }
        }
        return result;
    }

    private FailureCode debugQi(ServerPlayer player, DebugQi request) {
        // 调试附属已检查权限；提交、存档标记与身体推送沿用 OP 真元命令的同一路径。
        if (!player.level().getServer().isSameThread()) {
            return FailureCode.ACCESS_DENIED;
        }
        if (state == null || failed.contains(player.getUUID())) {
            return FailureCode.BODY_NOT_FOUND;
        }
        HumanPlayerBody body = state.find(player.getUUID());
        if (body == null) {
            return FailureCode.BODY_NOT_FOUND;
        }
        FailureCode failure = body.debugQi(millis(player.level().getServer()), request);
        state.setDirty();
        body.publishGathering();
        return failure;
    }

    private HumanPlayerBody requireBody(UUID playerId) {
        // 必装宿主未完成加载是接线错误，不能临时创建另一个身体掩盖问题。
        HumanPlayerBody body = state == null ? null : state.find(playerId);
        if (body == null || failed.contains(playerId)) {
            throw HumanErrors.invalid(HumanErrors.BODY_MISSING);
        }
        return body;
    }

    private static byte[] healthFailure(HealthHostResponse.Builder response, FailureCode code) {
        // 保留公共失败码，调用方可区分未绑定与已认领后被拒绝。
        RequestFailure failure = RequestFailure.newBuilder()
                .setCode(code)
                .build();
        return HealthHostProtobuf.encodeResponse(response.setFailure(failure).build());
    }

    private void leave(ServerPlayer player, MinecraftServer server) {
        // 仅结束宿主自己的移动实例，其他附属动作保持各自的生命周期规则。
        HumanPlayerBody body = state == null ? null : state.find(player.getUUID());
        if (body != null && observers.containsKey(player.getUUID()) && !failed.contains(player.getUUID())) {
            try {
                body.disconnectGathering(millis(server));
                body.observe(millis(server), HumanPlayerBody.Motion.IDLE);
                body.settle(millis(server));
                state.setDirty();
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        }
        observers.remove(player.getUUID());
        sentRevisions.remove(player.getUUID());
        watching.remove(player.getUUID());
        introspecting.remove(player.getUUID());
        gatheringPackets.remove(player.getUUID());
        animations.stop(player);
        HumanTechniqueHost.disconnect(player.getUUID());
    }

    private void revive(ServerPlayer player) {
        // 同 UUID 继续使用原身体，复活失败仍走统一宿主错误处理。
        try {
            HumanPlayerBody body = requireBody(player.getUUID());
            if (!body.snapshot().vitality().dead()) {
                return;
            }
            HumanTechniqueHost.recoverIfDead(player);
            body.revive(millis(player.level().getServer()));
            state.setDirty();
            player.sendOverlayMessage(Component.literal(HumanRevival.message()));
        } catch (RuntimeException failure) {
            fail(player, failure);
        }
    }

    private void resetGathering(ServerPlayer player) {
        // 换维度和重生时结束旧场景；新会话必须由玩家重新开始，不能沿用旧动画或吸气输入。
        animations.stop(player);
        HumanPlayerBody body = state == null ? null : state.find(player.getUUID());
        if (body == null || failed.contains(player.getUUID())) {
            return;
        }
        try {
            long nowMillis = millis(player.level().getServer());
            state.bindReleaseSink(player);
            body.connectGathering(nowMillis, response -> sendGathering(player, response));
            body.observe(nowMillis, HumanPlayerBody.Motion.IDLE);
            observers.put(player.getUUID(), new HumanMovementObserver());
            state.selectEnvironment(player, nowMillis);
            body.publishGathering();
            introspecting.remove(player.getUUID());
            sentRevisions.remove(player.getUUID());
            ProgressionFabric.publishProgress(player);
            state.setDirty();
        } catch (RuntimeException failure) {
            fail(player, failure);
        }
    }

    private void tick(MinecraftServer server) {
        // 每 tick 只采样位置并检查边界；身体纯计算在动作切换、每秒负载或已有事件时执行。
        if (state == null) {
            return;
        }
        long millis = millis(server);
        if (server.getTickCount() % 20 == 0) {
            gatheringPackets.clear();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            HumanMovementObserver observer = observers.get(id);
            if (observer == null || failed.contains(id)) {
                continue;
            }
            try {
                HumanPlayerBody body = state.find(id);
                // 先用旧区域完成刚过去的呼吸区间，再切换位置与结算运动，避免新位置追溯摄入。
                boolean breathingChanged = body.advanceGathering(millis);
                state.selectEnvironment(player, millis);
                HumanPlayerBody.Motion motion = observer.sample(player);
                boolean changed = body.observe(millis, motion, observer.moved());
                HumanTechniqueHost.recoverIfDead(player);
                if (changed || breathingChanged) {
                    animations.synchronize(player, body.motion(), body.breathingActive(), body.breathingAutomatic());
                }
                if (changed && !breathingChanged) {
                    body.publishGathering();
                }
                if (changed || breathingChanged) {
                    state.setDirty();
                }
                // UI 为必装接线，按身体版本推送；缺少接收通道不能静默跳过。
                requireChannel(player, MeridianChannels.BODY_VIEW);
                if (sentRevisions.getOrDefault(id, -1L) != body.snapshot().revision()) {
                    var update = BodyViewUpdate.newBuilder().setProtocolVersion(1)
                            .setBody(BodyProtobuf.toMessage(body.snapshot()))
                            .setPresentationResource("meridian_human:meridian/adult.pb");
                    // 人体门槛和进度条由本包随同一版本发布，UI 不解释人体规则。
                    boolean internalView = introspecting.contains(id) && ProgressionFabric.canInspect(player);
                    update.setIntrospection(internalView);
                    if (internalView) {
                        update.addOperations(dev.kizuna.meridian.human.server.HumanOpeningViews.update(
                                update.getBody(), HumanContent.bundle().getOpeningRulesList()));
                        update.setOpening(MeridianOpeningOptions.newBuilder()
                                .addAllRules(HumanContent.bundle().getOpeningRulesList()));
                    } else {
                        // 撤销订阅同时撤掉规则目录；基础身体快照仍供其他必装界面使用。
                        update.setOpening(MeridianOpeningOptions.getDefaultInstance());
                    }
                    sendBodyView(player, update.build());
                    sentRevisions.put(id, body.snapshot().revision());
                }
                if (watching.contains(id) && (changed || server.getTickCount() % 20 == 0)) {
                    player.sendOverlayMessage(Component.literal(HumanNumbers.summary(body)));
                }
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        }
        state.renewEnvironment(millis);
        state.flushHandoffs(stateFile(server));
    }

    private void sendGathering(ServerPlayer player, GatheringResponse response) {
        // 动画读取服务端确认的吐纳状态，独立于 UI 接收器；客户端播放回执不改变身体。
        if (response.hasView() && response.getView().hasBreathing()) {
            HumanPlayerBody body = state.find(player.getUUID());
            animations.synchronize(player, body.motion(), response.getView().getBreathing().getActive(),
                    response.getView().getBreathing().getAutomatic());
        }
        // 关闭连接无需发送；已连接却缺少必装 UI 通道必须报告错误。
        if (player.connection.isAcceptingMessages()) {
            requireChannel(player, MeridianChannels.GATHERING_RESPONSE);
            ServerPlayNetworking.send(player, MeridianChannels.GATHERING_RESPONSE.payload(response.toByteArray()));
        }
    }

    private void sendBodyView(ServerPlayer player, BodyViewUpdate update) {
        // 订阅回执和身体投影共享固定响应通道，身份由当前连接决定。
        requireChannel(player, MeridianChannels.BODY_VIEW);
        ServerPlayNetworking.send(player, MeridianChannels.BODY_VIEW.payload(update.toByteArray()));
    }

    private void requireChannel(ServerPlayer player, ProtobufChannel channel) {
        // 握手完成后的响应始终使用固定接收人，不以客户端 body ID 选择连接。
        if (!ServerPlayNetworking.canSend(player, channel.type())) {
            throw new IllegalStateException(HumanErrors.UI_CHANNEL_MISSING + ": " + channel.type().id());
        }
    }

    private boolean acceptsInput(ProtobufChannel channel, ProtobufChannel.Payload packet, ServerPlayer player) {
        // 空包、超长包、已关闭连接与暂停的身体直接丢弃；四条请求通道共用每秒 40 次的连接限流。
        if (!channel.accepts(packet) || !player.connection.isAcceptingMessages() || state == null
                || failed.contains(player.getUUID())) {
            return false;
        }
        return gatheringPackets.merge(player.getUUID(), 1, Integer::sum) <= 40;
    }

    private void registerCommands() {
        // body status/tissue、opening 与 watch body 挂在 Framework 的 /meridian 注册点；玩家查看自己的身体为权限 0，
        // 查看他人另要权限 2。没有设置疲劳或自报动作的普通玩家命令。
        MeridianCommands.register(MeridianCommands.Branch.BODY, "status", MeridianCommands.PLAYER, (node, context) ->
                MeridianCommands.optionalEntity(node, (parent, target) -> {
                    parent.executes(command -> status(command.getSource(), target.resolve(command)));
                    HumanBodyStatus.attachDetail(parent, target);
                }));
        MeridianCommands.register(MeridianCommands.Branch.BODY, "tissue", MeridianCommands.PLAYER, (node, context) -> {
            // 组织 ID 在前、可选目标在后（对照表 #5）；补全用执行者实际身体的组织，改造后自动反映新构型。
            var tissue = Commands.argument("id", StringArgumentType.word()).suggests((command, builder) -> {
                HumanPlayerBody body = state == null || command.getSource().getPlayer() == null ? null
                        : state.find(command.getSource().getPlayer().getUUID());
                if (body != null) {
                    for (var definition : body.snapshot().structure().tissues()) {
                        if (definition.id().value().startsWith(builder.getRemaining())) {
                            builder.suggest(definition.id().value());
                        }
                    }
                }
                return builder.buildFuture();
            });
            MeridianCommands.optionalEntity(tissue, (parent, target) -> parent.executes(command -> inspect(
                    command.getSource(), target.resolve(command), StringArgumentType.getString(command, "id"))));
            node.then(tissue);
        });

        // 开脉：/meridian opening 本身显示状态，start/pause 只作用于执行者本人。
        MeridianCommands.registerBranchCommand(MeridianCommands.Branch.OPENING, MeridianCommands.PLAYER,
                command -> opening(command.getSource(), "status", null));
        MeridianCommands.register(MeridianCommands.Branch.OPENING, "pause", MeridianCommands.PLAYER, (node, context) ->
                node.executes(command -> opening(command.getSource(), "pause", null)));
        MeridianCommands.register(MeridianCommands.Branch.OPENING, "start", MeridianCommands.PLAYER, (node, context) ->
                node.then(Commands.argument("meridian", StringArgumentType.word())
                        .suggests((command, builder) -> {
                            // 补全来自当前身体，经脉替换后不会继续显示不存在的目标。
                            HumanPlayerBody body = state == null || command.getSource().getPlayer() == null ? null
                                    : state.find(command.getSource().getPlayer().getUUID());
                            if (body != null) {
                                for (var meridian : body.snapshot().structure().meridians()) {
                                    if (meridian.id().value().startsWith(builder.getRemaining())) {
                                        builder.suggest(meridian.id().value());
                                    }
                                }
                            }
                            return builder.buildFuture();
                        }).executes(command -> opening(command.getSource(), "start",
                                StringArgumentType.getString(command, "meridian")))));

        // 下肢余力实时观察登记为 watch 的 body 频道，开关与可选目标由注册点生成。
        MeridianCommands.registerWatchChannel("body", MeridianCommands.PLAYER, this::watch);
    }

    private int status(CommandSourceStack source, Entity target) throws CommandSyntaxException {
        // 玩家目标显示人体摘要；其他实体交给登记的身体视图（类人生物由 Threatening 提供）。
        if (target instanceof ServerPlayer player) {
            return inspect(source, player, null);
        }
        if (HumanBodyStatus.showOther(source, target)) {
            return 1;
        }
        source.sendFailure(Component.translatable(HumanLang.STATUS_NO_BODY, target.getDisplayName()));
        return 0;
    }

    private int inspect(CommandSourceStack source, Entity target, String tissueId) {
        // 人体数值只属于玩家；查看时把尚未结算的最后一个区间结清，使显示值对应本次服务端模拟时间。
        if (!(target instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable(HumanLang.TARGET_NOT_PLAYER, target.getDisplayName()));
            return 0;
        }
        HumanPlayerBody body = state == null ? null : state.find(player.getUUID());
        if (body == null || failed.contains(player.getUUID())) {
            source.sendSystemMessage(Component.literal(HumanErrors.BODY_MISSING));
            return 0;
        }
        try {
            body.settle(millis(player.level().getServer()));
            state.setDirty();
            source.sendSystemMessage(Component.literal(tissueId == null ? HumanNumbers.summary(body)
                    : HumanNumbers.tissue(body.snapshot(), tissueId)));
            if (tissueId == null) {
                // 总览末尾附加目标玩家行走负荷提供者的说明；查看单个组织时不附加。
                HumanMovementLoads.status(player)
                        .ifPresent(line -> source.sendSystemMessage(Component.literal(line)));
            }
            return 1;
        } catch (IllegalArgumentException failure) {
            source.sendSystemMessage(Component.literal(failure.getMessage()));
            return 0;
        }
    }

    private int opening(CommandSourceStack source, String operation, String meridianId) throws CommandSyntaxException {
        // 玩家入口只选择规则和目标，所有资源与条件都由 Framework 权威检查。
        ServerPlayer player = source.getPlayerOrException();
        HumanPlayerBody body = state == null ? null : state.find(player.getUUID());
        if (body == null || failed.contains(player.getUUID())) {
            player.sendSystemMessage(Component.literal(HumanErrors.BODY_MISSING));
            return 0;
        }
        try {
            long now = millis(player.level().getServer());
            FailureCode failure = FailureCode.FAILURE_CODE_UNSPECIFIED;
            if (operation.equals("start")) {
                failure = body.startOpening(now, meridianId);
            } else if (operation.equals("pause")) {
                failure = body.pauseOpening(now);
            } else {
                body.settle(now);
            }
            state.setDirty();
            if (failure != FailureCode.FAILURE_CODE_UNSPECIFIED) {
                player.sendSystemMessage(Component.literal(HumanErrors.openingRejected(failure)));
                return 0;
            }
            player.sendSystemMessage(Component.literal(HumanOpeningNumbers.summary(body.snapshot())));
            return 1;
        } catch (RuntimeException failure) {
            fail(player, failure);
            return 0;
        }
    }

    private int watch(CommandSourceStack source, Collection<? extends Entity> targets,
                      MeridianCommands.WatchSwitch watchSwitch) {
        // 实时观察仅选择输出，不触发额外做功或建立另一份玩家状态；只有玩家有动作栏，非玩家目标跳过。
        int changed = 0;
        for (Entity target : targets) {
            if (!(target instanceof ServerPlayer player)) {
                continue;
            }
            if (watchSwitch == MeridianCommands.WatchSwitch.ON) {
                watching.add(player.getUUID());
            } else {
                watching.remove(player.getUUID());
                player.sendOverlayMessage(Component.empty());
            }
            changed++;
        }

        // 一个玩家都没有选中时报告第一个目标，避免静默成功。
        if (changed == 0) {
            source.sendFailure(Component.translatable(HumanLang.TARGET_NOT_PLAYER,
                    targets.iterator().next().getDisplayName()));
            return 0;
        }
        source.sendSystemMessage(Component.translatable(watchSwitch == MeridianCommands.WatchSwitch.ON
                ? HumanLang.WATCH_BODY_ON : HumanLang.WATCH_BODY_OFF));
        return changed;
    }

    private void fail(ServerPlayer player, RuntimeException failure) {
        // 单个身体失败后保留现场并暂停，避免逐 tick 重复扣费、刷屏或自动重置存档。
        animations.stop(player);
        if (failed.add(player.getUUID())) {
            LOG.error(HumanErrors.HOST_FAILED + ": " + player.getUUID(), failure);
            player.sendSystemMessage(Component.literal(HumanErrors.HOST_RECOVERY));
        }
    }

    private static Path stateFile(MinecraftServer server) {
        // 身体世界存档沿用原来的位置，世界保存与 tick 末交接写同一个文件。
        return server.getWorldPath(LevelResource.ROOT).resolve("data/meridian_human.dat");
    }

    private static HumanWorldState loadState(Path file) {
        // 读取或校验失败时记录原因并返回 null，身体全部暂停，等待人工处理存档。
        try {
            return HumanWorldState.load(file);
        } catch (IOException | RuntimeException invalid) {
            LOG.error(HumanErrors.INVALID_SAVED_BODY, invalid);
            return null;
        }
    }

    private static long millis(MinecraftServer server) {
        // 主世界累计时间不受昼夜 /time set 影响；服务器暂停时模拟时间同步停止。
        return Math.multiplyExact(server.overworld().getGameTime(), 50);
    }
}
