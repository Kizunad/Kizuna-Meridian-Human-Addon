package dev.kizuna.meridian.human.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
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
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Screenshot;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.apache.commons.lang3.Validate.isTrue;

/** 真客户端同时验证疾跑预测与 PA 实际播放，截图只作为审片辅助。 */
public final class HumanGuardClientTest implements FabricClientGameTest {
    private static final String HOLD = "guard_arms";
    private static final String HIT = "guard_arms_hit";
    private static final String LOWER = "guard_arms_lower";
    private static final String WEAPON_HOLD = "guard_weapon";
    private static final String WEAPON_HIT = "guard_weapon_hit";
    private static final String WEAPON_LOWER = "guard_weapon_lower";
    private static final int REVIEW_FOV = 46;
    private static final int REVIEW_PITCH = -9;
    private static final int FRAME_WIDTH = 1280;
    private static final int FRAME_HEIGHT = 720;
    private static final int ESTABLISH_TICKS = 20;
    private static final String FORMAL_GUARD = "meridian_basic_techniques:guard";
    private static final int RAISE_SHOT_TICKS = 24;
    private static final int LATE_LEAD_TICKS = 5;
    private static final int EARLY_LEAD_TICKS = 9;
    private static final int STRIKE_LEAD_TICKS = 7;
    private static final long MILLIS_PER_TICK = 50;
    private static final double ATTACKER_SIDE = 1.1;
    private static final int UNARMED_TICKS = 55;
    private static final int ARMED_TICKS = 65;
    private static final int AFTER_CONTACT_TICKS = 45;
    private static final int FINISH_TICKS = 32;
    private static final int SETTLE_TICKS = 3;
    private static final int LONG_GUARD_TICKS = 45;
    private static final int AFTER_RECOVERY_TICKS = 12;
    private static final double ATTACK_DISTANCE = 2;
    private static final double HIT_HP = 0.1;
    private static final double STAND_Y = 65;
    private static final int FULL_FOOD = 20;
    private static final int SAVED_XP_LEVEL = 37;
    private static final BlockPos SAVED_BLOCK = new BlockPos(1, 64, 1);

    @Override
    public void runTest(ClientGameTestContext context) {
        // 在收势中停服，并在同一存档重进核对玩家、区块和身体。
        Departing departing;
        var world = context.worldBuilder().create();
        var save = world.getWorldSave();
        var serverThread = world.getServer().computeOnServer(MinecraftServer::getRunningThread);
        try {
            prepare(context, world);
            recordWindup(context, world);
            sprintBeforeGuard(context);
            String cast = start(context, world);
            assertLocked(context);
            context.waitTicks(LONG_GUARD_TICKS);
            context.runOnClient(client -> isTrue(playing(client, HOLD), "长守保持段已经丢失"));
            screenshot(context, "p4-guard-hold");
            feedback(context, world);
            release(context, world, cast);
            shortTap(context, world);
            weaponGuard(context, world);
            departing = departure(context, world);
            world.getServer().runOnServer(HumanGuardClientTest::markForSave);
        } finally {
            // 直接请求整合服停机，让本地玩家留在在线列表里进入 SERVER_STOPPING。
            context.runOnClient(Minecraft::disconnectWithSavingScreen);
        }
        context.waitFor(client -> !serverThread.isAlive());
        ServerPlayer departed = departing.player();
        isTrue(!HumanTechniqueHost.isCasting(departed.getUUID()), "断线停服后仍占用输入");
        isTrue(departed.getAttributeValue(Attributes.MOVEMENT_SPEED) == departing.speed(),
                "断线停服后旧实体残留减速");
        isTrue(!TechniqueState.read(departing.body().snapshot()).hasActiveCast(), "断线停服后身体残留施放");
        assertSaved(save, departed.getUUID());
        context.waitFor(client -> !HumanGuardClient.blocksSprint());
        try (var reloaded = save.open()) {
            reloaded.getServer().runOnServer(HumanGuardClientTest::assertReloaded);
        }
    }

    private static void markForSave(MinecraftServer server) {
        // 在最后一次服务器操作才改三个互相独立的持久化标记。
        var player = player(server);
        var playerId = player.getUUID();
        var state = HumanTechniqueHost.state();
        var body = state.find(playerId);
        isTrue(HumanTechniqueHost.isCasting(playerId), "停服前玩家应仍在施放");
        isTrue(!body.progress().getQiNotesRead(), "测试身体已有已读标记");
        var progress = body.progress().toBuilder()
                .setQiNotesRead(true /* read */)
                .build();
        body.writeProgress(progress);
        state.setDirty();
        player.setExperienceLevels(SAVED_XP_LEVEL);
        isTrue(server.overworld().setBlockAndUpdate(SAVED_BLOCK, Blocks.EMERALD_BLOCK.defaultBlockState()),
                "写入停服测试方块失败");
    }

    private static void assertSaved(TestWorldSave save, UUID playerId) {
        // 重新登录本身也会清残留，须在开服前直接解码磁盘上的身体。
        var file = save.getSaveDirectory().resolve("data/meridian_human.dat");
        try {
            var body = HumanWorldState.load(file).find(playerId);
            isTrue(body != null && body.progress().getQiNotesRead(), "停服未写入已读进度");
            isTrue(!TechniqueState.read(body.snapshot()).hasActiveCast(), "停服存档残留施放");
        } catch (IOException failure) {
            throw new AssertionError("无法读取停服后的人体存档 " + file, failure);
        }
    }

    private static void assertReloaded(MinecraftServer server) {
        // 从同一目录新启动的服务器读取三个标记。
        var player = player(server);
        isTrue(player.experienceLevel == SAVED_XP_LEVEL, "重进后玩家经验等级丢失");
        isTrue(server.overworld().getBlockState(SAVED_BLOCK).is(Blocks.EMERALD_BLOCK), "重进后世界方块丢失");
        var body = HumanTechniqueHost.state().find(player.getUUID());
        isTrue(body != null && body.progress().getQiNotesRead(), "重进后人体进度丢失");
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
        world.getServer().runOnServer(HumanGuardClientTest::strike);
        context.waitFor(client -> playing(client, HIT));
        screenshot(context, "p4-guard-hit");
        context.waitTicks(AFTER_RECOVERY_TICKS);
        context.runOnClient(client -> isTrue(playing(client, HOLD), "受击反馈后没有回到保持"));
    }

    private static void strike(MinecraftServer server) {
        // 固定从玩家正面击中头部，防御结果与动画均走正式服务端入口。
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
    }

    private static void weaponGuard(ClientGameTestContext context, TestSingleplayerContext world) {
        // 同一连接从空手切到铁剑，受击与松手仍由同一次服务器施放触发。
        var frames = new GuardFrames();
        var server = world.getServer();
        context.runOnClient(client -> client.options.fov().set(REVIEW_FOV));
        context.getInput().lookAt(0, REVIEW_PITCH);
        frames.advance(context, ESTABLISH_TICKS);
        String cast = start(context, world);
        context.waitFor(client -> playing(client, HOLD));
        frames.advance(context, UNARMED_TICKS);
        server.runOnServer(host -> player(host).setItemInHand(
                InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD)));
        context.waitFor(client -> playing(client, WEAPON_HOLD) && client.player.getMainHandItem().is(Items.IRON_SWORD));
        frames.advance(context, ARMED_TICKS);
        screenshot(context, "weapon-guard-hold");
        server.runOnServer(HumanGuardClientTest::strike);
        context.waitFor(client -> playing(client, WEAPON_HIT));
        frames.advance(context, 1);
        screenshot(context, "weapon-guard-hit");
        frames.advance(context, AFTER_CONTACT_TICKS);
        server.runOnServer(host -> HumanGuardTestCatalog.release(player(host), cast));
        context.waitFor(client -> playing(client, WEAPON_LOWER));
        frames.advance(context, 1);
        screenshot(context, "weapon-guard-lower");
        frames.advance(context, FINISH_TICKS);
        context.runOnClient(client -> isTrue(!playing(client, WEAPON_HOLD) && !playing(client, WEAPON_LOWER),
                "持剑收势结束后仍有守姿"));
        server.runOnServer(host -> player(host).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
    }

    private static void recordWindup(ClientGameTestContext context, TestSingleplayerContext world) {
        // 同一机位和伤害请求可在两版完整装配中重放，由实际目录决定是否赶上。
        String directory = System.getenv("HUMAN_GUARD_WINDUP_FRAMES");
        if (directory == null) {
            return;
        }
        var root = Path.of(directory);
        context.runOnClient(client -> client.options.fov().set(REVIEW_FOV));
        context.getInput().lookAt(0, REVIEW_PITCH);
        for (var item : List.of(Items.AIR, Items.IRON_SWORD)) {
            var stack = new ItemStack(item);
            world.getServer().runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND, stack));
            String shot = item == Items.AIR ? "arms" : "sword";
            var frames = new GuardFrames(root.resolve(shot).toString());
            frames.advance(context, ESTABLISH_TICKS);
            var cast = world.getServer().computeOnServer(HumanGuardClientTest::startFormalGuard);
            frames.advance(context, RAISE_SHOT_TICKS);
            world.getServer().runOnServer(server -> HumanGuardTestCatalog.release(player(server), cast));
            frames.advance(context, AFTER_RECOVERY_TICKS);
        }
        world.getServer().runOnServer(server ->
                player(server).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
        contactShot(context, world, new GuardFrames(root.resolve("late").toString()), LATE_LEAD_TICKS);
        contactShot(context, world, new GuardFrames(root.resolve("early").toString()), EARLY_LEAD_TICKS);
    }

    private static String startFormalGuard(MinecraftServer server) {
        // 录像只通过服务端施放装配里的正式格挡。
        var outcome = HumanGuardTestCatalog.start(player(server), FORMAL_GUARD);
        isTrue(outcome.getPhase() == CastPhase.WARMUP, "正式格挡起手失败：" + outcome);
        return outcome.getCastId();
    }

    private static Husk opponent(MinecraftServer server) {
        // 对手在正前方偏侧举剑，正面镜头同时看见挥手和守者的双臂。
        var target = player(server);
        var husk = EntityTypes.HUSK.create(target.level(), EntitySpawnReason.COMMAND);
        husk.setNoAi(true /* noAi */);
        husk.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        husk.setPos(target.position().add(ATTACKER_SIDE, 0, ATTACK_DISTANCE));
        husk.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        husk.setYBodyRot(husk.getYRot());
        target.level().addFreshEntity(husk);
        return husk;
    }

    private static void contactShot(ClientGameTestContext context, TestSingleplayerContext world,
                                    GuardFrames frames, int leadTicks) {
        // 两次出剑到命中的间隔相同，只有玩家按下格挡的时间不同。
        var host = world.getServer();
        var attacker = host.computeOnServer(HumanGuardClientTest::opponent);
        var shot = host.computeOnServer(server -> new ContactShot(attacker, leadTicks));
        host.runOnServer(server -> ContactShot.active = shot);
        try {
            frames.advance(context, ESTABLISH_TICKS + RAISE_SHOT_TICKS);
            host.runOnServer(server -> {
                isTrue(shot.resolved, "镜头未覆盖挥剑命中");
                HumanGuardTestCatalog.release(player(server), shot.castId);
            });
        } finally {
            host.runOnServer(server -> {
                ContactShot.active = null;
                attacker.discard();
            });
        }
        frames.advance(context, AFTER_RECOVERY_TICKS);
    }

    private static final class ContactShot {
        private static ContactShot active;
        private final Husk attacker;
        private final int leadTicks;
        private int tick;
        private String castId;
        private boolean resolved;

        static {
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                if (active != null) {
                    active.advance(server);
                }
            });
        }

        ContactShot(Husk attacker, int leadTicks) {
            // 由同一个服务端时钟驱动出剑、起守和实际结算。
            this.attacker = attacker;
            this.leadTicks = leadTicks;
        }

        void advance(MinecraftServer server) {
            // 命中固定在建立镜头的最后一拍。
            tick++;
            if (tick == ESTABLISH_TICKS - STRIKE_LEAD_TICKS) {
                attacker.swing(InteractionHand.MAIN_HAND);
            }
            if (tick == ESTABLISH_TICKS - leadTicks) {
                castId = startFormalGuard(server);
            }
            if (tick == ESTABLISH_TICKS) {
                checkContact(player(server), attacker, leadTicks);
                resolved = true;
            }
        }
    }

    private static void checkContact(ServerPlayer target, Husk attacker, int leadTicks) {
        // 录制时核对真实施放时钟及本击扣血部位，错过防御时头部应直接吃下伤害。
        var body = HumanTechniqueHost.state().find(target.getUUID());
        var cast = TechniqueState.read(body.snapshot()).getActiveCast();
        long warmup = cast.getWarmupEndMillis() - cast.getStartedMillis();
        long elapsed = target.level().getGameTime() * MILLIS_PER_TICK - cast.getStartedMillis();
        isTrue(elapsed == leadTicks * MILLIS_PER_TICK, "镜头时钟偏移 " + elapsed);
        var damage = new VanillaDamageFabric.DirectDamage(target, target.damageSources().mobAttack(attacker),
                "head", HIT_HP);
        var hit = VanillaDamageFabric.damageDirect(damage).orElseThrow();
        var losses = hit.health().getDamageResolution().getLossesList();
        String part = losses.getFirst().getTarget().getPartId();
        boolean onTime = elapsed >= warmup;
        if (onTime) {
            isTrue(Set.of("left_forearm", "right_forearm").contains(part), "应挡住，实际命中 " + part);
        } else {
            isTrue("head".equals(part), "起手中，实际命中 " + part);
        }
        LoggerFactory.getLogger(HumanGuardClientTest.class)
                .info("正式格挡起手 {} ms，按键至命中 {} ms，扣血部位 {}", warmup, elapsed, part);
    }

    private static final class GuardFrames {
        private final Path directory;
        private int frame;

        GuardFrames() {
            // 宿主回归沿用原有的可选逐帧输出目录。
            this(System.getenv("HUMAN_GUARD_FRAMES"));
        }

        GuardFrames(String directory) {
            // 每个连续镜头的逐拍截图写入自己的目录。
            if (directory == null) {
                this.directory = null;
                return;
            }
            try {
                this.directory = Files.createDirectories(Path.of(directory));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        void advance(ClientGameTestContext context, int ticks) {
            // Fabric 在指定游戏时刻渲染真实世界，每 tick 输出一帧，普通回归只推进时间。
            if (directory == null) {
                context.waitTicks(ticks);
                return;
            }
            for (int tick = 0; tick < ticks; tick++) {
                context.waitTick();
                Path path = directory.resolve("guard-%04d.png".formatted(frame++));
                context.runOnClient(client -> capture(client, path));
            }
        }

        private static void capture(Minecraft client, Path path) {
            // 异步读回这一拍的完整画面，等待截图落盘会额外推进游戏时钟。
            var renderer = client.gameRenderer;
            var window = client.getWindow();
            int width = window.getWidth();
            int height = window.getHeight();
            window.setWidth(FRAME_WIDTH);
            window.setHeight(FRAME_HEIGHT);
            var target = renderer.mainRenderTarget();
            target.resize(FRAME_WIDTH, FRAME_HEIGHT);
            try {
                renderer.update(DeltaTracker.ONE);
                renderer.extract(DeltaTracker.ONE, true /* renderLevel */);
                renderer.render(DeltaTracker.ONE, true /* renderLevel */);
                RenderSystem.getDevice().createCommandEncoder().submit();
                Screenshot.takeScreenshot(target, image -> {
                    try (image) {
                        image.writeToFile(path);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
            } finally {
                window.setWidth(width);
                window.setHeight(height);
                target.resize(width, height);
            }
        }
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
