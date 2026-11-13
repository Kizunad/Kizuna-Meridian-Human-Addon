package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 服务端换掉玩家实体（死亡重生、看完终末之诗回主世界）以后，射箭和打击都由新实体执行。 */
public final class HumanReplacedPlayerGameTest {
    private static final double ARROW_SEARCH_RADIUS = 64;
    private static final int AMMO_COUNT = 4;
    /** 重生后人体血量下一 tick 才投影到新实体上，等它活过来再出手。 */
    private static final int SETTLE_TICKS = 1;

    private enum Replacement { DEATH, END_CREDITS }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void respawnedPlayerShootsFromTheNewEntity(GameTestHelper context) {
        // 死亡重生不保留物品，旧实体手里是空的。
        shootsAfter(context, Replacement.DEATH);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void playerBackFromTheEndShootsFromTheNewEntity(GameTestHelper context) {
        // 末地返回保留物品，旧实体留在末地。
        shootsAfter(context, Replacement.END_CREDITS);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void respawnedPlayerStrikesWithTheNewEntity(GameTestHelper context) {
        // 兵器打击读施放者手里的物品和面前的目标。
        strikesAfter(context, Replacement.DEATH, Limb.WEAPON);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void playerBackFromTheEndStrikesWithTheNewEntity(GameTestHelper context) {
        // 旧实体在末地，面前没有主世界的目标。
        strikesAfter(context, Replacement.END_CREDITS, Limb.WEAPON);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void respawnedPlayerKicksWithTheNewEntity(GameTestHelper context) {
        // 腿击不看手里的物品，只看施放者是谁、站在哪。
        strikesAfter(context, Replacement.DEATH, Limb.LEG);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void playerBackFromTheEndKicksWithTheNewEntity(GameTestHelper context) {
        // 和兵器打击同一种换法，执行器不同。
        strikesAfter(context, Replacement.END_CREDITS, Limb.LEG);
    }

    private enum Limb { WEAPON, LEG }

    private static void shootsAfter(GameTestHelper context, Replacement replacement) {
        // 先射一箭，让宿主为旧实体建好执行器；换实体后的第二箭要从新实体身上射出、扣新实体的箭。
        var shots = new HumanProjectileGameTest.Shots(context);
        try {
            arm(shots.player);
            shots.fixture.submit(shot(shots));
            context.assertTrue(shots.arrows().size() == 1, "换实体前没有射出箭");
            shots.removeProjectiles();
            ServerPlayer current = replace(context, shots.fixture, replacement);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                try (shots) {
                    arm(current);
                    shots.fixture.submit(shot(shots));
                    List<AbstractArrow> arrows = arrowsOf(current);
                    arrows.forEach(AbstractArrow::discard);
                    context.assertTrue(arrows.size() == 1, "换实体后的箭没有从新实体身上射出：" + arrows.size() + " 支");
                    context.assertTrue(current.getOffhandItem().getCount() == AMMO_COUNT - 1,
                            "换实体后没有扣新实体的箭：剩 " + current.getOffhandItem().getCount());
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            shots.close();
            throw failure;
        }
    }

    private static void strikesAfter(GameTestHelper context, Replacement replacement, Limb limb) {
        // 同一个目标挨两下：换实体前一下建好执行器，换实体后一下要算在新实体头上。
        var fixture = new HumanCombatFixture(context);
        try {
            fixture.human.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            fixture.submit(strike(limb, "before_replacement"));
            context.assertTrue(target.doses.size() == 1, "换实体前没有打中目标");
            ServerPlayer current = replace(context, fixture, replacement);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                try (fixture) {
                    current.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    fixture.submit(strike(limb, "after_replacement"));
                    context.assertTrue(target.doses.size() == 2, "换实体后没有打中新实体面前的目标");
                    context.assertTrue(target.sources.getLast().getEntity() == current,
                            "换实体后的打击没有算在新实体头上");
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static EffectIntent strike(Limb limb, String castId) {
        // 兵器用正式破门斩的勾击，腿击用测试目录的单目标前腿击。
        if (limb == Limb.LEG) {
            return HumanCombatFixture.legStrike(castId);
        }
        return HumanCombatFixture.strike("hook", 0, castId);
    }

    private static EffectIntent shot(HumanProjectileGameTest.Shots shots) {
        // 每箭一个新的施放身份，朝视线平射。
        return shots.intent(HumanProjectileGameTest.settings(ProjectileWeaponCategory.BOW).build(), Hand.MAIN_HAND);
    }

    private static void arm(ServerPlayer player) {
        // 主手弓、副手箭，和玩家实际射箭时一样。
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW, AMMO_COUNT));
    }

    private static List<AbstractArrow> arrowsOf(ServerPlayer player) {
        // 只认这个实体所在世界里、归它所有的箭。
        return player.level().getEntitiesOfClass(AbstractArrow.class,
                player.getBoundingBox().inflate(ARROW_SEARCH_RADIUS), arrow -> arrow.getOwner() == player);
    }

    private static ServerPlayer replace(GameTestHelper context, HumanCombatFixture fixture, Replacement replacement) {
        // 两种换法都由客户端的重生指令触发；新实体出生在世界出生点，放回原来的位置和朝向再继续。
        ServerPlayer old = fixture.human.player;
        Vec3 position = old.position();
        ServerPlayer current;
        if (replacement == Replacement.DEATH) {
            // 先清空物品栏，免得死亡掉落物留在测试场地里。
            old.getInventory().clearContent();
            current = HumanRevivalGameTest.killAndRespawn(context, fixture.human);
        } else {
            current = returnFromEnd(context, old);
        }
        context.assertTrue(current != old, "服务端没有换出新的玩家实体");
        current.snapTo(position.x, position.y, position.z, 0, 0);
        current.setNoGravity(true /* noGravity */);
        context.assertTrue(current.level() == context.getLevel(), "新实体不在测试所在的世界");
        return current;
    }

    private static ServerPlayer returnFromEnd(GameTestHelper context, ServerPlayer player) {
        // 原版传送进末地，再像跳进返回传送门那样放终末之诗；客户端看完发重生指令，服务端这时换实体。
        MinecraftServer server = player.level().getServer();
        ServerLevel end = server.getLevel(Level.END);
        player.teleportTo(end, player.getX(), player.getY(), player.getZ(), Set.of(), player.getYRot(),
                player.getXRot(), true /* resetCamera */);
        context.assertTrue(player.level() == end, "玩家没有进到末地");
        player.showEndCredits();
        player.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return server.getPlayerList().getPlayer(player.getUUID());
    }
}
