package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueService.StaggerKind;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 魅惑定身、被擒、倒地的玩家输入门禁：三种都不能攻击、用物品、挖掘、施放和跳。
 * 魅惑和被擒另把移速压到 0；倒地的 ×0.2 和受击减速只由功法库施加，爬行姿势见 {@code PlayerDownPoseMixin}。
 */
public final class HumanStaggerLock {
    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath("meridian_human", "stagger_lock");
    private static final Set<StaggerKind> LOCKING = EnumSet.of(StaggerKind.CHARM, StaggerKind.HOLD, StaggerKind.DOWN);
    private static final Set<StaggerKind> IMMOBILIZING = EnumSet.of(StaggerKind.CHARM, StaggerKind.HOLD);
    private static final double STOPPED = -1;
    private static final Map<UUID, Set<StaggerKind>> locked = new HashMap<>();
    private static MinecraftServer server;

    private HumanStaggerLock() {
        // 不创建身体，不额外保存僵直时钟。
    }

    static void initialize() {
        // 在服务就绪后订阅唯一权威事件；下线和停服释放实体身份。
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> server = starting);
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addStaggerListener(HumanStaggerLock::apply));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, stopped) -> locked.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            locked.clear();
            server = null;
        });
    }

    public static boolean isLocked(UUID playerId) {
        // 输入检查只读轻量状态，不因每个移动包触发身体查询。
        return locked.containsKey(playerId);
    }

    private static void apply(TechniqueService.StaggerChanged event) {
        // 受击僵直不锁输入；其余三种按种类记下，开始时放下手里正在用的东西（举着的盾也放下），再按剩下的种类重算修饰。
        if (!LOCKING.contains(event.kind())) {
            return;
        }
        UUID uuid = event.entityUuid();
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) {
            locked.remove(uuid);
            return;
        }
        Set<StaggerKind> kinds = locked.computeIfAbsent(uuid, ignored -> EnumSet.noneOf(StaggerKind.class));
        if (event.remainingMillis() > 0) {
            kinds.add(event.kind());
            player.stopUsingItem();
        } else {
            kinds.remove(event.kind());
        }
        if (kinds.isEmpty()) {
            locked.remove(uuid);
        }
        restrain(player, kinds);
    }

    private static void restrain(ServerPlayer player, Set<StaggerKind> kinds) {
        // 只保留与当前种类相符的修饰：定身的两种移速归零，三种都不能跳。
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        speed.removeModifier(SPEED_ID);
        jump.removeModifier(SPEED_ID);
        if (kinds.stream().anyMatch(IMMOBILIZING::contains)) {
            speed.addTransientModifier(stopped());
        }
        if (!kinds.isEmpty()) {
            jump.addTransientModifier(stopped());
        }
    }

    private static AttributeModifier stopped() {
        // 乘总值减到 0。
        return new AttributeModifier(SPEED_ID, STOPPED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }
}
