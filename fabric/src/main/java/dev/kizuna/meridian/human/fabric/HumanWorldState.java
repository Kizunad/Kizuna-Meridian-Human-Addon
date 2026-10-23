package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.ReleaseResult;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HumanWorldState.class);
    private static final long WINDOW_MILLIS = 30_000;
    private static final long RELEASE_RETENTION_MILLIS = 7L * 24_000 * 50;
    private final Map<String, ReleaseRecord> releaseIds = new HashMap<>();
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
            requireField(nbt, "epoch", NbtElement.STRING_TYPE);
            requireList(nbt, "players", NbtElement.COMPOUND_TYPE);
            requireField(nbt, "released_qi", NbtElement.BYTE_ARRAY_TYPE);
            requireList(nbt, "environment_sources", NbtElement.BYTE_ARRAY_TYPE);
            requireList(nbt, "release_ids", NbtElement.COMPOUND_TYPE);
            requireList(nbt, "pending_credits", NbtElement.COMPOUND_TYPE);
            var bodies = new ArrayList<BodySnapshot>();
            var receipts = new ArrayList<CommandReceipt>();
            var released = QiInventory.empty();
            var breathingActions = new HashMap<UUID, Set<String>>();
            NbtList entries = nbt.getList("players", NbtElement.COMPOUND_TYPE);
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
                NbtList savedReceipts = entry.getList("receipts", NbtElement.COMPOUND_TYPE);
                for (int receiptIndex = 0; receiptIndex < savedReceipts.size(); receiptIndex++) {
                    requireField(savedReceipts.getCompound(receiptIndex), "value", NbtElement.BYTE_ARRAY_TYPE);
                    receipts.add(MeridianProtocol.decodeReceipt(savedReceipts.getCompound(receiptIndex).getByteArray("value")));
                }
                // 逐身体字段只能为空，唯一释放账必须来自世界字段，拒绝旧格式混合入账。
                var legacyReleased = BodyProtobuf.fromInventory(BodyMessages.QiInventory.parseFrom(entry.getByteArray("released_qi")));
                if (!legacyReleased.entries().isEmpty()) {
                    throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                }
                bodies.add(body);
                var instances = new HashSet<String>();
                NbtList actions = entry.getList("breathing_actions", NbtElement.STRING_TYPE);
                for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                    if (actions.getString(actionIndex).isBlank() || !instances.add(actions.getString(actionIndex))) {
                        throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                    }
                }
                breathingActions.put(playerId, Set.copyOf(instances));
            }
            var sources = new ArrayList<EnvironmentQiSource>();
            NbtList savedSources = nbt.getList("environment_sources", NbtElement.BYTE_ARRAY_TYPE);
            for (int index = 0; index < savedSources.size(); index++) {
                EnvironmentQiSource source = EnvironmentQiSource.parseFrom(((net.minecraft.nbt.NbtByteArray) savedSources.get(index)).getByteArray());
                EnvironmentProtobuf.validateSource(source);
                if (!source.getValidFrom().getEpoch().equals(epoch)
                        || !source.getValidUntil().getEpoch().equals(epoch)
                        || !source.getValidFrom().getDomain().equals("human:minecraft")
                        || !source.getValidUntil().getDomain().equals("human:minecraft")) {
                    throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                }
                sources.add(source);
            }
            released = BodyProtobuf.fromInventory(BodyMessages.QiInventory.parseFrom(nbt.getByteArray("released_qi")));
            var store = new InMemoryBodyStore(bodies, receipts, released, sources);
            var state = new HumanWorldState(epoch, store);
            var savedIds = nbt.getList("release_ids", NbtElement.COMPOUND_TYPE);
            for (int index = 0; index < savedIds.size(); index++) {
                var entry = savedIds.getCompound(index);
                requireField(entry, "request", NbtElement.BYTE_ARRAY_TYPE);
                requireField(entry, "millis", NbtElement.LONG_TYPE);
                var request = RegionRelease.parseFrom(entry.getByteArray("request"));
                validateRelease(request);
                long millis = entry.getLong("millis");
                if (millis < 0 || state.releaseIds.putIfAbsent(request.getReleaseId(),
                        new ReleaseRecord(request, millis)) != null) {
                    throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                }
            }
            var savedPending = nbt.getList("pending_credits", NbtElement.COMPOUND_TYPE);
            var queuedIds = new HashSet<String>();
            for (int index = 0; index < savedPending.size(); index++) {
                var entry = savedPending.getCompound(index);
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
                    var record = state.releaseIds.get(releaseId);
                    if (record == null || !queuedIds.add(releaseId)
                            || !BodyProtobuf.fromInventory(record.request().getReleasedQi()).equals(qi)
                            || !state.releaseSourceId(record.request()).equals(sourceId)) {
                        throw HumanErrors.invalid(HumanErrors.INVALID_SAVED_BODY);
                    }
                }
                if (fromDefault) {
                    state.queueDefaultRelease(sourceId, qi);
                } else {
                    state.pendingCredits.add(new PendingCredit(sourceId, qi, false, releaseId));
                }
            }
            // 余额不一致的失败队列仍须恢复，实际扣转时记录错误并保留，不能让重启丢掉重试机会。
            for (BodySnapshot body : bodies) {
                UUID playerId = UUID.fromString(body.id().value());
                state.players.put(playerId, new HumanPlayerBody(body, store, breathingActions.get(playerId)));
            }
            return state;
        } catch (Exception failure) {
            throw HumanErrors.invalidSave(failure);
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
        time(millis);
        validateRelease(request);
        if (!request.getPosition().getDimensionId().equals(world.getRegistryKey().getValue().toString())) {
            throw HumanErrors.hostRejected(FailureCode.INVALID_REQUEST);
        }
        ReleaseRecord previous = releaseIds.get(request.getReleaseId());
        if (previous != null && !previous.request().equals(request)) {
            throw HumanErrors.hostRejected(FailureCode.REQUEST_ID_CONFLICT);
        }
        String sourceId = ensureRegion(world, new ChunkPos(Math.floorDiv(request.getPosition().getBlockX(), 16),
                Math.floorDiv(request.getPosition().getBlockZ(), 16)), millis);
        if (previous == null) {
            pendingCredits.add(new PendingCredit(sourceId == null ? "" : sourceId,
                    BodyProtobuf.fromInventory(request.getReleasedQi()), false, request.getReleaseId()));
            releaseIds.put(request.getReleaseId(), new ReleaseRecord(request, millis));
            markDirty();
        }
        var result = ReleaseResult.newBuilder().setDuplicate(previous != null);
        if (sourceId != null) {
            result.setSourceId(sourceId);
        }
        return result.build();
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
        time(millis);
        var due = new HashSet<String>();
        for (EnvironmentQiSource source : store.environmentSources()) {
            if (millis >= source.getValidUntil().getElapsedMillis()) {
                due.add(source.getSpace().getSourceId());
            }
        }
        var iterator = pendingCredits.iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            // 无区域账户也等到受理后的下一个边界；跳过精确边界的 tick 仍能补处理。
            boolean dueDefault = pending.sourceId().isEmpty()
                    && millis / WINDOW_MILLIS > releaseIds.get(pending.releaseId()).millis() / WINDOW_MILLIS;
            if (pending.sourceId().isEmpty() ? dueDefault : due.contains(pending.sourceId())) {
                try {
                    if (pending.sourceId().isEmpty()) {
                        store.creditReleasedQi(pending.qi());
                    } else if (pending.fromDefault()) {
                        // 共享账户已混合密度，按当前密度分取同量，不能拿旧密度直接扣款。
                        store.transferReleasedToSource(pending.sourceId(), QiAccounting.select(store.environmentQi(), pending.qi()));
                    } else {
                        store.creditEnvironmentSource(pending.sourceId(), pending.qi());
                    }
                    iterator.remove();
                    markDirty();
                } catch (RuntimeException failure) {
                    // 失败项继续随世界保存并等待下一窗，不能阻断其他入账或吞掉余额不一致。
                    LOG.error(HumanErrors.ENVIRONMENT_CREDIT_FAILED + ": " + pending.sourceId(), failure);
                }
            }
        }
        for (String sourceId : due) {
            var source = store.environmentSource(sourceId).orElseThrow();
            store.renewEnvironmentWindow(sourceId, source.getRevision(), time(millis),
                    time(Math.addExact(millis, WINDOW_MILLIS)), rules);
            markDirty();
        }
        var pendingIds = new HashSet<String>();
        for (PendingCredit pending : pendingCredits) {
            pendingIds.add(pending.releaseId());
        }
        if (releaseIds.entrySet().removeIf(entry -> !pendingIds.contains(entry.getKey())
                && millis >= entry.getValue().millis()
                && millis - entry.getValue().millis() >= RELEASE_RETENTION_MILLIS)) {
            markDirty();
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
        var ids = new NbtList();
        for (ReleaseRecord record : releaseIds.values()) {
            var entry = new NbtCompound();
            entry.putByteArray("request", record.request().toByteArray());
            entry.putLong("millis", record.millis());
            ids.add(entry);
        }
        nbt.put("release_ids", ids);
        var pending = new NbtList();
        for (PendingCredit credit : pendingCredits) {
            var entry = new NbtCompound();
            entry.putString("source_id", credit.sourceId());
            entry.putByteArray("qi", BodyProtobuf.toInventory(credit.qi()).toByteArray());
            entry.putString("kind", credit.fromDefault() ? "default" : "external");
            entry.putString("release_id", credit.releaseId());
            pending.add(entry);
        }
        nbt.put("pending_credits", pending);
        return nbt;
    }
}
