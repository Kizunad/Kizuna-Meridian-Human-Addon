package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.human.fabric.HumanGuardTestCatalog.RECOVERY_TICKS;
import static dev.kizuna.meridian.human.fabric.HumanGuardTestCatalog.WARMUP_TICKS;

/** 真实登录玩家的守式准入、完整占用、退出清理和动画通知。 */
public final class HumanGuardGameTest {
    private static final double EPSILON = 1e-7;
    private static final double ATTACK_DISTANCE = 2;
    private static final double SMALL_HIT = 0.1;
    private static final double DESTROY_ARM = 100;
    private static final long CONTROL_MILLIS = 1000;
    private static final int DIG_WAIT_TICKS = 55;
    private static final int DIG_SEQUENCE = 7;
    private static final float FRONT_YAW = 0;
    private static final float BACK_YAW = 180;
    private static final String WEAPON_ENVIRONMENT = "meridian_human_test:weapon_guard";

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void raisedShieldRejectsBeforeStoppingUse(GameTestHelper context) {
        // 拒绝必须保留已经举起的盾、身体版本与移速；放下以后同一守式可以起。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD));
            fixture.player.startUsingItem(InteractionHand.MAIN_HAND);
            context.assertTrue(fixture.player.isUsingItem(), "夹具没有举盾");
            long revision = fixture.body().snapshot().revision();
            double speed = speed(fixture.player);
            var rejected = HumanGuardTestCatalog.start(fixture.player);
            context.assertTrue(rejected.getPhase() != CastPhase.WARMUP
                    && rejected.toString().contains(HumanLang.GUARD_SHIELD_RAISED), "举盾应拒绝起守");
            context.assertTrue(fixture.player.isUsingItem(), "拒绝起式却放下了盾");
            context.assertTrue(fixture.body().snapshot().revision() == revision, "拒绝仍修改身体");
            assertSpeed(context, fixture.player, speed);
            fixture.player.stopUsingItem();
            start(context, fixture);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void interactionsStayBlockedThroughRecovery(GameTestHelper context) {
        // 已开始的物品使用立即停止；五个回调和绕过回调的直接入口均受同一把锁约束。
        var fixture = new HumanTechniqueTestPlayer(context);
        fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        fixture.player.startUsingItem(InteractionHand.MAIN_HAND);
        context.assertTrue(fixture.player.isUsingItem(), "夹具未开始物品使用");
        String cast = start(context, fixture);
        context.assertTrue(!fixture.player.isUsingItem(), "起守没有停止已有使用");
        assertInteractions(context, fixture);
        context.runAfterDelay(WARMUP_TICKS + 1, () -> {
            assertInteractions(context, fixture);
            HumanGuardTestCatalog.release(fixture.player, cast);
            assertInteractions(context, fixture);
            context.runAfterDelay(RECOVERY_TICKS - 1, () -> assertInteractions(context, fixture));
            context.runAfterDelay(RECOVERY_TICKS + 1, () -> {
                try (fixture) {
                    context.assertTrue(!HumanTechniqueHost.isCasting(fixture.player.getUUID()), "收势结束仍锁输入");
                    fixture.player.startUsingItem(InteractionHand.MAIN_HAND);
                    context.assertTrue(fixture.player.isUsingItem(), "结束后直接使用未恢复");
                    context.succeed();
                }
            });
        });
    }

    private static void assertInteractions(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 每种已有回调分别断言，另验证 startUsingItem 和直接破坏入口没有漏网。
        var player = fixture.player;
        var level = player.level();
        var hand = InteractionHand.MAIN_HAND;
        BlockPos pos = context.absolutePos(new BlockPos(1, 1, 1));
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
        context.assertTrue(AttackEntityCallback.EVENT.invoker().interact(player, level, hand, player, null)
                == InteractionResult.FAIL, "守或收势中攻击未拦");
        context.assertTrue(AttackBlockCallback.EVENT.invoker().interact(player, level, hand, pos, Direction.UP)
                == InteractionResult.FAIL, "守或收势中挖掘未拦");
        context.assertTrue(UseItemCallback.EVENT.invoker().interact(player, level, hand)
                == InteractionResult.FAIL, "守或收势中物品未拦");
        context.assertTrue(UseBlockCallback.EVENT.invoker().interact(player, level, hand, hit)
                == InteractionResult.FAIL, "守或收势中方块交互未拦");
        context.assertTrue(UseEntityCallback.EVENT.invoker().interact(player, level, hand, player, null)
                == InteractionResult.FAIL, "守或收势中实体交互未拦");
        player.startUsingItem(hand);
        context.assertTrue(!player.isUsingItem(), "直接物品使用绕过施放锁");
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        context.assertTrue(!player.gameMode.destroyBlock(pos) && level.getBlockState(pos).is(Blocks.STONE),
                "直接破坏绕过施放锁");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void pendingDigCannotFinishAfterGuard(GameTestHelper context) {
        // 在起守前送出开始和提前完成包，让原版进入延迟挖掘，再验证结束守式也不会续挖。
        var fixture = new HumanTechniqueTestPlayer(context);
        var player = fixture.player;
        player.setGameMode(GameType.SURVIVAL);
        BlockPos pos = context.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(Vec3.atCenterOf(pos.above()));
        player.setOnGround(true /* onGround */);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        context.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        dig(player, pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        dig(player, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        String cast = start(context, fixture);
        HumanGuardTestCatalog.release(player, cast);
        context.runAfterDelay(DIG_WAIT_TICKS, () -> {
            try (fixture) {
                context.assertTrue(context.getLevel().getBlockState(pos).is(Blocks.STONE), "原有挖掘在守后偷做完");
                context.assertTrue(player.gameMode.destroyBlock(pos), "守后新挖掘未恢复");
                context.succeed();
            }
        });
    }

    private static void dig(ServerPlayer player, BlockPos pos, ServerboundPlayerActionPacket.Action action) {
        // 正式原版游戏模式处理器保存已开始或延迟完成进度。
        player.gameMode.handleBlockBreakAction(pos, action, Direction.UP, player.level().getMaxY(), DIG_SEQUENCE);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void sprintBlockedFromWarmupThroughFullRecovery(GameTestHelper context) {
        // 先有真实疾跑，再起守；属性只剩目录四成，直接调用和疾跑包均不能恢复疾跑。
        var fixture = new HumanTechniqueTestPlayer(context);
        double baseline = speed(fixture.player);
        fixture.player.setSprinting(true /* sprinting */);
        context.assertTrue(fixture.player.isSprinting(), "夹具不能疾跑");
        String cast = start(context, fixture);
        assertNoSprint(context, fixture.player, baseline);
        context.runAfterDelay(WARMUP_TICKS + 1, () -> {
            assertNoSprint(context, fixture.player, baseline);
            HumanGuardTestCatalog.release(fixture.player, cast);
            assertNoSprint(context, fixture.player, baseline);
            context.runAfterDelay(RECOVERY_TICKS - 1, () -> assertNoSprint(context, fixture.player, baseline));
            context.runAfterDelay(RECOVERY_TICKS + 1, () -> {
                try (fixture) {
                    assertClean(context, fixture.player, baseline);
                    fixture.player.setSprinting(true /* sprinting */);
                    context.assertTrue(fixture.player.isSprinting(), "收势后不能重新疾跑");
                    context.succeed();
                }
            });
        });
    }

    private static void assertNoSprint(GameTestHelper context, ServerPlayer player, double baseline) {
        // 速度断言使用实际属性；客户端包经过正式连接处理器。
        context.assertTrue(!player.isSprinting(), "守没有停止已有疾跑");
        player.setSprinting(true /* sprinting */);
        player.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(player,
                ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        context.assertTrue(!player.isSprinting(), "守或完整收势中仍可疾跑");
        assertSpeed(context, player, baseline * HumanGuardTestCatalog.MOVEMENT);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shortTapAndRepeatedReleaseDoNotMixAnimations(GameTestHelper context) {
        // 同 tick 松开仍走完抬手后收势，重复释放与旧阶段通知不能影响下一次守。
        var fixture = new HumanTechniqueTestPlayer(context);
        String cast = start(context, fixture);
        HumanGuardTestCatalog.release(fixture.player, cast);
        HumanGuardTestCatalog.release(fixture.player, cast);
        context.runAfterDelay(WARMUP_TICKS + 1, () -> {
            context.assertTrue(TechniqueFabric.service().activeGuard(fixture.player.getUUID()).isEmpty(), "短按出现守窗");
            HumanTechniquePresentation.lowerGuard(fixture.player, cast);
            GuardAnimationProbe.assertPlays(context, fixture, GuardAnimationProbe.LOWER, 1);
            GuardAnimationProbe.assertStopped(context, fixture, GuardAnimationProbe.HOLD);
        });
        context.runAfterDelay(WARMUP_TICKS + RECOVERY_TICKS + 1, () -> {
            try (fixture) {
                GuardAnimationProbe.assertStopped(context, fixture, GuardAnimationProbe.LOWER);
                String next = start(context, fixture);
                fixture.connection.packets().clear();
                HumanGuardTestCatalog.release(fixture.player, cast);
                HumanTechniquePresentation.lowerGuard(fixture.player, cast);
                HumanTechniquePresentation.stopCast(fixture.player, cast);
                context.assertTrue(GuardAnimationProbe.events(fixture).isEmpty(), "旧施放串进新守动作");
                context.assertTrue(active(fixture).equals(next), "旧释放结束新守");
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void onlyGuardedHitsPlayFeedbackAndLastArmLowersAfterHit(GameTestHelper context) {
        // 用真实 Damage 事实驱动表现；原前臂、背面与透劲不能伪装成格挡。
        var fixture = new HumanTechniqueTestPlayer(context);
        fixture.player.setGameMode(GameType.SURVIVAL);
        var attacker = context.spawn(EntityTypes.ZOMBIE, 1, 1, 1);
        attacker.setNoAi(true /* noAi */);
        attacker.setPos(fixture.player.position().add(0, 0, ATTACK_DISTANCE));
        fixture.player.setYRot(FRONT_YAW);
        start(context, fixture);
        context.runAfterDelay(WARMUP_TICKS + 1, () -> {
            try (fixture) {
                var source = fixture.player.damageSources().mobAttack(attacker);
                fixture.connection.packets().clear();
                GuardAnimationProbe.hit(fixture.player, source, "head", SMALL_HIT);
                GuardAnimationProbe.assertPlays(context, fixture, GuardAnimationProbe.HIT, 1);
                GuardAnimationProbe.hit(fixture.player, source, "left_forearm", SMALL_HIT);
                fixture.player.setYRot(BACK_YAW);
                GuardAnimationProbe.hit(fixture.player, source, "head", SMALL_HIT);
                fixture.player.setYRot(FRONT_YAW);
                GuardAnimationProbe.penetrate(fixture.player, source, SMALL_HIT);
                GuardAnimationProbe.assertPlays(context, fixture, GuardAnimationProbe.HIT, 1);
                GuardAnimationProbe.hit(fixture.player, source, "left_forearm", DESTROY_ARM);
                fixture.connection.packets().clear();
                GuardAnimationProbe.hit(fixture.player, source, "head", DESTROY_ARM);
                GuardAnimationProbe.assertLastArmOrder(context, fixture);
                context.assertTrue(TechniqueFabric.service().activeGuard(fixture.player.getUUID()).isEmpty(),
                        "毁尽前臂未撤防");
                context.succeed();
            } finally {
                attacker.discard();
            }
        });
    }

    @GameTest(environment = WEAPON_ENVIRONMENT, maxTicks = DEFAULT_TIMEOUT)
    public void guardOpensWithTheActualMainHand(GameTestHelper context) {
        // 左主手铁剑、右主手铁斧均横架；副手的剑不会让空主手进入兵器守姿。
        assertOpening(context, Items.IRON_SWORD, Items.AIR, HumanoidArm.LEFT, GuardAnimationProbe.WEAPON_HOLD);
        assertOpening(context, Items.IRON_AXE, Items.AIR, HumanoidArm.RIGHT, GuardAnimationProbe.WEAPON_HOLD);
        assertOpening(context, Items.AIR, Items.IRON_SWORD, HumanoidArm.RIGHT, GuardAnimationProbe.HOLD);
        assertOpening(context, Items.AIR, Items.AIR, HumanoidArm.RIGHT, GuardAnimationProbe.HOLD);
        context.succeed();
    }

    private static void assertOpening(GameTestHelper context, Item main, Item off, HumanoidArm arm, String clip) {
        // 起手当刻检查服务端发出的片段，排除稍后换姿掩盖错误的首次播放。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            var player = fixture.player;
            player.setMainArm(arm);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(main));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(off));
            start(context, fixture);
            GuardAnimationProbe.assertPlays(context, fixture, clip, 1);
            context.assertTrue(GuardAnimationProbe.startTick(fixture, clip) == player.level().getGameTime(),
                    "守式起播时间应等于起手当刻");
        }
    }

    @GameTest(environment = WEAPON_ENVIRONMENT, maxTicks = DEFAULT_TIMEOUT)
    public void switchingToStickKeepsTheGuardClock(GameTestHelper context) {
        // 在有效守窗切快捷栏，下一 tick 已经换姿，施放与动画时钟都不能重置。
        var fixture = new HumanTechniqueTestPlayer(context);
        var player = fixture.player;
        var inventory = player.getInventory();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        int stickSlot = 1;
        inventory.setItem(stickSlot, new ItemStack(Items.STICK));
        String cast = start(context, fixture);
        long origin = GuardAnimationProbe.startTick(fixture, GuardAnimationProbe.WEAPON_HOLD);
        context.runAfterDelay(WARMUP_TICKS + 1, () -> {
            inventory.setSelectedSlot(stickSlot);
            context.runAfterDelay(1, () -> {
                try (fixture) {
                    GuardAnimationProbe.assertStopped(context, fixture, GuardAnimationProbe.WEAPON_HOLD);
                    GuardAnimationProbe.assertPlays(context, fixture, GuardAnimationProbe.HOLD, 1);
                    context.assertTrue(GuardAnimationProbe.startTick(fixture, GuardAnimationProbe.HOLD) == origin,
                            "换姿重播了抬手");
                    var guard = TechniqueFabric.service().activeGuard(player.getUUID()).orElseThrow();
                    context.assertTrue(cast.equals(guard.castId()), "换姿中断了原守窗");
                    HumanGuardTestCatalog.release(player, cast);
                    GuardAnimationProbe.assertPlays(context, fixture, GuardAnimationProbe.LOWER, 1);
                    context.succeed();
                }
            });
        });
    }

    @GameTest(environment = WEAPON_ENVIRONMENT, maxTicks = DEFAULT_TIMEOUT)
    public void weaponContactAndBreakChooseTheMatchingClips(GameTestHelper context) {
        // 同一击结算完成后立即看 PA 包；完整剑由兵器吃力，碎剑当场回空手。
        var armed = swordsman(context);
        var breaking = swordsman(context);
        var attacker = context.spawn(EntityTypes.ZOMBIE, 1, 1, 1);
        attacker.setNoAi(true /* noAi */);
        var worn = breaking.player.getMainHandItem();
        worn.setDamageValue(worn.getMaxDamage() - 1);
        String cast = start(context, armed);
        start(context, breaking);
        context.runAfterDelay(WARMUP_TICKS + 1, () -> {
            try (armed; breaking) {
                for (var fixture : List.of(armed, breaking)) {
                    attacker.setPos(fixture.player.position().add(0, 0, ATTACK_DISTANCE));
                    GuardAnimationProbe.hit(fixture.player, fixture.player.damageSources().mobAttack(attacker),
                            "head", SMALL_HIT);
                }
                GuardAnimationProbe.assertPlays(context, armed, GuardAnimationProbe.WEAPON_HIT, 1);
                HumanGuardTestCatalog.release(armed.player, cast);
                GuardAnimationProbe.assertPlays(context, armed, GuardAnimationProbe.WEAPON_LOWER, 1);
                context.assertTrue(breaking.player.getMainHandItem().isEmpty(), "最后一点耐久的剑应当碎掉");
                GuardAnimationProbe.assertPlays(context, breaking, GuardAnimationProbe.HIT, 1);
                GuardAnimationProbe.assertPlays(context, breaking, GuardAnimationProbe.WEAPON_HIT, 0);
                GuardAnimationProbe.assertStopped(context, breaking, GuardAnimationProbe.WEAPON_HOLD);
                GuardAnimationProbe.assertPlays(context, breaking, GuardAnimationProbe.HOLD, 1);
                context.succeed();
            } finally {
                attacker.discard();
            }
        });
    }

    private static HumanTechniqueTestPlayer swordsman(GameTestHelper context) {
        // 两名守方都用生存模式，让 Damage 对其真实主手兑现耐久损耗。
        var fixture = new HumanTechniqueTestPlayer(context);
        fixture.player.setGameMode(GameType.SURVIVAL);
        fixture.player.setYRot(FRONT_YAW);
        fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        return fixture;
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void hardControlsCancelWithoutLowering(GameTestHelper context) {
        // 三种硬控都直接停保持；硬控自己的移速锁释放后没有守式残留。
        for (var kind : List.of(TechniqueService.StaggerKind.CHARM, TechniqueService.StaggerKind.HOLD,
                TechniqueService.StaggerKind.DOWN)) {
            try (var fixture = new HumanTechniqueTestPlayer(context)) {
                double baseline = speed(fixture.player);
                start(context, fixture);
                double movement = 0;
                if (kind == TechniqueService.StaggerKind.DOWN) {
                    movement = HumanGuardTestCatalog.MOVEMENT;
                }
                var service = TechniqueFabric.service();
                var playerId = fixture.player.getUUID();
                service.stagger(playerId, new TechniqueService.Stagger(CONTROL_MILLIS, movement, kind));
                GuardAnimationProbe.assertPlays(context, fixture, GuardAnimationProbe.LOWER, 0);
                GuardAnimationProbe.assertStopped(context, fixture, GuardAnimationProbe.HOLD);
                service.releaseStagger(playerId, kind);
                assertClean(context, fixture.player, baseline);
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void dimensionTransferClearsOldLocksBeforeArrival(GameTestHelper context) {
        // 跨维度应在旧实体离开前清锁，不能等新世界的下一 tick。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            double baseline = speed(fixture.player);
            start(context, fixture);
            var end = context.getLevel().getServer().getLevel(Level.END);
            var player = fixture.player;
            player.teleportTo(end, player.getX(), player.getY(), player.getZ(), Set.of(), 0, 0,
                    true /* resetCamera */);
            context.assertTrue(player.level() == end, "没有跨到末地");
            assertClean(context, player, baseline);
            GuardAnimationProbe.assertStoppedBeforeTransfer(context, fixture);
            start(context, fixture);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void aliveReplacementDisconnectAndDeathCleanGuard(GameTestHelper context) {
        // 原版终末之诗换出活的新实体；旧实体与新实体都不能持有上次守式锁。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            double baseline = speed(fixture.player);
            var player = fixture.player;
            var end = context.getLevel().getServer().getLevel(Level.END);
            player.teleportTo(end, player.getX(), player.getY(), player.getZ(), Set.of(), 0, 0,
                    true /* resetCamera */);
            start(context, fixture);
            player.showEndCredits();
            player.connection.handleClientCommand(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            var replacement = end.getServer().getPlayerList().getPlayer(player.getUUID());
            context.assertTrue(replacement != player, "没有换出活的新实体");
            assertClean(context, player, baseline);
            assertClean(context, replacement, baseline);
            GuardAnimationProbe.assertStopped(context, fixture, GuardAnimationProbe.HOLD);
        }
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            double baseline = speed(fixture.player);
            start(context, fixture);
            HumanRevivalGameTest.killAndRespawn(context, fixture);
            assertClean(context, fixture.player, baseline);
            GuardAnimationProbe.assertStopped(context, fixture, GuardAnimationProbe.HOLD);
        }
        var disconnected = new HumanTechniqueTestPlayer(context);
        double baseline = speed(disconnected.player);
        start(context, disconnected);
        disconnected.close();
        assertClean(context, disconnected.player, baseline);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void heldAndRecoveringDisconnectClearBodyBeforeUnbind(GameTestHelper context) {
        // 让施放实际进入守段，再分别在维持和收势中断线，不能只清实体而残留身体施放。
        var held = new HumanTechniqueTestPlayer(context);
        var recovering = new HumanTechniqueTestPlayer(context);
        double heldSpeed = speed(held.player);
        double recoveringSpeed = speed(recovering.player);
        start(context, held);
        String cast = start(context, recovering);
        context.runAfterDelay(WARMUP_TICKS + 1, () -> {
            var heldBody = held.body();
            var recoveringBody = recovering.body();
            HumanGuardTestCatalog.release(recovering.player, cast);
            held.close();
            recovering.close();
            assertClean(context, held.player, heldSpeed);
            assertClean(context, recovering.player, recoveringSpeed);
            context.assertTrue(!TechniqueState.read(heldBody.snapshot()).hasActiveCast(), "守中断线残留身体施放");
            context.assertTrue(!TechniqueState.read(recoveringBody.snapshot()).hasActiveCast(), "收势中断线残留身体施放");
            context.succeed();
        });
    }

    private static String start(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 确认真正进入起手后才进行后续断言。
        var outcome = HumanGuardTestCatalog.start(fixture.player);
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "守式起手失败：" + outcome);
        return active(fixture);
    }

    private static String active(HumanTechniqueTestPlayer fixture) {
        // 施放身份来自身体权威状态，不能由测试另造。
        return TechniqueState.read(fixture.body().snapshot()).getActiveCast().getRequest().getCastId();
    }

    private static double speed(ServerPlayer player) {
        // 读取原版最终属性，覆盖疾跑和宿主减速修正的叠加。
        return player.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    private static void assertSpeed(GameTestHelper context, ServerPlayer player, double expected) {
        // 小数比较容忍浮点舍入，仍能抓到四成与疾跑倍率的差别。
        double actual = speed(player);
        context.assertTrue(Math.abs(actual - expected) < EPSILON, "移动属性未恢复到预期：" + actual);
    }

    private static void assertClean(GameTestHelper context, ServerPlayer player, double baseline) {
        // 清理同时核对输入、疾跑锁与实体属性，不能只看功法已结束。
        var playerId = player.getUUID();
        context.assertTrue(!HumanTechniqueHost.isCasting(playerId), "退出仍残留输入锁");
        context.assertTrue(!HumanTechniqueHost.blocksSprint(playerId), "退出仍残留疾跑锁");
        assertSpeed(context, player, baseline);
    }
}
