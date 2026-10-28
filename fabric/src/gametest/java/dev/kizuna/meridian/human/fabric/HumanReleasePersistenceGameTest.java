package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionQuery;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionRelease;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 释放交接的整体回归：真实文件、合并保存、故障重试及持久遗忘。 */
public final class HumanReleasePersistenceGameTest implements FabricGameTest {
    private static final long WINDOW_MILLIS = 30_000;
    private static final long AFTER_SEVEN_DAYS_MILLIS = 8_400_001;
    private static final int RESTART_COUNT = 4;
    private static final int BATCH_SIZE = 32;
    private static final int REGION_COORDINATE = -1;
    private static final int CONFLICT_COORDINATE = 100;
    private static final double RELEASE_AMOUNT = 4;
    private static final double RELEASE_DENSITY = 0.01;
    private static final int DISTANT_REGION_BLOCK = 1_000_000;
    private static final double FIRST_SHARE = 0.1;
    private static final double SECOND_SHARE = 0.2;
    private static final double SHARED_RELEASE = 0.3;
    private static final long HALF_WINDOW_MILLIS = WINDOW_MILLIS / 2;

    private record RoundingFixture(HumanWorldState state, RegionView first, RegionView second) { }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void crossRegionRoundingTailReturnsAndSurvivesReload(TestContext context) throws Exception {
        // 旧存档的区域合并与共享账户采用不同求和顺序，最后一笔不能永远留在队列。
        var fixture = roundingFixture(context, 0);
        var restored = fixture.state();
        restored.renewEnvironment(WINDOW_MILLIS);
        var first = restored.query(context.getWorld(), 0, 0, WINDOW_MILLIS);
        var second = restored.query(context.getWorld(), DISTANT_REGION_BLOCK, 0, WINDOW_MILLIS);
        assertReturned(context, fixture.first(), first, FIRST_SHARE);
        assertReturned(context, fixture.second(), second, SHARED_RELEASE - FIRST_SHARE);
        var returned = restored.writeNbt(new NbtCompound());
        context.assertTrue(returned.getList("pending_credits", NbtElement.COMPOUND_TYPE).isEmpty(),
                "浮点尾差使跨区域归位永久滞留");
        context.assertTrue(BodyProtobuf.fromInventory(BodyMessages.QiInventory
                .parseFrom(returned.getByteArray("released_qi"))).entries().isEmpty(), "共享账户留下尾差");
        var reloaded = HumanWorldState.read(returned);
        reloaded.renewEnvironment(WINDOW_MILLIS * 2);
        context.assertTrue(reloaded.writeNbt(new NbtCompound()).getList("pending_credits", NbtElement.COMPOUND_TYPE)
                .isEmpty(), "重载恢复了已归位队列");
        context.assertTrue(reloaded.query(context.getWorld(), 0, 0, WINDOW_MILLIS * 2).getInventory()
                .equals(first.getInventory()), "重载重复向第一区域入账");
        context.assertTrue(reloaded.query(context.getWorld(), DISTANT_REGION_BLOCK, 0, WINDOW_MILLIS * 2)
                .getInventory().equals(second.getInventory()), "重载重复向第二区域入账");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void roundingAllocationPreservesRegionWindowAndPendingAcrossReload(TestContext context) {
        // 较晚创建的区域尚未到期时不能提前入账；重载后继续使用实际余额处理尾笔。
        var fixture = roundingFixture(context, HALF_WINDOW_MILLIS);
        fixture.state().renewEnvironment(WINDOW_MILLIS);
        var first = fixture.state().query(context.getWorld(), 0, 0, WINDOW_MILLIS);
        var second = fixture.state().query(context.getWorld(), DISTANT_REGION_BLOCK, 0, WINDOW_MILLIS);
        assertReturned(context, fixture.first(), first, FIRST_SHARE);
        context.assertTrue(second.getInventory().equals(fixture.second().getInventory()), "未到期区域提前入账");
        var saved = fixture.state().writeNbt(new NbtCompound());
        context.assertTrue(saved.getList("pending_credits", NbtElement.COMPOUND_TYPE).size() == 1,
                "未到期区域的归位意图丢失");
        var reloaded = HumanWorldState.read(saved);
        long secondWindow = WINDOW_MILLIS + HALF_WINDOW_MILLIS;
        reloaded.renewEnvironment(secondWindow);
        var returnedSecond = reloaded.query(context.getWorld(), DISTANT_REGION_BLOCK, 0, secondWindow);
        assertReturned(context, fixture.second(), returnedSecond, SHARED_RELEASE - FIRST_SHARE);
        context.assertTrue(reloaded.writeNbt(new NbtCompound()).getList("pending_credits", NbtElement.COMPOUND_TYPE)
                .isEmpty(), "跨窗重载后尾笔未能归位");
        context.complete();
    }

    private static RoundingFixture roundingFixture(TestContext context, long secondCreatedAt) {
        // 直接读取旧队列的 0.1 + 0.2 与账户 0.3，不用新分配规则构造期望数据。
        var state = new HumanWorldState();
        var first = state.query(context.getWorld(), 0, 0, 0);
        var second = state.query(context.getWorld(), DISTANT_REGION_BLOCK, 0, secondCreatedAt);
        var saved = state.writeNbt(new NbtCompound());
        saved.putByteArray("released_qi", BodyProtobuf.toInventory(releaseQi(SHARED_RELEASE)).toByteArray());
        var pending = new NbtList();
        pending.add(defaultCredit(first.getSourceId(), FIRST_SHARE));
        pending.add(defaultCredit(second.getSourceId(), SECOND_SHARE));
        saved.put("pending_credits", pending);
        return new RoundingFixture(HumanWorldState.read(saved), first, second);
    }

    private static void assertReturned(TestContext context, RegionView before, RegionView after, double amount) {
        // 检查目的地完整库存，删除队列或清空默认账户不能冒充实际归位。
        var expected = QiAccounting.credit(BodyProtobuf.fromInventory(before.getInventory()), releaseQi(amount));
        context.assertTrue(BodyProtobuf.fromInventory(after.getInventory()).equals(expected), "区域实际入账不守恒");
    }

    private static QiInventory releaseQi(double amount) {
        // 使用真实环境释放相态与属性，数量刻意保留十进制浮点边界。
        return new QiInventory(List.of(new Qi(Qi.QiAttribute.of("qi_variants:wither"), amount, RELEASE_DENSITY)));
    }

    private static NbtCompound defaultCredit(String sourceId, double amount) {
        // 与旧存档完全一致的排队格式，不调用新分配器准备预期值。
        var entry = new NbtCompound();
        entry.putString("source_id", sourceId);
        entry.putString("kind", "default");
        entry.putString("release_id", "");
        entry.putByteArray("qi", BodyProtobuf.toInventory(releaseQi(amount)).toByteArray());
        return entry;
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void memoryAcceptanceAndDuplicateDoNotConfirmDisk(TestContext context) throws Exception {
        // 先保存旧状态，模拟首次受理与重复受理后均未触发世界保存就退出。
        Fixture fixture = fixture(context);
        HumanWorldState state = fixture.state();
        RegionRelease release = fixture.release();
        Path file = fixture.file();
        var world = context.getWorld();
        state.save(file.toFile());
        var accepted = state.release(release, world, 1);
        var duplicate = state.release(release, world, 1);
        context.assertTrue(!accepted.getPersisted() && duplicate.getDuplicate() && !duplicate.getPersisted(),
                "内存受理或 duplicate 被误当作写盘确认");
        HumanWorldState restored = read(file);
        context.assertTrue(!restored.release(release, world, 1).getDuplicate(),
                "夹具没有保留首次受理前的旧文件");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void durableReleaseSurvivesRepeatedReloadAndCreditsOnce(TestContext context) throws Exception {
        // 接收端先落盘、响应丢失及发送端旧文件反复重放，都只保留一笔待入账事实。
        Fixture fixture = fixture(context);
        RegionRelease release = fixture.release();
        Path file = fixture.file();
        var world = context.getWorld();
        var accepted = fixture.state().release(release, world, 1);
        context.assertTrue(!accepted.getPersisted() && !accepted.getDuplicate(), "首次受理不应同步写盘");
        fixture.state().flushHandoffs(file);
        QiInventory expected = QiAccounting.credit(BodyProtobuf.fromInventory(fixture.original().getInventory()),
                BodyProtobuf.fromInventory(release.getReleasedQi()));
        for (int restart = 0; restart < RESTART_COUNT; restart++) {
            HumanWorldState restored = read(file);
            long boundaryMillis = (restart + 1L) * WINDOW_MILLIS;
            var duplicate = restored.release(release, world, boundaryMillis - 1);
            context.assertTrue(duplicate.getPersisted() && duplicate.getDuplicate(), "重启重试未确认原请求");
            restored.renewEnvironment(boundaryMillis);
            restored.save(file.toFile());
            var actual = read(file).query(world, REGION_COORDINATE,
                    REGION_COORDINATE, boundaryMillis);
            context.assertTrue(BodyProtobuf.fromInventory(actual.getInventory()).equals(expected),
                    "重复响应丢失或重启导致重复入账");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void failedWriteKeepsOldFileAndDuplicateMustRetryDisk(TestContext context) throws Exception {
        // 用同名目录阻止临时文件写入，确保首次失败及内存 duplicate 都不能确认持久化。
        Fixture fixture = fixture(context);
        HumanWorldState state = fixture.state();
        RegionRelease release = fixture.release();
        Path file = fixture.file();
        var world = context.getWorld();
        state.save(file.toFile());
        byte[] original = Files.readAllBytes(file);
        Path blocked = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectory(blocked);
        try {
            assertWriteFails(context, fixture);
            context.assertTrue(state.release(release, world, 1).getDuplicate(),
                    "失败受理的 ID 未保留在内存");
            assertWriteFails(context, fixture);
            state.save(file.toFile());
            context.assertTrue(state.isDirty(), "世界保存失败清除了 dirty");
            context.assertTrue(Arrays.equals(original, Files.readAllBytes(file)), "写失败破坏了旧文件");
        } finally {
            Files.delete(blocked);
        }
        state.flushHandoffs(file);
        var recovered = state.release(release, world, 1);
        context.assertTrue(recovered.getPersisted() && recovered.getDuplicate(), "磁盘恢复后未完成原请求交接");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void failedAtomicReplacementDoesNotConfirmDisk(TestContext context) throws Exception {
        // 非空目标目录使原子替换失败，不能把临时文件已写完当作提交完成。
        Fixture fixture = fixture(context);
        Path file = fixture.file();
        Files.createDirectory(file);
        Path sentinel = file.resolve("sentinel");
        Files.writeString(sentinel, "保留原目标");
        assertWriteFails(context, fixture);
        context.assertTrue(Files.readString(sentinel).equals("保留原目标"), "替换失败破坏了原目标");
        context.assertTrue(fixture.state().isDirty(), "原子替换失败清除了 dirty");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void unresolvedReleaseSurvivesSevenDaysAndRejectsConflictingContent(TestContext context) throws Exception {
        // 没有发送端持久删除证明时，已入账 ID 也不能按时间回收。
        Fixture fixture = fixture(context);
        HumanWorldState state = fixture.state();
        RegionRelease release = fixture.release();
        Path file = fixture.file();
        var world = context.getWorld();
        state.release(release, world, 1);
        state.flushHandoffs(file);
        state.renewEnvironment(AFTER_SEVEN_DAYS_MILLIS);
        state.save(file.toFile());
        HumanWorldState restored = read(file);
        var duplicate = restored.release(release, world, AFTER_SEVEN_DAYS_MILLIS);
        context.assertTrue(duplicate.getDuplicate() && duplicate.getPersisted(), "七日后未决 ID 被回收");
        var changedPosition = release.getPosition().toBuilder()
                .setBlockX(CONFLICT_COORDINATE)
                .build();
        var moved = release.toBuilder()
                .setPosition(changedPosition)
                .build();
        var changedQi = release.toBuilder()
                .setReleasedQi(fixture.original().getInventory())
                .build();
        assertConflict(context, restored, moved);
        assertConflict(context, restored, changedQi);
        context.assertTrue(restored.writeNbt(new NbtCompound())
                .getList("release_ids", NbtElement.COMPOUND_TYPE).size() == 1, "冲突改变了去重记录");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void batchAndWorldSaveShareOneAttemptPerTick(TestContext context) throws Exception {
        // 多笔释放共用快照，普通世界保存也不能绕过同 tick 的次数限制。
        Fixture fixture = fixture(context);
        HumanWorldState state = fixture.state();
        state.bind(context.getWorld().getServer());
        for (int index = 0; index < BATCH_SIZE; index++) {
            var release = fixture.release().toBuilder().setReleaseId("batch:" + index).build();
            context.assertTrue(!state.release(release, context.getWorld(), 1).getPersisted(), "首次受理提前确认");
        }
        state.flushHandoffs(fixture.file());
        context.assertTrue(read(fixture.file()).writeNbt(new NbtCompound())
                .getList("release_ids", NbtElement.COMPOUND_TYPE).size() == BATCH_SIZE, "未合并保存");
        byte[] saved = Files.readAllBytes(fixture.file());
        state.release(fixture.release(), context.getWorld(), 1);
        state.flushHandoffs(fixture.file());
        state.save(fixture.file().toFile());
        context.assertTrue(Arrays.equals(saved, Files.readAllBytes(fixture.file())), "同 tick 重复写盘");
        context.assertTrue(!state.release(fixture.release(), context.getWorld(), 1).getPersisted(), "未写盘却确认");
        context.waitAndRun(1, () -> {
            state.flushHandoffs(fixture.file());
            context.assertTrue(state.release(fixture.release(), context.getWorld(), 1).getPersisted(),
                    "下一 tick 未保存延后的请求");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void failedBatchOnlyRetriesNextTick(TestContext context) throws Exception {
        // 失败也消耗本 tick 的保存机会，磁盘恢复后须等下一 tick，避免故障时重复重写。
        Fixture fixture = fixture(context);
        HumanWorldState state = fixture.state();
        state.bind(context.getWorld().getServer());
        state.release(fixture.release(), context.getWorld(), 1);
        Path blocked = fixture.file().resolveSibling(fixture.file().getFileName() + ".tmp");
        Files.createDirectory(blocked);
        try {
            state.flushHandoffs(fixture.file());
        } finally {
            Files.delete(blocked);
        }
        state.save(fixture.file().toFile());
        context.assertTrue(!Files.exists(fixture.file()) && state.isDirty(), "同 tick 重试了失败保存");
        context.waitAndRun(1, () -> {
            state.flushHandoffs(fixture.file());
            context.assertTrue(state.release(fixture.release(), context.getWorld(), 1).getPersisted(),
                    "下一 tick 没有重试失败保存");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void unsupportedDirectorySyncStillConfirmsPersistence(TestContext context) throws Exception {
        // 走 Windows 的目录能力路径，保存完整文件后仍应返回真实确认。
        Fixture fixture = fixture(context, new HumanWorldState(DurableStateFile.DirectorySync.UNSUPPORTED));
        fixture.state().release(fixture.release(), context.getWorld(), 1);
        fixture.state().flushHandoffs(fixture.file());
        context.assertTrue(!fixture.state().isDirty(), "目录同步不支持导致保存失败");
        context.assertTrue(fixture.state().release(fixture.release(), context.getWorld(), 1).getPersisted(),
                "目录同步不支持导致 persisted 永远为 false");
        context.assertTrue(read(fixture.file()).release(fixture.release(), context.getWorld(), 1).getPersisted(),
                "正式文件没有保存已确认请求");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void forgetWaitsForCreditAndDurableDeletionThenReclaimsHistory(TestContext context) throws Exception {
        // 待入账时不删除；删除写入失败和响应丢失都能用旧文件恢复并安全重试。
        Fixture fixture = fixture(context);
        HumanWorldState state = fixture.state();
        String releaseId = fixture.release().getReleaseId();
        state.release(fixture.release(), context.getWorld(), 1);
        state.flushHandoffs(fixture.file());
        context.assertTrue(!state.forgetRelease(releaseId).getPersisted(), "提前删除待入账事实");
        state.renewEnvironment(WINDOW_MILLIS);
        state.save(fixture.file().toFile());
        context.assertTrue(!state.forgetRelease(releaseId).getPersisted(), "内存遗忘冒充写盘");
        Path blocked = fixture.file().resolveSibling(fixture.file().getFileName() + ".tmp");
        Files.createDirectory(blocked);
        try {
            state.flushHandoffs(fixture.file());
            context.assertTrue(!state.forgetRelease(releaseId).getPersisted(), "遗忘写失败仍确认");
        } finally {
            Files.delete(blocked);
        }
        verifyForgetRecovery(context, fixture);
        context.complete();
    }

    private static void verifyForgetRecovery(TestContext context, Fixture fixture) throws IOException {
        // 删除尚未落盘时重启仍有去重项；成功后重启只重试遗忘，不能重发原释放。
        String releaseId = fixture.release().getReleaseId();
        HumanWorldState restored = read(fixture.file());
        context.assertTrue(restored.release(fixture.release(), context.getWorld(), 1).getDuplicate(),
                "删除未落盘却丢失去重项");
        restored.forgetRelease(releaseId);
        restored.flushHandoffs(fixture.file());
        HumanWorldState afterLostResponse = read(fixture.file());
        context.assertTrue(afterLostResponse.forgetRelease(releaseId).getPersisted(), "遗忘重试不幂等");
        context.assertTrue(afterLostResponse.writeNbt(new NbtCompound())
                .getList("release_ids", NbtElement.COMPOUND_TYPE).isEmpty(), "已完成历史未回收");
        QiInventory expected = QiAccounting.credit(BodyProtobuf.fromInventory(fixture.original().getInventory()),
                BodyProtobuf.fromInventory(fixture.release().getReleasedQi()));
        RegionView actual = afterLostResponse.query(context.getWorld(), REGION_COORDINATE,
                REGION_COORDINATE, WINDOW_MILLIS);
        context.assertTrue(BodyProtobuf.fromInventory(actual.getInventory()).equals(expected), "遗忘改变余额");
    }

    private static Fixture fixture(TestContext context) throws IOException {
        // 每项回归使用独立文件和状态，库存及群系仍由真实世界和生产实现生成。
        return fixture(context, new HumanWorldState());
    }

    private static Fixture fixture(TestContext context, HumanWorldState state) throws IOException {
        // 测试使用真实世界生成区域，保存能力可以指定为平台不支持目录同步。
        var world = context.getWorld();
        RegionView original = state.query(world, REGION_COORDINATE, REGION_COORDINATE, 0);
        var position = RegionQuery.newBuilder()
                .setDimensionId(world.getRegistryKey().getValue().toString())
                .setBlockX(REGION_COORDINATE)
                .setBlockZ(REGION_COORDINATE)
                .build();
        var qi = new QiInventory(List.of(new Qi(Qi.QiAttribute.of("qi_variants:wither"),
                RELEASE_AMOUNT, RELEASE_DENSITY)));
        var release = RegionRelease.newBuilder()
                .setReleaseId("gametest:durable-release")
                .setPosition(position)
                .setReleasedQi(BodyProtobuf.toInventory(qi))
                .build();
        Path directory = Files.createTempDirectory(Path.of("."), "release-persistence-");
        return new Fixture(state, release, directory.resolve("human.dat"), original);
    }

    private static HumanWorldState read(Path file) throws IOException {
        // 必须读取实际压缩文件，不能用内存 writeNbt 冒充重启恢复。
        return HumanWorldState.read(NbtIo.readCompressed(file.toFile()).getCompound("data"));
    }

    private static void assertWriteFails(TestContext context, Fixture fixture) {
        // 异步保存失败保留 dirty，后续重试不能生成持久化确认。
        HumanWorldState state = fixture.state();
        state.release(fixture.release(), context.getWorld(), 1);
        state.flushHandoffs(fixture.file());
        context.assertTrue(state.isDirty()
                && !state.release(fixture.release(), context.getWorld(), 1).getPersisted(), "写失败却确认");
    }

    private static void assertConflict(TestContext context, HumanWorldState state, RegionRelease request) {
        // 比较完整请求，位置或库存变化必须在任何写盘和入账之前拒绝。
        boolean conflict = false;
        try {
            state.release(request, context.getWorld(), AFTER_SEVEN_DAYS_MILLIS);
        } catch (HumanErrors.HostRejected expected) {
            conflict = expected.code() == FailureCode.REQUEST_ID_CONFLICT;
        }
        context.assertTrue(conflict, "相同 ID 的不同内容未返回 REQUEST_ID_CONFLICT");
    }

    private record Fixture(HumanWorldState state, RegionRelease release, Path file, RegionView original) { }
}
