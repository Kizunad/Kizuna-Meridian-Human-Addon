package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.Message;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Envelope;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Kind;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.ServerProtocol;
import dev.kizuna.inventoryui.server.fabric.FabricServerModule;
import dev.kizuna.inventoryui.server.fabric.InventoryUiServer;
import dev.kizuna.meridian.progression.fabric.ProgressionItems;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewRequest;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyViewUpdate;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.UUID;

/** 通过实际玩家、统一握手、右键物品和网络订阅验证内观授权闭环。 */
public final class HumanInnerVisionGameTest implements FabricGameTest {
    private static final int OP_LEVEL = 4;
    private static final int RESET_SETTLE_TICKS = 3;
    private static final ResourceLocation VIEW_REQUEST = new ResourceLocation("meridian", "body_view_request");
    private static final ResourceLocation VIEW_UPDATE = new ResourceLocation("meridian", "body_view");
    private static final String UNLOCK_MESSAGE = "message.meridian_progression.inner_vision_unlocked";

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void readingUnlocksInspectionAndResetRevokesSubscription(GameTestHelper context) {
        // 札记已读也不能跳过内观卷；整个回归使用玩家真实身体而非独立存储夹具。
        var fixture = new HumanTechniqueTestPlayer(context);
        try {
            handshake(fixture);
            command(fixture.player, "meridian progression unlock");
            command(fixture.player, "meridian progression reset inner_vision");
            String denied = subscribe(fixture);
            context.assertTrue(reply(fixture, denied).getFailure().getCode() == FailureCode.ACCESS_DENIED,
                    "未读内观卷仍可订阅内部视图");
            readScroll(context, fixture);
            String accepted = subscribe(fixture);
            context.assertTrue(reply(fixture, accepted).getIntrospection(), "读卷后内观订阅未获准");
            fixture.connection.packets.clear();
            command(fixture.player, "meridian progression reset inner_vision");
            context.runAfterDelay(RESET_SETTLE_TICKS, () -> verifyReset(context, fixture));
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void readScroll(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // give 与真实物品 use 共用正式注册身份，首次提示必须是动作栏消息且只出现一次。
        command(fixture.player, "give @s meridian_progression:inner_vision_scroll");
        context.assertTrue(fixture.player.getInventory().contains(new ItemStack(ProgressionItems.INNER_VISION_SCROLL)),
                "give 未得到内观残卷");
        fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ProgressionItems.INNER_VISION_SCROLL));
        var world = fixture.player.serverLevel();
        ProgressionItems.INNER_VISION_SCROLL.use(world, fixture.player, InteractionHand.MAIN_HAND);
        context.assertTrue(fixture.body().progress().getInnerVisionScrollRead(), "右键没有立即写入内观进度");
        ProgressionItems.INNER_VISION_SCROLL.use(world, fixture.player, InteractionHand.MAIN_HAND);
        long prompts = fixture.connection.packets.stream()
                .filter(ClientboundSystemChatPacket.class::isInstance)
                .map(ClientboundSystemChatPacket.class::cast)
                .filter(ClientboundSystemChatPacket::overlay)
                .filter(packet -> packet.content().getContents() instanceof TranslatableContents text
                        && text.getKey().equals(UNLOCK_MESSAGE))
                .count();
        context.assertTrue(prompts == 1, "内观首次动作栏提示缺失或重复：" + prompts);
    }

    private static void verifyReset(GameTestHelper context, HumanTechniqueTestPlayer fixture) {
        // 撤权应马上清除订阅；随后仅解锁权限不能恢复旧订阅，必须重新发请求。
        try {
            var progress = fixture.body().progress();
            context.assertTrue(!progress.getInnerVisionScrollRead() && progress.getQiNotesRead()
                    && progress.getGatheringScrollRead() && progress.getMiddleJiaoScrollRead(),
                    "单卷重置误改其他卷或未清内观");
            context.assertTrue(!latestView(fixture).getIntrospection(), "重置后仍推送内部视图");
            fixture.connection.packets.clear();
            command(fixture.player, "meridian progression unlock inner_vision");
            context.runAfterDelay(RESET_SETTLE_TICKS, () -> {
                try (fixture) {
                    context.assertTrue(!latestView(fixture).getIntrospection(), "旧订阅在重新授权后复活");
                    context.assertTrue(reply(fixture, subscribe(fixture)).getIntrospection(), "重新订阅失败");
                    context.succeed();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void handshake(HumanTechniqueTestPlayer fixture) {
        // 公共能力从必装入口读取，通过实际 Fabric 通道进行完整协议握手。
        var registry = new ServerModuleRegistry<ServerPlayer>();
        var server = fixture.player.getServer();
        FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui_server", FabricServerModule.class)
                .forEach(module -> module.register(server, registry));
        var hello = Envelope.newBuilder()
                .setProtocol(ServerProtocol.VERSION)
                .setKind(Kind.HELLO)
                .putAllCapabilities(registry.capabilities())
                .build();
        send(fixture.player, InventoryUiServer.CHANNEL, hello);
    }

    private static String subscribe(HumanTechniqueTestPlayer fixture) {
        // 请求只能声明本具身体和订阅意图，不能携带解锁字段。
        var request = BodyViewRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(fixture.body().snapshot().id().value())
                .setIntrospection(true /* value */)
                .build();
        send(fixture.player, VIEW_REQUEST, request);
        return request.getRequestId();
    }

    private static void send(ServerPlayer player, ResourceLocation channel, Message payload) {
        // 只替换客户端传输端，解码、线程切换和处理仍走已注册网络接收器。
        var buffer = PacketByteBufs.create();
        try {
            buffer.writeBytes(payload.toByteArray());
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(channel, buffer));
        } finally {
            buffer.release();
        }
    }

    private static BodyViewUpdate reply(HumanTechniqueTestPlayer fixture, String requestId) {
        // 读取实际服务端响应，失败码与授权位不得由测试自行推算。
        return fixture.connection.packets.stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(ClientboundCustomPayloadPacket.class::cast)
                .filter(packet -> packet.getIdentifier().equals(VIEW_UPDATE))
                .map(HumanInnerVisionGameTest::decodeView)
                .filter(update -> update.getRequestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少内观请求回执"));
    }

    private static BodyViewUpdate latestView(HumanTechniqueTestPlayer fixture) {
        // 定时推送用于验证服务器确已撤销订阅，不依赖客户端撤权包。
        return fixture.connection.packets.stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(ClientboundCustomPayloadPacket.class::cast)
                .filter(packet -> packet.getIdentifier().equals(VIEW_UPDATE))
                .map(HumanInnerVisionGameTest::decodeView)
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new IllegalStateException("缺少身体推送"));
    }

    private static BodyViewUpdate decodeView(ClientboundCustomPayloadPacket packet) {
        // 读取副本以保留原始包的 readerIndex，后续断言仍可使用同一证据。
        try {
            var buffer = packet.getData();
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return BodyViewUpdate.parseFrom(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void command(ServerPlayer player, String command) {
        // OP 验收也调用正式命令树，不直接写进度。
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(OP_LEVEL), command);
    }
}
