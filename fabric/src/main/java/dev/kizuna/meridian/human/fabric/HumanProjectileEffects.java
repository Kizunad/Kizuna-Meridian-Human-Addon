package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.DrawStrengthArm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/** 公开的行矢世界适配入口；调用方负责效果身份去重和施放生命周期。 */
public final class HumanProjectileEffects {
    static final double BOW_SPEED = 3.0;
    private static final double CROSSBOW_SPEED = 3.15;
    private static final double FIREWORK_SPEED = 1.6;
    private static final double ARROW_BASE_DAMAGE = 2.0;
    private static final double FIREWORK_EYE_OFFSET = 0.15;
    private static final float SOUND_VOLUME = 1;
    private static final float SOUND_PITCH = 1;
    private static final float EMPTY_PITCH = 0.8F;
    private static final int FIREWORK_DURABILITY = 3;
    private static final int MAX_PIERCING = Byte.MAX_VALUE;
    private final LivingEntity actor;
    private final HumanProjectileAim aim;

    public HumanProjectileEffects(LivingEntity actor, Predicate<LivingEntity> hostile) {
        // 两类宿主共用箭实体、弹道与消耗规则，敌我由各自宿主决定。
        this.actor = actor;
        this.aim = new HumanProjectileAim(actor, hostile);
    }

    public void lockTarget(EffectIntent effect) {
        // 起手只冻结几何事实，目标死亡或离开也不重新挑选。
        aim.lockTarget(effect);
    }

    public void launch(EffectIntent effect) {
        // 选手来自起手快照，撒放时重新读当前物品；缺武器或弹药都只响空弦。
        InteractionHand hand = selectedHand(effect);
        ItemStack weapon = actor.getItemInHand(hand);
        LaunchProjectile settings = effect.getLaunchProjectile().getProjectile();
        if (HumanProjectileItems.category(weapon) != settings.getProjectileWeapon()) {
            emptyShot(settings);
            return;
        }
        ItemStack ammunition = HumanProjectileItems.find(actor, hand);
        if (ammunition.isEmpty()) {
            emptyShot(settings);
            return;
        }
        double baseSpeed = baseSpeed(settings.getProjectileWeapon(), ammunition);
        double strength = drawStrength(settings, hand);
        Vec3 velocity = aim.velocity(effect, baseSpeed, strength);
        List<ItemStack> drawn = HumanProjectileItems.draw(actor, weapon, ammunition);
        if (drawn.isEmpty()) {
            emptyShot(settings);
            return;
        }
        ShotContext shot = new ShotContext(hand, weapon, settings, velocity);
        fireVolley(shot, drawn);
    }

    public void finish(String castId) {
        // 清理只释放瞄准快照，已经射出的原版实体继续飞行。
        aim.finish(castId);
    }

    private void fireVolley(ShotContext shot, List<ItemStack> ammunition) {
        // 三支箭的扇角沿用原版附魔数据，每支实际出箭才磨损武器。
        ServerLevel level = (ServerLevel) actor.level();
        ItemStack weapon = shot.weapon();
        int count = ammunition.size();
        float spread = EnchantmentHelper.processProjectileSpread(level, weapon, actor, 0);
        double step = 0;
        if (count > 1) {
            step = spread * 2 / (count - 1);
        }
        for (int index = 0; index < count; index++) {
            double offset = (count - 1) % 2 * step / 2;
            double sign = 1;
            if (index % 2 != 0) {
                sign = -1;
            }
            double angle = offset + sign * ((index + 1) / 2) * step;
            ItemStack projectileItem = ammunition.get(index);
            fireOne(shot, projectileItem, angle);
            wear(shot, projectileItem);
            if (weapon.isEmpty()) {
                break;
            }
        }
        playShot(shot.settings(), SOUND_PITCH);
    }

    private void fireOne(ShotContext shot, ItemStack ammunition, double angle) {
        // 每支箭使用同一发的中心初速，独立叠加附魔扇角和声明散布。
        Projectile projectile = create(shot, ammunition);
        Vec3 velocity = HumanProjectileAim.scatter(actor, HumanProjectileAim.multishot(shot.velocity(), angle),
                shot.settings().getSpread().getValue());
        Vec3 movement = inheritedMovement(actor);
        Projectile.spawnProjectile(projectile, (ServerLevel) actor.level(), ammunition, entity -> {
            // shoot 初始化朝向；精确速度随后设置，避免 float 初速改变离散求解落点。
            entity.shoot(velocity.x, velocity.y, velocity.z, (float) velocity.length(), 0);
            entity.setDeltaMovement(velocity.add(movement));
        });
    }

    private Projectile create(ShotContext shot, ItemStack ammunition) {
        // 原版工厂保留药箭、光灵箭、武器附魔与火矢的生成效果，不改弩上弦组件。
        if (ammunition.is(Items.FIREWORK_ROCKET)) {
            return new FireworkRocketEntity(actor.level(), ammunition, actor, actor.getX(),
                    actor.getEyeY() - FIREWORK_EYE_OFFSET, actor.getZ(), true /* shotAtAngle */);
        }
        ArrowItem item = (ArrowItem) Items.ARROW;
        if (ammunition.getItem() instanceof ArrowItem arrowItem) {
            item = arrowItem;
        }
        AbstractArrow arrow = item.createArrow(actor.level(), ammunition, actor, shot.weapon());
        LaunchProjectile settings = shot.settings();
        arrow.setBaseDamage(ARROW_BASE_DAMAGE * settings.getDamageMultiplier().getValue());
        arrow.setCritArrow(settings.getCritical());
        int piercing = Math.min(MAX_PIERCING, arrow.getPierceLevel() + settings.getPiercing());
        HumanTechniqueArrow properties = (HumanTechniqueArrow) arrow;
        properties.meridianHumanSetPiercing((byte) piercing);
        properties.meridianHumanShieldCooldown(settings.getShieldCooldownMillis());
        if (settings.getProjectileWeapon() == ProjectileWeaponCategory.CROSSBOW) {
            arrow.setSoundEvent(SoundEvents.CROSSBOW_HIT);
        }
        if (!(actor instanceof Player)) {
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        }
        return arrow;
    }

    private void wear(ShotContext shot, ItemStack ammunition) {
        // 原版弩每支烟花磨损三点；普通箭每支一点，耐久附魔由 ItemStack 处理。
        if (!(actor instanceof Player) || actor.hasInfiniteMaterials()) {
            return;
        }
        int durability = 1;
        if (ammunition.is(Items.FIREWORK_ROCKET)) {
            durability = FIREWORK_DURABILITY;
        }
        shot.weapon().hurtAndBreak(durability, actor, shot.hand().asEquipmentSlot());
    }

    private double drawStrength(LaunchProjectile settings, InteractionHand hand) {
        // 撒放时只查一次拉弦臂系数，功法已记做功，不能再按一次近战扣费。
        if (!settings.getMultiplyDrawStrength()) {
            return 1;
        }
        HumanoidArm arm = drawArm(actor, hand, settings.getDrawStrengthArm());
        return VanillaDamageFabric.armCoefficient(actor, arm);
    }

    static HumanoidArm drawArm(LivingEntity actor, InteractionHand hand, DrawStrengthArm selection) {
        // 先由主副手映射到身体左右侧，再按目录选择武器臂或另一臂。
        HumanoidArm mainArm = actor.getMainArm();
        HumanoidArm weaponArm = mainArm;
        if (hand == InteractionHand.OFF_HAND) {
            weaponArm = mainArm.getOpposite();
        }
        if (selection == DrawStrengthArm.OTHER_ARM) {
            return weaponArm.getOpposite();
        }
        return weaponArm;
    }

    static InteractionHand selectedHand(EffectIntent effect) {
        // 实际出招手在起手冻结，切换惯用手不能交换这次使用的物品栏位置。
        if (effect.getSelectedHand() == Hand.OFF_HAND) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    private static double baseSpeed(ProjectileWeaponCategory category, ItemStack ammunition) {
        // 26.2 弩发射烟花使用独立初速，火箭爆炸伤害保持原版。
        if (ammunition.is(Items.FIREWORK_ROCKET)) {
            return FIREWORK_SPEED;
        }
        if (category == ProjectileWeaponCategory.CROSSBOW) {
            return CROSSBOW_SPEED;
        }
        return BOW_SPEED;
    }

    static Vec3 inheritedMovement(LivingEntity actor) {
        // 与原版弓相同，继承水平惯性；站地时不把竖直速度传给箭。
        Vec3 movement = actor.getKnownMovement();
        if (actor.onGround()) {
            return new Vec3(movement.x, 0, movement.z);
        }
        return movement;
    }

    private void emptyShot(LaunchProjectile settings) {
        // 缺弹药仍确认本次撒放，避免效果重试退款或随后补发。
        playShot(settings, EMPTY_PITCH);
    }

    private void playShot(LaunchProjectile settings, float pitch) {
        // 使用施放者自己的声音类别，玩家与敌对生物分别进入对应音量滑块。
        var sound = SoundEvents.ARROW_SHOOT;
        if (settings.getProjectileWeapon() == ProjectileWeaponCategory.CROSSBOW) {
            sound = SoundEvents.CROSSBOW_SHOOT;
        }
        actor.level().playSound(null, actor.getX(), actor.getY(), actor.getZ(), sound,
                actor.getSoundSource(), SOUND_VOLUME, pitch);
    }

    private record ShotContext(InteractionHand hand, ItemStack weapon, LaunchProjectile settings, Vec3 velocity) { }
}
