package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlCommand;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlEntity;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlIntent;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlPoint;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlReply;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlRequest;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlSession;
import dev.kizuna.meridian.human.server.PlayerControlDriver;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.human.server.PlayerControlHost;
import dev.kizuna.meridian.protocol.ProtoWire;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** 玩家受控会话的执行宿主；身体、费用和实际打击仍走既有权威入口。 */
public final class HumanControl implements PlayerControlHost {
    private static final int MAX_COMMAND_BYTES = 4_096;
    private static final int MAX_INTENT_BYTES = 4_096;
    private static final int INTENT_TTL_TICKS = 4;
    private static final long MILLIS_PER_TICK = 50;
    private static final double SENSE_RANGE = 32;
    private static final double FULL_ATTACK = 1;
    private static final float MAX_PITCH = 90;
    private static final Map<UUID, Active> active = new HashMap<>();
    private static MinecraftServer server;
    private static PlayerControlDriver driver;
    private static CastRequest delegated;
    private static UUID delegatePlayer;

    static void initialize() {
        // 通道必须在两端公共初始化时注册；服务就绪后再解析可信控制入口。
        HumanChannels.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            server = started;
            var drivers = FabricLoader.getInstance().getEntrypoints(PlayerControlDriver.ENTRYPOINT,
                    PlayerControlDriver.class);
            if (drivers.size() != 1) {
                throw new IllegalStateException(HumanErrors.CONTROL_DRIVER_MISSING);
            }
            driver = drivers.getFirst();
        });
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addCastGuard(HumanControl::allowsCast));
        ServerTickEvents.END_SERVER_TICK.register(HumanControl::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, stopped) -> end(handler.player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                end(player);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            active.clear();
            server = null;
            driver = null;
        });
    }

    public static boolean controlled(UUID player) {
        // 网络、交互和库存入口只读活动身份，不查询身体。
        return active.containsKey(player);
    }

    public static boolean blocksAction(UUID player) {
        // 原版攻击回调在本次可信代发作用域内可通行；客户端不能建立这个作用域。
        return controlled(player) && !player.equals(delegatePlayer);
    }

    static boolean allowsTarget(Player player, LivingEntity target) {
        // 受控打击只认当前施药者指定目标，普通状态保持原版队伍规则。
        Active value = active.get(player.getUUID());
        if (value != null) {
            return target.getStringUUID().equals(value.session.getOpponentUuid());
        }
        return !player.isAlliedTo(target);
    }

    private static boolean allowsCast(UUID player, CastRequest request) {
        // 对象身份绑定一次同步调用，重复 UUID 或伪造 cast_id 都不能绕过准入。
        return !controlled(player) || player.equals(delegatePlayer) && request == delegated;
    }

    @Override
    public byte[] exchange(String caller, byte[] payload) {
        // 先校验主线程与可信来源，再解析有界协议；不会提供网络注册入口。
        if (!CALLER.equals(caller) || server == null || !server.isSameThread()
                || payload.length == 0 || payload.length > MAX_COMMAND_BYTES) {
            throw HumanErrors.invalid(HumanErrors.INVALID_CONTROL_COMMAND);
        }
        try {
            ControlCommand command = ControlCommand.parseFrom(payload);
            validateCommand(command);
            ControlSession session = command.getSession();
            String sessionId = session.getSessionId();
            UUID playerId = UUID.fromString(session.getPlayerUuid());
            UUID.fromString(sessionId); // 会话必须是合法 UUID，非法时抛出 IllegalArgumentException。
            UUID.fromString(session.getCasterUuid()); // 同样校验可信施药者身份。
            boolean accepted = apply(command, playerId);
            var reply = ControlReply.newBuilder()
                    .setSessionId(sessionId)
                    .setAccepted(accepted)
                    .build();
            return ProtoWire.write(reply);
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException(HumanErrors.INVALID_CONTROL_PROTOCOL, invalid);
        }
    }

    private static boolean apply(ControlCommand command, UUID playerId) {
        // 只允许同一会话刷新；旧结束包不能撤销新控制，会话不写进存档。
        ControlSession session = command.getSession();
        Active current = active.get(playerId);
        var action = command.getAction();
        boolean matches = current != null && current.session.getSessionId().equals(session.getSessionId());
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (action == ControlCommand.Action.END) {
            if (matches) {
                if (player == null) {
                    active.remove(playerId);
                } else {
                    end(player);
                }
            }
            return true;
        }
        if (player == null || !player.isAlive() || session.getEndsTick() <= server.overworld().getGameTime()) {
            return false;
        }
        if (matches) {
            return update(command, current);
        }
        if (action != ControlCommand.Action.START || current != null) {
            return false;
        }
        active.put(playerId, new Active(session));
        player.stopUsingItem();
        player.closeContainer();
        return true;
    }

    private static boolean update(ControlCommand command, Active current) {
        // 首次请求重试只确认原身份；UPDATE 可以换目标、缩短期限，不能延长或换施药者。
        ControlSession session = command.getSession();
        if (command.getAction() == ControlCommand.Action.START) {
            return session.equals(current.started);
        }
        if (!session.getCasterUuid().equals(current.session.getCasterUuid())
                || session.getStartedTick() != current.session.getStartedTick()
                || session.getEndsTick() > current.session.getEndsTick()) {
            return false;
        }
        current.session = session;
        return true;
    }

    private static void validateCommand(ControlCommand command) {
        // 可信入口仍拒绝未知操作、身份缺失和非法期限，不能靠缺省枚举刷新现有会话。
        var action = command.getAction();
        var session = command.getSession();
        long startedTick = session.getStartedTick();
        String opponentId = session.getOpponentUuid();
        if (!command.hasSession() || !command.getUnknownFields().asMap().isEmpty()
                || !session.getUnknownFields().asMap().isEmpty()
                || action != ControlCommand.Action.START && action != ControlCommand.Action.UPDATE
                && action != ControlCommand.Action.END
                || startedTick < 0
                || action != ControlCommand.Action.END && session.getEndsTick() <= startedTick) {
            throw HumanErrors.invalid(HumanErrors.INVALID_CONTROL_COMMAND);
        }
        if (!opponentId.isEmpty()) {
            UUID.fromString(opponentId); // 非空攻击目标必须是有效 UUID。
        }
    }

    private static void tick(MinecraftServer currentServer) {
        // 每 tick 重读身体和持物，先校验意图再发送或执行，不缓存权威快照。
        long tick = currentServer.overworld().getGameTime();
        for (UUID id : List.copyOf(active.keySet())) {
            ServerPlayer player = currentServer.getPlayerList().getPlayer(id);
            Active value = active.get(id);
            if (player == null) {
                active.remove(id);
            } else if (!player.isAlive() || tick >= value.session.getEndsTick()) {
                end(player);
            } else {
                drive(player, value, tick);
            }
        }
    }

    private static void drive(ServerPlayer player, Active value, long tick) {
        // 同一请求的序号只前进一次，受控期间原版受击与魅惑定身仍有优先级。
        ControlRequest request = request(player, value, tick);
        try {
            byte[] payload = driver.exchange(PlayerControlDriver.CALLER, request.toByteArray());
            if (payload.length == 0 || payload.length > MAX_INTENT_BYTES) {
                throw new IllegalStateException(HumanErrors.CONTROL_INTENT_TOO_LARGE);
            }
            ControlIntent intent = ControlIntent.parseFrom(payload);
            validate(request, intent);
            if (!send(player, intent)) {
                return;
            }
            if (!HumanStaggerLock.isLocked(player.getUUID())) {
                execute(player, request, intent);
            }
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(HumanErrors.INVALID_CONTROL_INTENT_PROTOCOL, invalid);
        }
    }

    private static ControlRequest request(ServerPlayer player, Active value, long tick) {
        // inspect 以 Human 唯一身体时钟结算，已学功法来自同一份身体进度。
        var body = HumanTechniqueHost.state().find(player.getUUID());
        body.settle(tick * MILLIS_PER_TICK);
        var snapshot = body.snapshot();
        var position = point(player.position());
        var held = HumanTechniqueHeldItems.inspect(player);
        var request = ControlRequest.newBuilder()
                .setSession(value.session)
                .setSequence(++value.sequence)
                .setServerTick(tick)
                .setBody(BodyProtobuf.toMessage(snapshot))
                .addAllKnown(TechniqueState.read(snapshot).getKnownList())
                .setHeldItems(held)
                .setPosition(position)
                .setYaw(player.getYRot())
                .setPitch(player.getXRot());
        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(SENSE_RANGE), other -> other != player && other.isAlive())) {
            request.addNearby(perceive(player, entity));
        }
        return request.build();
    }

    private static ControlEntity perceive(ServerPlayer player, LivingEntity entity) {
        // 视线、坐标与实体身份由服务端采样，决策层不能要求客户端补身体数据。
        var position = point(entity.position());
        var eyes = point(entity.getEyePosition());
        return ControlEntity.newBuilder()
                .setUuid(entity.getStringUUID())
                .setPosition(position)
                .setEyes(eyes)
                .setVisible(player.hasLineOfSight(entity))
                .build();
    }

    private static ControlPoint point(Vec3 position) {
        // 位置仅供本次决策，不能解释为客户端传送命令。
        return ControlPoint.newBuilder()
                .setX(position.x)
                .setY(position.y)
                .setZ(position.z)
                .build();
    }

    private static void validate(ControlRequest request, ControlIntent intent) {
        // 意图必须对应此 tick；失效、乱序、非有限移动和越界视角均拒绝。
        long tick = request.getServerTick();
        var session = request.getSession();
        long validUntil = intent.getValidUntilTick();
        float pitch = intent.getPitch();
        float forward = intent.getForward();
        float sideways = intent.getSideways();
        String attackId = intent.getAttackUuid();
        if (!intent.getSessionId().equals(session.getSessionId())
                || intent.getSequence() != request.getSequence() || intent.getServerTick() != tick
                || validUntil <= tick || validUntil > tick + INTENT_TTL_TICKS
                || intent.getEnded() || !Float.isFinite(intent.getYaw()) || !Float.isFinite(pitch)
                || Math.abs(pitch) > MAX_PITCH
                || !Float.isFinite(forward) || Math.abs(forward) > 1
                || !Float.isFinite(sideways) || Math.abs(sideways) > 1) {
            throw new IllegalStateException(HumanErrors.INVALID_CONTROL_INTENT);
        }
        if (!attackId.isEmpty() && !attackId.equals(session.getOpponentUuid())) {
            throw new IllegalStateException(HumanErrors.CONTROL_TARGET_DENIED);
        }
    }

    private static void execute(ServerPlayer player, ControlRequest input, ControlIntent intent) {
        // 攻击和功法只由服务器执行，有限作用域让原版攻击回调通行。
        player.setYRot(intent.getYaw());
        player.setXRot(intent.getPitch());
        UUID playerId = player.getUUID();
        String attackId = intent.getAttackUuid();
        delegatePlayer = playerId;
        try {
            if (intent.hasTechnique()) {
                cast(player, input, intent);
            } else if (!attackId.isEmpty() && !HumanTechniqueHost.isCasting(playerId)) {
                var target = player.level().getEntity(UUID.fromString(attackId));
                if (target instanceof LivingEntity living && living.isAlive() && player.hasLineOfSight(living)
                        && player.isWithinEntityInteractionRange(living, 0)
                        && player.getAttackStrengthScale(0) >= FULL_ATTACK) {
                    player.attack(living);
                }
            }
        } finally {
            delegated = null;
            delegatePlayer = null;
        }
    }

    private static void cast(ServerPlayer player, ControlRequest input, ControlIntent intent) {
        // 代发仍检查已学、持物、绑定、费用、冷却和僵直，不给 NPC 脑特殊免费通道。
        var request = CastRequest.newBuilder()
                .setBodyId(input.getBody().getId())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(intent.getTechnique())
                .setTargetId(input.getSession().getOpponentUuid())
                .build();
        delegated = request;
        TechniqueFabric.service().cast(player.getUUID(), request);
    }

    private static boolean send(ServerPlayer player, ControlIntent intent) {
        // 缺少必装通道只断开违规连接；下线路径负责清会话，不能中断服务器 tick。
        if (!ServerPlayNetworking.canSend(player, HumanChannels.CONTROL.type())) {
            player.connection.disconnect(Component.literal(HumanErrors.CONTROL_CHANNEL_MISSING));
            return false;
        }
        ServerPlayNetworking.send(player, HumanChannels.CONTROL.payload(intent.toByteArray()));
        return true;
    }

    private static void end(ServerPlayer player) {
        // 清身份后发终止意图；断线时无需向已关闭的连接发包。
        Active previous = active.remove(player.getUUID());
        if (previous == null) {
            return;
        }
        if (player.connection.isAcceptingMessages()) {
            var intent = ControlIntent.newBuilder()
                    .setSessionId(previous.session.getSessionId())
                    .setSequence(++previous.sequence)
                    .setServerTick(server.overworld().getGameTime())
                    .setEnded(true /* ended */)
                    .build();
            send(player, intent);
        }
    }

    private static final class Active {
        private final ControlSession started;
        private ControlSession session;
        private long sequence;

        private Active(ControlSession session) {
            // 会话只持有控制身份与单调序号，不保存身体副本。
            this.session = session;
            this.started = session;
        }
    }
}
