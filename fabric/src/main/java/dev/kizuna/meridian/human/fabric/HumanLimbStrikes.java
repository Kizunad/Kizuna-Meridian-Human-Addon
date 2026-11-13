package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueSounds;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ArmSelection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeFacing;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.server.TechniqueLimbStrikes;
import dev.kizuna.meridian.technique.server.TechniqueService.StrikeOutcome;
import dev.kizuna.meridian.technique.server.TechniqueService.StrikeReport;
import dev.kizuna.meridian.technique.server.TechniqueStrikeTargets;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 拳掌与腿共用的打击执行器；施放者可以是玩家或生物，敌我、声明目标和鞋由宿主给出。 */
public final class HumanLimbStrikes {
    /** 双手出拳在单手剂量上再乘的倍数。 */
    static final double BOTH_ARMS_FACTOR = 1.2;
    private static final double HALF = 0.5;
    private static final double TARGET_MARGIN = 0.1;
    private static final double SINGLE_TARGET_ANGLE = 60;
    private static final double BACKWARD_YAW = 180;
    private static final float ATTACK_SOUND_VOLUME = 1.0F;
    private static final float ATTACK_SOUND_PITCH = 1.0F;
    private final LivingEntity actor;
    private final Host host;
    private final Map<String, Map<String, Set<String>>> casts = new HashMap<>();

    public HumanLimbStrikes(LivingEntity actor, Host host) {
        // 每个施放者一份执行器，同一施放的去重组跨多次打击保留到收势。
        this.actor = actor;
        this.host = host;
    }

    public void strike(EffectIntent effect) {
        // 腿击意图按线格式换成肢击意图，拳与腿此后走同一段代码。
        LimbStrikeIntent intent = limbIntent(effect);
        LimbStrike strike = intent.getStrike();
        Map<String, Set<String>> groups = casts.computeIfAbsent(effect.getCastId(), ignored -> new HashMap<>());
        DamageSource source = declaredDamageSource(strike.getDamageTypeId());
        double dose = dose(intent);
        actor.swing(swingHand(strike));
        playAttackSound();
        List<LivingEntity> candidates = candidates(effect, strike);
        List<String> selected = TechniqueStrikeTargets.select(strike, groups,
                candidates.stream().map(LivingEntity::getStringUUID).toList());
        groups.computeIfAbsent(strike.getDedupGroup(), ignored -> new HashSet<>()).addAll(selected);
        Hit hit = new Hit(effect, strike, source, (float) dose, intent.getHitSoundsList());
        try (var ignored = VanillaStrikeContext.attack(actor, source)) {
            for (LivingEntity target : candidates) {
                if (selected.contains(target.getStringUUID())) {
                    hitTarget(hit, target);
                }
            }
        }
        boolean wearsBoots = strike.getLimbCase() == LimbStrike.LimbCase.LEG && strike.getLastStrike();
        if (wearsBoots) {
            host.wearBoots(intent.getLegRules().getDurabilityCost());
        }
    }

    public boolean actsFor(LivingEntity entity) {
        // 宿主按实体缓存执行器时，用它判断缓存的是不是同一个实体。
        return actor == entity;
    }

    public void finish(String castId) {
        // 收势或取消后释放本次施放的去重预算，下一次施放从空集合开始。
        casts.remove(castId);
    }

    private static LimbStrikeIntent limbIntent(EffectIntent effect) {
        // 只接受两种打击意图；其他意图交到这里说明宿主分派写错了。
        if (effect.hasLimbStrike()) {
            return effect.getLimbStrike();
        }
        if (effect.hasLegStrike()) {
            return TechniqueLimbStrikes.fromLeg(effect.getLegStrike());
        }
        throw new IllegalArgumentException("肢击执行器只接受腿击或肢击意图");
    }

    private double dose(LimbStrikeIntent intent) {
        // 腿取书级基础伤害加靴子护甲项；拳只取书级空手伤害，双手再乘 1.2；最后乘本击倍率。
        LimbStrike strike = intent.getStrike();
        double multiplier = strike.getMultiplier().getValue();
        if (strike.getLimbCase() == LimbStrike.LimbCase.LEG) {
            var rules = intent.getLegRules();
            double base = rules.getBaseDamage().getValue() + rules.getArmorDamage().getValue() * host.bootArmor();
            return base * multiplier;
        }
        double base = intent.getArmRules().getBaseDamage().getValue() * multiplier;
        if (strike.getArm() == ArmSelection.BOTH_ARMS) {
            return base * BOTH_ARMS_FACTOR;
        }
        return base;
    }

    private static InteractionHand swingHand(LimbStrike strike) {
        // 前手出拳挥副手，后手、双手和腿都挥主手；肌肉侧由伤害类型标签决定，不看这里。
        if (strike.getLimbCase() == LimbStrike.LimbCase.ARM && strike.getArm() == ArmSelection.FRONT_ARM) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    private DamageSource declaredDamageSource(String damageTypeId) {
        // 目录提供完整 Damage ID，直接解析并查注册表，不拼接固定命名空间。
        Identifier identifier = Identifier.parse(damageTypeId);
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, identifier);
        Holder<DamageType> type = actor.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(key);
        return new DamageSource(type, actor);
    }

    private void hitTarget(Hit hit, LivingEntity target) {
        // 盾挡只影响伤害是否成功，不吞掉声明的被挡击退；只有真正受伤才播命中声音；打中和被挡回报给功法服务。
        boolean blocked = target.isBlocking() && facesAttacker(target);
        boolean damaged = target.hurtServer((ServerLevel) actor.level(), hit.source(), hit.dose());
        if (damaged || (blocked && hit.strike().getKnockbackWhenBlocked())) {
            HumanLimbKnockback.apply(new HumanLimbKnockback.Push(actor, target, hit.strike(), hit.source(),
                    hit.dose(), facing(hit.strike())));
        }
        if (damaged) {
            TechniqueSounds.playAll(actor, target, hit.hitSounds());
            report(hit.effect(), target, StrikeOutcome.HIT);
        } else if (blocked) {
            report(hit.effect(), target, StrikeOutcome.BLOCKED);
        }
    }

    private void report(EffectIntent effect, LivingEntity target, StrikeOutcome outcome) {
        // 意图带了所属式才回报；旧存档承接来的意图没有式，打击照常结算但不报。
        if (!effect.hasTechnique()) {
            return;
        }
        TechniqueFabric.service().reportStrike(new StrikeReport(effect.getCastId(), effect.getTechnique(),
                actor.getUUID(), target.getUUID(), outcome));
    }

    private boolean facesAttacker(LivingEntity target) {
        // 盾只保护正面，背后命中不能触发被挡时击退分支。
        Vec3 toward = actor.position().subtract(target.position()).multiply(1, 0, 1);
        Vec3 view = target.getViewVector(1).multiply(1, 0, 1);
        if (toward.lengthSqr() == 0 || view.lengthSqr() == 0) {
            return false;
        }
        return view.normalize().dot(toward.normalize()) > 0;
    }

    private void playAttackSound() {
        // 拳脚没有原版武器分类，统一播放一次服务端重击声，类别由宿主按施放者给出。
        actor.level().playSound(null, actor.getX(), actor.getY(), actor.getZ(), SoundEvents.PLAYER_ATTACK_STRONG,
                host.soundSource(), ATTACK_SOUND_VOLUME, ATTACK_SOUND_PITCH);
    }

    private List<LivingEntity> candidates(EffectIntent effect, LimbStrike strike) {
        // 单体先取宿主声明的目标，再取视线内第一个；范围打击按朝向、距离和形状稳定排序。
        double distance = strike.getDistance().getValue();
        List<LivingEntity> nearby = actor.level().getEntitiesOfClass(LivingEntity.class,
                        actor.getBoundingBox().inflate(distance), this::eligible)
                .stream()
                .sorted(Comparator.<LivingEntity>comparingDouble(actor::distanceToSqr)
                        .thenComparing(LivingEntity::getStringUUID))
                .toList();
        if (strike.getShape() == StrikeShape.SINGLE_TARGET) {
            Optional<LivingEntity> single = host.declaredTarget(effect, distance)
                    .filter(this::eligible)
                    .or(() -> aimed(nearby, strike));
            if (single.isPresent()) {
                return List.of(single.get());
            }
        }
        return nearby.stream()
                .filter(target -> actor.distanceToSqr(target) <= distance * distance)
                .filter(target -> inSector(target, strike))
                .toList();
    }

    private boolean eligible(LivingEntity target) {
        // 目标资格：活着、看得见、宿主认作可打；标记盔甲架不算实体目标。
        if (target == actor || !target.isAlive() || target.isSpectator() || !host.hittable(target)
                || !actor.hasLineOfSight(target)) {
            return false;
        }
        return !(target instanceof ArmorStand stand) || !stand.isMarker();
    }

    private Optional<LivingEntity> aimed(List<LivingEntity> nearby, LimbStrike strike) {
        // 单体打击取打击方向上视线内第一个目标，方块挡住的部分不算。
        double distance = strike.getDistance().getValue();
        Vec3 start = actor.getEyePosition();
        Vec3 end = start.add(facing(strike).scale(distance));
        var block = actor.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, actor));
        Vec3 limit = block.getLocation();
        return nearby.stream()
                .filter(target -> target.getBoundingBox().inflate(TARGET_MARGIN).clip(start, limit).isPresent())
                .min(Comparator.comparingDouble(target -> target.getBoundingBox().inflate(TARGET_MARGIN)
                        .clip(start, limit).orElseThrow().distanceToSqr(start)));
    }

    private boolean inSector(LivingEntity target, LimbStrike strike) {
        // 扇形只比较水平夹角，目标高度仍由最大距离和视线过滤控制。
        Vec3 delta = target.position().subtract(actor.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() == 0) {
            return true;
        }
        double angle = strike.getAngle().getValue();
        if (strike.getShape() == StrikeShape.SINGLE_TARGET) {
            angle = SINGLE_TARGET_ANGLE;
        }
        double threshold = Math.cos(Math.toRadians(angle * HALF));
        return facing(strike).dot(delta.normalize()) >= threshold;
    }

    private Vec3 facing(LimbStrike strike) {
        // 身后打击把方向翻转 180 度，其余沿施放者当前水平朝向。
        double yaw = actor.getYRot();
        if (strike.getFacing() == StrikeFacing.STRIKE_BACKWARD) {
            yaw += BACKWARD_YAW;
        }
        return Vec3.directionFromRotation(0, (float) yaw);
    }

    /** 施放者一侧的差别：敌我、声明目标、靴子和声音类别。 */
    public interface Host {
        /** 宿主的敌我规则认为这个目标可以打。 */
        boolean hittable(LivingEntity target);

        /** 单体打击优先命中的声明目标；玩家没有声明目标时为空。 */
        Optional<LivingEntity> declaredTarget(EffectIntent effect, double reach);

        /** 腿法剂量里计入的靴子护甲点数；没有靴子事实的宿主给 0。 */
        double bootArmor();

        /** 腿法末击结算一次鞋耐久；创造模式、光脚和不磨鞋的宿主什么也不做。 */
        void wearBoots(int durabilityCost);

        /** 施放者重击声的声音类别。 */
        SoundSource soundSource();
    }

    private record Hit(EffectIntent effect, LimbStrike strike, DamageSource source, float dose,
                       List<SoundCue> hitSounds) { }
}
