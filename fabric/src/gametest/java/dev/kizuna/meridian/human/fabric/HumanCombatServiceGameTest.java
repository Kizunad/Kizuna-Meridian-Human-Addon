package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.playeranimation.network.AnimationPackets;
import dev.kizuna.playeranimation.protocol.pb.PlayerAnimationProtocol.PlayRequest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 从学习、绑定、费用到玩家世界效果的真实服务链。 */
public final class HumanCombatServiceGameTest implements FabricGameTest {
    private static final long MILLIS_PER_TICK = 50;
    private static final int KNOCK_HIT_TICKS = 34;
    private static final int KNOCK_END_TICKS = 60;
    private static final int KNOCK_FOLLOW_TICKS = 50;
    private static final int CLEAVE_FOLLOW_TICKS = 94;
    private static final int ALL_TIMELINES_TICKS = 163;
    private static final int FOLLOW_CLIP_MILLIS = 400;
    private static final int FINALE_CLIP_MILLIS = 1500;
    private static final int FINALE_EXHAUSTION_TICKS = 130;
    private static final int FINALE_RESTORED_TICKS = 280;
    private static final int ISOLATION_DISTANCE = 12;
    private static final double HEALTHY_TARGET_HP = 1000;
    private static final double EXHAUSTED_MULTIPLIER = 0.6;
    private static final double PEAK_MULTIPLIER = 1.4;
    // 万门一斩承接从目录 1500 ms 起播：前荡轻击 2300 ms、落地重劈 4850 ms。
    private static final int FINALE_LIGHT_HIT_AFTER_FOLLOW_TICKS = 16;
    private static final int FINALE_HIT_AFTER_FOLLOW_TICKS = 67;
    private static final int SHORT_TEST_TIMEOUT = 100;
    private static final int TIMELINE_TEST_TIMEOUT = 220;
    private static final int EXHAUSTION_TEST_TIMEOUT = 450;
    private static final int FINALE_TEST_TIMEOUT = 250;
    private static final double TICKS_PER_SECOND = 20;
    private static final double KNOCK_DIRT_DAMAGE = 1.5;
    private static final int KNOCK_HIT_COUNT = 3;
    private static final long STAGGER_MILLIS = 200;
    private static final double STAGGER_MOVEMENT = 0.5;
    private static final TechniqueService.Stagger HIT_STAGGER = new TechniqueService.Stagger(STAGGER_MILLIS,
            STAGGER_MOVEMENT);
    private static final float LIGHT_HIT = 0.2F;
    private static final float HEAVY_HIT = 4;
    private static final int ATTACKER_OFFSET = 6;
    private static final int ATTACKER_HEIGHT = 2;
    private static final int STAGGER_EXPIRY_TICKS = 30;
    private static final int JOIN_INVULNERABILITY_TICKS = 61;
    private static final int STAGGER_TEST_TIMEOUT = 150;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void doorAnimationsFollowSelectedHandAndOriginalStartTick(TestContext context) throws Exception {
        // 真实学习、选手与宿主播放经过同一服务链，最终检查发往玩家的 Protobuf 包。
        for (String move : List.of("knock", "cleave", "ram")) {
            for (Hand hand : Hand.values()) {
                try (var fixture = prepared(context)) {
                    fixture.human.player.setStackInHand(hand, new ItemStack(Items.IRON_AXE));
                    fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
                    long startTick = context.getWorld().getTime();
                    accepted(context, cast(fixture, move, CastEntry.AUTO));
                    String suffix = "";
                    if (hand == Hand.OFF_HAND) {
                        suffix = "_off";
                    }
                    String clip = "door_breaker_" + move + suffix;
                    assertAnimationPacket(context, fixture, clip, startTick);
                }
            }
        }
        context.complete();
    }

    private static void assertAnimationPacket(TestContext context, HumanCombatFixture fixture,
                                              String clip, long startTick) throws Exception {
        // 解析实际网络输出，不能只断言映射函数返回了预期字符串。
        var plays = new ArrayList<PlayRequest>();
        for (var packet : fixture.human.connection.packets) {
            if (packet instanceof CustomPayloadS2CPacket payload
                    && payload.getChannel().equals(AnimationPackets.PLAY)) {
                plays.add(AnimationPackets.read(payload.getData(), PlayRequest.parser()));
            }
        }
        context.assertTrue(plays.stream().anyMatch(play -> play.getAnimationId().equals(clip)
                && play.getStartTick() == startTick
                && play.getPlayerUuid().equals(fixture.human.player.getUuidAsString())),
                "玩家没有收到正确的 PA 片段和原始起点：" + clip + " / " + plays);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void handsRejectEmptyAndContainersAndSelectByCategoryWearThenMain(TestContext context) {
        // 同一真实施放入口覆盖拒绝码及选手，失败不能偷偷扣费或留下活动。
        try (var fixture = prepared(context)) {
            reject(context, cast(fixture, "knock", CastEntry.AUTO), TechniqueFailure.HELD_ITEM_REQUIRED);
            for (Item item : List.of(Items.SHULKER_BOX, Items.BUNDLE)) {
                fixture.human.player.setStackInHand(Hand.MAIN_HAND, container(item));
                reject(context, cast(fixture, "knock", CastEntry.AUTO), TechniqueFailure.HELD_ITEM_UNUSABLE);
            }
        }
        List<Hands> cases = List.of(new Hands(Items.AIR, Items.IRON_AXE, Selection.OFF),
                new Hands(Items.DIRT, Items.WOODEN_SWORD, Selection.OFF),
                new Hands(Items.DIAMOND_AXE, Items.IRON_AXE, Selection.OFF),
                new Hands(Items.IRON_AXE, Items.IRON_AXE, Selection.MAIN),
                new Hands(Items.IRON_PICKAXE, Items.WOODEN_SWORD, Selection.MAIN));
        for (Hands hands : cases) {
            try (var fixture = prepared(context)) {
                fixture.hold(hands.main(), hands.off());
                accepted(context, cast(fixture, "knock", CastEntry.AUTO));
                var selected = TechniqueState.read(fixture.human.body().snapshot()).getActiveCast().getSelectedHand();
                var expected = TechniqueMessages.Hand.MAIN_HAND;
                if (hands.selected() == Selection.OFF) {
                    expected = TechniqueMessages.Hand.OFF_HAND;
                }
                context.assertTrue(selected == expected, "选手顺序错误：" + hands);
            }
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TEST_TIMEOUT)
    public void switchingSelectedHandItemChangesHitAndEmptyHandMissStillPays(TestContext context) {
        // 起手固定手但不固定物品，改成泥土和变空分别在真实生效 tick 检查。
        var changed = prepared(context);
        var empty = prepared(context);
        moveAside(empty, 1);
        changed.hold(Items.IRON_AXE, Items.SHIELD);
        empty.hold(Items.IRON_AXE, Items.SHIELD);
        var target = changed.target(0, HumanCombatFixture.TARGET_DISTANCE);
        var missed = empty.target(0, HumanCombatFixture.TARGET_DISTANCE);
        accepted(context, cast(changed, "knock", CastEntry.AUTO));
        accepted(context, cast(empty, "knock", CastEntry.AUTO));
        double paid = storedQi(empty);
        changed.human.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.DIRT));
        empty.human.player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        context.waitAndRun(KNOCK_HIT_TICKS + 1, () -> {
            try (changed; empty) {
                double totalDose = target.doses.stream().mapToDouble(Float::doubleValue).sum();
                context.assertTrue(target.doses.size() == KNOCK_HIT_COUNT && Math.abs(totalDose - KNOCK_DIRT_DAMAGE)
                        < HumanCombatFixture.EPSILON, "打击沿用了起手铁斧");
                context.assertTrue(changed.human.player.getMainHandStack().isEmpty(), "未消耗当前泥土");
                context.assertTrue(missed.doses.isEmpty(), "选定手空了却改用另一手攻击");
                context.assertTrue(storedQi(empty) <= paid + HumanCombatFixture.EPSILON, "落空退还了费用");
                context.complete();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TEST_TIMEOUT)
    public void castingBlocksRealAttackAndSwingPacketsThenResetsCooldown(TestContext context) {
        // 真正走服务端攻击包与挥手包，取消期间不能伤害目标或重复记做功。
        var fixture = prepared(context);
        fixture.hold(Items.IRON_AXE, Items.SHIELD);
        var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
        double originalSpeed = fixture.human.player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        accepted(context, cast(fixture, "knock", CastEntry.AUTO));
        var tissues = fixture.human.body().snapshot().tissueStates();
        var packet = PlayerInteractEntityC2SPacket.attack(target, false /* playerSneaking */);
        fixture.human.player.networkHandler.onPlayerInteractEntity(packet);
        fixture.human.player.networkHandler.onHandSwing(new HandSwingC2SPacket(Hand.MAIN_HAND));
        context.assertTrue(target.doses.isEmpty(), "原版左键穿过了施放锁");
        context.assertTrue(fixture.human.body().snapshot().tissueStates().equals(tissues), "原版挥手重复做功");
        double expectedSpeed = originalSpeed * HumanCombatFixture.definition("knock").getMovementSpeedMultiplier();
        double speed = fixture.human.player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        context.assertTrue(speed > 0 && Math.abs(speed - expectedSpeed) < HumanCombatFixture.EPSILON,
                "施放没有应用目录移动倍率");
        context.waitAndRun(KNOCK_END_TICKS + 1, () -> {
            try (fixture) {
                context.assertTrue(!HumanTechniqueHost.isCasting(fixture.human.player.getUuid()), "结束仍锁攻击");
                context.assertTrue(fixture.human.player.getAttackCooldownProgress(0) < 1, "结束未重置原版攻击冷却");
                context.complete();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void warmupInterruptionDoesNotWearWeapon(TestContext context) {
        // 起手成功扣费后被僵直打断，尚未发生末击就不能提前扣物品。
        try (var fixture = prepared(context)) {
            fixture.hold(Items.IRON_AXE, Items.AIR);
            accepted(context, cast(fixture, "cleave", CastEntry.AUTO));
            TechniqueFabric.service().stagger(fixture.human.player.getUuid(), HIT_STAGGER);
            context.assertTrue(fixture.human.player.getMainHandStack().getDamage() == 0, "起手打断扣了耐久");
            context.assertTrue(!HumanTechniqueHost.isCasting(fixture.human.player.getUuid()), "打断未清理移动锁");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = STAGGER_TEST_TIMEOUT)
    public void cleaveWarmupInterruptedOnlyByStaggeringHit(TestContext context) {
        // 真实生物攻击经伤害附属换算：轻击低于僵直门槛不打断破门起手，重击打断、减速并拒绝新式。
        var attacker = attacker(context);
        var fixture = prepared(context);
        context.waitAndRun(JOIN_INVULNERABILITY_TICKS, () -> {
            try (fixture) {
                var player = fixture.human.player;
                fixture.hold(Items.IRON_AXE, Items.AIR);
                double originalSpeed = player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                accepted(context, cast(fixture, "cleave", CastEntry.AUTO));
                player.damage(player.getDamageSources().mobAttack(attacker), LIGHT_HIT);
                context.assertTrue(HumanTechniqueHost.isCasting(player.getUuid()), "低于门槛的轻击打断了起手");
                context.assertTrue(staggerMillis(player) == 0, "轻击产生了僵直");
                player.timeUntilRegen = 0;
                player.damage(player.getDamageSources().mobAttack(attacker), HEAVY_HIT);
                context.assertTrue(!HumanTechniqueHost.isCasting(player.getUuid()), "僵直重击没有打断起手");
                context.assertTrue(staggerMillis(player) > 0, "重击没有僵直");
                assertSpeed(context, player, originalSpeed * STAGGER_MOVEMENT, "僵直移动倍率错误");
                reject(context, cast(fixture, "knock", CastEntry.AUTO), TechniqueFailure.STAGGERED);
            } finally {
                attacker.discard();
            }
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = STAGGER_TEST_TIMEOUT)
    public void staggerHalvesMovementUntilExpiry(TestContext context) {
        // 未施放时受重击也僵直：移动速度乘 0.5，身体时钟到期后恢复原速。
        var attacker = attacker(context);
        var fixture = prepared(context);
        var player = fixture.human.player;
        double originalSpeed = player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        context.waitAndRun(JOIN_INVULNERABILITY_TICKS, () -> {
            player.damage(player.getDamageSources().mobAttack(attacker), HEAVY_HIT);
            attacker.discard();
            context.assertTrue(staggerMillis(player) > 0, "未施放时重击没有僵直");
            assertSpeed(context, player, originalSpeed * STAGGER_MOVEMENT, "僵直未减速");
            context.waitAndRun(STAGGER_EXPIRY_TICKS, () -> {
                try (fixture) {
                    assertSpeed(context, player, originalSpeed, "僵直到期未恢复原速");
                    context.assertTrue(staggerMillis(player) == 0, "僵直没有按身体时钟到期");
                    context.complete();
                }
            });
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TIMELINE_TEST_TIMEOUT)
    public void sevenStandaloneStrikesUseCatalogTicksAndChainCanRehit(TestContext context) {
        // 七式同时放到彼此隔离的位置，记录真实伤害时刻而非只比对目录字段。
        var runs = new ArrayList<Timeline>();
        String[] names = {"knock", "cleave", "ram", "hook", "sweep", "close", "chain"};
        for (int index = 0; index < names.length; index++) {
            var fixture = prepared(context);
            moveAside(fixture, index);
            fixture.hold(Items.DIRT, Items.SHIELD);
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            target.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(HEALTHY_TARGET_HP);
            target.setHealth((float) HEALTHY_TARGET_HP);
            accepted(context, cast(fixture, names[index], CastEntry.AUTO));
            long started = TechniqueState.read(fixture.human.body().snapshot()).getActiveCast().getStartedMillis();
            runs.add(new Timeline(fixture, target, names[index], started));
        }
        context.waitAndRun(ALL_TIMELINES_TICKS, () -> {
            try {
                for (Timeline run : runs) {
                    List<Long> expected = expectedHits(run);
                    context.assertTrue(run.target().hitMillis.equals(expected),
                            run.name() + " 打击时刻错误：" + run.target().hitMillis + " / " + expected
                                    + "; targetRemoved=" + run.target().isRemoved());
                    assertImpulses(context, run, 0);
                }
                context.complete();
            } finally {
                runs.forEach(run -> run.fixture().close());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TEST_TIMEOUT)
    public void followUpShortensWarmupAndEndsPredecessorWithinWindow(TestContext context) {
        // 过早显式承接被拒，收势后半段进入新式并沿用原式正常冷却边界。
        var fixture = prepared(context);
        fixture.hold(Items.IRON_AXE, Items.SHIELD);
        double originalSpeed = fixture.human.player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        accepted(context, cast(fixture, "knock", CastEntry.AUTO));
        var original = TechniqueState.read(fixture.human.body().snapshot()).getActiveCast();
        reject(context, cast(fixture, "cleave", CastEntry.FOLLOW_UP), TechniqueFailure.FOLLOW_UP_UNAVAILABLE);
        context.waitAndRun(KNOCK_FOLLOW_TICKS, () -> {
            try (fixture) {
                accepted(context, cast(fixture, "cleave", CastEntry.FOLLOW_UP));
                double expectedSpeed = originalSpeed
                        * HumanCombatFixture.definition("cleave").getMovementSpeedMultiplier();
                double speed = fixture.human.player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                context.assertTrue(Math.abs(speed - expectedSpeed) < HumanCombatFixture.EPSILON,
                        "承接叠加了前式限速");
                var progress = TechniqueState.read(fixture.human.body().snapshot());
                var current = progress.getActiveCast();
                context.assertTrue(current.getClipStartMillis() == FOLLOW_CLIP_MILLIS, "承接剪辑起播点错误");
                long warmup = HumanCombatFixture.definition("cleave").getPhases().getWarmupMillis();
                context.assertTrue(current.getWarmupEndMillis() - current.getStartedMillis()
                        == warmup - FOLLOW_CLIP_MILLIS, "承接没有缩短起手");
                context.assertTrue(!current.getRequest().getCastId().equals(original.getRequest().getCastId()),
                        "前式收势没有被新式替换");
                long cooldown = progress.getCooldownsOrThrow(HumanCombatTestCatalog.NAMESPACE + ":knock");
                context.assertTrue(cooldown == original.getRecoveryEndMillis()
                        + HumanCombatFixture.definition("knock").getCooldownMillis(), "承接提前了前式冷却");
                context.complete();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = EXHAUSTION_TEST_TIMEOUT)
    public void finaleRequiresFollowUpAndEarlyInterruptionStillExhaustsThenRecovers(TestContext context) {
        // 真正承接终式并在起跳前打断，已原子提交的手臂曲线仍进入虚脱和恢复。
        var fixture = prepared(context);
        fixture.hold(Items.IRON_AXE, Items.SHIELD);
        reject(context, cast(fixture, "finale", CastEntry.AUTO), TechniqueFailure.FOLLOW_UP_REQUIRED);
        accepted(context, cast(fixture, "cleave", CastEntry.AUTO));
        context.waitAndRun(CLEAVE_FOLLOW_TICKS, () -> {
            accepted(context, cast(fixture, "finale", CastEntry.FOLLOW_UP));
            var active = TechniqueState.read(fixture.human.body().snapshot()).getActiveCast();
            context.assertTrue(active.getClipStartMillis() == FINALE_CLIP_MILLIS, "终式未从聚势后起播");
            TechniqueFabric.service().stagger(fixture.human.player.getUuid(), HIT_STAGGER);
            context.assertTrue(!HumanTechniqueHost.isCasting(fixture.human.player.getUuid()), "终式打断没有解锁");
            context.waitAndRun(FINALE_EXHAUSTION_TICKS, () -> assertArms(context, fixture, EXHAUSTED_MULTIPLIER));
            context.waitAndRun(FINALE_RESTORED_TICKS, () -> {
                try (fixture) {
                    assertArms(context, fixture, 1);
                    context.complete();
                }
            });
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = FINALE_TEST_TIMEOUT)
    public void finaleHitsAtShortenedTickInsideArmPeak(TestContext context) {
        // 承接终式实际跃起并命中，检查伤害链发生在曲线峰顶并成功支付挥手做功。
        var fixture = prepared(context);
        fixture.hold(Items.IRON_AXE, Items.SHIELD);
        accepted(context, cast(fixture, "cleave", CastEntry.AUTO));
        context.waitAndRun(CLEAVE_FOLLOW_TICKS, () -> {
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            target.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(HEALTHY_TARGET_HP);
            target.setHealth((float) HEALTHY_TARGET_HP);
            accepted(context, cast(fixture, "finale", CastEntry.FOLLOW_UP));
            long started = TechniqueState.read(fixture.human.body().snapshot()).getActiveCast().getStartedMillis();
            double[] beforeWork = new double[1];
            context.waitAndRun(FINALE_HIT_AFTER_FOLLOW_TICKS - 1,
                    () -> beforeWork[0] = HumanCombatWorldGameTest.armFatigue(fixture, "right"));
            context.waitAndRun(FINALE_HIT_AFTER_FOLLOW_TICKS + 1, () -> {
                try (fixture) {
                    // 目标在两刀范围内，先吃前荡轻击，再吃曲线峰顶的落地重劈。
                    long light = started + FINALE_LIGHT_HIT_AFTER_FOLLOW_TICKS * MILLIS_PER_TICK;
                    long expected = started + FINALE_HIT_AFTER_FOLLOW_TICKS * MILLIS_PER_TICK;
                    context.assertTrue(target.hitMillis.equals(List.of(light, expected)),
                            "终式打击 tick 错误：" + target.hitMillis);
                    assertImpulses(context, new Timeline(fixture, target, "finale", started), FINALE_CLIP_MILLIS);
                    assertArms(context, fixture, PEAK_MULTIPLIER);
                    context.assertTrue(target.getHealth() < HEALTHY_TARGET_HP, "终式没有实际造成伤害");
                    context.assertTrue(HumanCombatWorldGameTest.armFatigue(fixture, "right") > beforeWork[0],
                            "峰顶挥手没有完成做功");
                    context.complete();
                }
            });
        });
    }

    private static void assertArms(TestContext context, HumanCombatFixture fixture, double multiplier) {
        // 读取 Framework 实际能力，不把持有曲线记录等同于曲线已生效。
        var body = fixture.human.body().snapshot();
        var ordinaryMessage = BodyProtobuf.toMessage(body).toBuilder()
                .clearModifiers()
                .build();
        var ordinary = BodyProtobuf.fromMessage(ordinaryMessage);
        var arms = body.structure().tissues().stream()
                .filter(tissue -> tissue.tags().stream().anyMatch(tag -> tag.value().equals("human:arm_muscle")))
                .toList();
        context.assertTrue(!arms.isEmpty(), "人体没有手臂肌肉");
        for (var arm : arms) {
            double actual = TissueCapabilities.available(body, arm.id()).strengthNewtons();
            double expected = TissueCapabilities.available(ordinary, arm.id()).strengthNewtons() * multiplier;
            context.assertTrue(Math.abs(actual - expected) < HumanCombatFixture.EPSILON, "手臂曲线倍率错误");
        }
    }

    private static long staggerMillis(ServerPlayerEntity player) {
        // 僵直剩余时长只读功法库服务端权威记录。
        return TechniqueFabric.service().staggerRemainingMillis(player.getUuid());
    }

    private static void assertSpeed(TestContext context, ServerPlayerEntity player, double expected, String message) {
        // 移动速度读属性最终值，覆盖僵直临时修饰与施放限速的乘积。
        double speed = player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        context.assertTrue(Math.abs(speed - expected) < HumanCombatFixture.EPSILON, message + "：" + speed);
    }

    private static ZombieEntity attacker(TestContext context) {
        // 静止僵尸只提供真实生物攻击来源，徒手命中按钝击换算僵直。
        ZombieEntity zombie = context.spawnEntity(EntityType.ZOMBIE, ATTACKER_OFFSET, ATTACKER_HEIGHT, ATTACKER_OFFSET);
        zombie.setAiDisabled(true /* aiDisabled */);
        return zombie;
    }

    private static HumanCombatFixture prepared(TestContext context) {
        // 学习和开脉走实际服务；测试目录仍支付小额真实气与组织做功。
        var fixture = new HumanCombatFixture(context);
        long now = fixture.human.player.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
        context.assertTrue(fixture.human.body().completeOpening(now, "left_large_intestine_meridian")
                == FailureCode.FAILURE_CODE_UNSPECIFIED, "开脉失败");
        for (String volume : List.of("upper", "middle", "lower")) {
            TechniqueFabric.service().learn(fixture.human.player.getUuid(),
                    HumanCombatTestCatalog.NAMESPACE + ":" + volume);
        }
        return fixture;
    }

    private static CastOutcome cast(HumanCombatFixture fixture, String name, CastEntry entry) {
        // 请求只包含输入和目录引用，不能把持物、费用或算子伪造到请求里。
        var reference = TechniqueReference.newBuilder()
                .setId(HumanCombatTestCatalog.NAMESPACE + ":" + name)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(fixture.human.player.getUuidAsString())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .setEntry(entry);
        if (!fixture.targets.isEmpty()) {
            request.setTargetId(fixture.targets.get(0).getUuidAsString());
        }
        return TechniqueFabric.service().cast(fixture.human.player.getUuid(), request.build());
    }

    private static void accepted(TestContext context, CastOutcome result) {
        // 只接受真实起手提交，不以请求没有抛异常当作施放成功。
        context.assertTrue(result.getPhase() == CastPhase.WARMUP, "施放失败：" + result);
    }

    private static void reject(TestContext context, CastOutcome result, TechniqueFailure expected) {
        // 明确业务失败码，不能把库存不足或宿主错误误当成预期拒绝。
        context.assertTrue(result.getFailure() == expected, "拒绝码错误：" + result + " / " + expected);
    }

    private static ItemStack container(Item item) {
        // 保留实际 NBT 库存，空容器不应触发这一拒绝。
        var stack = new ItemStack(item);
        var items = new NbtList();
        items.add(new ItemStack(Items.STONE).writeNbt(new NbtCompound()));
        NbtCompound inventory;
        if (item == Items.SHULKER_BOX) {
            inventory = stack.getOrCreateSubNbt("BlockEntityTag");
        } else {
            inventory = stack.getOrCreateNbt();
        }
        inventory.put("Items", items);
        return stack;
    }

    private static void moveAside(HumanCombatFixture fixture, int index) {
        // 在独立高度隔离并发施放，避免横向落进其他测试结构或玩家的搜索范围。
        Vec3d pos = fixture.human.player.getPos().add(0, (index + 1) * ISOLATION_DISTANCE, 0);
        fixture.human.player.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
    }

    private static List<Long> expectedHits(Timeline run) {
        // 横扫前后同组只能击中同一目标一次，连门各组应各有一次。
        List<Long> all = HumanCombatFixture.definition(run.name()).getEffectsList().stream()
                .filter(TechniqueEffect::hasWeaponStrike)
                .map(effect -> effect.getAtMillis() + run.started()).toList();
        if (run.name().equals("sweep")) {
            return List.of(all.get(0));
        }
        return all;
    }

    private static void assertImpulses(TestContext context, Timeline run, long clipStartMillis) {
        // 比对实际 setVelocity 时刻与分量，同时要求限速不把冲量或主动移速归零。
        var player = (HumanCombatFixture.ObservedPlayer) run.fixture().human.player;
        for (TechniqueEffect effect : HumanCombatFixture.definition(run.name()).getEffectsList()) {
            if (!effect.hasImpulse()) {
                continue;
            }
            long expectedTime = run.started() + effect.getAtMillis() - clipStartMillis;
            double speed = effect.getImpulse().getSpeed().getValue() / TICKS_PER_SECOND;
            boolean applied = player.velocities.stream().anyMatch(change -> change.millis() == expectedTime
                    && change.movement() > 0 && matchesImpulse(change.velocity(), effect.getImpulse(), speed));
            context.assertTrue(applied, run.name() + " 冲量没有在声明 tick 原速执行：" + effect.getAtMillis());
        }
    }

    private static boolean matchesImpulse(Vec3d velocity, Impulse impulse, double speed) {
        // UP 只检查竖直分量，其余方向只检查水平分量，允许正交冲量同刻合成。
        if (impulse.getDirection() == ImpulseDirection.UP) {
            return Math.abs(velocity.y - speed) < HumanCombatFixture.EPSILON;
        }
        return Math.abs(velocity.horizontalLength() - speed) < HumanCombatFixture.EPSILON;
    }

    private static double storedQi(HumanCombatFixture fixture) {
        // 被动流动可能改变经脉和在途分布，身体总库存用于检查落空没有退费。
        return fixture.human.body().snapshot().totalQi();
    }

    private enum Selection { MAIN, OFF }
    private record Hands(Item main, Item off, Selection selected) { }
    private record Timeline(HumanCombatFixture fixture, HumanCombatFixture.Target target, String name, long started) { }
}
