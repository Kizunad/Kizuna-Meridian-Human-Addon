package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.environment.TerrainQiGenerator;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.ForgetReleaseResult;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.ReleaseResult;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.EnvironmentProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiConfig;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpace;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpaceKind;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiRegionRequest;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Minecraft 持久化容器只包装 Protobuf 字节，正文仍由框架负责定义和校验。 */
final class HumanWorldState extends PersistentState {
    private static final Logger LOG = LoggerFactory.getLogger(HumanWorldState.class);
    private static final long WINDOW_MILLIS = 30_000;
    private static final int CHUNK_WIDTH_BLOCKS = 16;
    private static final int UNSAVED_TICK = -1;
    // 未获发送端持久删除证明的请求不能按时间回收。
    private final Map<String, ReleaseRecord> releaseIds = new HashMap<>();
    private final Set<String> persistedReleaseIds = new HashSet<>();
    private final Set<String> unsavedForgetIds = new HashSet<>();
    private final DurableStateFile.DirectorySync directorySync;
    private MinecraftServer server;
    private int lastSaveTick = UNSAVED_TICK;
    private final List<PendingCredit> pendingCredits = new ArrayList<>();
    private final String epoch;
    private final Map<UUID, HumanPlayerBody> players = new HashMap<>();
    private final InMemoryBodyStore store;
    private final EnvironmentQiRules rules;
    private final TerrainQiGenerator generation;
    private final int regionSizeChunks;

    private record ReleaseRecord(RegionRelease request, long millis) { }
    private record PendingCredit(String sourceId, QiInventory qi, boolean fromDefault, String releaseId) { }

    HumanWorldState() {
        // 每个新世界获得稳定时钟世代，重启时读取保存的值。
        this(DurableStateFile.directorySync(System.getProperty("os.name")));
    }

    HumanWorldState(DurableStateFile.DirectorySync directorySync) {
        // 目录能力与文件同步分开处理，平台不支持目录通道时仍可完成持久化。
        this(UUID.randomUUID().toString(), new InMemoryBodyStore(List.of(), List.of(), QiInventory.empty()),
                directorySync);
    }

    private HumanWorldState(String epoch, InMemoryBodyStore store) {
        // 已有世界不能重抽时钟世代，否则原身体时间无法继续比较。
        this(epoch, store, DurableStateFile.directorySync(System.getProperty("os.name")));
    }

    private HumanWorldState(String epoch, InMemoryBodyStore store, DurableStateFile.DirectorySync directorySync) {
        // 世界内容配置与平台保存能力相互独立。
        this.epoch = epoch;
        this.store = store;
        this.directorySync = directorySync;
        var configPath = FabricLoader.getInstance().getConfigDir().resolve("meridian-environment.toml");
        EnvironmentQiConfig config;
        if (Files.exists(configPath)) {
            config = EnvironmentConfigLoader.load(configPath);
        } else {
            config = EnvironmentConfigLoader.defaults();
        }
        rules = new EnvironmentQiRules(config);
        generation = new TerrainQiGenerator(config.getGeneration());
        regionSizeChunks = config.getRegionSizeChunks();
    }

    private record SavedPlayers(List<BodySnapshot> bodies, List<CommandReceipt> receipts,
                                Map<UUID, Set<String>> breathingActions) { }

    static HumanWorldState read(NbtCompound nbt) {
        // 先校验各列表封装，再恢复身体、区域和交接事实；任一错误使整份存档拒绝加载。
        try {
            requireField(nbt, "epoch", NbtElement.STRING_TYPE);
            String epoch = nbt.getString("epoch");
            // 时钟世代必须是合法 UUID，损坏时 fromString 抛出校验异常。
            UUID.fromString(epoch);
            requireList(nbt, "players", NbtElement.COMPOUND_TYPE);
            requireField(nbt, "released_qi", NbtElement.BYTE_ARRAY_TYPE);
            requireList(nbt, "environment_sources", NbtElement.BYTE_ARRAY_TYPE);
            requireList(nbt, "release_ids", NbtElement.COMPOUND_TYPE);
            requireList(nbt, "pending_credits", NbtElement.COMPOUND_TYPE);

            SavedPlayers saved = readPlayers(nbt.getList("players", NbtElement.COMPOUND_TYPE), epoch);
            var released = BodyProtobuf.fromInventory(
                    BodyMessages.QiInventory.parseFrom(nbt.getByteArray("released_qi")));
            var sources = readSources(nbt.getList("environment_sources", NbtElement.BYTE_ARRAY_TYPE), epoch);
            var store = new InMemoryBodyStore(saved.bodies(), saved.receipts(), released, sources);
            var state = new HumanWorldState(epoch, store);
            state.readReleaseIds(nbt.getList("release_ids", NbtElement.COMPOUND_TYPE));
            state.readPendingCredits(nbt.getList("pending_credits", NbtElement.COMPOUND_TYPE));

            // 失败队列也须恢复，余额不一致留待执行时报告，不能让重启吞掉重试机会。
            for (BodySnapshot body : saved.bodies()) {
                UUID playerId = UUID.fromString(body.id().value());
                var restored = new HumanPlayerBody(body, store, saved.breathingActions().get(playerId));
                state.players.put(playerId, restored);
                if (restored.snapshot().revision() != body.revision()) {
                    state.markDirty();
                }
            }
            state.persistedReleaseIds.addAll(state.releaseIds.keySet());
            return state;
        } catch (Exception failure) {
            throw HumanErrors.invalidSave(failure);
        }
    }

    private static SavedPlayers readPlayers(NbtList entries, String epoch) throws IOException {
        // 每具身体只恢复自己的回执与呼吸动作，释放余额必须来自世界唯一账户。
        var bodies = new ArrayList<BodySnapshot>();
        var receipts = new ArrayList<CommandReceipt>();
        var breathingActions = new HashMap<UUID, Set<String>>();
        for (int index = 0; index < entries.size(); index++) {
            NbtCompound entry = entries.getCompound(index);
            requireField(entry, "body", NbtElement.BYTE_ARRAY_TYPE);
            requireField(entry, "released_qi", NbtElement.BYTE_ARRAY_TYPE);
            requireList(entry, "receipts", NbtElement.COMPOUND_TYPE);
            requireList(entry, "breathing_actions", NbtElement.STRING_TYPE);
            var body = BodyProtobuf.decodeSnapshot(entry.getByteArray("body"));
            UUID playerId = UUID.fromString(body.id().value());
            if (!body.settledAt().epoch().equals(epoch) || breathingActions.containsKey(playerId)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            var legacyReleased = BodyProtobuf.fromInventory(
                    BodyMessages.QiInventory.parseFrom(entry.getByteArray("released_qi")));
            if (!legacyReleased.entries().isEmpty()) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            bodies.add(body);
            receipts.addAll(readReceipts(entry.getList("receipts", NbtElement.COMPOUND_TYPE)));
            breathingActions.put(playerId, readBreathingActions(entry.getList("breathing_actions",
                    NbtElement.STRING_TYPE)));
        }
        return new SavedPlayers(bodies, receipts, breathingActions);
    }

    private static List<CommandReceipt> readReceipts(NbtList entries) {
        // 先检查载荷字段再解码回执，避免 NBT 空值掩盖损坏。
        var receipts = new ArrayList<CommandReceipt>();
        for (int index = 0; index < entries.size(); index++) {
            NbtCompound entry = entries.getCompound(index);
            requireField(entry, "value", NbtElement.BYTE_ARRAY_TYPE);
            receipts.add(MeridianProtocol.decodeReceipt(entry.getByteArray("value")));
        }
        return receipts;
    }

    private static Set<String> readBreathingActions(NbtList entries) {
        // 呼吸动作按实例唯一，重复或空身份都表示存档损坏。
        var instances = new HashSet<String>();
        for (int index = 0; index < entries.size(); index++) {
            String instance = entries.getString(index);
            if (instance.isBlank() || !instances.add(instance)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
        }
        return Set.copyOf(instances);
    }

    private static List<EnvironmentQiSource> readSources(NbtList entries, String epoch) throws IOException {
        // 区域必须属于当前世界的同一时钟世代，不能混入其他世界的窗口。
        var sources = new ArrayList<EnvironmentQiSource>();
        for (int index = 0; index < entries.size(); index++) {
            var bytes = ((net.minecraft.nbt.NbtByteArray) entries.get(index)).getByteArray();
            EnvironmentQiSource source = EnvironmentQiSource.parseFrom(bytes);
            EnvironmentProtobuf.validateSource(source);
            if (!source.getValidFrom().getEpoch().equals(epoch)
                    || !source.getValidUntil().getEpoch().equals(epoch)
                    || !source.getValidFrom().getDomain().equals("human:minecraft")
                    || !source.getValidUntil().getDomain().equals("human:minecraft")) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            sources.add(source);
        }
        return sources;
    }

    private void readReleaseIds(NbtList entries) throws IOException {
        // 完整请求与受理时间一起恢复；相同 ID 出现两次时拒绝账本。
        for (int index = 0; index < entries.size(); index++) {
            NbtCompound entry = entries.getCompound(index);
            requireField(entry, "request", NbtElement.BYTE_ARRAY_TYPE);
            requireField(entry, "millis", NbtElement.LONG_TYPE);
            var request = RegionRelease.parseFrom(entry.getByteArray("request"));
            validateRelease(request);
            long millis = entry.getLong("millis");
            if (millis < 0 || releaseIds.putIfAbsent(request.getReleaseId(),
                    new ReleaseRecord(request, millis)) != null) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
        }
    }

    private void readPendingCredits(NbtList entries) throws IOException {
        // 外部待入账必须与去重原请求一致，才能证明恢复后的同一数量不会重复入账。
        var queuedIds = new HashSet<String>();
        for (int index = 0; index < entries.size(); index++) {
            NbtCompound entry = entries.getCompound(index);
            requireField(entry, "source_id", NbtElement.STRING_TYPE);
            requireField(entry, "qi", NbtElement.BYTE_ARRAY_TYPE);
            requireField(entry, "kind", NbtElement.STRING_TYPE);
            requireField(entry, "release_id", NbtElement.STRING_TYPE);
            String sourceId = entry.getString("source_id");
            String kind = entry.getString("kind");
            String releaseId = entry.getString("release_id");
            var qi = BodyProtobuf.fromInventory(BodyMessages.QiInventory.parseFrom(entry.getByteArray("qi")));
            boolean fromDefault = kind.equals("default");
            if ((!fromDefault && !kind.equals("external")) || qi.entries().isEmpty()
                    || (!sourceId.isEmpty() && store.environmentSource(sourceId).isEmpty())
                    || (fromDefault && (sourceId.isEmpty() || !releaseId.isEmpty()))) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            if (!fromDefault) {
                var record = releaseIds.get(releaseId);
                if (record == null || !queuedIds.add(releaseId)
                        || !BodyProtobuf.fromInventory(record.request().getReleasedQi()).equals(qi)
                        || !releaseSourceId(record.request()).equals(sourceId)) {
                    throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                }
            }
            if (fromDefault) {
                queueDefaultRelease(sourceId, qi);
            } else {
                pendingCredits.add(new PendingCredit(sourceId, qi, false, releaseId));
            }
        }
    }

    private static void requireField(NbtCompound nbt, String key, int type) {
        // 缺字段或错类型直接停止加载，不让 NBT 的空值兜底吞掉损坏。
        if (!nbt.contains(key, type)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
        }
    }

    private static void requireList(NbtCompound nbt, String key, int elementType) {
        // 检查列表元素类型，避免 getList 在类型不符时静默返回空列表。
        requireField(nbt, key, NbtElement.LIST_TYPE);
        NbtList list = (NbtList) nbt.get(key);
        if (list.getHeldType() != NbtElement.END_TYPE && list.getHeldType() != elementType) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
        }
    }

    HumanPlayerBody join(ServerPlayerEntity player, long millis) {
        // 恢复会话本身也可能释放，必须先绑定按执行时位置选区的归位回调。
        HumanPlayerBody body = players.computeIfAbsent(player.getUuid(),
                id -> HumanPlayerBody.born(id, epoch, millis, store));
        bindReleaseSink(player);
        body.resume(millis);
        markDirty();
        return body;
    }

    void bindReleaseSink(ServerPlayerEntity player) {
        // 重生会替换实体；释放回调必须先于新实体的任何身体命令重新绑定。
        HumanPlayerBody body = players.get(player.getUuid());
        body.bindReleaseSink(qi -> {
            // 回调读取实时玩家位置，不保留登录时或旧窗口的区域。
            String sourceId = ensureRegion(player.getServerWorld(), player.getChunkPos(),
                    Math.multiplyExact(player.getServer().getOverworld().getTime(), 50L));
            if (sourceId != null) {
                queueDefaultRelease(sourceId, qi);
            }
            markDirty();
        });
    }

    private void queueDefaultRelease(String sourceId, QiInventory qi) {
        // 同一区域共用一笔归位数量，避免每条身体命令都增加存档项和扣款舍入次数。
        for (int index = 0; index < pendingCredits.size(); index++) {
            PendingCredit pending = pendingCredits.get(index);
            if (pending.fromDefault() && pending.sourceId().equals(sourceId)) {
                pendingCredits.set(index, new PendingCredit(sourceId,
                        QiInventory.combine(List.of(pending.qi(), qi)), true, ""));
                return;
            }
        }
        pendingCredits.add(new PendingCredit(sourceId, qi, true, ""));
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
        // 玩家和服务查询共用区域初始化，保持同一区域唯一库存。
        HumanPlayerBody body = players.get(player.getUuid());
        body.selectEnvironment(ensureRegion(player.getServerWorld(), player.getChunkPos(), millis), millis);
    }

    String ensureRegion(ServerWorld world, ChunkPos chunk, long millis) {
        // 仅从固定区域中心群系初始化一次，重访始终沿用权威库存。
        // 这里只校验受理时间，负值会抛出 INVALID_REQUEST。
        time(millis);
        String dimension = world.getRegistryKey().getValue().toString();
        if (!dimension.equals("minecraft:overworld")) {
            return null;
        }
        int regionX = rules.regionCoordinate(chunk.x);
        int regionZ = rules.regionCoordinate(chunk.z);
        String sourceId = "human:world_" + epoch + "/" + dimension.replace(':', '/') + "/" + regionX + "/" + regionZ;
        if (store.environmentSource(sourceId).isEmpty()) {
            // 相同区域使用固定中心的群系，生成后只读取保存库存，不会随进入位置重抽。
            BlockPos center = new BlockPos((regionX * regionSizeChunks * 16) + regionSizeChunks * 8,
                    world.getSeaLevel(), (regionZ * regionSizeChunks * 16) + regionSizeChunks * 8);
            String biome = world.getBiome(center).getKey().orElseThrow().getValue().toString();
            double volumeBlocks = rules.regionVolumeBlocks(world.getHeight());
            var request = TerrainQiRegionRequest.newBuilder().setProtocolVersion(1)
                    .setWorldSeed(Long.toString(world.getSeed())).setDimensionId(dimension)
                    .setSourceId(sourceId).setBiomeId(biome).setVolumeBlocks(volumeBlocks).build();
            var seed = generation.generateRegion(request);
            var space = EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId(sourceId)
                    .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(volumeBlocks).build();
            store.registerEnvironmentSource(EnvironmentAbsorption.initialize(space,
                    BodyProtobuf.fromInventory(seed.getInitialQi()), time(millis), time(Math.addExact(millis, WINDOW_MILLIS)), rules));
            markDirty();
        }
        return sourceId;
    }

    RegionView query(ServerWorld world, int blockX, int blockZ, long millis) {
        // 服务只读取区域库存；负坐标按区块整除规则定位，不初始化身体。
        String sourceId = ensureRegion(world, new ChunkPos(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)), millis);
        if (sourceId == null) {
            return null;
        }
        var source = store.environmentSource(sourceId).orElseThrow();
        double volume = source.getSpace().getVolumeBlocks();
        return RegionView.newBuilder().setSourceId(sourceId).setVolumeBlocks(volume)
                .setInventory(source.getInventory())
                .setConcentration(BodyProtobuf.fromInventory(source.getInventory()).totalQi() / volume)
                .setSourceRevision(source.getRevision()).build();
    }

    ReleaseResult release(RegionRelease request, ServerWorld world, long millis) {
        // 去重先比较完整原请求，再排队；失败不消耗 ID，也不直接改变本窗库存。
        // 校验受理时间；负值会抛出 INVALID_REQUEST。
        time(millis);
        validateRelease(request);
        String releaseId = request.getReleaseId();
        var position = request.getPosition();
        if (unsavedForgetIds.contains(releaseId)) {
            throw HumanErrors.hostRejected(FailureCode.REQUEST_ID_CONFLICT);
        }
        if (!position.getDimensionId().equals(world.getRegistryKey().getValue().toString())) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        ReleaseRecord previous = releaseIds.get(releaseId);
        if (previous != null && !previous.request().equals(request)) {
            throw HumanErrors.hostRejected(FailureCode.REQUEST_ID_CONFLICT);
        }
        int chunkX = Math.floorDiv(position.getBlockX(), CHUNK_WIDTH_BLOCKS);
        int chunkZ = Math.floorDiv(position.getBlockZ(), CHUNK_WIDTH_BLOCKS);
        String sourceId = ensureRegion(world, new ChunkPos(chunkX, chunkZ), millis);
        if (previous == null) {
            pendingCredits.add(new PendingCredit(sourceId == null ? "" : sourceId,
                    BodyProtobuf.fromInventory(request.getReleasedQi()), false, releaseId));
            releaseIds.put(releaseId, new ReleaseRecord(request, millis));
            markDirty();
        }
        var result = ReleaseResult.newBuilder()
                .setDuplicate(previous != null)
                .setPersisted(persistedReleaseIds.contains(releaseId));
        if (sourceId != null) {
            result.setSourceId(sourceId);
        }
        return result.build();
    }

    ForgetReleaseResult forgetRelease(String releaseId) {
        // 发送端仅能在持久删除原请求后调用；待入账事实仍需保留原请求以便恢复校验。
        if (releaseId.isBlank()) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        if (pendingCredits.stream().anyMatch(credit -> credit.releaseId().equals(releaseId))) {
            return ForgetReleaseResult.getDefaultInstance();
        }
        if (releaseIds.remove(releaseId) != null) {
            unsavedForgetIds.add(releaseId);
            markDirty();
        }
        return ForgetReleaseResult.newBuilder()
                .setPersisted(!unsavedForgetIds.contains(releaseId))
                .build();
    }

    void bind(MinecraftServer server) {
        // 普通世界保存和 tick 末交接共用同一个保存次数限制。
        this.server = server;
    }

    void flushHandoffs(Path file) {
        // 同 tick 的所有新受理和遗忘合并；重复查询已持久请求不会再触发保存。
        if (!persistedReleaseIds.containsAll(releaseIds.keySet()) || !unsavedForgetIds.isEmpty()) {
            save(file.toFile());
        }
    }

    @Override
    public void save(File file) {
        // 世界保存与交接使用相同的原子写入，后续保存失败不能截断已确认的文件。
        if (!isDirty()) {
            return;
        }
        if (server != null && !server.isStopping()) {
            int tick = server.getTicks();
            if (lastSaveTick == tick) {
                return;
            }
            lastSaveTick = tick;
        }
        try {
            saveDurably(file.toPath());
        } catch (IOException failure) {
            LOG.error(HumanErrors.SAVE_FAILED, failure);
        }
    }

    private void saveDurably(Path file) throws IOException {
        // ID、原请求、待入账事实及余额一起保存；任何失败都保留 dirty 供重试。
        markDirty();
        var root = new NbtCompound();
        root.put("data", writeNbt(new NbtCompound()));
        NbtHelper.putDataVersion(root);
        DurableStateFile.write(file, temporary -> NbtIo.writeCompressed(root, temporary.toFile()), directorySync);
        persistedReleaseIds.clear();
        persistedReleaseIds.addAll(releaseIds.keySet());
        unsavedForgetIds.clear();
        setDirty(false /* dirty */);
    }

    private static void validateRelease(RegionRelease request) {
        // 正数量和有限值交给框架库存解码校验，不限制第三方属性。
        if (request.getReleaseId().isBlank() || !request.hasPosition()
                || request.getPosition().getDimensionId().isBlank() || !request.hasReleasedQi()
                || BodyProtobuf.fromInventory(request.getReleasedQi()).entries().isEmpty()) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
    }

    private String releaseSourceId(RegionRelease request) {
        // 存档校验根据原位置推导目的地，不创建任何隐藏区域。
        String dimension = request.getPosition().getDimensionId();
        if (!dimension.equals("minecraft:overworld")) {
            return "";
        }
        int x = rules.regionCoordinate(Math.floorDiv(request.getPosition().getBlockX(), 16));
        int z = rules.regionCoordinate(Math.floorDiv(request.getPosition().getBlockZ(), 16));
        return "human:world_" + epoch + "/" + dimension.replace(':', '/') + "/" + x + "/" + z;
    }

    void renewEnvironment(long millis) {
        // 先入账到期区域的待处理明细，再基于更新后的版本和库存重算新窗预算。
        BodyMessages.TimeCursor validFrom = time(millis);
        Set<String> due = store.environmentSources().stream()
                .filter(source -> millis >= source.getValidUntil().getElapsedMillis())
                .map(source -> source.getSpace().getSourceId())
                .collect(java.util.stream.Collectors.toSet());
        creditPending(due, millis);
        for (String sourceId : due) {
            var source = store.environmentSource(sourceId).orElseThrow();
            BodyMessages.TimeCursor validUntil = time(Math.addExact(millis, WINDOW_MILLIS));
            store.renewEnvironmentWindow(sourceId, source.getRevision(), validFrom, validUntil, rules);
            markDirty();
        }
    }

    private void creditPending(Set<String> due, long millis) {
        // 只在对应供给窗到期后入账；失败项保留原请求，继续等待下一个边界。
        creditDefaultPending(due);
        var iterator = pendingCredits.iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            if (pending.fromDefault()) {
                continue;
            }
            String sourceId = pending.sourceId();
            QiInventory qi = pending.qi();
            boolean defaultAccount = sourceId.isEmpty();
            // 无区域账户也等到受理后的下一个边界；跳过精确边界的 tick 仍能补处理。
            boolean dueDefault = defaultAccount
                    && millis / WINDOW_MILLIS > releaseIds.get(pending.releaseId()).millis() / WINDOW_MILLIS;
            boolean dueSource = due.contains(sourceId);
            if (defaultAccount ? dueDefault : dueSource) {
                try {
                    if (defaultAccount) {
                        store.creditReleasedQi(qi);
                    } else {
                        store.creditEnvironmentSource(sourceId, qi);
                    }
                    iterator.remove();
                    markDirty();
                } catch (RuntimeException failure) {
                    // 失败项继续随世界保存并等待下一窗，不能阻断其他入账或吞掉余额不一致。
                    LOG.error(HumanErrors.ENVIRONMENT_CREDIT_FAILED + ": " + sourceId, failure);
                }
            }
        }
    }

    private void creditDefaultPending(Set<String> due) {
        // 所有区域共同划分唯一共享余额，不能等最后一笔失败后再单独猜测缺额。
        var claims = new LinkedHashMap<ReleasedQiAllocation.Kind, List<ReleasedQiAllocation.Claim>>();
        for (PendingCredit pending : pendingCredits) {
            if (pending.fromDefault()) {
                for (var qi : pending.qi().entries()) {
                    claims.computeIfAbsent(ReleasedQiAllocation.Kind.of(qi), ignored -> new ArrayList<>())
                            .add(new ReleasedQiAllocation.Claim(pending.sourceId(), qi));
                }
            }
        }
        for (var entries : claims.values()) {
            try {
                var plan = ReleasedQiAllocation.plan(store.environmentQi(), entries);
                var transfers = new LinkedHashMap<String, QiInventory>();
                var dueClaims = plan.stream().filter(claim -> due.contains(claim.sourceId())).toList();
                for (var claim : dueClaims) {
                    transfers.put(claim.sourceId(), new QiInventory(List.of(claim.qi())));
                }
                if (!transfers.isEmpty()) {
                    store.transferReleasedToSources(transfers);
                    dueClaims.forEach(this::removeDefaultCredit);
                    markDirty();
                }
            } catch (RuntimeException failure) {
                // 真正缺账保留对应相态属性的全部意图，其他种类与外部交接仍可入账。
                LOG.error(HumanErrors.ENVIRONMENT_CREDIT_FAILED, failure);
            }
        }
    }

    private void removeDefaultCredit(ReleasedQiAllocation.Claim claim) {
        // 只有整个批次提交成功才删除对应种类，其他属性和未到期区域继续保留。
        var kind = ReleasedQiAllocation.Kind.of(claim.qi());
        for (int index = 0; index < pendingCredits.size(); index++) {
            var pending = pendingCredits.get(index);
            if (pending.fromDefault() && pending.sourceId().equals(claim.sourceId())) {
                var remaining = pending.qi().entries().stream()
                        .filter(entry -> !ReleasedQiAllocation.Kind.of(entry).equals(kind)).toList();
                if (remaining.isEmpty()) {
                    pendingCredits.remove(index);
                } else {
                    pendingCredits.set(index, new PendingCredit(pending.sourceId(),
                            new QiInventory(remaining), pending.fromDefault(), pending.releaseId()));
                }
                return;
            }
        }
    }

    BodyMessages.TimeCursor time(long millis) {
        // 所有区域与身体共用世界模拟时钟，暂停时不会产生新的可摄入区间。
        if (millis < 0) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        return BodyMessages.TimeCursor.newBuilder().setDomain("human:minecraft").setEpoch(epoch).setElapsedMillis(millis).build();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        // 身体、余额及交接事实提交到同一文件，不能分别保存后确认。
        nbt.putString("epoch", epoch);
        nbt.put("players", savedPlayers());
        nbt.putByteArray("released_qi", BodyProtobuf.toInventory(store.environmentQi()).toByteArray());
        nbt.put("environment_sources", savedSources());
        nbt.put("release_ids", savedReleaseIds());
        nbt.put("pending_credits", savedPendingCredits());
        return nbt;
    }

    private NbtList savedPlayers() {
        // 每个玩家快照携带自己的动作与回执，默认释放账户仅保存在世界级字段。
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
        return entries;
    }

    private NbtList savedSources() {
        // 区域库存与版本使用原始 Protobuf 字节保存，重启不重抽库存。
        var sources = new NbtList();
        for (EnvironmentQiSource source : store.environmentSources()) {
            sources.add(new net.minecraft.nbt.NbtByteArray(source.toByteArray()));
        }
        return sources;
    }

    private NbtList savedReleaseIds() {
        // 保留未完成遗忘的完整请求，供冲突检查与待入账恢复校验。
        var ids = new NbtList();
        for (ReleaseRecord record : releaseIds.values()) {
            var entry = new NbtCompound();
            entry.putByteArray("request", record.request().toByteArray());
            entry.putLong("millis", record.millis());
            ids.add(entry);
        }
        return ids;
    }

    private NbtList savedPendingCredits() {
        // 待入账数量与其去重 ID 同时持久化，玩家默认释放仍按区域合并。
        var pending = new NbtList();
        for (PendingCredit credit : pendingCredits) {
            var entry = new NbtCompound();
            entry.putString("source_id", credit.sourceId());
            entry.putByteArray("qi", BodyProtobuf.toInventory(credit.qi()).toByteArray());
            entry.putString("kind", credit.fromDefault() ? "default" : "external");
            entry.putString("release_id", credit.releaseId());
            pending.add(entry);
        }
        return pending;
    }
}
