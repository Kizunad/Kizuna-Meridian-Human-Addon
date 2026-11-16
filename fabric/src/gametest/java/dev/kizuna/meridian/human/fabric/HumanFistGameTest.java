package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ArmRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegKnockbackKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostFacts;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueService.StrikeOutcome;
import dev.kizuna.meridian.technique.server.TechniqueService.StrikeReport;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 「测试拳」经 Human 玩家宿主出拳：持物不参与、剂量、出力侧、掀起、站地条件和打击回报。 */
public final class HumanFistGameTest {
    private static final double BASE_DAMAGE = 1.5;
    private static final double MULTIPLIER = 1.2;
    private static final double BOTH_ARMS = 1.2;
    private static final long MILLIS_PER_TICK = 50;
    private static final double TICKS_PER_SECOND = 20;
    /** 起手 100 ms 合两 tick，多等几 tick 保证出拳已经结算。 */
    private static final int STRIKE_WAIT_TICKS = 6;
    private static final int ISOLATION_DISTANCE = 12;
    /** 原版举盾要用满几 tick 才进入格挡。 */
    private static final int SHIELD_READY_TICKS = 6;
    /** 新登录玩家的出生无敌时长，过后盾挡才是唯一没受伤的原因。 */
    private static final int JOIN_INVULNERABILITY_TICKS = 60;
    private static final float FACING_ATTACKER_YAW = 180;
    private static final double LIFT_SPEED = 4;
    private static final List<Double> LIFT_RESISTANCES = List.of(0.0, 0.5);
    private static final String ARMS_NOT_GROUNDED = "要站在地上才能出拳";
    private static final String OPENED_MERIDIAN = "left_large_intestine_meridian";
    private static final List<Item> HELD_ITEMS = List.of(Items.AIR, Items.IRON_SWORD, Items.SHIELD, Items.TORCH);
    private static final List<Punch> PUNCHES = List.of(
            new Punch("front", Side.OFF, 1),
            new Punch("rear", Side.MAIN, 1),
            new Punch("both", Side.BOTH, BOTH_ARMS));

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void fistsCastWithAnyHeldItemAndLeaveItUntouched(GameTestHelper context) {
        // 两手同拿空手、剑、盾或火把都能起手；命中后两手物品的种类、数量和耐久都不变。
        List<Held> runs = new ArrayList<>();
        for (int index = 0; index < HELD_ITEMS.size(); index++) {
            Item item = HELD_ITEMS.get(index);
            var fixture = prepared(context, ServerPlayer::new);
            moveAside(fixture, index);
            fixture.hold(item, item);
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            runs.add(new Held(fixture, target, item));
            fixture.human.player.setOnGround(true /* onGround */);
            accepted(context, cast(fixture, "both"));
        }
        context.runAfterDelay(STRIKE_WAIT_TICKS, () -> {
            try {
                runs.forEach(run -> assertUntouched(context, run));
                context.succeed();
            } finally {
                runs.forEach(run -> run.fixture().close());
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void fistDoseAndMuscleSideFollowArmAndMainHand(GameTestHelper context) {
        // 剂量 = 书级 1.5 × 本击倍率，双手再 × 1.2；前手累副手侧、后手累主手侧，左撇子整体镜像。
        for (HumanoidArm mainArm : HumanoidArm.values()) {
            for (Punch punch : PUNCHES) {
                try (var fixture = new HumanCombatFixture(context)) {
                    fixture.human.player.setMainArm(mainArm);
                    var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
                    fixture.submit(punch(punch.name()));
                    String label = mainArm.getSerializedName() + " 主手 " + punch.name() + "：";
                    context.assertTrue(target.doses.size() == 1 && target.getHealth() < target.getMaxHealth(),
                            label + "出拳没有进入真实伤害链");
                    near(context, target.doses.get(0), BASE_DAMAGE * MULTIPLIER * punch.factor(), label + "剂量");
                    assertSides(context, fixture, punch, mainArm, label);
                }
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void liftVerticalSpeedScalesWithKnockbackResistance(GameTestHelper context) {
        // 掀起在推开之后把竖直速度设为 目录速度 ÷ 20 × (1 − 击退抗性)。
        for (double resistance : LIFT_RESISTANCES) {
            try (var fixture = new HumanCombatFixture(context)) {
                var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
                target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(resistance);
                fixture.submit(lift(punch("both")));
                double expected = LIFT_SPEED / TICKS_PER_SECOND * (1 - resistance);
                near(context, target.getDeltaMovement().y, expected, "击退抗性 " + resistance + " 的掀起竖直速度");
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void offGroundFistIsRefusedBeforePayment(GameTestHelper context) {
        // 水中、空中、攀爬、骑乘、滑翔各用一个学会测试拳的新玩家：
        // 宿主如实回报这项事实，起手返回 NOT_GROUNDED 和出拳的说法，身体修订号不变。
        for (Footing footing : Footing.values()) {
            try (var fixture = prepared(context, FootingPlayer::new)) {
                fixture.human.player.setOnGround(true /* onGround */);
                Optional<Entity> vehicle = leaveGround(context, fixture, footing);
                context.assertTrue(footing.reported(fixture.inspect().getInspectFacts()), footing + " 宿主没有回报");
                long revision = fixture.inspect().getBody().getRevision();
                CastOutcome result = cast(fixture, "front");
                context.assertTrue(result.getFailure() == TechniqueFailure.NOT_GROUNDED
                        && result.getReason().equals(ARMS_NOT_GROUNDED), footing + " 拒绝错误：" + result);
                context.assertTrue(fixture.inspect().getBody().getRevision() == revision, footing + " 拒绝前改了身体");
                vehicle.ifPresent(Entity::discard);
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void realFistCastReportsHitWithCastAndTechnique(GameTestHelper context) {
        // 真实起手到时出拳打中，回报恰好一次 HIT：施放身份、精确式、施放者和目标都对得上。
        var fixture = prepared(context, ServerPlayer::new);
        var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
        UUID caster = fixture.human.player.getUUID();
        List<StrikeReport> reports = new ArrayList<>();
        Consumer<StrikeReport> listener = reportsBy(caster, reports);
        TechniqueFabric.service().addStrikeListener(listener);
        fixture.human.player.setOnGround(true /* onGround */);
        String castId = UUID.randomUUID().toString();
        accepted(context, cast(fixture, "front", castId));
        context.runAfterDelay(STRIKE_WAIT_TICKS, () -> {
            try (fixture) {
                var expected = new StrikeReport(castId, reference("front"), caster, target.getUUID(),
                        StrikeOutcome.HIT);
                context.assertTrue(reports.equals(List.of(expected)), "打中回报错误：" + reports);
                context.succeed();
            } finally {
                TechniqueFabric.service().removeStrikeListener(listener);
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shieldBlockedFistReportsBlockedButUnnamedIntentStaysSilent(GameTestHelper context) {
        // 正面举盾挡下的出拳回报 BLOCKED；不带所属式的意图照样结算，但不回报。
        var attacker = new HumanCombatFixture(context);
        var defender = new HumanTechniqueTestPlayer(context);
        var server = context.getLevel().getServer();
        // 26.2 的 PVP 开关是游戏规则，测试结束恢复原设置。
        boolean originalPvp = server.getGameRules().get(GameRules.PVP);
        server.getGameRules().set(GameRules.PVP, true /* pvp */, server);
        raiseShield(attacker, defender);
        UUID caster = attacker.human.player.getUUID();
        List<StrikeReport> reports = new ArrayList<>();
        Consumer<StrikeReport> listener = reportsBy(caster, reports);
        TechniqueFabric.service().addStrikeListener(listener);
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS + 1, () -> {
            try (attacker; defender) {
                context.assertTrue(defender.player.isBlocking(), "真实盾没有进入格挡");
                float before = defender.player.getHealth();
                attacker.submit(punch("front"));
                EffectIntent named = named("rear");
                attacker.submit(named);
                near(context, defender.player.getHealth(), before, "盾应挡住出拳");
                var expected = new StrikeReport(named.getCastId(), reference("rear"), caster,
                        defender.player.getUUID(), StrikeOutcome.BLOCKED);
                context.assertTrue(reports.equals(List.of(expected)), "被挡回报错误：" + reports);
                context.succeed();
            } finally {
                TechniqueFabric.service().removeStrikeListener(listener);
                server.getGameRules().set(GameRules.PVP, originalPvp, server);
            }
        });
    }

    private static HumanCombatFixture prepared(GameTestHelper context,
                                               HumanTechniqueTestPlayer.PlayerFactory factory) {
        // 开一条手经、学会测试拳整卷，起手只剩站地条件和真实费用。
        var fixture = new HumanCombatFixture(context, factory);
        long now = fixture.human.player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        context.assertTrue(fixture.human.body().completeOpening(now, OPENED_MERIDIAN)
                == FailureCode.FAILURE_CODE_UNSPECIFIED, "开脉失败");
        TechniqueFabric.service().learn(fixture.human.player.getUUID(), HumanFistTestCatalog.VOLUME_ID);
        return fixture;
    }

    private static CastOutcome cast(HumanCombatFixture fixture, String name) {
        // 每次施放用新的施放身份。
        return cast(fixture, name, UUID.randomUUID().toString());
    }

    private static CastOutcome cast(HumanCombatFixture fixture, String name, String castId) {
        // 请求只带功法引用和施放身份，持物、费用和站地都由服务端自己读。
        var request = CastRequest.newBuilder()
                .setBodyId(fixture.human.player.getStringUUID())
                .setTechnique(reference(name))
                .setCastId(castId)
                .setEntry(CastEntry.AUTO)
                .build();
        return TechniqueFabric.service().cast(fixture.human.player.getUUID(), request);
    }

    private static TechniqueReference reference(String name) {
        // 测试拳三式都是第 1 版。
        return TechniqueReference.newBuilder()
                .setId(HumanFistTestCatalog.NAMESPACE + ":" + name)
                .setVersion(1)
                .build();
    }

    private static EffectIntent named(String name) {
        // 和调度器下发的一样标上所属式，宿主据此回报。
        return punch(name).toBuilder()
                .setTechnique(reference(name))
                .build();
    }

    private static Consumer<StrikeReport> reportsBy(UUID caster, List<StrikeReport> sink) {
        // 别的测试同时在出拳，只收这个施放者的回报。
        return report -> {
            if (report.casterUuid().equals(caster)) {
                sink.add(report);
            }
        };
    }

    private static void raiseShield(HumanCombatFixture attacker, HumanTechniqueTestPlayer defender) {
        // 守方站在出拳方正前方、面朝出拳方，副手举起真实盾并用满原版格挡延迟。
        Vec3 pos = attacker.human.player.position().add(0, 0, HumanCombatFixture.TARGET_DISTANCE);
        defender.player.snapTo(pos.x, pos.y, pos.z, FACING_ATTACKER_YAW, 0);
        defender.player.setYHeadRot(FACING_ATTACKER_YAW);
        defender.player.setGameMode(GameType.SURVIVAL);
        defender.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.player.startUsingItem(InteractionHand.OFF_HAND);
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            defender.player.doTick();
        }
    }

    static EffectIntent punch(String name) {
        // 把目录里的那一记出拳连同书级拳掌规则交给宿主，和调度到时下发的意图同形。
        TechniqueDefinition definition = TechniqueFabric.service().registry()
                .findLatest(HumanFistTestCatalog.NAMESPACE + ":" + name)
                .orElseThrow();
        TechniqueEffect source = definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasLimbStrike)
                .findFirst()
                .orElseThrow();
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        ArmRules rules = TechniqueFabric.service().registry().bookOf(reference).orElseThrow().getArmRules();
        var intent = LimbStrikeIntent.newBuilder()
                .setStrike(source.getLimbStrike())
                .setArmRules(rules)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(UUID.randomUUID().toString())
                .setEffectId(name)
                .setScheduledMillis(source.getAtMillis())
                .setLimbStrike(intent)
                .build();
    }

    private static EffectIntent lift(EffectIntent punch) {
        // 把目录里的推开改成掀起，水平分量沿用目录推开大小。
        var strike = punch.getLimbStrike().getStrike().toBuilder()
                .setKnockbackKind(LegKnockbackKind.LIFT)
                .setLiftSpeed(HumanTechniqueTestCatalog.quantity(LIFT_SPEED, Unit.METERS_PER_SECOND))
                .build();
        var intent = punch.getLimbStrike().toBuilder().setStrike(strike).build();
        return punch.toBuilder().setLimbStrike(intent).build();
    }

    private static void assertUntouched(GameTestHelper context, Held run) {
        // 出拳恰好命中一次，两手物品原样留着。
        String label = run.item() + "：";
        context.assertTrue(run.target().doses.size() == 1, label + "出拳没有恰好命中一次");
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = run.fixture().human.player.getItemInHand(hand);
            context.assertTrue(unchanged(stack, run.item()), label + hand + " 物品被出拳改动：" + stack);
        }
    }

    private static boolean unchanged(ItemStack stack, Item item) {
        // 空手仍空；拿着东西时仍是同一种、一个、没掉耐久。
        if (item == Items.AIR) {
            return stack.isEmpty();
        }
        return stack.is(item) && stack.getCount() == 1 && stack.getDamageValue() == 0;
    }

    private static void assertSides(GameTestHelper context, HumanCombatFixture fixture, Punch punch,
                                    HumanoidArm mainArm, String label) {
        // 只有出力侧手臂肌肉累积疲劳，另一侧保持为零。
        Set<HumanoidArm> working = switch (punch.side()) {
            case MAIN -> Set.of(mainArm);
            case OFF -> Set.of(mainArm.getOpposite());
            case BOTH -> Set.of(HumanoidArm.values());
        };
        for (HumanoidArm arm : HumanoidArm.values()) {
            boolean tired = HumanCombatWorldGameTest.armFatigue(fixture, arm.getSerializedName()) > 0;
            context.assertTrue(tired == working.contains(arm), label + arm.getSerializedName() + " 臂做功错误");
        }
    }

    private static Optional<Entity> leaveGround(GameTestHelper context, HumanCombatFixture fixture,
                                                Footing footing) {
        // 骑乘、滑翔和离地走原版入口；入水和攀爬只在实体 tick 里刷新，由测试玩家直接给出。
        var player = (FootingPlayer) fixture.human.player;
        if (footing == Footing.RIDING) {
            return Optional.of(mount(context, player));
        }
        switch (footing) {
            case WATER -> player.inWater = true;
            case AIR -> player.setOnGround(false /* onGround */);
            case CLIMBING -> player.climbing = true;
            case GLIDING -> player.startFallFlying();
            default -> throw new IllegalArgumentException("未处理的离地方式：" + footing);
        }
        return Optional.empty();
    }

    private static Minecart mount(GameTestHelper context, ServerPlayer player) {
        // 在玩家脚下放一辆真实矿车并坐上去，宿主读到的是原版乘客状态。
        var cart = new Minecart(EntityTypes.MINECART, player.level());
        cart.snapTo(player.getX(), player.getY(), player.getZ(), 0, 0);
        context.assertTrue(context.getLevel().addFreshEntity(cart), "矿车生成失败");
        context.assertTrue(player.startRiding(cart), "玩家没有坐上矿车");
        return cart;
    }

    private static void accepted(GameTestHelper context, CastOutcome result) {
        // 只认真实起手，不以请求没有抛异常当作施放成功。
        context.assertTrue(result.getPhase() == CastPhase.WARMUP, "施放失败：" + result);
    }

    private static void moveAside(HumanCombatFixture fixture, int index) {
        // 并发施放各占一层高度，互相不在对方的出拳范围里。
        Vec3 pos = fixture.human.player.position().add(0, (index + 1) * ISOLATION_DISTANCE, 0);
        fixture.human.player.snapTo(pos.x, pos.y, pos.z, 0, 0);
    }

    private static void near(GameTestHelper context, double actual, double expected, String label) {
        // 浮点剂量和速度只允许数值舍入误差。
        context.assertTrue(Math.abs(actual - expected) < HumanCombatFixture.EPSILON,
                label + "：预期 " + expected + "，实际 " + actual);
    }

    /** 测试玩家：入水和攀爬可由测试直接设定，其余都是原版玩家。 */
    static final class FootingPlayer extends ServerPlayer {
        boolean inWater;
        boolean climbing;

        FootingPlayer(MinecraftServer server, ServerLevel world, GameProfile profile,
                      ClientInformation information) {
            // 只多出两项可设的移动事实。
            super(server, world, profile, information);
        }

        @Override
        public boolean isInWater() {
            // 原版入水状态要等实体 tick 才刷新；测试设了就按入水回报。
            return inWater || super.isInWater();
        }

        @Override
        public boolean onClimbable() {
            // 原版攀爬读缓存的所在方块；测试设了就按攀爬回报。
            return climbing || super.onClimbable();
        }
    }

    private enum Footing {
        WATER, AIR, CLIMBING, RIDING, GLIDING;

        boolean reported(TechniqueHostFacts facts) {
            // 宿主回执里与这种离地方式对应的那一项移动事实。
            return switch (this) {
                case WATER -> facts.getInWater();
                case AIR -> !facts.getGrounded();
                case CLIMBING -> facts.getClimbing();
                case RIDING -> facts.getRiding();
                case GLIDING -> facts.getGliding();
            };
        }
    }

    private enum Side { OFF, MAIN, BOTH }
    private record Punch(String name, Side side, double factor) { }
    private record Held(HumanCombatFixture fixture, HumanCombatFixture.Target target, Item item) { }
}
