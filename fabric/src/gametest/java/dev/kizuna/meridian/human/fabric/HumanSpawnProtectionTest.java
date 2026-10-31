package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 专用服务端的出生点保护回归；GameTestServer 不实现该权限语义。 */
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
            if (!(server instanceof DedicatedServer)) {
                throw new AssertionError("出生点保护验证必须使用专用服务端");
            }
            verify(server);
            Files.writeString(Path.of("result.txt"), "PASS\n");
        } catch (IOException failure) {
            throw new IllegalStateException("出生点保护测试结果写入失败", failure);
        } finally {
            server.halt(false /* waitForServer */);
        }
    }

    private static void verify(MinecraftServer server) {
        // 保护区只有在管理员列表非空时启用，测试账号本身初始为非管理员。
        var world = server.overworld();
        var anchor = new BlockPos(0, TEST_HEIGHT, 0);
        world.setRespawnData(new LevelData.RespawnData(GlobalPos.of(world.dimension(), anchor), 0, 0));
        var operator = new NameAndId(UUID.randomUUID(), "protection_admin");
        server.getPlayerList().op(operator);
        try (var fixture = new HumanTechniqueTestPlayer(world)) {
            fixture.player.setGameMode(GameType.SURVIVAL);
            fixture.player.snapTo(BLOCK_CENTER, TEST_HEIGHT, BLOCK_CENTER, 0, 0);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            BlockPos door = anchor.south(DOOR_DISTANCE);
            placeDoor(server, door);
            if (!server.isUnderSpawnProtection(world, door, fixture.player)) {
                throw new AssertionError("专用服务端未启用出生点保护");
            }
            execute(fixture, "protected");
            if (!world.getBlockState(door).is(Blocks.OAK_DOOR)) {
                throw new AssertionError("功法破坏了出生点保护区的木门");
            }
            verifyOperator(fixture, door);
        } finally {
            server.getPlayerList().deop(operator);
        }
    }

    private static void verifyOperator(HumanTechniqueTestPlayer fixture, BlockPos door) {
        // 同一世界同一工具获得管理员权限后应正常破门，证明拒绝来自保护链。
        var server = fixture.player.level().getServer();
        var profile = fixture.player.nameAndId();
        server.getPlayerList().op(profile);
        try {
            execute(fixture, "operator");
            if (!fixture.player.level().getBlockState(door).isAir()) {
                throw new AssertionError("管理员仍不能劈开保护区木门");
            }
        } finally {
            server.getPlayerList().deop(profile);
        }
    }

    private static void placeDoor(MinecraftServer server, BlockPos pos) {
        // 双格木门使用真实方块及邻接更新，由正式挖掘链移除两半。
        var world = server.overworld();
        world.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(pos, Blocks.OAK_DOOR.defaultBlockState());
        world.setBlockAndUpdate(pos.above(), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void execute(HumanTechniqueTestPlayer fixture, String castId) {
        // 正式目录参数经真实 Human 执行器进入共享世界权限判断。
        EffectIntent effect = HumanCombatFixture.doors(castId);
        var submission = new TechniqueHost.EffectSubmission(castId, effect);
        new HumanTechniqueEffects().submit(fixture.player, submission);
    }
}
