package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/** 在正式宿主读档前写入旧格式玩家，测试不替换运行中的世界或功法宿主。 */
public final class HumanLegacyBodyTestBootstrap implements ModInitializer {
    static final UUID PLAYER_ID = UUID.randomUUID();
    static BodySnapshot legacyBody;

    @Override
    public void onInitialize() {
        // 仅测试模组注册夹具，Human 在 SERVER_STARTED 仍执行正式读档。
        ServerLifecycleEvents.SERVER_STARTING.register(HumanLegacyBodyTestBootstrap::writeLegacyBody);
    }

    private static void writeLegacyBody(MinecraftServer server) {
        // 沿用已有世界及离线玩家，只给本次专用 UUID 添加一个无标签快照。
        var file = server.getSavePath(WorldSavePath.ROOT).resolve("data/meridian_human.dat");
        try {
            HumanWorldState state;
            if (Files.exists(file)) {
                state = HumanWorldState.read(NbtIo.readCompressed(file.toFile()).getCompound("data"));
            } else {
                state = new HumanWorldState();
            }
            state.join(PLAYER_ID, 0);
            var data = state.writeNbt(new NbtCompound());
            var players = data.getList("players", NbtElement.COMPOUND_TYPE);
            for (int index = 0; index < players.size(); index++) {
                var entry = players.getCompound(index);
                var body = BodyProtobuf.decodeSnapshot(entry.getByteArray("body"));
                if (body.id().value().equals(PLAYER_ID.toString())) {
                    legacyBody = withoutTags(body);
                    entry.putByteArray("body", BodyProtobuf.encodeSnapshot(legacyBody));
                }
            }
            var root = new NbtCompound();
            root.put("data", data);
            NbtHelper.putDataVersion(root);
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(root, file.toFile());
        } catch (IOException failure) {
            throw new IllegalStateException("旧身体夹具写入失败", failure);
        }
    }

    private static BodySnapshot withoutTags(BodySnapshot body) {
        // 只删除旧版本没有的标签字段，原库存、开脉、进度等身体事实保持不变。
        var snapshot = BodyProtobuf.toMessage(body);
        var structure = snapshot.getStructure().toBuilder();
        structure.getTissuesBuilderList().forEach(module -> module.clearTags());
        structure.getOrgansBuilderList().forEach(module -> module.clearTags());
        structure.getMeridiansBuilderList().forEach(module -> module.clearTags());
        return BodyProtobuf.fromMessage(snapshot.toBuilder().setStructure(structure).build());
    }
}
