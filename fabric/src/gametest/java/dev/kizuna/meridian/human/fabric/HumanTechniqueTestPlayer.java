package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.progression.ProgressionProtocol;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.playeranimation.network.AnimationPackets;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.util.List;
import java.util.UUID;

/** 经过真实登录、频道协商和断线事件的测试玩家，网络输出记录在内存中。 */
final class HumanTechniqueTestPlayer implements AutoCloseable {
    private static final int NAME_ID_LENGTH = 8;
    final ServerPlayer player;
    final RecordingConnection connection;

    HumanTechniqueTestPlayer(GameTestHelper context) {
        // 每个普通夹具使用独立玩家，防止施放与费用在测试间共享。
        this(context, UUID.randomUUID());
    }

    HumanTechniqueTestPlayer(GameTestHelper context, UUID playerId) {
        // 只替换传输层，玩家列表、Human 登录和 Fabric 网络事件均使用正式实现。
        this(context, playerId, ServerPlayer::new);
    }

    HumanTechniqueTestPlayer(GameTestHelper context, UUID playerId, PlayerFactory factory) {
        // 允许观察实际实体速度入口，不替换登录、网络处理或身体宿主。
        this(context.getLevel(), playerId, factory);
    }

    HumanTechniqueTestPlayer(ServerLevel world) {
        // 专用服务端验证沿用相同登录夹具，不依赖 GameTestServer。
        this(world, UUID.randomUUID(), ServerPlayer::new);
    }

    private HumanTechniqueTestPlayer(ServerLevel world, UUID playerId, PlayerFactory factory) {
        // 网络传输留在内存，世界、玩家管理器与身体宿主仍为真实实例。
        var profile = new GameProfile(playerId, "tech_" + playerId.toString().substring(0, NAME_ID_LENGTH));
        player = factory.create(world.getServer(), world, profile, ClientInformation.createDefault());
        connection = new RecordingConnection(List.of(MeridianChannels.BODY_VIEW.type().id(),
                MeridianChannels.GATHERING_RESPONSE.type().id(), MeridianChannels.OPENING_RESPONSE.type().id(),
                Identifier.parse(ProgressionProtocol.EVENT), AnimationPackets.PLAY.id(), AnimationPackets.STOP.id()));
        TestPlayers.logIn(player, connection);
        player.setGameMode(GameType.CREATIVE);
        player.setNoGravity(true /* noGravity */);
    }

    @FunctionalInterface
    interface PlayerFactory {
        ServerPlayer create(MinecraftServer server, ServerLevel world, GameProfile profile,
                            ClientInformation information);
    }

    HumanPlayerBody body() {
        // 读取 Human 正式绑定的世界状态，不创建第二份身体或替换宿主绑定。
        return state().find(player.getUUID());
    }

    HumanWorldState state() {
        // 与生产登录和 tick 使用同一个世界状态实例。
        return HumanTechniqueHost.state();
    }

    @Override
    public void close() {
        // 真实断线入口保证功法取消先于 Human 撤销认领，并从在线列表移除玩家。
        connection.disconnectFromClient();
    }
}
