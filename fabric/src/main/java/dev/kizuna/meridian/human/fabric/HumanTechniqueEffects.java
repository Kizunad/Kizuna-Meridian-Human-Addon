package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldEffects;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldImpulses;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueMovement;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 正常施放与未认领恢复共用的实体效果执行器，回执只在当前服务器连接内有效。 */
final class HumanTechniqueEffects {
    private static final double TICKS_PER_SECOND = 20;
    // 26.x 的游戏规则 ID 只允许小写路径，旧名 meridianTechniquePlayerBreakBlocks 改为下划线写法。
    private static final GameRule<Boolean> PLAYER_BREAK_BLOCKS = GameRuleBuilder.forBoolean(true /* defaultValue */)
            .category(GameRuleCategory.PLAYER)
            .buildAndRegister(Identifier.fromNamespaceAndPath("meridian_human", "technique_player_break_blocks"));
    private final Map<UUID, TechniqueWorldEffects> worldEffects = new HashMap<>();
    private final Map<UUID, HumanLegStrikeEffects> legEffects = new HashMap<>();
    // 每个玩家正在施放的 cast ID 及该式是否放行右键。
    private final Map<UUID, Map<String, Boolean>> castingIds = new HashMap<>();
    private final Map<UUID, Map<String, EffectIntent>> requests = new HashMap<>();
    private final Map<UUID, Map<EffectKey, EffectIntent>> effects = new HashMap<>();
    private final Map<UUID, Map<String, EffectIntent>> executingRequests = new HashMap<>();
    private final Map<UUID, Map<EffectKey, EffectIntent>> executingEffects = new HashMap<>();

    TechniqueHost.EffectCommit submit(ServerPlayer player, TechniqueHost.EffectSubmission submission) {
        // 身份由调用适配器认证；两种稳定身份都检查内容，失败不留下成功回执。
        if (!player.level().getServer().isSameThread()) {
            throw new TechniqueErrors.HostRejected(FailureCode.ACCESS_DENIED);
        }
        EffectIntent effect = submission.effect();
        TechniqueProtocol.validateEffect(effect);
        UUID playerId = player.getUUID();
        String requestId = submission.requestId();
        Map<String, EffectIntent> receipts = requests.computeIfAbsent(playerId, id -> new HashMap<>());
        Map<EffectKey, EffectIntent> identities = effects.computeIfAbsent(playerId, id -> new HashMap<>());
        String effectId = effect.getEffectId();
        if (effectId.isEmpty()) {
            effectId = requestId;
        }
        var key = new EffectKey(effect.getCastId(), effectId);
        EffectIntent byRequest = receipts.get(requestId);
        EffectIntent byEffect = identities.get(key);
        rejectConflict(byRequest, effect);
        rejectConflict(byEffect, effect);
        if (byRequest == null && byEffect == null) {
            executeOnce(player, submission, key);
        }
        receipts.put(requestId, effect);
        identities.put(key, effect);
        return new TechniqueHost.EffectCommit(requestId);
    }

    private void executeOnce(ServerPlayer player, TechniqueHost.EffectSubmission submission, EffectKey key) {
        // 执行中只用于阻止同步重入，不能被其他请求当作成功回执。
        UUID playerId = player.getUUID();
        String requestId = submission.requestId();
        EffectIntent effect = submission.effect();
        Map<String, EffectIntent> requests = executingRequests.computeIfAbsent(playerId, id -> new HashMap<>());
        Map<EffectKey, EffectIntent> identities = executingEffects.computeIfAbsent(playerId, id -> new HashMap<>());
        EffectIntent byRequest = requests.get(requestId);
        EffectIntent byEffect = identities.get(key);
        rejectConflict(byRequest, effect);
        rejectConflict(byEffect, effect);
        if (byRequest != null || byEffect != null) {
            throw new TechniqueErrors.HostRejected(FailureCode.INTERNAL_ERROR);
        }
        requests.put(requestId, effect);
        identities.put(key, effect);
        try {
            execute(player, submission);
        } finally {
            requests.remove(requestId);
            identities.remove(key);
        }
    }

    private static void rejectConflict(EffectIntent previous, EffectIntent effect) {
        // 同一请求或施放内效果 ID 不允许换内容，避免副作用被重做。
        if (previous != null && !previous.equals(effect)) {
            throw new TechniqueErrors.HostRejected(FailureCode.REQUEST_ID_CONFLICT);
        }
    }

    private void execute(ServerPlayer player, TechniqueHost.EffectSubmission submission) {
        // 移速修正不接管速度或跳跃，冲量仍通过原版跟踪器同步。
        EffectIntent effect = submission.effect();
        if (effect.hasStatusBenefit()) {
            TechniqueStatusBenefits.apply(player, effect.getStatusBenefit());
        } else if (effect.hasImpulse()) {
            Vec3 direction = impulseDirection(player, effect);
            double speedPerTick = effect.getImpulse().getSpeed().getValue() / TICKS_PER_SECOND;
            TechniqueWorldImpulses.apply(player, effect.getImpulse().getDirection(),
                    direction.scale(speedPerTick));
            HumanTechniqueHost.markImpulseMovement(player.getUUID());
        } else if (effect.hasMovement()) {
            movement(player, effect);
            updateCasting(player, effect);
        } else if (effect.hasPresentation()) {
            HumanTechniquePresentation.apply(player, effect);
            HumanTechniquePresentation.EVENT.invoker().apply(player, submission.requestId(), effect);
        } else if (effect.hasLegStrike()) {
            legWorld(player).strike(player, effect);
        } else if (effect.hasWeaponStrike()) {
            world(player).strike(effect);
        } else if (effect.hasBreakBlocks()) {
            world(player).breakBlocks(effect);
        } else {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
    }

    static void initialize() {
        // 初始化时注册玩家劈门总开关，不能等世界规则已经创建后再注册。
    }

    boolean isCasting(UUID playerId) {
        // 拦截原版攻击只读取已成功执行的施放约束。
        return castingIds.containsKey(playerId);
    }

    boolean blocksUse(UUID playerId) {
        // 同一玩家登记了多个施放时，任一式没声明放行右键就拦。
        Map<String, Boolean> casts = castingIds.get(playerId);
        return casts != null && casts.containsValue(false);
    }

    private void updateCasting(ServerPlayer player, EffectIntent effect) {
        // 承接先结束旧式再开始新式，施放状态按稳定身份独立维护。
        UUID playerId = player.getUUID();
        if (effect.getMovement().getAction() == MovementAction.LOCK) {
            castingIds.computeIfAbsent(playerId, id -> new HashMap<>())
                    .put(effect.getCastId(), effect.getMovement().getAllowUseWhileCasting());
        } else {
            Map<String, Boolean> casts = castingIds.get(playerId);
            if (casts != null) {
                casts.remove(effect.getCastId());
                if (casts.isEmpty()) {
                    castingIds.remove(playerId);
                }
            }
            finish(player, effect.getCastId());
        }
    }

    private TechniqueWorldEffects world(ServerPlayer player) {
        // 世界工具调用 Damage 的一次出手上下文，横扫多目标共享做功前系数。
        // 框架不预筛敌我；玩家不打原版同伴（同队、自己驯服的动物），/team 分开后照常可打。
        return worldEffects.computeIfAbsent(player.getUUID(), id -> new TechniqueWorldEffects(player,
                () -> player.level().getGameRules().get(PLAYER_BREAK_BLOCKS)
                        ? TechniqueWorldEffects.Policy.PLAYER : TechniqueWorldEffects.Policy.PLAYER_NO_BREAK,
                target -> !player.isAlliedTo(target),
                (actor, source, strike) -> {
                    try (var ignored = VanillaStrikeContext.attack(actor, source)) {
                        strike.run();
                    }
                }, "meridian_door_breaker"));
    }

    private static void movement(ServerPlayer player, EffectIntent effect) {
        // 每个施放有固定属性身份，重复移除限速不影响其他施放。
        var attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        // 修正身份改为 Identifier；施放 ID 可能含路径不允许的字符，仍用它派生的 UUID 作路径。
        UUID castUuid = UUID.nameUUIDFromBytes(
                ("meridian_human:technique_movement:" + effect.getCastId()).getBytes(StandardCharsets.UTF_8));
        Identifier modifierId = Identifier.fromNamespaceAndPath("meridian_human", "technique_movement/" + castUuid);
        AttributeModifier existing = attribute.getModifier(modifierId);
        if (effect.getMovement().getAction() == MovementAction.LOCK) {
            if (existing == null) {
                var modifier = new AttributeModifier(modifierId,
                        TechniqueMovement.multiplier(effect.getMovement()) - 1,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                attribute.addTransientModifier(modifier);
            }
        } else {
            if (existing == null) {
                return;
            }
            attribute.removeModifier(modifierId);
        }
        // 本人立即收到属性；旁观者仍由原版实体跟踪器消费属性脏标记。
        player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), List.of(attribute)));
    }

    static void validateRecovery(TechniqueHost.EffectSubmission submission) {
        // 恢复只允许停止和解锁，禁止补发离线冲量或开始表现。
        EffectIntent effect = submission.effect();
        TechniqueProtocol.validateEffect(effect);
        if (effect.hasPresentation() && effect.getPresentation().getAction() == PresentationAction.STOP) {
            return;
        }
        if (effect.hasMovement() && effect.getMovement().getAction() == MovementAction.UNLOCK) {
            return;
        }
        throw new TechniqueErrors.HostRejected(FailureCode.ACCESS_DENIED);
    }

    void forget(UUID playerId) {
        // 生命周期取消成功后释放连接内回执，不替代 STOP/UNLOCK 的实际执行。
        requests.remove(playerId);
        effects.remove(playerId);
        executingRequests.remove(playerId);
        executingEffects.remove(playerId);
        worldEffects.remove(playerId);
        legEffects.remove(playerId);
        castingIds.remove(playerId);
        HumanTechniquePresentation.forget(playerId);
    }

    void clear() {
        // 停服取消完成后不保留上个服务器的回执。
        requests.clear();
        effects.clear();
        executingRequests.clear();
        executingEffects.clear();
        worldEffects.clear();
        legEffects.clear();
        castingIds.clear();
        HumanTechniquePresentation.clear();
    }

    private static Vec3 impulseDirection(ServerPlayer player, EffectIntent effect) {
        // 水平方向只取服务端朝向；目标方向使用同世界实体的实际位置。
        return switch (effect.getImpulse().getDirection()) {
            case UP -> new Vec3(0, 1, 0);
            case FORWARD -> Vec3.directionFromRotation(0, player.getYRot());
            case BACKWARD -> Vec3.directionFromRotation(0, player.getYRot()).reverse();
            case INPUT -> inputDirection(player, effect);
            case TOWARD_TARGET -> targetDirection(player, effect.getTargetId());
            default -> throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        };
    }

    private static Vec3 inputDirection(ServerPlayer player, EffectIntent effect) {
        // 最大分量缩放避免平方溢出；零输入按声明选择前后方向。
        var input = effect.getDirection();
        double scale = Math.max(Math.abs(input.getX()), Math.abs(input.getZ()));
        if (scale == 0) {
            Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
            if (effect.getImpulse().getInputDefaultDirection() == ImpulseDirection.BACKWARD) {
                return forward.reverse();
            }
            return forward;
        }
        return new Vec3(input.getX() / scale, 0, input.getZ() / scale).normalize();
    }

    private static Vec3 targetDirection(ServerPlayer player, String targetId) {
        // 朝目标推进只归一化水平位移；目标无效不记录回执，允许服务用原身份重试。
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetId);
        } catch (IllegalArgumentException invalid) {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        Entity target = player.level().getEntity(targetUuid);
        if (target == null || target.isRemoved()) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        Vec3 direction = target.position().subtract(player.position()).multiply(1, 0, 1);
        if (direction.lengthSqr() == 0) {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        return direction.normalize();
    }

    private record EffectKey(String castId, String effectId) { }

    private HumanLegStrikeEffects legWorld(ServerPlayer player) {
        // 腿击使用目录中的完整伤害类型 ID，独立于持物打击的旧世界执行器。
        return legEffects.computeIfAbsent(player.getUUID(), id -> new HumanLegStrikeEffects());
    }

    private void finish(ServerPlayer player, String castId) {
        // 收势同时释放两类打击的去重游标，并重置原版攻击冷却。
        world(player).finish(castId);
        UUID playerId = player.getUUID();
        HumanLegStrikeEffects legWorld = legEffects.get(playerId);
        if (legWorld != null) {
            legWorld.finish(castId);
        }
    }
}
