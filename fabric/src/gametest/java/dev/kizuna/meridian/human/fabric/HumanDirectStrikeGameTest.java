package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Numb;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Penetration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeAttachment;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.UnaryOperator;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 肢击的直接路：指定部位、透劲、麻、卸兵器对有身体的目标怎么结算，对没身体的目标怎么退回普通路。 */
public final class HumanDirectStrikeGameTest {
    private static final int JOIN_INVULNERABILITY_TICKS = 60;
    private static final int SHIELD_READY_TICKS = 6;
    private static final float FACING_ATTACKER_YAW = 180;
    private static final double ORGAN_SHARE = 0.6;
    private static final double HALF_ARMOR = 0.5;
    private static final double NUMB_MULTIPLIER = 0.3;
    private static final long NUMB_MILLIS = 6_000;
    private static final int ARM_MUSCLES = 3;
    private static final String CHEST = "chest";
    private static final String ARMED_FOREARM = "right_forearm";
    private static final String NUMB_PREFIX = "meridian_human:numb_right_";
    private static final double PICKUP_RADIUS = 2;
    private static final int HURT_TICKS = 10;
    private static final double TARGET_ARMOR = 20;
    /** 人体伤害配置随包初值里，没身体的目标挨「麻」的时长：60 tick。 */
    private static final int DEFAULT_BODILESS_NUMB_TICKS = 60;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void directFistHitsTheNamedPartWithPenetrationNumbAndDisarm(GameTestHelper context) {
        // 对有身体的玩家：点名打胸、透劲 60% 时只伤胸、器官吃到六成；
        // 打持械前臂并附带整条手臂的麻时，伤的是拿东西那一侧的前臂，三块手臂肌肉都挂上麻，正在用的物品被打断。
        duel(context, false /* shielded */, (attacker, defender) -> {
            BodySnapshot before = defender.body().snapshot();
            context.assertTrue(defender.player.hurtTime == 0, "挨打之前就在受伤动作里");
            attacker.submit(direct(strike -> strike.setFixedPartId(CHEST)
                    .setPenetration(penetration(ORGAN_SHARE, HALF_ARMOR))));
            BodySnapshot pierced = defender.body().snapshot();
            // 直接路不经原版受伤入口，受伤动作（红闪、晃一下）是执行器补的；无敌帧不该跟着给。
            context.assertTrue(defender.player.hurtTime == HURT_TICKS && defender.player.hurtDuration == HURT_TICKS,
                    "直接路打中以后没有受伤动作：" + defender.player.hurtTime);
            context.assertTrue(defender.player.invulnerableTime <= HURT_TICKS, "直接路给了无敌帧");
            double chestLoss = partHp(before, CHEST) - partHp(pierced, CHEST);
            context.assertTrue(chestLoss > 0, "点名打胸的直接伤害没有伤到胸");
            context.assertTrue(Math.abs(totalPartHp(before) - totalPartHp(pierced) - chestLoss)
                    < HumanCombatFixture.EPSILON, "直接伤害伤到了胸以外的部位");
            near(context, (organHp(before) - organHp(pierced)) / chestLoss, ORGAN_SHARE, "器官吃到的份额");

            defender.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            defender.player.startUsingItem(InteractionHand.MAIN_HAND);
            context.assertTrue(defender.player.isUsingItem(), "守方没有进入使用物品的状态");
            attacker.submit(direct(strike -> strike.setArmedForearm(true /* armedForearm */)
                    .addAttachments(numbWholeArm()).setDisarm(true /* disarm */)));
            BodySnapshot locked = defender.body().snapshot();
            context.assertTrue(partHp(locked, ARMED_FOREARM) < partHp(pierced, ARMED_FOREARM),
                    "没有打在拿东西那一侧的前臂");
            long numbed = locked.modifiers().stream()
                    .filter(modifier -> modifier.id().value().startsWith(NUMB_PREFIX)).count();
            context.assertTrue(numbed == ARM_MUSCLES, "右臂三块肌肉应各挂一条麻，实际 " + locked.modifiers());
            context.assertTrue(!defender.player.isUsingItem(), "卸兵器没有打断玩家正在用的物品");
            context.assertTrue(!defender.player.getMainHandItem().isEmpty(), "玩家手里的东西不该掉");
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void directFistIsStoppedByAFacingShield(GameTestHelper context) {
        // 直接路不经原版受伤入口，盾由执行器先查：正面举盾时整下不算，身上不留麻。
        duel(context, true /* shielded */, (attacker, defender) -> {
            context.assertTrue(defender.player.isBlocking(), "真实盾没有进入格挡");
            BodySnapshot before = defender.body().snapshot();
            attacker.submit(direct(strike -> strike.setFixedPartId(CHEST).addAttachments(numbWholeArm())));
            BodySnapshot after = defender.body().snapshot();
            near(context, totalPartHp(after), totalPartHp(before), "被盾挡住的直接伤害");
            context.assertTrue(defender.player.hurtTime == 0, "被盾挡住还是做了受伤动作");
            context.assertTrue(after.modifiers().equals(before.modifiers()), "被盾挡住还是挂上了麻");
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void directFieldsFallBackToVanillaHurtForUnboundTargets(GameTestHelper context) {
        // 没绑身体的目标照旧走原版受伤入口，一下只进一次；卸兵器把它主手的东西打掉在地上。
        try (var fixture = new HumanCombatFixture(context)) {
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            fixture.submit(direct(strike -> strike.setArmedForearm(true /* armedForearm */)
                    .setPenetration(penetration(ORGAN_SHARE, HALF_ARMOR))
                    .addAttachments(numbWholeArm()).setDisarm(true /* disarm */)));
            context.assertTrue(target.doses.size() == 1 && target.getHealth() < target.getMaxHealth(),
                    "没绑身体的目标没有走原版受伤入口：" + target.doses);
            context.assertTrue(target.getMainHandItem().isEmpty(), "卸兵器没有打掉主手的东西");
            var dropped = context.getLevel().getEntitiesOfClass(ItemEntity.class,
                    target.getBoundingBox().inflate(PICKUP_RADIUS), item -> item.getItem().is(Items.IRON_SWORD));
            context.assertTrue(dropped.size() == 1, "打掉的东西没有掉在脚下：" + dropped);
            dropped.forEach(ItemEntity::discard);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unboundTargetsTakePenetrationAsArmorDiscountAndNumbAsSlownessAndWeakness(GameTestHelper context) {
        // 两头没绑身体、各有 20 点护甲的牛：普通一拳进伤害入口时护甲是 20，不给缓慢、虚弱；
        // 带透劲（护甲倍数 0.5）和麻的一拳进入口时护甲只算 10、掉的血更多，打完护甲回到 20，身上有缓慢和虚弱，
        // 多久、几级照人体伤害配置的随包初值（3 秒、各一级）。
        try (var fixture = new HumanCombatFixture(context)) {
            var plain = armored(fixture);
            fixture.submit(HumanFistGameTest.punch("rear"));
            float plainLoss = plain.getMaxHealth() - plain.getHealth();
            context.assertTrue(plain.armors.equals(List.of(TARGET_ARMOR)) && plainLoss > 0,
                    "普通一拳的护甲或伤害不对：" + plain.armors + " / " + plainLoss);
            context.assertTrue(!plain.hasEffect(MobEffects.SLOWNESS) && !plain.hasEffect(MobEffects.WEAKNESS),
                    "普通一拳不该给缓慢、虚弱");
            plain.discard();
            var pierced = armored(fixture);
            fixture.submit(direct(strike -> strike.setPenetration(penetration(ORGAN_SHARE, HALF_ARMOR))
                    .addAttachments(numbWholeArm())));
            float piercedLoss = pierced.getMaxHealth() - pierced.getHealth();
            context.assertTrue(pierced.armors.equals(List.of(TARGET_ARMOR * HALF_ARMOR)),
                    "透劲这一下的护甲没有按倍数算：" + pierced.armors);
            context.assertTrue(piercedLoss > plainLoss,
                    "护甲打了折，掉的血却没有更多：" + piercedLoss + " / " + plainLoss);
            near(context, pierced.getAttributeValue(Attributes.ARMOR), TARGET_ARMOR, "打完以后的护甲");
            context.assertTrue(!pierced.getAttribute(Attributes.ARMOR).hasModifier(HumanDirectStrikes.ARMOR_DISCOUNT),
                    "护甲打折的临时修正没有摘掉");
            for (var effect : List.of(MobEffects.SLOWNESS, MobEffects.WEAKNESS)) {
                var instance = pierced.getEffect(effect);
                context.assertTrue(instance != null && instance.getAmplifier() == 0
                        && instance.getDuration() == DEFAULT_BODILESS_NUMB_TICKS,
                        "带麻的一拳没有给没身体的目标配置里写的 3 秒一级原版状态：" + instance);
                context.assertTrue(!instance.isVisible() && instance.showIcon(), "挨麻给的状态要不出粒子、有图标：" + instance);
            }
        }
        context.succeed();
    }

    private static HumanCombatFixture.Target armored(HumanCombatFixture fixture) {
        // 牛没有护甲槽，直接把护甲属性的基础值调到 20；韧性照旧是 0。
        var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
        target.getAttribute(Attributes.ARMOR).setBaseValue(TARGET_ARMOR);
        return target;
    }

    /** 出拳方和守方面对面站好、打开 PVP，过了入场无敌再跑检查；结束时恢复规则、收回两个玩家。 */
    private static void duel(GameTestHelper context, boolean shielded, Check check) {
        var attacker = new HumanCombatFixture(context);
        var defender = new HumanTechniqueTestPlayer(context);
        var server = context.getLevel().getServer();
        boolean originalPvp = server.getGameRules().get(GameRules.PVP);
        server.getGameRules().set(GameRules.PVP, true /* pvp */, server);
        stand(attacker, defender, shielded);
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS + 1, () -> {
            try (attacker; defender) {
                check.run(attacker, defender);
                context.succeed();
            } finally {
                server.getGameRules().set(GameRules.PVP, originalPvp, server);
            }
        });
    }

    private static void stand(HumanCombatFixture attacker, HumanTechniqueTestPlayer defender, boolean shielded) {
        // 守方站在出拳方正前方、面朝出拳方；要举盾的副手拿真实盾并用满原版格挡延迟。
        Vec3 pos = attacker.human.player.position().add(0, 0, HumanCombatFixture.TARGET_DISTANCE);
        defender.player.snapTo(pos.x, pos.y, pos.z, FACING_ATTACKER_YAW, 0);
        defender.player.setYHeadRot(FACING_ATTACKER_YAW);
        defender.player.setGameMode(GameType.SURVIVAL);
        if (shielded) {
            defender.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            defender.player.startUsingItem(InteractionHand.OFF_HAND);
            for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
                defender.player.doTick();
            }
        }
    }

    private static EffectIntent direct(UnaryOperator<LimbStrike.Builder> change) {
        // 拿测试拳的后手拳，改成这条用例要的直接路写法。
        EffectIntent punch = HumanFistGameTest.punch("rear");
        var strike = change.apply(punch.getLimbStrike().getStrike().toBuilder()).build();
        return punch.toBuilder().setLimbStrike(punch.getLimbStrike().toBuilder().setStrike(strike)).build();
    }

    private static Penetration penetration(double organShare, double armorMultiplier) {
        return Penetration.newBuilder()
                .setOrganShare(HumanTechniqueTestCatalog.quantity(organShare, Unit.RATIO))
                .setArmorMultiplier(HumanTechniqueTestCatalog.quantity(armorMultiplier, Unit.RATIO))
                .build();
    }

    private static StrikeAttachment numbWholeArm() {
        // 缠腕那种麻：整条手臂的肌肉乘 0.3，持续 6 秒。
        Numb numb = Numb.newBuilder()
                .setMultiplier(HumanTechniqueTestCatalog.quantity(NUMB_MULTIPLIER, Unit.RATIO))
                .setDurationMillis(NUMB_MILLIS)
                .setWholeArm(true /* wholeArm */)
                .build();
        return StrikeAttachment.newBuilder().setNumb(numb).build();
    }

    private static double partHp(BodySnapshot body, String partId) {
        return body.partStates().stream().filter(part -> part.id().value().equals(partId))
                .findFirst().orElseThrow().hp();
    }

    private static double totalPartHp(BodySnapshot body) {
        return body.partStates().stream().mapToDouble(part -> part.hp()).sum();
    }

    private static double organHp(BodySnapshot body) {
        return body.organStates().stream().mapToDouble(organ -> organ.hp()).sum();
    }

    private static void near(GameTestHelper context, double actual, double expected, String label) {
        // 只允许数值舍入误差。
        context.assertTrue(Math.abs(actual - expected) < HumanCombatFixture.EPSILON,
                label + "：预期 " + expected + "，实际 " + actual);
    }

    /** 两个玩家摆好以后要做的检查。 */
    private interface Check {
        void run(HumanCombatFixture attacker, HumanTechniqueTestPlayer defender);
    }
}
