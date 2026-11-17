package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.environment.TerrainQiGenerator;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.DrawResult;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.ForgetReleaseResult;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionDraw;
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
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minecraft 持久化容器只包装 Protobuf 字节，正文仍由框架负责定义和校验。
 *
 * <p>存档文件由宿主自己读写：原版存档数据改为异步原位写入，不能保证已确认的交接事实不被截断。
 */
final class HumanWorldState {
    private static final Logger LOG = LoggerFactory.getLogger(HumanWorldState.class);
    private static final long WINDOW_MILLIS = 30_000;
    private static final int CHUNK_WIDTH_BLOCKS = 16;
    private static final int UNSAVED_TICK = -1;
    /** 装置每次取用记成从受理时刻起的一秒区间。 */
    private static final long DRAW_INTERVAL_MILLIS = 1_000;
    /** 取用去重保留两个供给窗口；装置重试必须在这之内。 */
    private static final long DRAW_RETENTION_MILLIS = 2 * WINDOW_MILLIS;
    /**
     * 只有取用要存时，距上次保存至少隔这么多 tick（5 秒）才写盘。装置每秒都取，整份保存不能跟着每秒一次；
     * 崩服最多丢这段时间里的取用，它们和改动的区域库存、待放回同在一个文件，一起丢，账不会乱。
     */
    static final int DRAW_SAVE_INTERVAL_TICKS = 100;
    /** 按比例分摊会有舍入，取用速率比窗口上限少留这一比例，避免越过上限。 */
    private static final double DRAW_RATE_MARGIN = 1e-9;
    // 未获发送端持久删除证明的请求不能按时间回收。
    private final Map<String, ReleaseRecord> releaseIds = new HashMap<>();
    private final Set<String> persistedReleaseIds = new HashSet<>();
    private final Set<String> unsavedForgetIds = new HashSet<>();
    private final Map<String, DrawRecord> drawIds = new LinkedHashMap<>();
    private final Set<String> persistedDrawIds = new HashSet<>();
    private final DurableStateFile.DirectorySync directorySync;
    private MinecraftServer server;
    private int lastSaveTick = UNSAVED_TICK;
    /** setDirty 的次数；写完的那份快照截于第几次之后，记在 savedChanges。 */
    private long changes;
    private long savedChanges;
    private final Queue<Written> written = new ConcurrentLinkedQueue<>();
    private CompletableFuture<Void> writing = CompletableFuture.completedFuture(null);
    /** 写盘线程编码时抛出的程序错误，留到 tick 末交接或等待保存时抛出。 */
    private RuntimeException encodeFailure;
    private final List<PendingCredit> pendingCredits = new ArrayList<>();
    private final Map<ReleasedQiAllocation.Kind, String> reportedCreditFailures = new HashMap<>();
    private final String epoch;
    private final Map<UUID, HumanPlayerBody> players = new HashMap<>();
    private final InMemoryBodyStore store;
    private final EnvironmentQiRules rules;
    private final TerrainQiGenerator generation;
    private final int regionSizeChunks;

    private record ReleaseRecord(RegionRelease request, long millis) { }
    private record PendingCredit(String sourceId, QiInventory qi, boolean fromDefault, String releaseId) { }
    /** 结果只存来源和实际取到的明细；重复与持久化标记按查询时的状态另填。 */
    private record DrawRecord(RegionDraw request, DrawResult result, long millis) { }
    /** 存盘那一刻的一名玩家：快照、吐纳占用和回执都是不可变值。 */
    private record SavedPlayer(BodySnapshot body, Set<String> breathingActions, List<CommandReceipt> receipts) { }
    /** 存盘那一刻的交接事实。 */
    private record SavedHandoffs(List<ReleaseRecord> releases, List<PendingCredit> credits, List<DrawRecord> draws) { }
    /** 存盘那一刻的整个世界状态，写盘线程拿它编码，主线程照常往下改。 */
    private record SavedState(String epoch, List<SavedPlayer> players, QiInventory releasedQi,
                              List<EnvironmentQiSource> sources, SavedHandoffs handoffs) { }
    /** 一份快照写进文件后能确认的东西：截于第几次变化、哪些受理、遗忘和取用记录。 */
    private record Covered(long changes, Set<String> releaseIds, Set<String> forgetIds,
                           Map<String, DrawRecord> draws) { }
    /** 写盘线程交回主线程的结果；failure 为空表示已原子替换进正式文件。 */
    private record Written(Covered covered, Exception failure) { }

    /** 编码、压缩、fsync 和改名都在这条线程上做；单线程，按提交顺序一份一份写。 */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "meridian-human-save");
        thread.setDaemon(true);
        return thread;
    });

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

    static HumanWorldState load(Path file) throws IOException {
        // 文件外层是带数据版本的压缩 NBT，正文在 data 字段；缺正文时按空正文校验并拒绝加载。
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        return read(root.getCompoundOrEmpty("data"));
    }

    static HumanWorldState read(CompoundTag nbt) {
        // 先校验各列表封装，再恢复身体、区域和交接事实；任一错误使整份存档拒绝加载。
        try {
            requireField(nbt, "epoch", Tag.TAG_STRING);
            String epoch = nbt.getString("epoch").orElseThrow();
            // 时钟世代必须是合法 UUID，损坏时 fromString 抛出校验异常。
            UUID.fromString(epoch);
            requireList(nbt, "players", Tag.TAG_COMPOUND);
            requireField(nbt, "released_qi", Tag.TAG_BYTE_ARRAY);
            requireList(nbt, "environment_sources", Tag.TAG_BYTE_ARRAY);
            requireList(nbt, "release_ids", Tag.TAG_COMPOUND);
            requireList(nbt, "pending_credits", Tag.TAG_COMPOUND);

            SavedPlayers saved = readPlayers(nbt.getList("players").orElseThrow(), epoch);
            var released = BodyProtobuf.fromInventory(
                    BodyMessages.QiInventory.parseFrom(nbt.getByteArray("released_qi").orElseThrow()));
            var sources = readSources(nbt.getList("environment_sources").orElseThrow(), epoch);
            var store = new InMemoryBodyStore(saved.bodies(), saved.receipts(), released, sources);
            var state = new HumanWorldState(epoch, store);
            // 旧存档的回执比 Store 保留的多，读入时已裁短；标记待保存，下次保存就把文件写小。
            if (store.receipts().size() != saved.receipts().size()) {
                state.setDirty();
            }
            state.readReleaseIds(nbt.getList("release_ids").orElseThrow());
            state.readPendingCredits(nbt.getList("pending_credits").orElseThrow());
            // 装置取用是后加的字段，旧存档没有就按空表读入；有就严格校验。
            if (nbt.contains("draw_ids")) {
                requireList(nbt, "draw_ids", Tag.TAG_COMPOUND);
                state.readDrawIds(nbt.getList("draw_ids").orElseThrow());
            }

            // 失败队列也须恢复，余额不一致留待执行时报告，不能让重启吞掉重试机会。
            for (BodySnapshot body : saved.bodies()) {
                UUID playerId = UUID.fromString(body.id().value());
                var restored = new HumanPlayerBody(body, store, saved.breathingActions().get(playerId));
                state.players.put(playerId, restored);
                if (restored.snapshot().revision() != body.revision()) {
                    state.setDirty();
                }
            }
            state.persistedReleaseIds.addAll(state.releaseIds.keySet());
            state.persistedDrawIds.addAll(state.drawIds.keySet());
            return state;
        } catch (Exception failure) {
            throw HumanErrors.invalidSave(failure);
        }
    }

    private static SavedPlayers readPlayers(ListTag entries, String epoch) throws IOException {
        // 每具身体只恢复自己的回执与呼吸动作，释放余额必须来自世界唯一账户。
        var bodies = new ArrayList<BodySnapshot>();
        var receipts = new ArrayList<CommandReceipt>();
        var breathingActions = new HashMap<UUID, Set<String>>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index).orElseThrow();
            requireField(entry, "body", Tag.TAG_BYTE_ARRAY);
            requireField(entry, "released_qi", Tag.TAG_BYTE_ARRAY);
            requireList(entry, "receipts", Tag.TAG_COMPOUND);
            requireList(entry, "breathing_actions", Tag.TAG_STRING);
            var body = BodyProtobuf.decodeSnapshot(entry.getByteArray("body").orElseThrow());
            UUID playerId = UUID.fromString(body.id().value());
            if (!body.settledAt().epoch().equals(epoch) || breathingActions.containsKey(playerId)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            var legacyReleased = BodyProtobuf.fromInventory(
                    BodyMessages.QiInventory.parseFrom(entry.getByteArray("released_qi").orElseThrow()));
            if (!legacyReleased.entries().isEmpty()) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
            bodies.add(body);
            receipts.addAll(readReceipts(entry.getList("receipts").orElseThrow()));
            breathingActions.put(playerId, readBreathingActions(entry.getList("breathing_actions").orElseThrow()));
        }
        return new SavedPlayers(bodies, receipts, breathingActions);
    }

    private static List<CommandReceipt> readReceipts(ListTag entries) {
        // 先检查载荷字段再解码回执，避免 NBT 空值掩盖损坏。
        var receipts = new ArrayList<CommandReceipt>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index).orElseThrow();
            requireField(entry, "value", Tag.TAG_BYTE_ARRAY);
            receipts.add(MeridianProtocol.decodeReceipt(entry.getByteArray("value").orElseThrow()));
        }
        return receipts;
    }

    private static Set<String> readBreathingActions(ListTag entries) {
        // 呼吸动作按实例唯一，重复或空身份都表示存档损坏。
        var instances = new HashSet<String>();
        for (int index = 0; index < entries.size(); index++) {
            String instance = entries.getString(index).orElseThrow();
            if (instance.isBlank() || !instances.add(instance)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
        }
        return Set.copyOf(instances);
    }

    private static List<EnvironmentQiSource> readSources(ListTag entries, String epoch) throws IOException {
        // 区域必须属于当前世界的同一时钟世代，不能混入其他世界的窗口。
        var sources = new ArrayList<EnvironmentQiSource>();
        for (int index = 0; index < entries.size(); index++) {
            var bytes = ((net.minecraft.nbt.ByteArrayTag) entries.get(index)).getAsByteArray();
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

    private void readReleaseIds(ListTag entries) throws IOException {
        // 完整请求与受理时间一起恢复；相同 ID 出现两次时拒绝账本。
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index).orElseThrow();
            requireField(entry, "request", Tag.TAG_BYTE_ARRAY);
            requireField(entry, "millis", Tag.TAG_LONG);
            var request = RegionRelease.parseFrom(entry.getByteArray("request").orElseThrow());
            validateRelease(request);
            long millis = entry.getLong("millis").orElseThrow();
            if (millis < 0 || releaseIds.putIfAbsent(request.getReleaseId(),
                    new ReleaseRecord(request, millis)) != null) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
        }
    }

    private void readDrawIds(ListTag entries) throws IOException {
        // 原请求、首次结果和受理时间一起恢复；重复 ID 或结果来源对不上原位置都说明存档损坏。
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index).orElseThrow();
            requireField(entry, "request", Tag.TAG_BYTE_ARRAY);
            requireField(entry, "result", Tag.TAG_BYTE_ARRAY);
            requireField(entry, "millis", Tag.TAG_LONG);
            var request = RegionDraw.parseFrom(entry.getByteArray("request").orElseThrow());
            validateDraw(request);
            var result = DrawResult.parseFrom(entry.getByteArray("result").orElseThrow());
            BodyProtobuf.fromInventory(result.getDrawnQi());
            long millis = entry.getLong("millis").orElseThrow();
            String expectedSource = drawSourceId(request);
            boolean sourceMatches = result.hasSourceId()
                    ? result.getSourceId().equals(expectedSource)
                    : expectedSource.isEmpty();
            if (millis < 0 || !sourceMatches
                    || drawIds.putIfAbsent(request.getDrawId(), new DrawRecord(request, result, millis)) != null) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
        }
    }

    private void readPendingCredits(ListTag entries) throws IOException {
        // 外部待入账必须与去重原请求一致，才能证明恢复后的同一数量不会重复入账。
        var queuedIds = new HashSet<String>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index).orElseThrow();
            requireField(entry, "source_id", Tag.TAG_STRING);
            requireField(entry, "qi", Tag.TAG_BYTE_ARRAY);
            requireField(entry, "kind", Tag.TAG_STRING);
            requireField(entry, "release_id", Tag.TAG_STRING);
            String sourceId = entry.getString("source_id").orElseThrow();
            String kind = entry.getString("kind").orElseThrow();
            String releaseId = entry.getString("release_id").orElseThrow();
            var qi = BodyProtobuf.fromInventory(BodyMessages.QiInventory.parseFrom(
                    entry.getByteArray("qi").orElseThrow()));
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

    private static void requireField(CompoundTag nbt, String key, int type) {
        // 缺字段或错类型直接停止加载，不让 NBT 的空值兜底吞掉损坏。
        Tag value = nbt.get(key);
        if (value == null || value.getId() != type) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
        }
    }

    private static void requireList(CompoundTag nbt, String key, int elementType) {
        // 列表可以混放不同类型，逐个检查元素，避免按类型取值时静默得到空值。
        requireField(nbt, key, Tag.TAG_LIST);
        ListTag list = (ListTag) nbt.get(key);
        for (Tag element : list) {
            if (element.getId() != elementType) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
            }
        }
    }

    HumanPlayerBody join(ServerPlayer player, long millis) {
        // 恢复会话本身也可能释放，必须先绑定按执行时位置选区的归位回调。
        HumanPlayerBody body = players.computeIfAbsent(player.getUUID(),
                id -> HumanPlayerBody.born(id, epoch, millis, store));
        bindReleaseSink(player);
        body.resume(millis);
        setDirty();
        return body;
    }

    void bindReleaseSink(ServerPlayer player) {
        // 重生会替换实体；释放回调和行走负荷都必须先于新实体的任何身体命令重新绑定。
        HumanPlayerBody body = players.get(player.getUUID());
        body.bindMovementLoad(() -> HumanMovementLoads.multiplier(player));
        body.bindReleaseSink(qi -> {
            // 回调读取实时玩家位置，不保留登录时或旧窗口的区域。
            String sourceId = ensureRegion(player.level(), player.chunkPosition(),
                    Math.multiplyExact(player.level().getServer().overworld().getGameTime(), 50L));
            if (sourceId != null) {
                queueDefaultRelease(sourceId, qi);
            }
            setDirty();
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
        setDirty();
        return body;
    }

    HumanPlayerBody find(UUID playerId) {
        // 查询不创建、不恢复、不初始化玩家资源。
        return players.get(playerId);
    }

    void selectEnvironment(ServerPlayer player, long millis) {
        // 玩家和服务查询共用区域初始化，保持同一区域唯一库存。
        HumanPlayerBody body = players.get(player.getUUID());
        body.selectEnvironment(ensureRegion(player.level(), player.chunkPosition(), millis), millis);
    }

    String ensureRegion(ServerLevel world, ChunkPos chunk, long millis) {
        // 仅从固定区域中心群系初始化一次，重访始终沿用权威库存。
        // 这里只校验受理时间，负值会抛出 INVALID_REQUEST。
        time(millis);
        String dimension = world.dimension().identifier().toString();
        if (!generation.supports(dimension)) {
            return null;
        }
        int regionX = rules.regionCoordinate(chunk.x());
        int regionZ = rules.regionCoordinate(chunk.z());
        String sourceId = "human:world_" + epoch + "/" + dimension.replace(':', '/') + "/" + regionX + "/" + regionZ;
        if (store.environmentSource(sourceId).isEmpty()) {
            // 相同区域使用固定中心的群系，生成后只读取保存库存，不会随进入位置重抽。
            BlockPos center = new BlockPos((regionX * regionSizeChunks * 16) + regionSizeChunks * 8,
                    world.getSeaLevel(), (regionZ * regionSizeChunks * 16) + regionSizeChunks * 8);
            String biome = world.getBiome(center).unwrapKey().orElseThrow().identifier().toString();
            double volumeBlocks = rules.regionVolumeBlocks(world.getHeight());
            var request = TerrainQiRegionRequest.newBuilder().setProtocolVersion(1)
                    .setWorldSeed(Long.toString(world.getSeed())).setDimensionId(dimension)
                    .setSourceId(sourceId).setBiomeId(biome).setVolumeBlocks(volumeBlocks).build();
            var seed = generation.generateRegion(request);
            var space = EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId(sourceId)
                    .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(volumeBlocks).build();
            store.registerEnvironmentSource(EnvironmentAbsorption.initialize(space,
                    BodyProtobuf.fromInventory(seed.getInitialQi()), time(millis), time(Math.addExact(millis, WINDOW_MILLIS)), rules));
            setDirty();
        }
        return sourceId;
    }

    RegionView query(ServerLevel world, int blockX, int blockZ, long millis) {
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
                .setSourceRevision(source.getRevision())
                .setSpareAbsorptionPerSecond(drawableRate(source, millis)).build();
    }

    ReleaseResult release(RegionRelease request, ServerLevel world, long millis) {
        // 去重先比较完整原请求，再排队；失败不消耗 ID，也不直接改变本窗库存。
        // 校验受理时间；负值会抛出 INVALID_REQUEST。先收回写完的存盘，persisted 才是最新的。
        time(millis);
        collectWrites();
        validateRelease(request);
        String releaseId = request.getReleaseId();
        var position = request.getPosition();
        if (unsavedForgetIds.contains(releaseId)) {
            throw HumanErrors.hostRejected(FailureCode.REQUEST_ID_CONFLICT);
        }
        if (!position.getDimensionId().equals(world.dimension().identifier().toString())) {
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
            setDirty();
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
        collectWrites();
        if (pendingCredits.stream().anyMatch(credit -> credit.releaseId().equals(releaseId))) {
            return ForgetReleaseResult.getDefaultInstance();
        }
        if (releaseIds.remove(releaseId) != null) {
            unsavedForgetIds.add(releaseId);
            setDirty();
        }
        return ForgetReleaseResult.newBuilder()
                .setPersisted(!unsavedForgetIds.contains(releaseId))
                .build();
    }

    DrawResult draw(RegionDraw request, ServerLevel world, long millis) {
        // 去重先比完整原请求；首次受理才真正取，取到的量当场进默认账户并排队在下个窗口放回原区域。
        time(millis);
        validateDraw(request);
        collectWrites();
        String drawId = request.getDrawId();
        var position = request.getPosition();
        if (!position.getDimensionId().equals(world.dimension().identifier().toString())) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        DrawRecord previous = drawIds.get(drawId);
        if (previous != null) {
            if (!previous.request().equals(request)) {
                throw HumanErrors.hostRejected(FailureCode.REQUEST_ID_CONFLICT);
            }
            return previous.result().toBuilder().setDuplicate(true)
                    .setPersisted(persistedDrawIds.contains(drawId)).build();
        }

        // 主世界外没有区域，登记一笔空结果，重试同样拿到零。
        int chunkX = Math.floorDiv(position.getBlockX(), CHUNK_WIDTH_BLOCKS);
        int chunkZ = Math.floorDiv(position.getBlockZ(), CHUNK_WIDTH_BLOCKS);
        String sourceId = ensureRegion(world, new ChunkPos(chunkX, chunkZ), millis);
        var result = DrawResult.newBuilder().setDrawnQi(BodyProtobuf.toInventory(QiInventory.empty()));
        if (sourceId != null) {
            QiInventory drawn = drawFromRegion(sourceId, request.getAmount(), millis);
            result.setSourceId(sourceId).setDrawnQi(BodyProtobuf.toInventory(drawn));
        }

        // 首次结果随取用记录保存，攒满保存间隔写盘后重发才确认持久化。
        DrawResult accepted = result.build();
        drawIds.put(drawId, new DrawRecord(request, accepted, millis));
        setDirty();
        return accepted;
    }

    private QiInventory drawFromRegion(String sourceId, double wantedQi, long millis) {
        // 先结清到期窗口，保证区间落在当前窗口内；窗口剩不到一秒就截到窗尾。
        renewEnvironment(millis);
        EnvironmentQiSource source = store.environmentSource(sourceId).orElseThrow();
        long untilMillis = drawUntil(source, millis);
        BodyMessages.TimeCursor from = time(millis);
        BodyMessages.TimeCursor until = time(untilMillis);

        // 能取的量受三者限制：想要的、这一秒剩下的共享速率、区域实际库存。
        double intervalSeconds = (untilMillis - millis) / 1000.0;
        double rateBudgetQi = drawableRate(source, millis) * intervalSeconds;
        QiInventory stock = BodyProtobuf.fromInventory(source.getInventory());
        double drawableQi = Math.min(wantedQi, Math.min(rateBudgetQi, stock.totalQi()));
        QiInventory drawn = proportionalShare(stock, drawableQi);
        if (drawn.entries().isEmpty()) {
            return drawn;
        }

        // Framework 扣区域、占速率、把取出量放进默认账户；这里登记下个窗口放回同一区域。
        store.drawEnvironmentSource(sourceId, source.getRevision(), from, until, drawn);
        queueDefaultRelease(sourceId, drawn);
        return drawn;
    }

    private static long drawUntil(EnvironmentQiSource source, long millis) {
        // 取用区间从受理时刻起一秒，窗口剩不到一秒就截到窗尾。
        return Math.min(Math.addExact(millis, DRAW_INTERVAL_MILLIS), source.getValidUntil().getElapsedMillis());
    }

    private double drawableRate(EnvironmentQiSource source, long millis) {
        // 这一秒装置还能取的速率：共享速率剩下的部分，再按窗口上限的固定比例留一点余量，
        // 占满后再取得到的是零，而不是一点舍入尘埃。查询和取用共用这一条，起熬预估和实际取到的对得上。
        // 窗口已到期、还没续上时，续窗会清空占用，按当前上限算。
        double marginRate = source.getMaxAbsorptionPerSecond() * DRAW_RATE_MARGIN;
        long untilMillis = drawUntil(source, millis);
        if (untilMillis <= millis) {
            return transferableRate(source.getMaxAbsorptionPerSecond() - marginRate);
        }
        double spareRate = EnvironmentAbsorption.spareRate(source, time(millis), time(untilMillis));
        return transferableRate(spareRate - marginRate);
    }

    private static double transferableRate(double rate) {
        // 占满后余量再减一次留量，会剩下正的舍入尘埃；低于最小可流转量的速率一秒也取不出任何一份，按零报，
        // 和 proportionalShare 实际取到的一致。
        return rate < QiAccounting.MIN_TRANSFER_QI ? 0 : rate;
    }

    private static QiInventory proportionalShare(QiInventory stock, double wantedQi) {
        // 任何属性都能用：按库存里各属性所占比例分摊，不先把某一种取空。
        double stockQi = stock.totalQi();
        if (wantedQi <= 0 || stockQi <= 0) {
            return QiInventory.empty();
        }
        double fraction = Math.min(1, wantedQi / stockQi);
        var shares = new ArrayList<Qi>();
        for (Qi entry : stock.entries()) {
            double shareQi = entry.amount() * fraction;
            // 低于最小可流转量、或大余额减不动的那份留在区域里，不让 Framework 整笔拒绝。
            if (shareQi < QiAccounting.MIN_TRANSFER_QI || entry.amount() - shareQi == entry.amount()) {
                continue;
            }
            shares.add(entry.withAmount(shareQi));
        }
        return new QiInventory(shares);
    }

    void bind(MinecraftServer server) {
        // 普通世界保存和 tick 末交接共用同一个保存次数限制。
        this.server = server;
    }

    void flushHandoffs(Path file) {
        // 同 tick 的所有新受理和遗忘合并；重复查询已持久请求不会再触发保存。
        // 释放和遗忘要在两 tick 内确认，当 tick 就写；取用攒到间隔满了再写，期间任何一次保存都会顺带写进去。
        // 本 tick 早些时候的世界保存还在写盘线程上时先不写：下一 tick 开始收回它以后，再看还缺什么。
        throwEncodeFailure();
        if (!writing.isDone()) {
            return;
        }
        collectWrites();
        throwEncodeFailure();
        if (!persistedReleaseIds.containsAll(releaseIds.keySet()) || !unsavedForgetIds.isEmpty() || drawsDue()) {
            startWrite(file);
        }
    }

    private boolean drawsDue() {
        // 没有未写的取用就不用存；没绑服务端时（单元测试）没有 tick 可数，照旧立即写。
        if (persistedDrawIds.containsAll(drawIds.keySet())) {
            return false;
        }
        return server == null || server.getTickCount() - lastSaveTick >= DRAW_SAVE_INTERVAL_TICKS;
    }

    void setDirty() {
        // 变化只做标记，写盘集中在世界保存与 tick 末交接。
        changes++;
    }

    boolean isDirty() {
        // 最新的变化还没写进文件：在写盘线程上没写完、写失败都算，下次保存继续重试。
        return changes != savedChanges;
    }

    void save(File file) {
        // 停服和 flush 保存：写盘线程把这份和之前排着的都写完再返回，后续保存失败不能截断已确认的文件。
        startWrite(file.toPath());
        awaitSaves();
    }

    void saveInBackground(File file) {
        // 普通世界保存：主线程只截快照，编码、压缩、fsync 在写盘线程做，写完下次收回时确认。
        startWrite(file.toPath());
    }

    void awaitSaves() {
        // 等写盘线程把已提交的写完，再在主线程收回结果；每 tick 开始、停服、flush 保存和测试用。
        writing.join();
        collectWrites();
        throwEncodeFailure();
    }

    private void startWrite(Path file) {
        // 世界保存与交接共用同 tick 一次的机会，失败也算用掉；ID、原请求、待入账事实及余额截进同一份快照。
        // 停服时运行循环已经退出、tick 不再走，不受这个限制：最后一 tick 刚写过，也要把下线结算写进去。
        if (!isDirty()) {
            return;
        }
        if (server != null && server.isRunning()) {
            int tick = server.getTickCount();
            if (lastSaveTick == tick) {
                return;
            }
            lastSaveTick = tick;
        }
        SavedState saved = capture();
        var covered = new Covered(changes, Set.copyOf(releaseIds.keySet()), Set.copyOf(unsavedForgetIds),
                Map.copyOf(drawIds));
        writing = CompletableFuture.runAsync(() -> written.add(write(file, saved, covered)), WRITER);
    }

    private Written write(Path file, SavedState saved, Covered covered) {
        // 写盘线程上编码、压缩并原子替换正式文件；任何失败都带回主线程，旧文件保持原样。
        try {
            var root = new CompoundTag();
            root.put("data", encode(saved, new CompoundTag()));
            NbtUtils.addCurrentDataVersion(root);
            DurableStateFile.write(file, temporary -> NbtIo.writeCompressed(root, temporary), directorySync);
            return new Written(covered, null);
        } catch (IOException | RuntimeException failure) {
            return new Written(covered, failure);
        }
    }

    private void collectWrites() {
        // 主线程按写完的顺序收回结果：写盘失败记日志、保留 dirty；编码出错是程序错误，先记下。
        for (Written result = written.poll(); result != null; result = written.poll()) {
            if (result.failure() instanceof RuntimeException failure) {
                encodeFailure = failure;
            } else if (result.failure() != null) {
                LOG.error(HumanErrors.SAVE_FAILED, result.failure());
            } else {
                confirm(result.covered());
            }
        }
    }

    private void throwEncodeFailure() {
        // 和同步写盘时一样从 tick 末交接或保存处抛出，不能被释放、取用把错误转成失败回执吞掉。
        if (encodeFailure != null) {
            throw new IllegalStateException(HumanErrors.SAVE_FAILED, encodeFailure);
        }
    }

    private void confirm(Covered covered) {
        // 文件里就是这份快照：持久确认按快照里的 ID 重算，快照之后的受理、遗忘和取用仍等下一份。
        savedChanges = covered.changes();
        persistedReleaseIds.clear();
        persistedReleaseIds.addAll(covered.releaseIds());
        unsavedForgetIds.removeAll(covered.forgetIds());
        // 取用 ID 过期后可以重新受理：文件里那条和现在这条是同一笔取用，才算已写盘。
        persistedDrawIds.clear();
        covered.draws().forEach((drawId, record) -> {
            if (record.equals(drawIds.get(drawId))) {
                persistedDrawIds.add(drawId);
            }
        });
    }

    private static void validateRelease(RegionRelease request) {
        // 正数量和有限值交给框架库存解码校验，不限制第三方属性。
        if (request.getReleaseId().isBlank() || !request.hasPosition()
                || request.getPosition().getDimensionId().isBlank() || !request.hasReleasedQi()
                || BodyProtobuf.fromInventory(request.getReleasedQi()).entries().isEmpty()) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
    }

    private static void validateDraw(RegionDraw request) {
        // 数量必须是正有限数；位置格式与释放相同，维度是否存在由入口检查。
        if (request.getDrawId().isBlank() || !request.hasPosition()
                || request.getPosition().getDimensionId().isBlank()
                || !Double.isFinite(request.getAmount()) || request.getAmount() <= 0) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
    }

    private String drawSourceId(RegionDraw request) {
        // 存档校验用：取用结果的来源必须就是原位置所在区域。
        String dimension = request.getPosition().getDimensionId();
        if (!generation.supports(dimension)) {
            return "";
        }
        int x = rules.regionCoordinate(Math.floorDiv(request.getPosition().getBlockX(), CHUNK_WIDTH_BLOCKS));
        int z = rules.regionCoordinate(Math.floorDiv(request.getPosition().getBlockZ(), CHUNK_WIDTH_BLOCKS));
        return "human:world_" + epoch + "/" + dimension.replace(':', '/') + "/" + x + "/" + z;
    }

    private String releaseSourceId(RegionRelease request) {
        // 存档校验根据原位置推导目的地，不创建任何隐藏区域。
        String dimension = request.getPosition().getDimensionId();
        if (!generation.supports(dimension)) {
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
            setDirty();
        }
        // 取用去重只保留两个窗口；取到的量另在放回队列里，清理记录不影响放回。
        boolean expired = drawIds.values().removeIf(record -> millis - record.millis() >= DRAW_RETENTION_MILLIS);
        if (expired) {
            persistedDrawIds.retainAll(drawIds.keySet());
            setDirty();
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
                    setDirty();
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
        for (var entry : claims.entrySet()) {
            var entries = entry.getValue();
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
                    setDirty();
                }
                reportedCreditFailures.remove(entry.getKey());
            } catch (RuntimeException failure) {
                // 真正缺账保留对应相态属性的全部意图，其他种类与外部交接仍可入账。
                // 计划每 tick 重算：同一失败、同一组数值只在第一次记 ERROR，数值变了再记，卡住时不逐 tick 刷屏。
                String report = creditFailureReport(failure, entry.getKey(), entries);
                if (!report.equals(reportedCreditFailures.put(entry.getKey(), report))) {
                    LOG.error(HumanErrors.ENVIRONMENT_CREDIT_FAILED + "：" + report, failure);
                }
            }
        }
        // 已经没有排队意图的种类不再保留记录，之后再出错照常记。
        reportedCreditFailures.keySet().retainAll(claims.keySet());
    }

    private String creditFailureReport(RuntimeException failure, ReleasedQiAllocation.Kind kind,
                                       List<ReleasedQiAllocation.Claim> entries) {
        // 失败原因、共享账户同种余额与各区域意图共同标识一次失败；double 文本可往返，数值变了文本必变。
        String account = store.environmentQi().entries().stream()
                .filter(qi -> ReleasedQiAllocation.Kind.of(qi).equals(kind))
                .map(qi -> Double.toString(qi.amount()))
                .findFirst().orElse("无");
        String intents = entries.stream()
                .map(claim -> claim.sourceId() + "=" + claim.qi().amount())
                .collect(java.util.stream.Collectors.joining("，"));
        return failure.getMessage() + " 种类=" + kind + " 共享账户=" + account + " 区域意图=[" + intents + "]";
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

    CompoundTag save(CompoundTag nbt) {
        // 身体、余额及交接事实提交到同一文件，不能分别保存后确认。
        return encode(capture(), nbt);
    }

    private SavedState capture() {
        // 主线程上截下存盘要的全部内容；身体快照、回执和来源都是不可变值，表另行复制。
        var saved = new ArrayList<SavedPlayer>();
        for (HumanPlayerBody body : players.values()) {
            saved.add(new SavedPlayer(body.snapshot(), body.breathingActions(), body.receipts()));
        }
        var handoffs = new SavedHandoffs(List.copyOf(releaseIds.values()), List.copyOf(pendingCredits),
                List.copyOf(drawIds.values()));
        return new SavedState(epoch, List.copyOf(saved), store.environmentQi(), store.environmentSources(), handoffs);
    }

    private static CompoundTag encode(SavedState saved, CompoundTag nbt) {
        // 按快照编码成存档格式；写盘线程和内存 save 共用。
        nbt.putString("epoch", saved.epoch());
        nbt.put("players", savedPlayers(saved.players()));
        nbt.putByteArray("released_qi", BodyProtobuf.toInventory(saved.releasedQi()).toByteArray());
        nbt.put("environment_sources", savedSources(saved.sources()));
        nbt.put("release_ids", savedReleaseIds(saved.handoffs().releases()));
        nbt.put("pending_credits", savedPendingCredits(saved.handoffs().credits()));
        nbt.put("draw_ids", savedDrawIds(saved.handoffs().draws()));
        return nbt;
    }

    private static ListTag savedPlayers(List<SavedPlayer> players) {
        // 每个玩家快照携带自己的动作与回执，默认释放账户仅保存在世界级字段。
        var entries = new ListTag();
        for (SavedPlayer player : players) {
            var entry = new CompoundTag();
            entry.putByteArray("body", BodyProtobuf.encodeSnapshot(player.body()));
            entry.putByteArray("released_qi", BodyProtobuf.toInventory(QiInventory.empty()).toByteArray());
            var actions = new ListTag();
            for (String instance : player.breathingActions()) {
                actions.add(net.minecraft.nbt.StringTag.valueOf(instance));
            }
            entry.put("breathing_actions", actions);
            var receipts = new ListTag();
            for (CommandReceipt receipt : player.receipts()) {
                var saved = new CompoundTag();
                saved.putByteArray("value", MeridianProtocol.encodeReceipt(receipt));
                receipts.add(saved);
            }
            entry.put("receipts", receipts);
            entries.add(entry);
        }
        return entries;
    }

    private static ListTag savedSources(List<EnvironmentQiSource> environmentSources) {
        // 区域库存与版本使用原始 Protobuf 字节保存，重启不重抽库存。
        var sources = new ListTag();
        for (EnvironmentQiSource source : environmentSources) {
            sources.add(new net.minecraft.nbt.ByteArrayTag(source.toByteArray()));
        }
        return sources;
    }

    private static ListTag savedReleaseIds(List<ReleaseRecord> releases) {
        // 保留未完成遗忘的完整请求，供冲突检查与待入账恢复校验。
        var ids = new ListTag();
        for (ReleaseRecord record : releases) {
            var entry = new CompoundTag();
            entry.putByteArray("request", record.request().toByteArray());
            entry.putLong("millis", record.millis());
            ids.add(entry);
        }
        return ids;
    }

    private static ListTag savedDrawIds(List<DrawRecord> draws) {
        // 原请求和首次结果都存原始 Protobuf 字节，重载后同 ID 重发仍拿到同一结果。
        var ids = new ListTag();
        for (DrawRecord record : draws) {
            var entry = new CompoundTag();
            entry.putByteArray("request", record.request().toByteArray());
            entry.putByteArray("result", record.result().toByteArray());
            entry.putLong("millis", record.millis());
            ids.add(entry);
        }
        return ids;
    }

    private static ListTag savedPendingCredits(List<PendingCredit> credits) {
        // 待入账数量与其去重 ID 同时持久化，玩家默认释放仍按区域合并。
        var pending = new ListTag();
        for (PendingCredit credit : credits) {
            var entry = new CompoundTag();
            entry.putString("source_id", credit.sourceId());
            entry.putByteArray("qi", BodyProtobuf.toInventory(credit.qi()).toByteArray());
            entry.putString("kind", credit.fromDefault() ? "default" : "external");
            entry.putString("release_id", credit.releaseId());
            pending.add(entry);
        }
        return pending;
    }
}
