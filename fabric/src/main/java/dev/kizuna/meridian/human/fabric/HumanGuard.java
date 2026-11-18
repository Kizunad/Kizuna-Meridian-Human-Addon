package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.GuardMotion;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.Optional;

/** 玩家守式只接准入和表现；持续阶段与防御事实分别由功法库和 Damage 提供。 */
final class HumanGuard {
    private static MinecraftServer server;

    private HumanGuard() {
        // 宿主不保存第二份权威守状态。
    }

    static void initialize() {
        // 准入先于 Movement LOCK，已举的盾不会因拒绝起式而被放下。
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> server = starting);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
        TechniqueFabric.SERVICE_STARTED.register(service -> {
            service.addCastCheck((entity, request) -> {
                ServerPlayer player = server.getPlayerList().getPlayer(entity);
                if (player != null && isGuard(request.getTechnique()) && player.isUsingItem()
                        && player.getUseItem().getUseAnimation() == ItemUseAnimation.BLOCK) {
                    return Optional.of(HumanLang.GUARD_SHIELD_RAISED);
                }
                return Optional.empty();
            });
            service.addCastStateListener(event -> {
                ServerPlayer player = server.getPlayerList().getPlayer(event.entityUuid());
                var active = event.active();
                if (player != null && active.hasSustained() && event.phase() == CastPhase.RECOVERING) {
                    HumanTechniquePresentation.lowerGuard(player, active.getRequest().getCastId());
                }
            });
        });
        VanillaDamageFabric.registerDamageObserver(HumanGuard::hit);
    }

    static boolean isGuard(TechniqueReference technique) {
        // 精确式来自服务端 Movement 意图，不能用移速倍率推断守式。
        return TechniqueFabric.service().registry().find(technique).stream()
                .flatMap(definition -> definition.getEffectsList().stream()).anyMatch(TechniqueEffect::hasGuard);
    }

    private static void hit(VanillaDamageFabric.DamageEvent event) {
        // 只消费判定时冻结的身份；不查询事后守状态，也不让旧命中恢复已经停止的姿态。
        if (!(event.target() instanceof ServerPlayer player)) {
            return;
        }
        var body = event.result().body();
        if (body.getVitality().getDead()) {
            return;
        }
        event.input().guarded().ifPresent(guard -> {
            if (guard.bodyId().equals(body.getId())
                    && HumanTechniqueHost.hasCast(player.getUUID(), guard.castId())) {
                HumanTechniquePresentation.guardMotion(player, guard.castId(), GuardMotion.HIT);
            }
        });
    }
}
