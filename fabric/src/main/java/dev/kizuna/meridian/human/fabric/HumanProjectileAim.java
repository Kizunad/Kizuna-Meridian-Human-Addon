package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanProjectileBallistics;
import dev.kizuna.meridian.human.server.HumanProjectileBallistics.LobProfile;
import dev.kizuna.meridian.human.server.HumanProjectileBallistics.Vector;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ConvergeProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LobProjectile;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** 施放内瞄准快照与方向求解，不存储身体或世界实体副本。 */
final class HumanProjectileAim {
    private static final double MILLIS_PER_TICK = 50;
    private static final double ARROW_EYE_OFFSET = 0.1;
    private static final double TARGET_MARGIN = 0.1;
    private static final double SNAP_DISTANCE = 32;
    private static final double TWO_PI = Math.PI * 2;
    private static final double VERTICAL_EPSILON = 0.001;
    private static final int PREDICTION_ITERATIONS = 8;
    private final LivingEntity actor;
    private final Predicate<LivingEntity> hostile;
    private final Map<String, Target> locked = new HashMap<>();
    private final Map<String, Double> arrivals = new HashMap<>();

    HumanProjectileAim(LivingEntity actor, Predicate<LivingEntity> hostile) {
        // 目标筛选由玩家或生物宿主传入，不在人体适配中推断阵营。
        this.actor = actor;
        this.hostile = hostile;
    }

    void lockTarget(EffectIntent effect) {
        // 稳定 cast 身份只允许第一次采样；目标后续消失仍保留原落点与速度。
        locked.computeIfAbsent(effect.getCastId(), ignored -> {
            double range = effect.getTargetLock().getMaximumDistance().getValue();
            Vec3 end = sightPoint(range);
            LivingEntity target = rayTarget(end);
            if (!(actor instanceof Player)) {
                LivingEntity requested = targetEntity(effect.getTargetId());
                if (requested != null && actor.distanceTo(requested) <= range) {
                    target = requested;
                }
            }
            if (target == null) {
                return new Target(end, Vec3.ZERO, effect.getScheduledMillis());
            }
            return new Target(target.getBoundingBox().getCenter(), target.getKnownMovement(),
                    effect.getScheduledMillis());
        });
    }

    Vec3 velocity(EffectIntent effect, double baseSpeed, double strength) {
        // 抛射解已经是实际所需初速，肌力只约束可达到的上限，不能求解后再乘一次。
        LaunchProjectile settings = effect.getLaunchProjectile().getProjectile();
        double speed = baseSpeed * settings.getSpeedMultiplier().getValue() * strength;
        if (settings.hasLob()) {
            LobProjectile lob = settings.getLob();
            Vec3 center = lobCenter(effect, lob.getMaximumDistance().getValue());
            Vec3 target = randomGroundPoint(center, lob.getRadius().getValue());
            var profile = profile(lob, baseSpeed * strength);
            return vector(HumanProjectileBallistics.lob(displacement(target), profile, inherited()).velocity());
        }
        if (settings.hasConverge()) {
            return convergence(effect, baseSpeed, strength);
        }
        Vec3 direction = direction(effect, speed);
        if (settings.hasLookOffset()) {
            double declaredAngle = settings.getLookOffset().getValue();
            double angle = declaredAngle;
            if (settings.getMirrorByHand() && effect.getSelectedHand() == Hand.OFF_HAND) {
                angle = -declaredAngle;
            }
            return snapped(yaw(direction, angle), settings).normalize().scale(speed);
        }
        return snapped(direction, settings).normalize().scale(speed);
    }

    private Vec3 convergence(EffectIntent effect, double baseSpeed, double strength) {
        // 首支仰射冻结共同到达时刻；其余仰射以各自撒放时刻反解剩余飞行时间。
        LaunchProjectile settings = effect.getLaunchProjectile().getProjectile();
        ConvergeProjectile converge = settings.getConverge();
        Target target = requireTarget(effect.getCastId());
        long now = nowMillis();
        LobProfile profile = profile(converge.getFallback(), baseSpeed * strength);
        if (!arrivals.containsKey(effect.getCastId()) && referenceMillis(effect) <= now) {
            // 首箭已晚于参考平射时刻，按普通抛射执行，不向身体查询过去的曲线。
            return vector(HumanProjectileBallistics.lob(displacement(target.at(now)), profile, inherited()).velocity());
        }
        double arrival = arrivals.computeIfAbsent(effect.getCastId(), ignored -> arrival(effect, baseSpeed));
        double ticks = (arrival - now) / MILLIS_PER_TICK;
        Vec3 predicted = target.at(arrival);
        var shot = HumanProjectileBallistics.converge(displacement(predicted), ticks,
                converge.getMinimumElevation().getValue(), profile, inherited());
        return vector(shot.velocity());
    }

    private double arrival(EffectIntent effect, double baseSpeed) {
        // 参考时刻是原剪辑平射时刻减承接偏移，曲线预估不得推进权威身体。
        var intent = effect.getLaunchProjectile();
        LaunchProjectile settings = intent.getProjectile();
        ConvergeProjectile converge = settings.getConverge();
        long referenceMillis = referenceMillis(effect);
        double strength = 1;
        if (settings.getMultiplyDrawStrength()) {
            strength = referenceStrength(effect, referenceMillis);
        }
        double speed = baseSpeed * converge.getReferenceSpeedMultiplier().getValue() * strength;
        Target target = requireTarget(effect.getCastId());
        double flight = flightTicks(target, referenceMillis, speed);
        return referenceMillis + flight * MILLIS_PER_TICK + converge.getArrivalDelayMillis();
    }

    private double referenceStrength(EffectIntent effect, long referenceMillis) {
        // 未来平射使用同一次施放尚待登记的曲线，当前撒放仍只读取已经生效的权威状态。
        var found = VanillaDamageFabric.inspectBody(actor);
        if (found.isEmpty()) {
            return 1;
        }
        var settings = effect.getLaunchProjectile().getProjectile();
        HumanoidArm drawArm = HumanProjectileEffects.drawArm(actor, HumanProjectileEffects.selectedHand(effect),
                settings.getDrawStrengthArm());
        BodySnapshot candidate = projectedBody(found.get(), effect.getCastId());
        return VanillaDamageFabric.armCoefficientAt(candidate, drawArm, referenceMillis);
    }

    private static BodySnapshot projectedBody(BodySnapshot snapshot, String castId) {
        // 候选仅补当前施放未登记且未到期的曲线；取消、换式和死体不能借用旧曲线。
        var body = BodyProtobuf.fromMessage(snapshot);
        var progress = TechniqueState.read(body);
        if (body.vitality().dead() || !progress.hasActiveCast()) {
            return snapshot;
        }
        var active = progress.getActiveCast();
        if (!active.getRequest().getCastId().equals(castId)) {
            return snapshot;
        }
        var candidate = snapshot.toBuilder();
        long now = body.settledAt().elapsedMillis();
        for (var modifier : active.getPendingModifiersList()) {
            long duration = modifier.getCurve(modifier.getCurveCount() - 1).getOffsetMillis();
            long end = Math.addExact(modifier.getStartMillis(), duration);
            if (end > now) {
                candidate.addModifiers(modifier);
            }
        }
        return candidate.build();
    }

    private static long referenceMillis(EffectIntent effect) {
        // 承接偏移只换算一次；迟到的首箭不能请求身体接口回溯已经过去的时刻。
        var intent = effect.getLaunchProjectile();
        return intent.getCastStartedMillis() + intent.getProjectile().getConverge().getReferenceShotMillis()
                - intent.getClipStartMillis();
    }

    private Vec3 direction(EffectIntent effect, double speed) {
        // 玩家普通射击跟随撒放视线；锁定或生物目标按速度外推并补偿空气弹道。
        Target target = null;
        long now = nowMillis();
        if (effect.getLockTarget()) {
            target = requireTarget(effect.getCastId());
        } else if (!(actor instanceof Player)) {
            LivingEntity entity = targetEntity(effect.getTargetId());
            if (entity != null) {
                target = new Target(entity.getBoundingBox().getCenter(), entity.getKnownMovement(), now);
            }
        }
        if (target == null) {
            return actor.getLookAngle();
        }
        double flight = flightTicks(target, now, speed);
        Vec3 point = target.at(now + flight * MILLIS_PER_TICK);
        Vec3 total = vector(HumanProjectileBallistics.velocityTo(displacement(point), flight));
        return total.subtract(HumanProjectileEffects.inheritedMovement(actor)).normalize();
    }

    private double flightTicks(Target target, double releaseMillis, double speed) {
        // 迭代提前量只读起手速度；目标受碰撞或主动变向不被当作跟踪制导。
        double ticks = 1;
        Vector movement = inherited();
        for (int iteration = 0; iteration < PREDICTION_ITERATIONS; iteration++) {
            Vec3 point = target.at(releaseMillis + ticks * MILLIS_PER_TICK);
            ticks = HumanProjectileBallistics.directFlightTicks(displacement(point), speed, movement);
        }
        return ticks;
    }

    private Vec3 snapped(Vec3 direction, LaunchProjectile settings) {
        // 每个扇区独立选最近目标，偏角是中心两侧的半角，墙后目标不吸附。
        if (!settings.hasTargetSnapAngle()) {
            return direction;
        }
        double cosine = Math.cos(Math.toRadians(settings.getTargetSnapAngle().getValue()));
        Vec3 origin = origin();
        LivingEntity target = actor.level().getEntitiesOfClass(LivingEntity.class,
                        actor.getBoundingBox().inflate(SNAP_DISTANCE), this::eligible).stream()
                .filter(actor::hasLineOfSight)
                .filter(entity -> origin.distanceTo(entity.getBoundingBox().getCenter()) <= SNAP_DISTANCE)
                .filter(entity -> entity.getBoundingBox().getCenter().subtract(origin).normalize().dot(direction)
                        >= cosine)
                .min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
        if (target == null) {
            return direction;
        }
        return target.getBoundingBox().getCenter().subtract(origin).normalize();
    }

    private LivingEntity rayTarget(Vec3 end) {
        // 实际生物包围盒与视线相交才锁定，沿视线最近的有效目标优先。
        Vec3 eye = actor.getEyePosition();
        return actor.level().getEntitiesOfClass(LivingEntity.class,
                        actor.getBoundingBox().expandTowards(end.subtract(eye)).inflate(TARGET_MARGIN),
                        this::eligible).stream()
                .filter(entity -> entity.getBoundingBox().inflate(TARGET_MARGIN).clip(eye, end).isPresent())
                .min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
    }

    private LivingEntity targetEntity(String id) {
        // 目标身份必须仍指向同世界可敌对的活体，普通无目标射击仍按视线执行。
        if (id.isEmpty()) {
            return null;
        }
        Entity entity = ((ServerLevel) actor.level()).getEntity(UUID.fromString(id));
        if (entity instanceof LivingEntity living && eligible(living)) {
            return living;
        }
        return null;
    }

    private boolean eligible(LivingEntity entity) {
        // 标记盔甲架、自己、死体与旁观者不能成为锁定或扇区吸附目标。
        return entity != actor && entity.isAlive() && !entity.isSpectator()
                && !(entity instanceof ArmorStand stand && stand.isMarker()) && hostile.test(entity);
    }

    private Vec3 sightPoint(double distance) {
        // 锁点用有限距离的真实视线交点，没碰到方块时保留视线末端。
        Vec3 eye = actor.getEyePosition();
        Vec3 end = eye.add(actor.getLookAngle().scale(distance));
        return actor.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, actor)).getLocation();
    }

    private Vec3 lobCenter(EffectIntent effect, double maximumDistance) {
        // 锁定沿用起手快照；生物可向已知掩体后目标抛射，玩家仍由服务端视线选点。
        if (effect.getLockTarget()) {
            return requireTarget(effect.getCastId()).position();
        }
        if (!(actor instanceof Player)) {
            LivingEntity target = targetEntity(effect.getTargetId());
            if (target != null && actor.distanceTo(target) <= maximumDistance) {
                return target.getBoundingBox().getCenter();
            }
        }
        return sightPoint(maximumDistance);
    }

    private Vec3 randomGroundPoint(Vec3 center, double maximumRadius) {
        // 圆盘均匀取点，平方根避免落点过度聚在中心，不改内容声明的半径。
        var random = actor.getRandom();
        double angle = random.nextDouble() * TWO_PI;
        double radius = Math.sqrt(random.nextDouble()) * maximumRadius;
        Vec3 sample = center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        Vec3 bottom = new Vec3(sample.x, actor.level().getMinY(), sample.z);
        var ground = actor.level().clip(new ClipContext(sample, bottom, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, actor));
        if (ground.getType() == HitResult.Type.BLOCK) {
            return ground.getLocation();
        }
        return sample;
    }

    static Vec3 scatter(LivingEntity actor, Vec3 velocity, double degrees) {
        // 散布有严格角度上限，原版高斯不确定度无法表达目录中的最大散布角。
        if (degrees == 0) {
            return velocity;
        }
        Vec3 direction = velocity.normalize();
        Vec3 right = right(direction);
        Vec3 up = right.cross(direction).normalize();
        var random = actor.getRandom();
        double cosine = 1 - random.nextDouble() * (1 - Math.cos(Math.toRadians(degrees)));
        double sine = Math.sqrt(1 - cosine * cosine);
        double azimuth = random.nextDouble() * TWO_PI;
        return direction.scale(cosine).add(right.scale(sine * Math.cos(azimuth)))
                .add(up.scale(sine * Math.sin(azimuth))).scale(velocity.length());
    }

    static Vec3 multishot(Vec3 velocity, double degrees) {
        // 多重射击在当前射线的横向平面展开，仰头时仍保留附魔声明的真实夹角。
        double angle = Math.toRadians(degrees);
        Vec3 right = right(velocity.normalize());
        return velocity.scale(Math.cos(angle)).add(right.scale(velocity.length() * Math.sin(angle)));
    }

    private static Vec3 right(Vec3 direction) {
        // 近垂直射击改用横向参考轴，避免叉积归一化退为零向量。
        Vec3 horizontal = direction.cross(new Vec3(0, 1, 0));
        if (horizontal.lengthSqr() < VERTICAL_EPSILON) {
            return direction.cross(new Vec3(1, 0, 0)).normalize();
        }
        return horizontal.normalize();
    }

    private static Vec3 yaw(Vec3 direction, double degrees) {
        // 用双精度三角函数保持扇区镜像与速度模长，不引入原版查表旋转的左右误差。
        double angle = Math.toRadians(degrees);
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        return new Vec3(direction.x * cosine - direction.z * sine, direction.y,
                direction.x * sine + direction.z * cosine);
    }

    private Target requireTarget(String castId) {
        // 漏掉起手锁定属于宿主接线错误，不能换成撒放时的新目标。
        Target target = locked.get(castId);
        if (target == null) {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        return target;
    }

    private Vector displacement(Vec3 target) {
        // 箭出生在眼下，反解必须使用同一出生点，不能使用脚底位置。
        Vec3 delta = target.subtract(origin());
        return new Vector(delta.x, delta.y, delta.z);
    }

    private Vec3 origin() {
        // 与 26.2 AbstractArrow 的生物构造器保持一致。
        return actor.getEyePosition().add(0, -ARROW_EYE_OFFSET, 0);
    }

    private Vector inherited() {
        // 反解与原版发射使用相同的射手惯性，移动中抛射不会重复偏移落点。
        Vec3 movement = HumanProjectileEffects.inheritedMovement(actor);
        return new Vector(movement.x, movement.y, movement.z);
    }

    private long nowMillis() {
        // 使用宿主共享的主世界累计 tick，不能混用各维度日时。
        return (long) (((ServerLevel) actor.level()).getServer().overworld().getGameTime() * MILLIS_PER_TICK);
    }

    private static LobProfile profile(LobProjectile lob, double baseSpeed) {
        // 动画仰角和速度上限均来自目录，实体适配不按功法名称硬编码数值。
        return new LobProfile(lob.getElevation().getValue(), baseSpeed * lob.getMaximumSpeedMultiplier().getValue());
    }

    private static Vec3 vector(Vector vector) {
        // Minecraft 向量只存在于 Fabric 边界，纯弹道计算不依赖游戏类型。
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    void finish(String castId) {
        // 已出箭不受清理影响，后续施放不沿用本次锁点或到达时刻。
        locked.remove(castId);
        arrivals.remove(castId);
    }

    private record Target(Vec3 position, Vec3 velocity, double sampledMillis) {
        Vec3 at(double millis) {
            // 外推只使用锁定瞬间的速度，不读取之后的实体状态。
            return position.add(velocity.scale((millis - sampledMillis) / MILLIS_PER_TICK));
        }
    }
}
