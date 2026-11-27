package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.GuardMotion;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.GuardStyle;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.Optional;

/** 玩家守式只接准入和表现；持续阶段与防御事实分别由功法库和 Damage 提供。 */
final class HumanGuard {
    private static final String YUNXI_PARRY = "meridian_story:flash_parry";
    private static final TagKey<Item> GUARD_WEAPONS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("meridian_vanilla_damage", "guard_weapons"));
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
                if (player == null) {
                    return Optional.empty();
                }
                var reference = request.getTechnique();
                var guard = service.registry().find(reference).stream()
                        .flatMap(definition -> definition.getEffectsList().stream())
                        .filter(TechniqueEffect::hasGuard).map(TechniqueEffect::getGuard).findFirst();
                if (guard.isEmpty()) {
                    return Optional.empty();
                }
                if (player.isUsingItem() && player.getUseItem().getUseAnimation() == ItemUseAnimation.BLOCK) {
                    return Optional.of(HumanLang.GUARD_SHIELD_RAISED);
                }
                if (guard.get().hasStamina()) {
                    var body = service.statusHost(entity).orElseThrow().inspect().body();
                    return HumanGuardStamina.rejection(body, reference.getId(), guard.get().getStamina());
                }
                return Optional.empty();
            });
            service.addCastStateListener(event -> {
                ServerPlayer player = server.getPlayerList().getPlayer(event.entityUuid());
                var active = event.active();
                if (player != null && active.hasSustained() && event.phase() == CastPhase.RECOVERING) {
                    var sustained = active.getSustained();
                    String cast = active.getRequest().getCastId();
                    if (YUNXI_PARRY.equals(active.getRequest().getTechnique().getId())) {
                        boolean recoil = sustained.getGuardContact() || sustained.getExhausted();
                        GuardMotion motion = recoil ? GuardMotion.HIT : GuardMotion.LOWER;
                        HumanTechniquePresentation.exitYunxiGuard(player, cast, motion);
                    } else if (sustained.getExhausted()) {
                        GuardStyle style = style(player);
                        if (sustained.hasWeaponContactOnBreak()) {
                            style = sustained.getWeaponContactOnBreak() ? GuardStyle.WEAPON : GuardStyle.ARMS;
                        }
                        HumanTechniquePresentation.breakGuard(player, cast, style);
                    } else {
                        HumanTechniquePresentation.lowerGuard(player, cast);
                    }
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

    static GuardStyle style(ServerPlayer player) {
        // Damage 用同一物品标签检查逻辑主手；玩家惯用哪只手只影响客户端镜像。
        return player.getMainHandItem().is(GUARD_WEAPONS) ? GuardStyle.WEAPON : GuardStyle.ARMS;
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
                if (guard.guard().hasStamina()) {
                    var progress = TechniqueState.read(BodyProtobuf.fromMessage(body));
                    if (progress.getActiveCast().getSustained().getExhausted()) {
                        return;
                    }
                }
                if (YUNXI_PARRY.equals(guard.technique().getId())) {
                    HumanTechniquePresentation.exitYunxiGuard(player, guard.castId(), GuardMotion.HIT);
                    return;
                }
                HumanTechniquePresentation.refreshGuard(player);
                GuardStyle contact = event.input().weaponGuarded() ? GuardStyle.WEAPON : GuardStyle.ARMS;
                HumanTechniquePresentation.guardMotion(player, guard.castId(), GuardMotion.HIT, contact);
            }
        });
    }
}
