package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.environment.TerrainQiGenerator;
import dev.kizuna.meridian.protocol.EnvironmentProtobuf;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.PersistentState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;

/** Minecraft 持久化容器只包装 Protobuf 字节，正文仍由框架负责定义和校验。 */
final class HumanWorldState extends PersistentState {
    private static final long WINDOW_MILLIS = 30_000;
    private final String epoch;
    private final Map<UUID, HumanPlayerBody> players = new HashMap<>();
    private final InMemoryBodyStore store;
    private final EnvironmentQiRules rules;
    private final TerrainQiGenerator generation;
    private final int regionSizeChunks;

    HumanWorldState() {
        // 每个新世界获得稳定时钟世代，重启时读取保存的值。
        this(UUID.randomUUID().toString(), new InMemoryBodyStore(List.of(), List.of(), QiInventory.empty()));
    }

    private HumanWorldState(String epoch, InMemoryBodyStore store) {
        // 已有世界不能重抽时钟世代，否则原身体时间无法继续比较。
        this.epoch = epoch;
        this.store = store;
        var configPath = FabricLoader.getInstance().getConfigDir().resolve("meridian-environment.toml");
        var config = Files.exists(configPath) ? EnvironmentConfigLoader.load(configPath) : EnvironmentConfigLoader.defaults();
        rules = new EnvironmentQiRules(config);
        generation = new TerrainQiGenerator(config.getGeneration());
        regionSizeChunks = config.getRegionSizeChunks();
    }

    static HumanWorldState read(NbtCompound nbt) {
        // NBT 只承担 Minecraft 文件封装；每个身体、回执和余额均从 protoc 消息恢复。
        try {
            String epoch = nbt.getString("epoch");
            UUID.fromString(epoch);
            if (!nbt.contains("players", NbtElement.LIST_TYPE)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            var bodies = new ArrayList<BodySnapshot>();
            var receipts = new ArrayList<CommandReceipt>();
            var released = QiInventory.empty();
            var breathingActions = new HashMap<UUID, Set<String>>();
            NbtList entries = nbt.getList("players", NbtElement.COMPOUND_TYPE);
            for (int index = 0; index < entries.size(); index++) {
                NbtCompound entry = entries.getCompound(index);
                if (!entry.contains("receipts", NbtElement.LIST_TYPE)
                        || !entry.contains("released_qi", NbtElement.BYTE_ARRAY_TYPE)) {
                    throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                }
                var body = BodyProtobuf.decodeSnapshot(entry.getByteArray("body"));
                UUID playerId = UUID.fromString(body.id().value());
                if (!body.settledAt().epoch().equals(epoch) || breathingActions.containsKey(playerId)) {
                    throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                }
                NbtList savedReceipts = entry.getList("receipts", NbtElement.COMPOUND_TYPE);
                for (int receiptIndex = 0; receiptIndex < savedReceipts.size(); receiptIndex++) {
                    receipts.add(MeridianProtocol.decodeReceipt(savedReceipts.getCompound(receiptIndex).getByteArray("value")));
                }
                // 旧存档逐身体保存释放账，新格式统一保存；读取旧值后合并到唯一世界账户。
                var legacyReleased = BodyProtobuf.fromInventory(BodyMessages.QiInventory.parseFrom(entry.getByteArray("released_qi")));
                released = dev.kizuna.meridian.core.QiAccounting.credit(released, legacyReleased);
                bodies.add(body);
                var instances = new HashSet<String>();
                NbtList actions = entry.getList("breathing_actions", NbtElement.STRING_TYPE);
                for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                    instances.add(actions.getString(actionIndex));
                }
                breathingActions.put(playerId, Set.copyOf(instances));
            }
            var sources = new ArrayList<EnvironmentQiSource>();
            NbtList savedSources = nbt.getList("environment_sources", NbtElement.BYTE_ARRAY_TYPE);
            for (int index = 0; index < savedSources.size(); index++) {
                EnvironmentQiSource source = EnvironmentQiSource.parseFrom(((net.minecraft.nbt.NbtByteArray) savedSources.get(index)).getByteArray());
                EnvironmentProtobuf.validateSource(source);
                sources.add(source);
            }
            if (nbt.contains("released_qi", NbtElement.BYTE_ARRAY_TYPE)) {
                released = dev.kizuna.meridian.core.QiAccounting.credit(released,
                        BodyProtobuf.fromInventory(BodyMessages.QiInventory.parseFrom(nbt.getByteArray("released_qi"))));
            }
            var store = new InMemoryBodyStore(bodies, receipts, released, sources);
            var state = new HumanWorldState(epoch, store);
            for (BodySnapshot body : bodies) {
                UUID playerId = UUID.fromString(body.id().value());
                state.players.put(playerId, new HumanPlayerBody(body, store, breathingActions.get(playerId)));
            }
            return state;
        } catch (Exception failure) {
            throw HumanErrors.invalidSave(failure);
        }
    }

    HumanPlayerBody join(UUID playerId, long millis) {
        // 只有不存在的 UUID 才首次创建；重连和重生均继续同一权威身体。
        HumanPlayerBody body = players.computeIfAbsent(playerId, id -> HumanPlayerBody.born(id, epoch, millis, store));
        body.resume(millis);
        markDirty();
        return body;
    }

    HumanPlayerBody find(UUID playerId) {
        // 查询不创建、不恢复、不初始化玩家资源。
        return players.get(playerId);
    }

    void selectEnvironment(ServerPlayerEntity player, long millis) {
        // 仅从当前群系元数据初始化背景区域，无方块扫描或房间识别。
        HumanPlayerBody body = players.get(player.getUuid());
        String dimension = player.getWorld().getRegistryKey().getValue().toString();
        if (!dimension.equals("minecraft:overworld")) {
            body.selectEnvironment(null, millis);
            return;
        }
        int regionX = rules.regionCoordinate(player.getChunkPos().x);
        int regionZ = rules.regionCoordinate(player.getChunkPos().z);
        String sourceId = "human:world_" + epoch + "/" + dimension.replace(':', '/') + "/" + regionX + "/" + regionZ;
        if (store.environmentSource(sourceId).isEmpty()) {
            // 相同区域使用固定中心的群系，生成后只读取保存库存，不会随进入位置重抽。
            BlockPos center = new BlockPos((regionX * regionSizeChunks * 16) + regionSizeChunks * 8,
                    player.getWorld().getSeaLevel(), (regionZ * regionSizeChunks * 16) + regionSizeChunks * 8);
            String biome = player.getWorld().getBiome(center).getKey().orElseThrow().getValue().toString();
            double volumeBlocks = rules.regionVolumeBlocks(player.getWorld().getHeight());
            var request = TerrainQiRegionRequest.newBuilder().setProtocolVersion(1)
                    .setWorldSeed(Long.toString(player.getServerWorld().getSeed())).setDimensionId(dimension)
                    .setSourceId(sourceId).setBiomeId(biome).setVolumeBlocks(volumeBlocks).build();
            var seed = generation.generateRegion(request);
            var space = EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId(sourceId)
                    .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(volumeBlocks).build();
            store.registerEnvironmentSource(EnvironmentAbsorption.initialize(space,
                    BodyProtobuf.fromInventory(seed.getInitialQi()), time(millis), time(millis + WINDOW_MILLIS), rules));
            markDirty();
        }
        body.selectEnvironment(sourceId, millis);
    }

    void renewEnvironment(long millis) {
        // 所有身体先结清旧窗后统一续窗；剩余库存保留，多人共享供给预算不提前重置。
        for (EnvironmentQiSource source : store.environmentSources()) {
            if (millis >= source.getValidUntil().getElapsedMillis()) {
                store.renewEnvironmentWindow(source.getSpace().getSourceId(), source.getRevision(),
                        time(millis), time(millis + WINDOW_MILLIS), rules);
                markDirty();
            }
        }
    }

    private BodyMessages.TimeCursor time(long millis) {
        // 所有区域与身体共用世界模拟时钟，暂停时不会产生新的可摄入区间。
        return BodyMessages.TimeCursor.newBuilder().setDomain("human:minecraft").setEpoch(epoch).setElapsedMillis(millis).build();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        // 世界保存将身体、回执与释放账写入同一个持久化文件，避免分别保存导致重复发放。
        nbt.putString("epoch", epoch);
        var entries = new NbtList();
        for (HumanPlayerBody body : players.values()) {
            var entry = new NbtCompound();
            entry.putByteArray("body", BodyProtobuf.encodeSnapshot(body.snapshot()));
            entry.putByteArray("released_qi", BodyProtobuf.toInventory(QiInventory.empty()).toByteArray());
            var actions = new NbtList();
            for (String instance : body.breathingActions()) {
                actions.add(net.minecraft.nbt.NbtString.of(instance));
            }
            entry.put("breathing_actions", actions);
            var receipts = new NbtList();
            for (CommandReceipt receipt : body.receipts()) {
                var saved = new NbtCompound();
                saved.putByteArray("value", MeridianProtocol.encodeReceipt(receipt));
                receipts.add(saved);
            }
            entry.put("receipts", receipts);
            entries.add(entry);
        }
        nbt.put("players", entries);
        nbt.putByteArray("released_qi", BodyProtobuf.toInventory(store.environmentQi()).toByteArray());
        var sources = new NbtList();
        for (EnvironmentQiSource source : store.environmentSources()) {
            sources.add(new net.minecraft.nbt.NbtByteArray(source.toByteArray()));
        }
        nbt.put("environment_sources", sources);
        return nbt;
    }
}
