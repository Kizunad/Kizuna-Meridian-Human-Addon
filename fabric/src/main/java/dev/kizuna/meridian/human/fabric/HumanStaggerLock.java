package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 魅惑定身的玩家输入门禁；受击僵直仅由功法库施加减速。 */
public final class HumanStaggerLock {
    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath("meridian_human", "stagger_lock");
    private static final Set<UUID> locked = new HashSet<>();
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
        return locked.contains(playerId);
    }

    private static void apply(TechniqueService.StaggerChanged event) {
        // 只锁魅惑的主动移动与跳跃，保留下落、击退和转头的原版物理。
        if (event.kind() != TechniqueService.StaggerKind.CHARM) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(event.entityUuid());
        if (player == null) {
            locked.remove(event.entityUuid());
            return;
        }
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        speed.removeModifier(SPEED_ID);
        jump.removeModifier(SPEED_ID);
        if (event.remainingMillis() > 0) {
            locked.add(event.entityUuid());
            player.stopUsingItem();
            speed.addTransientModifier(new AttributeModifier(SPEED_ID, -1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            jump.addTransientModifier(new AttributeModifier(SPEED_ID, -1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else {
            locked.remove(event.entityUuid());
        }
    }
}
