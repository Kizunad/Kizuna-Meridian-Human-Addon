package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanRevival;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewRequest;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewUpdate;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** 真实玩家死亡、重生、健康投影和腿力恢复共用原来的权威身体。 */
public final class HumanRevivalGameTest implements FabricGameTest {
    private static final long MILLIS_PER_TICK = 50;
    private static final int RECOVERY_TICKS = 80;
    private static final int TEST_TICK_LIMIT = 160;
    private static final float LETHAL_DAMAGE = 1000000;
    private static final float HEAL_AMOUNT = 1000;
    private static final double MINIMUM_HP_RATIO = 0.1;
    private static final double TOLERANCE = 0.00001;
    private static final double TEST_QI = 0.2;
    private static final Identifier VIEW_REQUEST = new Identifier("meridian:body_view_request");
    private static final Identifier VIEW_UPDATE = new Identifier("meridian:body_view");

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TEST_TICK_LIMIT)
    public void deathRespawnRestoresHealthSpendsLifespanAndRecoversLegWork(TestContext context) {
        // 学习先于死亡，重生必须保留目录进度和开脉，立刻起手只因疲劳被拒。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            prepare(context, fixture);
            double beforeSpent = fixture.body().snapshot().vitality().spentLifespanSeconds();
            ServerPlayerEntity revived = killAndRespawn(context, fixture);
            revived.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            verifyRevival(context, fixture, revived, beforeSpent);
            supplyQi(context, fixture);
            var exhausted = cast(revived);
            context.assertTrue(exhausted.getFailure() == TechniqueFailure.MODULE_UNAVAILABLE,
                    "全身疲劳没有拒绝腿力功法：" + exhausted);
            context.waitAndRun(1, () -> verifyHealth(context, fixture, revived));
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void livingEntityReplacementAndDimensionTravelDoNotSpendLifespan(TestContext context) {
        // 存活换实体与跨维度事件均使用正式 Fabric 回调，不应扣费或清掉真元。
        try (var fixture = new HumanTechniqueTestPlayer(context)) {
            var before = fixture.body().snapshot();
            ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(
                    fixture.player, fixture.player, true /* alive */);
            var destination = fixture.player.getServer().getWorld(World.NETHER);
            fixture.player.teleport(destination, fixture.player.getX(), fixture.player.getY(),
                    fixture.player.getZ(), fixture.player.getYaw(), fixture.player.getPitch());
            context.assertTrue(fixture.player.getWorld() == destination, "玩家没有实际换维度");
            var after = fixture.body().snapshot();
            context.assertTrue(before.vitality().spentLifespanSeconds() == after.vitality().spentLifespanSeconds(),
                    "存活换维度扣了寿元");
            context.assertTrue(before.totalQi() == after.totalQi(), "存活换维度清掉真元");
            context.assertTrue(!after.vitality().dead(), "存活换维度改变生死状态");
        }
        context.complete();
    }

    private static void prepare(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 真实功法的测试目录只降低资源门槛，腿槽、做功和效果仍来自发布目录。
        fixture.player.changeGameMode(GameMode.SURVIVAL);
        long now = now(fixture.player);
        context.assertTrue(fixture.body().completeOpening(now, "left_large_intestine_meridian")
                == FailureCode.FAILURE_CODE_UNSPECIFIED, "测试开脉失败");
        for (String volume : List.of("upper", "middle", "lower")) {
            TechniqueFabric.service().learn(fixture.player.getUuid(), HumanCombatTestCatalog.NAMESPACE + ":" + volume);
        }
    }

    private static ServerPlayerEntity killAndRespawn(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 可信躯干大伤害通过人体蔓延耗尽所有部位，再走原版死亡和玩家管理器重生。
        var hit = HealthTarget.newBuilder().setPartId("chest").build();
        var damage = DamageBody.newBuilder()
                .setSourceId("human_test:revival")
                .setDamageType("human_test:revival")
                .setKind(DamageKind.CUSTOM)
                .setHit(hit)
                .setDamageHp(LETHAL_DAMAGE)
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(fixture.player.getUuidAsString())
                .setExpectedRevision(fixture.body().snapshot().revision())
                .setDamageBody(damage)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        var result = fixture.body().applyHealth(now(fixture.player), request);
        context.assertTrue(result.hasCommandCommitted(), "致死伤害未提交：" + result);
        fixture.player.damage(fixture.player.getDamageSources().genericKill(), LETHAL_DAMAGE);
        context.assertTrue(fixture.body().snapshot().vitality().dead(), "玩家死亡没有记录到身体");
        context.assertTrue(!fixture.player.isAlive(), "原版玩家未死亡");
        var revived = fixture.player.getServer().getPlayerManager().respawnPlayer(
                fixture.player, false /* alive */);
        revived.setNoGravity(true /* noGravity */);
        return revived;
    }

    private static void verifyRevival(TestContext context, HumanTechniqueTestPlayer fixture,
                                      ServerPlayerEntity revived, double beforeSpent) {
        // 权威身体、网络展示和动作栏各自读取真实输出，避免用红心代替寿元账验证。
        var body = fixture.body().snapshot();
        context.assertTrue(!body.vitality().dead(), "重生后身体仍为死态");
        context.assertTrue(body.vitality().spentLifespanSeconds() == beforeSpent + HumanBirth.SECONDS_PER_YEAR,
                "重生没有且仅扣一年寿元");
        for (var part : body.partStates()) {
            double maximum = body.structure().parts().stream().filter(item -> item.id().equals(part.id()))
                    .findFirst().orElseThrow().maxHp();
            context.assertTrue(Math.abs(part.hp() - maximum * MINIMUM_HP_RATIO) < TOLERANCE,
                    "部位没有恢复到 10%：" + part.id());
        }
        var view = requestView(fixture, revived);
        context.assertTrue(!view.hasFailure(), "UI 身体订阅被拒：" + view);
        long hints = fixture.connection.packets.stream()
                .filter(GameMessageS2CPacket.class::isInstance)
                .map(GameMessageS2CPacket.class::cast)
                .filter(packet -> packet.overlay() && packet.content().getString().equals(HumanRevival.message()))
                .count();
        context.assertTrue(hints == 1, "重生动作栏提示没有且仅发送一次");
    }

    private static void supplyQi(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 死亡本来会清算真元；只补功法气费，使接下来的失败单独验证腿力不足。
        var qi = DebugQi.newBuilder()
                .setMode(DebugQiMode.INJECT_FOR_DIFFUSION)
                .setMeridianId("left_large_intestine_meridian")
                .setAmount(TEST_QI)
                .build();
        context.assertTrue(fixture.body().debugQi(now(fixture.player), qi)
                == FailureCode.FAILURE_CODE_UNSPECIFIED, "补测试气费失败");
    }

    private static void verifyHealth(TestContext context, HumanTechniqueTestPlayer fixture,
                                     ServerPlayerEntity revived) {
        // 重生血条由 Healing 同步；恢复 HP 后仍须等待原有疲劳曲线释放腿力。
        try {
            verifyBodyProjection(context, fixture);
            // 内存连接没有网络轮询，显式执行正式网络处理器通常调用的玩家 tick。
            revived.playerTick();
            context.assertTrue(Math.abs(revived.getHealth() / revived.getMaxHealth() - MINIMUM_HP_RATIO)
                    < TOLERANCE, "重生红心没有同步为约 10%：" + revived.getHealth());
            revived.heal(HEAL_AMOUNT);
            float healed = revived.getHealth();
            context.assertTrue(healed > revived.getMaxHealth() * MINIMUM_HP_RATIO, "重生后不能治疗");
            context.waitAndRun(RECOVERY_TICKS, () -> verifyRecovered(context, fixture, revived));
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void verifyRecovered(TestContext context, HumanTechniqueTestPlayer fixture,
                                        ServerPlayerEntity revived) {
        // 等原版重生无敌期和肌肉疲劳一起自然推进，再验证普通伤害与腿力施放。
        try (fixture) {
            float beforeDamage = revived.getHealth();
            revived.timeUntilRegen = 0;
            context.assertTrue(revived.damage(revived.getDamageSources().generic(), 1), "重生后不能受伤");
            context.assertTrue(revived.getHealth() < beforeDamage, "重生伤害没有同步红心");
            var outcome = cast(revived);
            context.assertTrue(outcome.getPhase() == CastPhase.WARMUP,
                    "自然恢复后腿力功法仍被拒：" + outcome);
            context.complete();
        }
    }

    private static void verifyBodyProjection(TestContext context, HumanTechniqueTestPlayer fixture) {
        // 订阅回执只确认授权，下一次服务端 tick 才沿同一通道推送完整身体。
        double expected = fixture.body().snapshot().vitality().spentLifespanSeconds();
        boolean synchronizedLifespan = fixture.connection.packets.stream()
                .filter(CustomPayloadS2CPacket.class::isInstance)
                .map(CustomPayloadS2CPacket.class::cast)
                .filter(packet -> packet.getChannel().equals(VIEW_UPDATE))
                .map(HumanRevivalGameTest::decodeView)
                .filter(BodyViewUpdate::hasBody)
                .anyMatch(update -> update.getBody().getVitality().getSpentLifespanSeconds() == expected);
        context.assertTrue(synchronizedLifespan, "UI 协议没有返回已扣寿元");
    }

    private static CastOutcome cast(ServerPlayerEntity player) {
        // 两次使用独立施放 ID，首次拒绝不应留下施放或冷却占用。
        var technique = TechniqueReference.newBuilder()
                .setId(HumanCombatTestCatalog.NAMESPACE + ":ram")
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(player.getUuidAsString())
                .setTechnique(technique)
                .setCastId(UUID.randomUUID().toString())
                .setEntry(CastEntry.AUTO)
                .build();
        return TechniqueFabric.service().cast(player.getUuid(), request);
    }

    private static BodyViewUpdate requestView(HumanTechniqueTestPlayer fixture, ServerPlayerEntity player) {
        // 普通身体界面无需内观解锁，仍经正式网络入口返回权威快照。
        var request = BodyViewRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(player.getUuidAsString())
                .build();
        var buffer = PacketByteBufs.create();
        try {
            buffer.writeBytes(request.toByteArray());
            player.networkHandler.onCustomPayload(new CustomPayloadC2SPacket(VIEW_REQUEST, buffer));
        } finally {
            buffer.release();
        }
        return fixture.connection.packets.stream()
                .filter(CustomPayloadS2CPacket.class::isInstance)
                .map(CustomPayloadS2CPacket.class::cast)
                .filter(packet -> packet.getChannel().equals(VIEW_UPDATE))
                .map(HumanRevivalGameTest::decodeView)
                .filter(update -> update.getRequestId().equals(request.getRequestId()))
                .findFirst().orElseThrow();
    }

    private static BodyViewUpdate decodeView(CustomPayloadS2CPacket packet) {
        // 保留原包读取指针，让同一连接的其他断言仍可读取其余同步结果。
        var buffer = packet.getData();
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        try {
            return BodyViewUpdate.parseFrom(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long now(ServerPlayerEntity player) {
        // 与 Human 生产调度使用相同的服务端世界时钟。
        return player.getServer().getOverworld().getTime() * MILLIS_PER_TICK;
    }
}
