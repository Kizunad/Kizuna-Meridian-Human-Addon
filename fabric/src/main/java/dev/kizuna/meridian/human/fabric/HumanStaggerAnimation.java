package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueService.StaggerKind;
import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.Set;

/**
 * 玩家僵直时的动作：受击僵直和魅惑定身开始时播 PA 的僵直剪辑，结束时停。
 * 剪辑 0.8 秒后停在末帧，不会自己结束，所以两种都结束了才停。倒地有爬行姿势，被擒由擒拿那边表现，都不播。
 */
final class HumanStaggerAnimation {
    private static final Set<StaggerKind> SHOWN = EnumSet.of(StaggerKind.HIT, StaggerKind.CHARM);
    private static MinecraftServer server;

    private HumanStaggerAnimation() {
        // 不保存僵直状态，剩余时长只问功法服务。
    }

    static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> server = starting);
        TechniqueFabric.SERVICE_STARTED.register(service ->
                service.addStaggerListener(event -> apply(service, event)));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
    }

    private static void apply(TechniqueService service, TechniqueService.StaggerChanged event) {
        if (!SHOWN.contains(event.kind())) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(event.entityUuid());
        if (player == null) {
            return;
        }
        if (event.remainingMillis() > 0) {
            // 僵直中又挨一下会重新收到开始事件：重播就是再踉跄一次。
            ServerPlayerAnimations.play(player, PlayerAnimationAction.STATUS_STAGGER);
            return;
        }
        for (StaggerKind kind : SHOWN) {
            if (service.staggerRemainingMillis(event.entityUuid(), kind) > 0) {
                return;
            }
        }
        ServerPlayerAnimations.stop(player, PlayerAnimationAction.STATUS_STAGGER);
    }
}
