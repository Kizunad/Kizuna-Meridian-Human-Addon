package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanProjectileBallistics;
import dev.kizuna.meridian.human.server.HumanProjectileBallistics.Vector;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.DrawStrengthArm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectileIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LobProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TargetLockIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.human.fabric.HumanTechniqueTestCatalog.quantity;

/** 正式宿主字节入口到原版投射物、物品消耗与施放输入的回归。 */
public final class HumanProjectileGameTest {
    private static final double EPSILON = 0.00001;
    private static final double BOW_SPEED = 3;
    private static final double CROSSBOW_SPEED = 3.15;
    private static final double FIREWORK_SPEED = 1.6;
    private static final double SHOT_MULTIPLIER = 1.3;
    private static final double GALE_ANGLE = 45;
    private static final double TEST_RADIUS = 64;
    private static final double LOB_RANGE = 2;
    private static final double LOB_SPEED_CAP = 1.1;
    private static final double IMPACT_MARGIN = 0.1;
    private static final int TEST_CENTER = 4;
    private static final int GROUND_HEIGHT = 1;
    private static final int GROUND_TARGET_Z = 6;
    private static final int GROUND_FLIGHT_LIMIT = 60;
    private static final double MOVEMENT_MULTIPLIER = 0.4;
    private static final int AMMO_COUNT = 4;
    private static final int INVENTORY_SLOT = 9;
    private static final int MULTISHOT_COUNT = 3;
    private static final int PIERCING_LEVEL = 2;
    private static final int ADDITIONAL_PIERCING = 1;
    private static final int NATIVE_TICKS = 3;
    private static final int SHIELD_WARMUP_TICKS = 6;
    private static final float FACING_ATTACKER_YAW = 180;
    private static final double POWER_ONE_DAMAGE = 9;
    private static final long MILLIS_PER_TICK = 50;
    private static final long SHIELD_COOLDOWN_MILLIS = 3000;
    private static final int HIDDEN_TARGET_Z = 6;
    private static final int COVER_Z = 3;
    private static final int COVER_TOP = 3;
    private static final int MOB_HEIGHT = 2;
    private static final double HIDDEN_TARGET_RANGE = 8;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void inspectAmmoOrderAndEffectReplayUseRealInventory(GameTestHelper context) {
        // 副手箭优先于背包，重试只认一次效果，弩原有装填不能冒充可用弹药。
        try (var shots = new Shots(context)) {
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            shots.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TIPPED_ARROW, AMMO_COUNT));
            ItemStack reserve = new ItemStack(Items.SPECTRAL_ARROW, AMMO_COUNT);
            shots.player.getInventory().setItem(INVENTORY_SLOT, reserve);
            var held = shots.fixture.inspect().getHeldItems();
            context.assertTrue(held.getMainHandProjectileWeapon() == ProjectileWeaponCategory.BOW
                    && held.hasOffHandProjectileWeapon() && held.getCanFindAmmunition(), "远程 inspect 事实缺失");
            EffectIntent shot = shots.intent(settings(ProjectileWeaponCategory.BOW).build(), Hand.MAIN_HAND);
            shots.fixture.submit(shot);
            shots.fixture.submit(shot);
            context.assertTrue(shots.arrows().size() == 1, "重试重复射箭");
            context.assertTrue(shots.player.getOffhandItem().getCount() == AMMO_COUNT - 1
                    && reserve.getCount() == AMMO_COUNT, "未先消耗副手箭");
            context.assertTrue(shots.arrows().getFirst().getPickupItemStackOrigin().is(Items.TIPPED_ARROW),
                    "药箭未交给原版箭工厂");
            shots.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            shots.fire(settings(ProjectileWeaponCategory.BOW).build(), Hand.MAIN_HAND);
            context.assertTrue(shots.arrows().stream().anyMatch(SpectralArrow.class::isInstance), "背包光灵箭丢失类型");
            context.assertTrue(reserve.getCount() == AMMO_COUNT - 1, "背包箭未扣除");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void multishotConsumesOneArrowAndKeepsChargedCrossbow(GameTestHelper context) {
        // 多重射击、附魔穿透与目录穿透相加，弩里原来的一发不参与本次射击。
        try (var shots = new Shots(context)) {
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            enchant(context, crossbow, Enchantments.MULTISHOT, 1);
            enchant(context, crossbow, Enchantments.PIERCING, PIERCING_LEVEL);
            ChargedProjectiles charged = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.SPECTRAL_ARROW)));
            crossbow.set(DataComponents.CHARGED_PROJECTILES, charged);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, crossbow);
            shots.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW));
            LaunchProjectile shot = settings(ProjectileWeaponCategory.CROSSBOW)
                    .setPiercing(ADDITIONAL_PIERCING)
                    .setCritical(true /* critical */)
                    .build();
            shots.fire(shot, Hand.MAIN_HAND);
            List<AbstractArrow> arrows = shots.arrows();
            context.assertTrue(arrows.size() == MULTISHOT_COUNT && shots.player.getOffhandItem().isEmpty(),
                    "多重射击支数或单支消耗错误");
            context.assertTrue(crossbow.getDamageValue() == MULTISHOT_COUNT, "多重射击没有每支磨损一次");
            context.assertTrue(charged.equals(crossbow.get(DataComponents.CHARGED_PROJECTILES)), "改掉了弩原有上弦");
            long recoverable = arrows.stream().filter(arrow -> arrow.pickup == AbstractArrow.Pickup.ALLOWED).count();
            context.assertTrue(recoverable == 1,
                    "额外多重箭可以拾取");
            for (AbstractArrow arrow : arrows) {
                context.assertTrue(arrow.getPierceLevel() == PIERCING_LEVEL + ADDITIONAL_PIERCING
                        && arrow.isCritArrow(), "穿透或暴击未保留");
                close(context, CROSSBOW_SPEED, arrow.getDeltaMovement().length(), "弩原版初速错误");
            }
            shots.fire(shot, Hand.MAIN_HAND);
            context.assertTrue(shots.arrows().size() == MULTISHOT_COUNT
                    && crossbow.getDamageValue() == MULTISHOT_COUNT, "空弦出箭或磨损");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void infinityCreativeAndFireworksFollowVanillaConsumption(GameTestHelper context) {
        // 无限可无普通箭起射，弩只从另一手取烟花；创造不会扣弹药或耐久。
        try (var shots = new Shots(context)) {
            ItemStack bow = new ItemStack(Items.BOW);
            enchant(context, bow, Enchantments.INFINITY, 1);
            enchant(context, bow, Enchantments.FLAME, 1);
            enchant(context, bow, Enchantments.POWER, 1);
            enchant(context, bow, Enchantments.PUNCH, 1);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, bow);
            context.assertTrue(shots.fixture.inspect().getHeldItems().getCanFindAmmunition(), "无限无箭被判缺弹药");
            shots.fire(settings(ProjectileWeaponCategory.BOW).build(), Hand.MAIN_HAND);
            AbstractArrow arrow = shots.arrows().getFirst();
            context.assertTrue(arrow.pickup == AbstractArrow.Pickup.CREATIVE_ONLY && arrow.isOnFire(),
                    "无限拾取或火矢未走原版生成钩子");
            context.assertTrue(arrow.getWeaponItem().getEnchantments().equals(bow.getEnchantments()),
                    "命中时的力量与冲击附魔没有保留武器副本");
            checkFireworks(shots);
            shots.player.setGameMode(GameType.CREATIVE);
            ItemStack creativeBow = new ItemStack(Items.BOW);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, creativeBow);
            shots.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            shots.fire(settings(ProjectileWeaponCategory.BOW).build(), Hand.MAIN_HAND);
            context.assertTrue(creativeBow.getDamageValue() == 0, "创造模式磨损弓");
        }
        context.succeed();
    }

    private static void checkFireworks(Shots shots) {
        // 背包烟花不能被弩发现，另一手烟花保留原版实体与每支三点耐久。
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        ItemStack fireworks = new ItemStack(Items.FIREWORK_ROCKET, AMMO_COUNT);
        shots.player.setItemInHand(InteractionHand.MAIN_HAND, crossbow);
        shots.player.getInventory().setItem(INVENTORY_SLOT, fireworks);
        shots.context.assertTrue(!shots.fixture.inspect().getHeldItems().getCanFindAmmunition(), "弩从背包找了烟花");
        shots.player.setItemInHand(InteractionHand.OFF_HAND, fireworks);
        shots.player.getInventory().setItem(INVENTORY_SLOT, ItemStack.EMPTY);
        shots.fire(settings(ProjectileWeaponCategory.CROSSBOW).build(), Hand.MAIN_HAND);
        var rocket = shots.projectiles().stream().filter(FireworkRocketEntity.class::isInstance)
                .findFirst().orElseThrow();
        close(shots.context, FIREWORK_SPEED, rocket.getDeltaMovement().length(), "烟花初速未沿用原版");
        shots.context.assertTrue(fireworks.getCount() == AMMO_COUNT - 1
                && crossbow.getDamageValue() == MULTISHOT_COUNT, "烟花消耗或原版耐久错误");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void offhandMirrorAndCurrentDrawStrengthAffectOnlyRelease(GameTestHelper context) {
        // 偏航按主副手镜像；另一手解剖侧与只读肌力查询一致，不额外提交做功。
        try (var shots = new Shots(context)) {
            shots.player.setGameMode(GameType.CREATIVE);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
            shots.player.setXRot((float) -GALE_ANGLE);
            LaunchProjectile gale = settings(ProjectileWeaponCategory.CROSSBOW)
                    .setLookOffset(quantity(GALE_ANGLE, Unit.DEGREE))
                    .setMirrorByHand(true /* mirrorByHand */)
                    .build();
            shots.fire(gale, Hand.MAIN_HAND);
            Vec3 main = shots.arrows().getFirst().getDeltaMovement();
            close(context, CROSSBOW_SPEED, main.length(), "仰头射击改变了声明初速");
            shots.removeProjectiles();
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            shots.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.CROSSBOW));
            shots.fire(gale, Hand.OFF_HAND);
            Vec3 off = shots.arrows().getFirst().getDeltaMovement();
            close(context, -main.x, off.x, "副手扇面没有反向");
            close(context, main.z, off.z, "副手镜像改变纵向速度");
            checkDrawStrength(shots);
        }
        context.succeed();
    }

    private static void checkDrawStrength(Shots shots) {
        // 以权威快照版本和当前查询系数对照，避免箭发射额外重复近战做功。
        shots.removeProjectiles();
        shots.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BOW));
        long revision = shots.fixture.inspect().getBody().getRevision();
        double coefficient = VanillaDamageFabric.armCoefficient(shots.player, HumanoidArm.RIGHT);
        LaunchProjectile bow = settings(ProjectileWeaponCategory.BOW)
                .setMultiplyDrawStrength(true /* multiplyDrawStrength */)
                .setDrawStrengthArm(DrawStrengthArm.OTHER_ARM)
                .setSpeedMultiplier(quantity(SHOT_MULTIPLIER, Unit.RATIO))
                .build();
        shots.fire(bow, Hand.OFF_HAND);
        close(shots.context, BOW_SPEED * SHOT_MULTIPLIER * coefficient,
                shots.arrows().getFirst().getDeltaMovement().length(), "拉弦系数未乘到初速");
        shots.context.assertTrue(shots.fixture.inspect().getBody().getRevision() == revision, "射箭额外提交了做功");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void mobUsesPublicExecutorWithoutAmmoDurabilityOrPickup(GameTestHelper context) {
        // 不绑定身体的原版生物直接使用公开入口，正式实体规则与玩家共用。
        var mob = context.spawnWithNoFreeWill(EntityTypes.PILLAGER, 1, 1, 1);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        mob.setItemInHand(InteractionHand.MAIN_HAND, crossbow);
        var effects = new HumanProjectileEffects(mob, target -> target != mob);
        try (var shots = new Shots(context)) {
            EffectIntent intent = shots.intent(settings(ProjectileWeaponCategory.CROSSBOW).build(), Hand.MAIN_HAND);
            effects.launch(intent);
            List<AbstractArrow> arrows = context.getLevel().getEntitiesOfClass(AbstractArrow.class,
                    mob.getBoundingBox().inflate(TEST_RADIUS), arrow -> arrow.getOwner() == mob);
            context.assertTrue(arrows.size() == 1 && crossbow.getDamageValue() == 0
                    && arrows.getFirst().pickup == AbstractArrow.Pickup.DISALLOWED, "生物射击策略错误");
            arrows.forEach(AbstractArrow::discard);
            effects.finish(intent.getCastId());
        } finally {
            mob.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, skyAccess = true)
    public void lobUsesVanillaTickOrder(GameTestHelper context) {
        // 原版箭真实 tick 的前三步必须与纯离散积分吻合，覆盖出生高度和阻力顺序。
        try (var shots = new Shots(context)) {
            shots.player.setGameMode(GameType.CREATIVE);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            context.setBlock(TEST_CENTER, GROUND_HEIGHT, GROUND_TARGET_Z, Blocks.STONE);
            LobProjectile lob = LobProjectile.newBuilder()
                    .setMaximumDistance(quantity(LOB_RANGE, Unit.METER))
                    .setRadius(quantity(0, Unit.METER))
                    .setElevation(quantity(HumanProjectileBallistics.LOB_ANGLE_DEGREES, Unit.DEGREE))
                    .setMaximumSpeedMultiplier(quantity(LOB_SPEED_CAP, Unit.RATIO))
                    .build();
            LaunchProjectile launch = settings(ProjectileWeaponCategory.BOW).setLob(lob).build();
            shots.fire(launch, Hand.MAIN_HAND);
            AbstractArrow arrow = shots.arrows().getFirst();
            verifyNativeTicks(context, arrow);
            for (int tick = NATIVE_TICKS; tick < GROUND_FLIGHT_LIMIT; tick++) {
                arrow.tick();
            }
            Vec3 target = shots.player.position().add(0, 0, LOB_RANGE);
            context.assertTrue(arrow.position().distanceTo(target) < IMPACT_MARGIN, "仰射未落到视线末端下方地面");
        }
        context.succeed();
    }

    private static void verifyNativeTicks(GameTestHelper context, AbstractArrow arrow) {
        // 独立积分只观察原版实体每一步位置，不覆盖箭速度或碰撞逻辑。
        Vec3 start = arrow.position();
        Vec3 velocity = arrow.getDeltaMovement();
        for (int tick = 0; tick < NATIVE_TICKS; tick++) {
            arrow.tick();
        }
        Vector expected = HumanProjectileBallistics.position(new Vector(velocity.x, velocity.y, velocity.z),
                NATIVE_TICKS);
        close(context, expected.x(), arrow.getX() - start.x, "原版箭 X 积分不同");
        close(context, expected.y(), arrow.getY() - start.y, "原版箭 Y 积分不同");
        close(context, expected.z(), arrow.getZ() - start.z, "原版箭 Z 积分不同");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, skyAccess = true)
    public void mobLobReachesRememberedTargetBehindCover(GameTestHelper context) {
        // 经过公开发射入口与原版箭碰撞，隔墙目标的位置决定落点而非眼前墙面。
        verifyHiddenLob(context, AimMode.CURRENT_TARGET);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, skyAccess = true)
    public void lockedMobLobKeepsHiddenPointAfterTargetDisappears(GameTestHelper context) {
        // 先冻结隔墙目标再移除实体并转头，抛射仍应落在原目标下方。
        verifyHiddenLob(context, AimMode.LOCKED_TARGET);
        context.succeed();
    }

    private static void verifyHiddenLob(GameTestHelper context, AimMode mode) {
        // 低墙挡住视线但留出抛射通道，目标脚下方块固定实际落地高度。
        var mob = context.spawnWithNoFreeWill(EntityTypes.PILLAGER, 1, MOB_HEIGHT, 1);
        var target = context.spawnWithNoFreeWill(EntityTypes.COW, 1, MOB_HEIGHT, HIDDEN_TARGET_Z);
        context.setBlock(1, GROUND_HEIGHT, HIDDEN_TARGET_Z, Blocks.STONE);
        for (int height = MOB_HEIGHT; height <= COVER_TOP; height++) {
            context.setBlock(1, height, COVER_Z, Blocks.STONE);
        }
        mob.setYRot(0);
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        var effects = new HumanProjectileEffects(mob, entity -> entity == target);
        try (var shots = new Shots(context)) {
            context.assertTrue(!mob.hasLineOfSight(target), "测试掩体没有挡住目标");
            Vec3 expected = target.position();
            EffectIntent requested = shots.intent(hiddenLob(), Hand.MAIN_HAND).toBuilder()
                    .setTargetId(target.getStringUUID())
                    .build();
            EffectIntent effect;
            if (mode == AimMode.LOCKED_TARGET) {
                effect = lockHiddenTarget(effects, requested);
                target.discard();
                mob.setYRot(FACING_ATTACKER_YAW);
            } else {
                effect = requested;
            }
            effects.launch(effect);
            target.discard();
            verifyHiddenImpact(context, mob, expected);
            effects.finish(effect.getCastId());
        } finally {
            target.discard();
            mob.discard();
        }
    }

    private static EffectIntent lockHiddenTarget(HumanProjectileEffects effects, EffectIntent shot) {
        // 锁定和撒放属于同一施放，发送前只冻结服务端已知的目标身份。
        TargetLockIntent targetLock = TargetLockIntent.newBuilder()
                .setMaximumDistance(quantity(HIDDEN_TARGET_RANGE, Unit.METER))
                .build();
        EffectIntent lock = shot.toBuilder()
                .setEffectId("lock")
                .setTargetLock(targetLock)
                .build();
        effects.lockTarget(lock);
        return shot.toBuilder().setLockTarget(true /* lockTarget */).build();
    }

    private static LaunchProjectile hiddenLob() {
        // 零散布与零圆盘半径让真实碰撞结果精确检查目标选择。
        LobProjectile lob = LobProjectile.newBuilder()
                .setMaximumDistance(quantity(HIDDEN_TARGET_RANGE, Unit.METER))
                .setRadius(quantity(0, Unit.METER))
                .setElevation(quantity(HumanProjectileBallistics.LOB_ANGLE_DEGREES, Unit.DEGREE))
                .setMaximumSpeedMultiplier(quantity(LOB_SPEED_CAP, Unit.RATIO))
                .build();
        return settings(ProjectileWeaponCategory.BOW).setLob(lob).build();
    }

    private static void verifyHiddenImpact(GameTestHelper context, LivingEntity mob,
                                           Vec3 expected) {
        // 逐步推进真实箭，最终位置必须越过掩体并停在目标脚下。
        List<AbstractArrow> arrows = context.getLevel().getEntitiesOfClass(AbstractArrow.class,
                mob.getBoundingBox().inflate(TEST_RADIUS), arrow -> arrow.getOwner() == mob);
        context.assertTrue(arrows.size() == 1, "生物抛射没有生成唯一箭实体");
        AbstractArrow arrow = arrows.getFirst();
        try {
            for (int tick = 0; tick < GROUND_FLIGHT_LIMIT; tick++) {
                arrow.tick();
            }
            context.assertTrue(arrow.position().distanceTo(expected) < IMPACT_MARGIN,
                    "抛射没有落在掩体后目标脚下：" + arrow.position() + " / " + expected);
        } finally {
            arrow.discard();
        }
    }

    private enum AimMode { CURRENT_TARGET, LOCKED_TARGET }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void techniquesAllowingUseKeepTheirExistingItemUse(GameTestHelper context) {
        // 放行右键的既有功法不能因射击宿主的起手和收势清理而被迫停用物品。
        try (var shots = new Shots(context)) {
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            shots.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW));
            shots.player.startUsingItem(InteractionHand.MAIN_HAND);
            MovementIntent lock = MovementIntent.newBuilder()
                    .setAction(MovementAction.LOCK)
                    .setMovementSpeedMultiplier(MOVEMENT_MULTIPLIER)
                    .setAllowUseWhileCasting(true /* allowUseWhileCasting */)
                    .build();
            EffectIntent start = EffectIntent.newBuilder()
                    .setCastId("projectile_allowed_use")
                    .setEffectId("lock")
                    .setMovement(lock)
                    .build();
            shots.fixture.submit(start);
            context.assertTrue(shots.player.isUsingItem(), "放行式起手停掉了已有物品使用");
            MovementIntent unlock = MovementIntent.newBuilder().setAction(MovementAction.UNLOCK).build();
            EffectIntent stop = start.toBuilder()
                    .setEffectId("unlock")
                    .setMovement(unlock)
                    .build();
            shots.fixture.submit(stop);
            context.assertTrue(shots.player.isUsingItem(), "放行式收势停掉了物品使用");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void castingStopsPriorUseAndBlocksBowAndCrossbowPackets(GameTestHelper context) {
        // 起手取消先前原版拉弓，锁定期间右键包无效，收势后可重新开始使用。
        try (var shots = new Shots(context)) {
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            shots.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW, AMMO_COUNT));
            shots.player.startUsingItem(InteractionHand.MAIN_HAND);
            MovementIntent lock = MovementIntent.newBuilder()
                    .setAction(MovementAction.LOCK)
                    .setMovementSpeedMultiplier(MOVEMENT_MULTIPLIER)
                    .build();
            EffectIntent start = EffectIntent.newBuilder()
                    .setCastId("projectile_input")
                    .setEffectId("lock")
                    .setMovement(lock)
                    .build();
            shots.fixture.submit(start);
            context.assertTrue(!shots.player.isUsingItem(), "起手没停掉原版拉弓");
            usePacket(shots.player);
            context.assertTrue(!shots.player.isUsingItem(), "施放时右键仍拉弓");
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
            usePacket(shots.player);
            context.assertTrue(!shots.player.isUsingItem(), "施放时右键仍装填");
            MovementIntent unlock = MovementIntent.newBuilder().setAction(MovementAction.UNLOCK).build();
            EffectIntent stop = start.toBuilder()
                    .setEffectId("unlock")
                    .setMovement(unlock)
                    .build();
            shots.fixture.submit(stop);
            usePacket(shots.player);
            context.assertTrue(shots.player.isUsingItem(), "结束后弩仍不能使用");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shieldCooldownRequiresRealVanillaBlocking(GameTestHelper context) {
        // 盾正面实际减伤才触发冷却，穿透箭保留原版绕盾语义。
        try (var shots = new Shots(context); var defender = new HumanTechniqueTestPlayer(context)) {
            Vec3 position = shots.player.position().add(0, 0, LOB_RANGE);
            defender.player.snapTo(position.x, position.y, position.z, FACING_ATTACKER_YAW, 0);
            defender.player.setYHeadRot(FACING_ATTACKER_YAW);
            defender.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD));
            defender.player.startUsingItem(InteractionHand.MAIN_HAND);
            for (int tick = 0; tick < SHIELD_WARMUP_TICKS; tick++) {
                defender.player.doTick();
            }
            context.assertTrue(defender.player.isBlocking(), "原版举盾尚未生效");
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            shots.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW));
            LaunchProjectile launch = settings(ProjectileWeaponCategory.BOW)
                    .setShieldCooldownMillis(SHIELD_COOLDOWN_MILLIS)
                    .build();
            shots.fire(launch, Hand.MAIN_HAND);
            assertBlocking(shots, defender.player, shots.arrows().getFirst());
        }
        context.succeed();
    }

    private static void assertBlocking(Shots shots, ServerPlayer defender, AbstractArrow arrow) {
        // 先检查穿透绕盾，再检查实际格挡后的完整三秒冷却边界。
        var source = shots.context.getLevel().damageSources().arrow(arrow, shots.player);
        HumanTechniqueArrow properties = (HumanTechniqueArrow) arrow;
        properties.meridianHumanSetPiercing((byte) 1);
        close(shots.context, 0, defender.applyItemBlocking(shots.context.getLevel(), source, 1), "穿透箭被盾挡住");
        shots.context.assertTrue(defender.isBlocking()
                && !defender.getCooldowns().isOnCooldown(defender.getMainHandItem()), "穿透绕盾触发了冷却");
        properties.meridianHumanSetPiercing((byte) 0);
        float blocked = defender.applyItemBlocking(shots.context.getLevel(), source, 1);
        shots.context.assertTrue(blocked > 0 && !defender.isUsingItem(), "未在实际格挡后破盾");
        var cooldowns = defender.getCooldowns();
        ItemStack shield = defender.getMainHandItem();
        shots.context.assertTrue(cooldowns.isOnCooldown(shield), "盾冷却没有交给原版冷却器");
        for (long tick = 1; tick < SHIELD_COOLDOWN_MILLIS / MILLIS_PER_TICK; tick++) {
            cooldowns.tick();
        }
        shots.context.assertTrue(cooldowns.isOnCooldown(shield), "盾冷却提前结束");
        cooldowns.tick();
        shots.context.assertTrue(!cooldowns.isOnCooldown(shield), "盾冷却超过三秒");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void potionArrowHitsWithVanillaPowerPunchAndFlame(GameTestHelper context) {
        // 让原版箭实际碰撞活体，验证保存的附魔与药水最终进入伤害和状态链。
        try (var shots = new Shots(context)) {
            ItemStack bow = new ItemStack(Items.BOW);
            enchant(context, bow, Enchantments.POWER, 1);
            enchant(context, bow, Enchantments.PUNCH, 1);
            enchant(context, bow, Enchantments.FLAME, 1);
            enchant(context, bow, Enchantments.INFINITY, 1);
            ItemStack arrow = PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON);
            shots.player.setItemInHand(InteractionHand.MAIN_HAND, bow);
            shots.player.setItemInHand(InteractionHand.OFF_HAND, arrow);
            var target = shots.fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            shots.player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
            shots.fire(settings(ProjectileWeaponCategory.BOW).build(), Hand.MAIN_HAND);
            shots.arrows().getFirst().tick();
            context.assertTrue(target.doses.size() == 1, "药箭未实际命中");
            close(context, POWER_ONE_DAMAGE, target.doses.getFirst(), "力量附魔的原版伤害错误");
            context.assertTrue(target.hasEffect(MobEffects.POISON) && target.isOnFire(), "药箭或火矢未作用于目标");
            context.assertTrue(target.getDeltaMovement().horizontalDistance() > 0, "冲击附魔未击退目标");
            context.assertTrue(shots.player.getOffhandItem().isEmpty(), "无限错误免耗了药箭");
        }
        context.succeed();
    }

    static LaunchProjectile.Builder settings(ProjectileWeaponCategory weapon) {
        // 所有必填字段均显式声明，测试世界效果经正式协议校验。
        return LaunchProjectile.newBuilder()
                .setProjectileWeapon(weapon)
                .setSpeedMultiplier(quantity(1, Unit.RATIO))
                .setDamageMultiplier(quantity(1, Unit.RATIO))
                .setCritical(false /* critical */)
                .setPiercing(0)
                .setSpread(quantity(0, Unit.DEGREE))
                .setLook(true /* look */)
                .setMultiplyDrawStrength(false /* multiplyDrawStrength */)
                .setShieldCooldownMillis(0)
                .setMirrorByHand(false /* mirrorByHand */);
    }

    private static void enchant(GameTestHelper context, ItemStack item, ResourceKey<Enchantment> key, int level) {
        // 从真实注册表取得附魔，生成与命中的数据驱动效果均使用原版定义。
        item.enchant(context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), level);
    }

    private static void close(GameTestHelper context, double expected, double actual, String message) {
        // float 空气阻力的原版误差只允许在明确的小容差内。
        context.assertTrue(Math.abs(expected - actual) <= EPSILON, message + "：" + expected + " / " + actual);
    }

    private static void usePacket(ServerPlayer player) {
        // 使用正式服务端网络处理器触发 Fabric 使用拦截。
        player.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0));
    }

    static final class Shots implements AutoCloseable {
        final HumanCombatFixture fixture;
        final ServerPlayer player;
        final GameTestHelper context;

        Shots(GameTestHelper context) {
            // 沿用真实登录和生存物品栏夹具，箭只按所属玩家收集。
            this.context = context;
            fixture = new HumanCombatFixture(context);
            player = fixture.human.player;
            player.setDeltaMovement(Vec3.ZERO);
        }

        EffectIntent intent(LaunchProjectile settings, Hand hand) {
            // 测试射击使用独立施放身份，不依赖尚未装配的正式行矢目录。
            long now = context.getLevel().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
            LaunchProjectileIntent launch = LaunchProjectileIntent.newBuilder()
                    .setProjectile(settings)
                    .setHeldItemRules(HumanCombatFixture.rules("knock"))
                    .setCastStartedMillis(now)
                    .setClipStartMillis(0)
                    .build();
            return EffectIntent.newBuilder()
                    .setCastId(UUID.randomUUID().toString())
                    .setEffectId("shot")
                    .setSelectedHand(hand)
                    .setScheduledMillis(now)
                    .setLaunchProjectile(launch)
                    .build();
        }

        void fire(LaunchProjectile settings, Hand hand) {
            // 只有正式宿主确认效果后才读取世界实体与物品消耗。
            fixture.submit(intent(settings, hand));
        }

        List<Projectile> projectiles() {
            // 限定所有者与测试空间，避免同批其他测试的箭影响支数。
            return context.getLevel().getEntitiesOfClass(Projectile.class,
                    player.getBoundingBox().inflate(TEST_RADIUS), projectile -> projectile.getOwner() == player);
        }

        List<AbstractArrow> arrows() {
            // 烟花保留独立实体类型，不能混进箭的穿透或暴击断言。
            return projectiles().stream().filter(AbstractArrow.class::isInstance)
                    .map(AbstractArrow.class::cast).toList();
        }

        void removeProjectiles() {
            // 夹具只清理自己生成的实体，不改同批其他玩家或测试。
            projectiles().forEach(Projectile::discard);
        }

        @Override
        public void close() {
            // 无论断言成功与否，释放测试箭和真实在线玩家。
            removeProjectiles();
            fixture.close();
        }
    }
}
