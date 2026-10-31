package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
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

    HumanTechniqueTestPlayer(net.minecraft.server.level.ServerLevel world) {
        // 专用服务端验证沿用相同登录夹具，不依赖 TestServer。
        this(world, UUID.randomUUID(), ServerPlayer::new);
    }

    private HumanTechniqueTestPlayer(net.minecraft.server.level.ServerLevel world,
                                     UUID playerId, PlayerFactory factory) {
        // 网络传输留在内存，世界、玩家管理器与身体宿主仍为真实实例。
        var server = world.getServer();
        var profile = new GameProfile(playerId, "tech_" + playerId.toString().substring(0, NAME_ID_LENGTH));
        player = factory.create(server, world, profile);
        connection = new RecordingConnection();
        var channels = ((ChannelInfoHolder) (Connection) connection).getPendingChannelsNames();
        channels.addAll(List.of(new ResourceLocation("meridian:body_view"),
                new ResourceLocation("meridian:gathering_response"), new ResourceLocation("meridian:opening_response"),
                new ResourceLocation("meridian_progression:event"), new ResourceLocation("kizuna_players_animation:play"),
                new ResourceLocation("kizuna_players_animation:stop")));
        server.getPlayerList().placeNewPlayer(connection, player);
        player.setGameMode(GameType.CREATIVE);
        player.setNoGravity(true /* noGravity */);
    }

    @FunctionalInterface
    interface PlayerFactory {
        ServerPlayer create(net.minecraft.server.MinecraftServer server,
                                  net.minecraft.server.level.ServerLevel world, GameProfile profile);
    }

    HumanPlayerBody body() {
        // 读取 Human 正式绑定的世界状态，不创建第二份身体或替换宿主绑定。
        return state().find(player.getUUID());
    }

    HumanWorldState state() {
        // 与生产登录和 tick 使用同一个 SavedData 实例。
        return player.getServer().overworld().getDataStorage()
                .get(HumanWorldState::read, "meridian_human");
    }

    @Override
    public void close() {
        // 真实断线入口保证功法取消先于 Human 撤销认领，并从在线列表移除玩家。
        if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != null) {
            player.connection.onDisconnect(Component.literal("GameTest 完成"));
        }
        connection.disconnect(Component.literal("GameTest 完成"));
    }

    static final class RecordingConnection extends Connection {
        final List<Packet<?>> packets = new ArrayList<>();
        private boolean open = true;

        RecordingConnection() {
            // 接收端仍为服务端，Fabric 的网络附加状态与真实连接一致。
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            // 保留发送的原始包供同步断言，不复制任何业务协议。
            packets.add(packet);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener callbacks) {
            // 传输成功回调按正常连接执行，业务层无需特殊测试分支。
            send(packet);
            if (callbacks != null) {
                callbacks.onSuccess();
            }
        }

        @Override
        public boolean isConnected() {
            // 网络入口据此检查连接仍有效。
            return open;
        }

        @Override
        public boolean isMemoryConnection() {
            // 本地测试不经过压缩和加密握手。
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            // 登录日志和玩家管理器需要可打印的连接地址。
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public void disconnect(Component reason) {
            // 连接关闭与玩家移除分别由真实网络处理器和测试传输层负责。
            open = false;
        }
    }
}
