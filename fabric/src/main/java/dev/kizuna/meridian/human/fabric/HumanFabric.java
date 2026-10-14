package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewUpdate;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.GatheringProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;

/** Fabric 宿主绑定；集成服务器与专服走相同的服务端权威路径。 */
public final class HumanFabric implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_human_host");
    private static final Identifier BODY_VIEW = new Identifier("meridian", "body_view");
    private static final Identifier GATHERING_REQUEST = new Identifier("meridian", "gathering_request");
    private static final Identifier GATHERING_RESPONSE = new Identifier("meridian", "gathering_response");
    private final Map<UUID, Integer> gatheringPackets = new HashMap<>();
    private final Map<UUID, HumanMovementObserver> observers = new HashMap<>();
    private final Map<UUID, Long> sentRevisions = new HashMap<>();
    private final Set<UUID> watching = new HashSet<>();
    private final Set<UUID> failed = new HashSet<>();
    private HumanWorldState state;

    @Override
    public void onInitialize() {
        // 宿主生命周期独立于客户端窗口，关掉 UI 后玩家移动仍正常记入同一身体。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // 原版加载失败会返回 null，不能用 getOrCreate 把损坏存档覆盖为新身体。
            var persistence = server.getOverworld().getPersistentStateManager();
            if (Files.exists(server.getSavePath(WorldSavePath.ROOT).resolve("data/meridian_human.dat"))) {
                state = persistence.get(HumanWorldState::read, "meridian_human");
                if (state == null) {
                    LOG.error(HumanErrors.INVALID_SAVED_BODY);
                }
            } else {
                state = new HumanWorldState();
                persistence.set("meridian_human", state);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> join(handler.player, server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> leave(handler.player, server));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerPlayNetworking.registerGlobalReceiver(GATHERING_REQUEST, (server, player, handler, buffer, sender) -> {
            // 网络线程只复制有界 Protobuf；身体归属、限流和结算在服务端线程执行。
            if (buffer.readableBytes() == 0 || buffer.readableBytes() > MeridianProtocol.MAX_PAYLOAD_BYTES) {
                return;
            }
            byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            server.execute(() -> {
                if (!handler.isConnectionOpen() || handler.player.networkHandler != handler
                        || state == null || failed.contains(player.getUuid())
                        || gatheringPackets.merge(player.getUuid(), 1, Integer::sum) > 40) {
                    return;
                }
                GatheringRequest request;
                try {
                    request = GatheringProtobuf.decodeRequest(payload);
                } catch (IllegalArgumentException invalid) {
                    // 非法网络输入只拒绝该包，不暂停玩家的合法行走与恢复。
                    return;
                }
                try {
                    HumanPlayerBody body = state.find(player.getUuid());
                    if (body == null) {
                        return;
                    }
                    sendGathering(player, body.gather(millis(server), request));
                    state.markDirty();
                } catch (RuntimeException failure) {
                    fail(player, failure);
                }
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // 保存前结清仍在线玩家，停止移动占用；不能把断线后的整段时间当作跑步。
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                leave(player, server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            observers.clear();
            sentRevisions.clear();
            watching.clear();
            failed.clear();
            gatheringPackets.clear();
            state = null;
        });
        registerCommands();
    }

    private void join(ServerPlayerEntity player, MinecraftServer server) {
        // 玩家身份来自服务端连接，客户端请求只能操作该连接绑定的身体。
        try {
            if (state == null) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            HumanPlayerBody body = state.join(player.getUuid(), millis(server));
            state.selectEnvironment(player, millis(server));
            body.connectGathering(millis(server), response -> sendGathering(player, response));
            observers.put(player.getUuid(), new HumanMovementObserver());
            sentRevisions.remove(player.getUuid());
            failed.remove(player.getUuid());
            player.sendMessage(Text.literal("人体已绑定：/meridian status 查看动作，/meridian watch on 实时观察下肢余力。"), false);
        } catch (RuntimeException failure) {
            fail(player, failure);
        }
    }

    private void leave(ServerPlayerEntity player, MinecraftServer server) {
        // 仅结束宿主自己的移动实例，其他附属动作保持各自的生命周期规则。
        HumanPlayerBody body = state == null ? null : state.find(player.getUuid());
        if (body != null && observers.containsKey(player.getUuid()) && !failed.contains(player.getUuid())) {
            try {
                body.disconnectGathering(millis(server));
                body.observe(millis(server), HumanPlayerBody.Motion.IDLE);
                body.settle(millis(server));
                state.markDirty();
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        }
        observers.remove(player.getUuid());
        sentRevisions.remove(player.getUuid());
        watching.remove(player.getUuid());
        gatheringPackets.remove(player.getUuid());
    }

    private void tick(MinecraftServer server) {
        // 每 tick 只采样位置并检查边界；身体纯计算在动作切换、每秒负载或已有事件时执行。
        if (state == null) {
            return;
        }
        long millis = millis(server);
        if (server.getTicks() % 20 == 0) {
            gatheringPackets.clear();
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID id = player.getUuid();
            HumanMovementObserver observer = observers.get(id);
            if (observer == null || failed.contains(id)) {
                continue;
            }
            try {
                HumanPlayerBody body = state.find(id);
                // 先用旧区域完成刚过去的呼吸区间，再切换位置与结算运动，避免新位置追溯摄入。
                boolean breathingChanged = body.advanceGathering(millis);
                state.selectEnvironment(player, millis);
                boolean changed = body.observe(millis, observer.sample(player));
                if (changed && !breathingChanged) {
                    body.publishGathering();
                }
                if (changed || breathingChanged) {
                    state.markDirty();
                }
                // 只有拥有已登记接收通道的玩家收到自己的快照，正常客户端未安装 UI 也能游玩。
                if (ServerPlayNetworking.canSend(player, BODY_VIEW)
                        && sentRevisions.getOrDefault(id, -1L) != body.snapshot().revision()) {
                    var update = BodyViewUpdate.newBuilder().setProtocolVersion(1)
                            .setBody(BodyProtobuf.toMessage(body.snapshot()))
                            .setPresentationResource("meridian_human:meridian/adult.pb").build();
                    var buffer = PacketByteBufs.create();
                    buffer.writeBytes(update.toByteArray());
                    ServerPlayNetworking.send(player, BODY_VIEW, buffer);
                    sentRevisions.put(id, body.snapshot().revision());
                }
                if (watching.contains(id) && (changed || server.getTicks() % 20 == 0)) {
                    player.sendMessage(Text.literal(HumanNumbers.summary(body)), true);
                }
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        }
        state.renewEnvironment(millis);
    }

    private void sendGathering(ServerPlayerEntity player, GatheringResponse response) {
        // 只发给当前玩家已注册的接收器，客户端提交的 body ID 不能决定响应接收人。
        if (player.networkHandler.isConnectionOpen() && ServerPlayNetworking.canSend(player, GATHERING_RESPONSE)) {
            var buffer = PacketByteBufs.create();
            buffer.writeBytes(response.toByteArray());
            ServerPlayNetworking.send(player, GATHERING_RESPONSE, buffer);
        }
    }

    private void registerCommands() {
        // 数值命令只能查看自己的身体；没有设置疲劳或自报动作的普通玩家命令。
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                CommandManager.literal("meridian")
                        .then(CommandManager.literal("status").executes(context -> inspect(context.getSource().getPlayerOrThrow(), null)))
                        .then(CommandManager.literal("tissue").then(CommandManager.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    // 用实际身体组织 ID 提供补全，未来改造后自动反映新的构型。
                                    HumanPlayerBody body = state == null ? null
                                            : state.find(context.getSource().getPlayerOrThrow().getUuid());
                                    if (body != null) {
                                        for (var tissue : body.snapshot().structure().tissues()) {
                                            if (tissue.id().value().startsWith(builder.getRemaining())) {
                                                builder.suggest(tissue.id().value());
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                }).executes(context -> inspect(context.getSource().getPlayerOrThrow(), StringArgumentType.getString(context, "id")))))
                        .then(CommandManager.literal("watch")
                                .then(CommandManager.literal("on").executes(context -> watch(context.getSource().getPlayerOrThrow(), true)))
                                .then(CommandManager.literal("off").executes(context -> watch(context.getSource().getPlayerOrThrow(), false))))));
    }

    private int inspect(ServerPlayerEntity player, String tissueId) {
        // 查看时把尚未结算的最后一个区间结清，使显示值对应本次服务端模拟时间。
        HumanPlayerBody body = state == null ? null : state.find(player.getUuid());
        if (body == null || failed.contains(player.getUuid())) {
            player.sendMessage(Text.literal(HumanErrors.BODY_MISSING), false);
            return 0;
        }
        try {
            body.settle(millis(player.getServer()));
            state.markDirty();
            player.sendMessage(Text.literal(tissueId == null ? HumanNumbers.summary(body)
                    : HumanNumbers.tissue(body.snapshot(), tissueId)), false);
            return 1;
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Text.literal(failure.getMessage()), false);
            return 0;
        }
    }

    private int watch(ServerPlayerEntity player, boolean enabled) {
        // 实时观察仅选择输出，不触发额外做功或建立另一份玩家状态。
        if (enabled) {
            watching.add(player.getUuid());
        } else {
            watching.remove(player.getUuid());
            player.sendMessage(Text.empty(), true);
        }
        player.sendMessage(Text.literal(enabled ? "已开启下肢余力实时观察" : "已关闭实时观察"), false);
        return 1;
    }

    private void fail(ServerPlayerEntity player, RuntimeException failure) {
        // 单个身体失败后保留现场并暂停，避免逐 tick 重复扣费、刷屏或自动重置存档。
        if (failed.add(player.getUuid())) {
            LOG.error(HumanErrors.HOST_FAILED + ": " + player.getUuid(), failure);
            player.sendMessage(Text.literal(HumanErrors.HOST_FAILED), false);
        }
    }

    private static long millis(MinecraftServer server) {
        // 主世界累计时间不受昼夜 /time set 影响；服务器暂停时模拟时间同步停止。
        return Math.multiplyExact(server.getOverworld().getTime(), 50);
    }
}
