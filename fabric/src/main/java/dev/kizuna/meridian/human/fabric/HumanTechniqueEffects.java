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
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 正常施放与未认领恢复共用的实体效果执行器，回执只在当前服务器连接内有效。 */
final class HumanTechniqueEffects {
    private static final double TICKS_PER_SECOND = 20;
    private static final GameRules.Key<GameRules.BooleanRule> PLAYER_BREAK_BLOCKS = GameRuleRegistry.register(
            "meridianTechniquePlayerBreakBlocks", GameRules.Category.PLAYER,
            GameRuleFactory.createBooleanRule(true /* defaultValue */));
    private final Map<UUID, TechniqueWorldEffects> worldEffects = new HashMap<>();
    private final Map<UUID, Set<String>> castingIds = new HashMap<>();
    private final Map<UUID, Map<String, EffectIntent>> requests = new HashMap<>();
    private final Map<UUID, Map<EffectKey, EffectIntent>> effects = new HashMap<>();
    private final Map<UUID, Map<String, EffectIntent>> executingRequests = new HashMap<>();
    private final Map<UUID, Map<EffectKey, EffectIntent>> executingEffects = new HashMap<>();

    TechniqueHost.EffectCommit submit(ServerPlayerEntity player, TechniqueHost.EffectSubmission submission) {
        // 身份由调用适配器认证；两种稳定身份都检查内容，失败不留下成功回执。
        if (!player.getServer().isOnThread()) {
            throw new TechniqueErrors.HostRejected(FailureCode.ACCESS_DENIED);
        }
        EffectIntent effect = submission.effect();
        TechniqueProtocol.validateEffect(effect);
        UUID playerId = player.getUuid();
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

    private void executeOnce(ServerPlayerEntity player, TechniqueHost.EffectSubmission submission, EffectKey key) {
        // 执行中只用于阻止同步重入，不能被其他请求当作成功回执。
        UUID playerId = player.getUuid();
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

    private void execute(ServerPlayerEntity player, TechniqueHost.EffectSubmission submission) {
        // 移速修正不接管速度或跳跃，冲量仍通过原版跟踪器同步。
        EffectIntent effect = submission.effect();
        if (effect.hasStatusBenefit()) {
            TechniqueStatusBenefits.apply(player, effect.getStatusBenefit());
        } else if (effect.hasImpulse()) {
            Vec3d direction = impulseDirection(player, effect);
            double speedPerTick = effect.getImpulse().getSpeed().getValue() / TICKS_PER_SECOND;
            TechniqueWorldImpulses.apply(player, effect.getImpulse().getDirection(),
                    direction.multiply(speedPerTick));
        } else if (effect.hasMovement()) {
            movement(player, effect);
            updateCasting(player, effect);
        } else if (effect.hasPresentation()) {
            HumanTechniquePresentation.apply(player, effect);
            HumanTechniquePresentation.EVENT.invoker().apply(player, submission.requestId(), effect);
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

    private void updateCasting(ServerPlayerEntity player, EffectIntent effect) {
        // 承接先结束旧式再开始新式，施放状态按稳定身份独立维护。
        UUID playerId = player.getUuid();
        if (effect.getMovement().getAction() == MovementAction.LOCK) {
            castingIds.computeIfAbsent(playerId, id -> new HashSet<>()).add(effect.getCastId());
        } else {
            Set<String> casts = castingIds.get(playerId);
            if (casts != null) {
                casts.remove(effect.getCastId());
                if (casts.isEmpty()) {
                    castingIds.remove(playerId);
                }
            }
            world(player).finish(effect.getCastId());
        }
    }

    private TechniqueWorldEffects world(ServerPlayerEntity player) {
        // 世界工具调用 Damage 的一次出手上下文，横扫多目标共享做功前系数。
        return worldEffects.computeIfAbsent(player.getUuid(), id -> new TechniqueWorldEffects(player,
                () -> player.getWorld().getGameRules().getBoolean(PLAYER_BREAK_BLOCKS)
                        ? TechniqueWorldEffects.Policy.PLAYER : TechniqueWorldEffects.Policy.PLAYER_NO_BREAK,
                target -> true,
                (actor, source, strike) -> {
                    try (var ignored = VanillaStrikeContext.attack(actor, source)) {
                        strike.run();
                    }
                }, "meridian_door_breaker"));
    }

    private static void movement(ServerPlayerEntity player, EffectIntent effect) {
        // 每个施放有固定属性身份，重复移除限速不影响其他施放。
        var attribute = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (attribute == null) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        UUID modifierId = UUID.nameUUIDFromBytes(
                ("meridian_human:technique_movement:" + effect.getCastId()).getBytes(StandardCharsets.UTF_8));
        EntityAttributeModifier existing = attribute.getModifier(modifierId);
        if (effect.getMovement().getAction() == MovementAction.LOCK) {
            if (existing == null) {
                var modifier = new EntityAttributeModifier(modifierId, "meridian_human:technique_movement",
                        TechniqueMovement.multiplier(effect.getMovement()) - 1,
                        EntityAttributeModifier.Operation.MULTIPLY_TOTAL);
                attribute.addTemporaryModifier(modifier);
            }
        } else {
            if (existing == null) {
                return;
            }
            attribute.removeModifier(modifierId);
        }
        // 本人立即收到属性；旁观者仍由原版实体跟踪器消费属性脏标记。
        player.networkHandler.sendPacket(new EntityAttributesS2CPacket(player.getId(), List.of(attribute)));
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
        castingIds.clear();
        HumanTechniquePresentation.clear();
    }

    private static Vec3d impulseDirection(ServerPlayerEntity player, EffectIntent effect) {
        // 水平方向只取服务端朝向；目标方向使用同世界实体的实际位置。
        return switch (effect.getImpulse().getDirection()) {
            case UP -> new Vec3d(0, 1, 0);
            case FORWARD -> Vec3d.fromPolar(0, player.getYaw());
            case BACKWARD -> Vec3d.fromPolar(0, player.getYaw()).negate();
            case INPUT -> inputDirection(player, effect);
            case TOWARD_TARGET -> targetDirection(player, effect.getTargetId());
            default -> throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        };
    }

    private static Vec3d inputDirection(ServerPlayerEntity player, EffectIntent effect) {
        // 最大分量缩放避免平方溢出；零输入按声明选择前后方向。
        var input = effect.getDirection();
        double scale = Math.max(Math.abs(input.getX()), Math.abs(input.getZ()));
        if (scale == 0) {
            Vec3d forward = Vec3d.fromPolar(0, player.getYaw());
            if (effect.getImpulse().getInputDefaultDirection() == ImpulseDirection.BACKWARD) {
                return forward.negate();
            }
            return forward;
        }
        return new Vec3d(input.getX() / scale, 0, input.getZ() / scale).normalize();
    }

    private static Vec3d targetDirection(ServerPlayerEntity player, String targetId) {
        // 朝目标推进只归一化水平位移；目标无效不记录回执，允许服务用原身份重试。
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetId);
        } catch (IllegalArgumentException invalid) {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        Entity target = player.getServerWorld().getEntity(targetUuid);
        if (target == null || target.isRemoved()) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        Vec3d direction = target.getPos().subtract(player.getPos()).multiply(1, 0, 1);
        if (direction.lengthSquared() == 0) {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        return direction.normalize();
    }

    private record EffectKey(String castId, String effectId) { }
}
