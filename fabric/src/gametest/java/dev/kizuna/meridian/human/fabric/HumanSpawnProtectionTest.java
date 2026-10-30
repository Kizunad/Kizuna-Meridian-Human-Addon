package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 专用服务端的出生点保护回归；TestServer 不实现该权限语义。 */
public final class HumanSpawnProtectionTest implements ModInitializer {
    private static final int TEST_HEIGHT = 64;
    private static final int DOOR_DISTANCE = 2;
    private static final double BLOCK_CENTER = 0.5;

    @Override
    public void onInitialize() {
        // 仅显式测试运行注册回调，常规 GameTest 不执行专用服务端任务。
        if (Boolean.getBoolean("meridian.human.spawnProtectionTest")) {
            ServerLifecycleEvents.SERVER_STARTED.register(HumanSpawnProtectionTest::run);
        }
    }

    private static void run(MinecraftServer server) {
        // 使用真实 DedicatedServer 的保护判断，验证无权限拒绝与管理员放行。
        try {
            if (!(server instanceof MinecraftDedicatedServer)) {
                throw new AssertionError("出生点保护验证必须使用专用服务端");
            }
            verify(server);
            Files.writeString(Path.of("result.txt"), "PASS\n");
        } catch (IOException failure) {
            throw new IllegalStateException("出生点保护测试结果写入失败", failure);
        } finally {
            server.stop(false /* waitForServer */);
        }
    }

    private static void verify(MinecraftServer server) {
        // 保护区只有在管理员列表非空时启用，测试账号本身初始为非管理员。
        var world = server.getOverworld();
        var anchor = new BlockPos(0, TEST_HEIGHT, 0);
        world.setSpawnPos(anchor, 0);
        var operator = new GameProfile(UUID.randomUUID(), "protection_admin");
        server.getPlayerManager().addToOperators(operator);
        try (var fixture = new HumanTechniqueTestPlayer(world)) {
            fixture.player.changeGameMode(GameMode.SURVIVAL);
            fixture.player.refreshPositionAndAngles(BLOCK_CENTER, TEST_HEIGHT, BLOCK_CENTER, 0, 0);
            fixture.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            BlockPos door = anchor.south(DOOR_DISTANCE);
            placeDoor(server, door);
            if (!server.isSpawnProtected(world, door, fixture.player)) {
                throw new AssertionError("专用服务端未启用出生点保护");
            }
            execute(fixture, "protected");
            if (!world.getBlockState(door).isOf(Blocks.OAK_DOOR)) {
                throw new AssertionError("功法破坏了出生点保护区的木门");
            }
            verifyOperator(fixture, door);
        } finally {
            server.getPlayerManager().removeFromOperators(operator);
        }
    }

    private static void verifyOperator(HumanTechniqueTestPlayer fixture, BlockPos door) {
        // 同一世界同一工具获得管理员权限后应正常破门，证明拒绝来自保护链。
        var server = fixture.player.getServer();
        var profile = fixture.player.getGameProfile();
        server.getPlayerManager().addToOperators(profile);
        try {
            execute(fixture, "operator");
            if (!fixture.player.getWorld().getBlockState(door).isAir()) {
                throw new AssertionError("管理员仍不能劈开保护区木门");
            }
        } finally {
            server.getPlayerManager().removeFromOperators(profile);
        }
    }

    private static void placeDoor(MinecraftServer server, BlockPos pos) {
        // 双格木门使用真实方块及邻接更新，由正式挖掘链移除两半。
        var world = server.getOverworld();
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(pos, Blocks.OAK_DOOR.getDefaultState());
        world.setBlockState(pos.up(), Blocks.OAK_DOOR.getDefaultState()
                .with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void execute(HumanTechniqueTestPlayer fixture, String castId) {
        // 正式目录参数经真实 Human 执行器进入共享世界权限判断。
        EffectIntent effect = HumanCombatFixture.doors(castId);
        var submission = new TechniqueHost.EffectSubmission(castId, effect);
        new HumanTechniqueEffects().submit(fixture.player, submission);
    }
}
