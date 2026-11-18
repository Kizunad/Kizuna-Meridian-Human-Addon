package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.fabric.client.HumanGuardClient;
import dev.kizuna.meridian.technique.fabric.client.TechniqueCastStatusClient;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.playeranimation.client.PlayerAnimationClient;
import dev.kizuna.playeranimation.client.PlayerPlayback;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import static org.apache.commons.lang3.Validate.isTrue;

/** 真客户端同时验证疾跑预测与 PA 实际播放，截图只作为审片辅助。 */
public final class HumanGuardClientTest implements FabricClientGameTest {
    private static final String HOLD = "guard_arms";
    private static final String HIT = "guard_arms_hit";
    private static final String LOWER = "guard_arms_lower";
    private static final int SETTLE_TICKS = 3;
    private static final int LONG_GUARD_TICKS = 45;
    private static final int AFTER_RECOVERY_TICKS = 12;
    private static final double ATTACK_DISTANCE = 2;
    private static final double HIT_HP = 0.1;
    private static final double STAND_Y = 65;
    private static final int FULL_FOOD = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        // 一次新建集成服里连续两次守，复用正式状态同步和动画网络入口。
        Departing departing;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            prepare(context, world);
            sprintBeforeGuard(context);
            String cast = start(context, world);
            assertLocked(context);
            context.waitTicks(LONG_GUARD_TICKS);
            context.runOnClient(client -> isTrue(playing(client, HOLD), "长守保持段已经丢失"));
            screenshot(context, "p4-guard-hold");
            feedback(context, world);
            release(context, world, cast);
            shortTap(context, world);
            departing = departure(context, world);
        }
        context.runOnClient(client -> isTrue(!HumanGuardClient.blocksSprint(), "离开世界残留疾跑预测锁"));
        ServerPlayer departed = departing.player();
        isTrue(!HumanTechniqueHost.isCasting(departed.getUUID()), "断线停服后仍占用输入");
        isTrue(departed.getAttributeValue(Attributes.MOVEMENT_SPEED) == departing.speed(),
                "断线停服后旧实体残留减速");
        isTrue(!TechniqueState.read(departing.body().snapshot()).hasActiveCast(), "断线停服后身体残留施放");
    }

    private static Departing departure(ClientGameTestContext context, TestSingleplayerContext world) {
        // 在真实收势尚未结束时退出，核对宿主撤销前清了身体进度和实体效果。
        var departing = world.getServer().computeOnServer(server -> {
            var player = player(server);
            double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
            var outcome = HumanGuardTestCatalog.start(player);
            isTrue(outcome.getPhase() == CastPhase.WARMUP, "退出场景起守失败");
            return new Departing(player, HumanTechniqueHost.state().find(player.getUUID()), speed, outcome.getCastId());
        });
        context.waitFor(client -> HumanGuardClient.blocksSprint());
        context.waitTicks(HumanGuardTestCatalog.WARMUP_TICKS + 1);
        world.getServer().runOnServer(server -> HumanGuardTestCatalog.release(player(server), departing.castId()));
        context.waitFor(client -> playing(client, LOWER));
        return departing;
    }

    private static void prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        // 用独立平地排除碰墙和饥饿，疾跑结果来自输入约束。
        world.getConnection().waitForChunksRender();
        var testServer = world.getServer();
        testServer.runCommand("fill -16 64 -16 16 90 16 air");
        testServer.runCommand("fill -16 64 -16 16 64 16 stone");
        testServer.runCommand("time set noon");
        testServer.runCommand("weather clear");
        testServer.runOnServer(server -> {
            var player = player(server);
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(0, STAND_Y, 0);
            player.getFoodData().setFoodLevel(FULL_FOOD);
        });
        context.runOnClient(client -> {
            client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            if (!client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
        context.getInput().lookAt(0, 0);
        context.waitTicks(SETTLE_TICKS);
    }

    private static void sprintBeforeGuard(ClientGameTestContext context) {
        // 真按前进与疾跑键，先证明场景能正常预测疾跑。
        context.getInput().holdKey(GLFW.GLFW_KEY_W);
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        context.waitFor(client -> client.player.isSprinting());
    }

    private static String start(ClientGameTestContext context, TestSingleplayerContext world) {
        // 服务端可信施放发起后等状态到达，客户端没有测试专用施放状态。
        String cast = world.getServer().computeOnServer(server -> {
            var outcome = HumanGuardTestCatalog.start(player(server));
            isTrue(outcome.getPhase() == CastPhase.WARMUP, "客户端场景起守失败：" + outcome);
            return outcome.getCastId();
        });
        context.waitFor(client -> HumanGuardClient.blocksSprint());
        context.waitTicks(1);
        return cast;
    }

    private static void assertLocked(ClientGameTestContext context) {
        // 已经按住疾跑与双击前进都必须失败，不能只检查服务端回滚后的值。
        context.runOnClient(client -> isTrue(!client.player.isSprinting(), "客户端维持了已有疾跑预测"));
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        context.getInput().releaseKey(GLFW.GLFW_KEY_W);
        context.waitTicks(1);
        context.getInput().pressKey(GLFW.GLFW_KEY_W);
        context.getInput().holdKey(GLFW.GLFW_KEY_W);
        context.waitTicks(1);
        context.runOnClient(client -> isTrue(!client.player.isSprinting(), "双击前进绕过守式疾跑约束"));
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        context.waitTicks(1);
        context.runOnClient(client -> isTrue(!client.player.isSprinting(), "疾跑键绕过守式预测约束"));
        context.getInput().releaseKey(GLFW.GLFW_KEY_W);
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
    }

    private static void feedback(ClientGameTestContext context, TestSingleplayerContext world) {
        // 正面头部真实肢击产生短反馈，反馈结束后原保持段仍在。
        world.getServer().runOnServer(server -> {
            var target = player(server);
            var attacker = EntityTypes.ZOMBIE.create(target.level(), EntitySpawnReason.COMMAND);
            attacker.setNoAi(true /* noAi */);
            attacker.setPos(target.position().add(0, 0, ATTACK_DISTANCE));
            target.setYRot(0);
            target.level().addFreshEntity(attacker);
            var damage = new VanillaDamageFabric.DirectDamage(target, target.damageSources().mobAttack(attacker),
                    "head", HIT_HP);
            isTrue(VanillaDamageFabric.damageDirect(damage).isPresent(), "客户端场景命中未结算");
            attacker.discard();
        });
        context.waitFor(client -> playing(client, HIT));
        screenshot(context, "p4-guard-hit");
        context.waitTicks(AFTER_RECOVERY_TICKS);
        context.runOnClient(client -> isTrue(playing(client, HOLD), "受击反馈后没有回到保持"));
    }

    private static void release(ClientGameTestContext context, TestSingleplayerContext world, String cast) {
        // 松开后仍读同 cast 的完整收势，随后两种锁和所有守片段一起撤掉。
        world.getServer().runOnServer(server -> HumanGuardTestCatalog.release(player(server), cast));
        context.waitFor(client -> TechniqueCastStatusClient.current(0)
                .map(current -> !current.status().getEndTimesUnknown()).orElse(false /* noCast */));
        context.waitFor(client -> playing(client, LOWER));
        context.runOnClient(client -> {
            isTrue(HumanGuardClient.blocksSprint(), "收势过早解开客户端疾跑锁");
            isTrue(playing(client, LOWER), "客户端没有播下抱架");
        });
        screenshot(context, "p4-guard-lower");
        context.waitTicks(AFTER_RECOVERY_TICKS);
        context.runOnClient(client -> {
            isTrue(!HumanGuardClient.blocksSprint(), "收势结束仍锁疾跑");
            isTrue(!playing(client, HOLD) && !playing(client, HIT) && !playing(client, LOWER), "收势结束残留守动画");
        });
        sprintBeforeGuard(context);
        context.getInput().releaseKey(GLFW.GLFW_KEY_W);
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
    }

    private static void shortTap(ClientGameTestContext context, TestSingleplayerContext world) {
        // 第二次在服务端同 tick 起松，客户端仍能接住完整身份且不残留上次动作。
        world.getServer().runOnServer(server -> {
            var player = player(server);
            var outcome = HumanGuardTestCatalog.start(player);
            isTrue(outcome.getPhase() == CastPhase.WARMUP, "第二次守未起");
            HumanGuardTestCatalog.release(player, outcome.getCastId());
            HumanGuardTestCatalog.release(player, outcome.getCastId());
        });
        context.waitTicks(HumanGuardTestCatalog.WARMUP_TICKS + HumanGuardTestCatalog.RECOVERY_TICKS
                + AFTER_RECOVERY_TICKS);
        context.runOnClient(client -> {
            isTrue(!HumanGuardClient.blocksSprint(), "短按卡住疾跑预测");
            isTrue(!playing(client, HOLD) && !playing(client, LOWER), "两次守串动画");
        });
        screenshot(context, "p4-guard-finished");
    }

    private static boolean playing(Minecraft client, String clip) {
        // 读取 PA 的真实播放实例，既验证收包也验证资源确实进入播放。
        var playback = PlayerAnimationClient.PLAYBACK.player(client.player.getUUID());
        return playback != null && playback.hasActive(clip, PlayerPlayback.now(0));
    }

    private static ServerPlayer player(MinecraftServer server) {
        // 单人测试世界只有这一条真实连接。
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        // 输出审片证据，断言通过仍由实际状态与动作检查决定。
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }

    private record Departing(ServerPlayer player, HumanPlayerBody body, double speed, String castId) { }

}
