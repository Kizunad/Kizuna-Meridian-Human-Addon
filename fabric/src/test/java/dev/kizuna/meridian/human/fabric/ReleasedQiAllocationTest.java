package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** 跨区域分配必须直接通过 Framework 的严格取用与扣转校验。 */
public final class ReleasedQiAllocationTest {
    private static final double DENSITY = 0.01;
    private static final double FIRST = 0.1;
    private static final double SECOND = 0.2;
    private static final double TOTAL = 0.3;
    private static final double EXTRA_ACCOUNT = 0.4;
    private static final double SHORT_ACCOUNT = 0.29;
    private static final int MINIMUM_SHARES = 4;
    private static final double CAST_ACCOUNT = 0.009000000000000001;
    private static final double CAST_FIRST_SHARE = 0.001;
    private static final double CAST_MIDDLE_SHARE = 0.007;
    private static final double CAST_LAST_SHARE = 0.0010000000000000009;
    private static final double PLAYTEST_ACCOUNT = 3.72;
    private static final double PLAYTEST_SMALL = 1.61;
    private static final double PLAYTEST_LARGE = 2.11;
    private static final double PROBE_ACCOUNT = 1.6500000000000001;
    private static final double PROBE_SMALL = 0.35;
    private static final double PROBE_LARGE = 1.3;
    private static final long SIMULATION_SEED = 20261102L;
    private static final int SIMULATION_TICKS = 100_000;
    private static final int WINDOW_TICKS = 600;
    private static final int REGIONS = 3;
    private static final double RELEASE_MIN = 0.001;
    private static final double RELEASE_SPAN = 2;
    private static final int STAGGERED_TICKS = 20_000;
    private static final int STAGGERED_RUNS = 2;
    private static final int MIN_REGIONS = 2;
    private static final int MAX_REGIONS = 4;
    // 单笔释放量按对数均匀取：只有小额，或从小额跨到 2。
    private static final double[][] RELEASE_RANGES = {{0.001, 0.01}, {0.001, 2}};
    // 每 tick 释放一笔的概率：每 tick 都放，或平均五 tick 一笔。
    private static final double[] RELEASE_CHANCES = {1, 0.2};

    @Test
    public void lastShareUsesActualRemainderAndStrictLedgerDrainsExactly() {
        // 十进制拆分先转小份再转实际余量，运行与预检使用完全相同的明细。
        var account = inventory(TOTAL);
        var claims = List.of(claim("second", SECOND), claim("first", FIRST));
        var plan = ReleasedQiAllocation.plan(account, claims);
        assertEquals(TOTAL, plan.stream().mapToDouble(claim -> claim.qi().amount()).sum(), 0);
        assertTrue(execute(account, plan).entries().isEmpty());
    }

    @Test
    public void recordedRealCastSharesDrainWithoutRoundingDust() {
        // 九笔真实施放在三个区域形成的存档数值，末区域请求曾大于逐笔扣款后的余额。
        var account = inventory(CAST_ACCOUNT);
        var claims = List.of(claim("first", CAST_FIRST_SHARE), claim("middle", CAST_MIDDLE_SHARE),
                claim("last", CAST_LAST_SHARE));
        var plan = ReleasedQiAllocation.plan(account, claims);
        assertEquals(claims.size(), plan.size());
        assertTrue(execute(account, plan).entries().isEmpty());
    }

    @Test
    public void exhaustingLastShareFromPlaytestStaysExecutable() {
        // 试玩反例：末笔取「账户 − 已分配」后按合并顺序复算会多 1 ULP，旧实现直接抛 NUMERIC_LIMIT。
        // 末笔按 ULP 下调到合并后不超过账户，小额照原数转，剩下不超过 1 ULP 的尘埃留在账户。
        assertDrainsWithinUlp(PLAYTEST_ACCOUNT, PLAYTEST_SMALL, PLAYTEST_LARGE);
        assertDrainsWithinUlp(PROBE_ACCOUNT, PROBE_SMALL, PROBE_LARGE);
    }

    @Test
    public void randomReleasesAcrossRegionsAlwaysPlanAndDrainEachWindow() {
        // 每 tick 一笔释放随机落到三个区域之一，账户与区域意图都按宿主的逐笔 double 加法累计。
        // 宿主每 tick 都重算计划，所以每 tick 都必须出得了计划；窗口末整批经 Framework 规则扣转，只剩 ULP 级尘埃。
        var random = new Random(SIMULATION_SEED);
        double account = 0;
        var intents = new LinkedHashMap<String, Double>();
        for (int tick = 0; tick < SIMULATION_TICKS; tick++) {
            double before = account;
            account += RELEASE_MIN + random.nextDouble() * RELEASE_SPAN;
            // 宿主按共享账户前后差额记释放，再并入释放时所在区域的意图。
            intents.merge("region-" + random.nextInt(REGIONS), account - before, Double::sum);
            var claims = intents.entrySet().stream().map(entry -> claim(entry.getKey(), entry.getValue())).toList();
            var plan = ReleasedQiAllocation.plan(inventory(account), claims);
            if (tick % WINDOW_TICKS == WINDOW_TICKS - 1) {
                double dust = remaining(execute(inventory(account), plan));
                assertTrue("第 " + tick + " tick 窗口末尘埃过大：" + dust, dust <= Math.ulp(account));
                account = dust;
                intents.clear();
            }
        }
    }

    @Test
    public void staggeredRegionWindowsNeverRejectQueuedReleases() {
        // 各区域窗口错开时承接余量的末笔可能单独先到期；2–4 区、两档释放量、两档释放频率各跑两轮，
        // 每 tick 的计划与每次到期扣转都不能被 Framework 拒绝。
        var seeds = new Random(SIMULATION_SEED);
        var rejections = new ArrayList<String>();
        for (int regions = MIN_REGIONS; regions <= MAX_REGIONS; regions++) {
            for (double[] range : RELEASE_RANGES) {
                for (double chance : RELEASE_CHANCES) {
                    for (int run = 0; run < STAGGERED_RUNS; run++) {
                        rejections.addAll(staggeredWindows(regions, range, chance, seeds.nextLong()));
                    }
                }
            }
        }
        assertTrue("错开窗口下被拒绝 " + rejections.size() + " 次，前几次：" + rejections.stream().limit(5).toList(),
                rejections.isEmpty());
    }

    @Test
    public void minimumTransfersStayTransferableAndDustWaitsForAccumulation() {
        // 最大份额承接尾差；不能把一个最小单位修正成不可流转的明细。
        double minimum = QiAccounting.MIN_TRANSFER_QI;
        var claims = List.of(claim("a", minimum), claim("b", minimum), claim("c", minimum), claim("d", minimum));
        var account = inventory(MINIMUM_SHARES * minimum);
        var plan = ReleasedQiAllocation.plan(account, claims);
        assertEquals(MINIMUM_SHARES, plan.size());
        assertTrue(execute(account, plan).entries().isEmpty());
        double dust = Math.nextDown(minimum);
        var dustAccount = inventory(dust);
        assertTrue(ReleasedQiAllocation.plan(dustAccount, List.of(claim("dust", dust))).isEmpty());
        assertEquals(dustAccount, execute(dustAccount, List.of()));
    }

    @Test
    public void unassignedBalanceIsPreservedAndRealShortageIsRejected() {
        // 下界等未排队余额不能被末笔扫走，真实缺账继续等待恢复而非静默丢弃。
        var claims = List.of(claim("a", FIRST), claim("b", SECOND));
        var account = inventory(EXTRA_ACCOUNT);
        var plan = ReleasedQiAllocation.plan(account, claims);
        assertEquals(FIRST, plan.get(0).qi().amount(), 0);
        assertEquals(SECOND, plan.get(1).qi().amount(), 0);
        assertFalse(execute(account, plan).entries().isEmpty());
        assertThrows(RuntimeException.class, () -> ReleasedQiAllocation.plan(inventory(SHORT_ACCOUNT), claims));
    }

    @Test
    public void missingKindIsRejectedByCodeWithoutClaimingPause() {
        // 共享账户没有这一种真元时按失败码拒绝；拒绝不会暂停身体，文案只给原因和失败码。
        var failure = assertThrows(HumanErrors.HostRejected.class,
                () -> ReleasedQiAllocation.plan(QiInventory.empty(), List.of(claim("a", FIRST))));
        assertEquals(FailureCode.INSUFFICIENT_QI, failure.code());
        assertTrue(failure.getMessage().startsWith(HumanErrors.HOST_REJECTED));
        assertTrue(failure.getMessage().endsWith("（" + FailureCode.INSUFFICIENT_QI + "）"));
        assertFalse(failure.getMessage().contains("暂停"));
    }

    private static List<String> staggeredWindows(int regions, double[] range, double chance, long seed) {
        // 每个区域 600 tick 一窗、相位随机；账户与区域意图按宿主的逐笔 double 加法累计。
        // 宿主每 tick 按全部意图重算计划，只把到期区域的明细整批扣转；被拒绝时保留意图，与宿主保留队列一致。
        var random = new Random(seed);
        int[] phases = new int[regions];
        for (int region = 0; region < regions; region++) {
            phases[region] = random.nextInt(WINDOW_TICKS);
        }
        double logMin = Math.log(range[0]);
        double logSpan = Math.log(range[1]) - logMin;
        double account = 0;
        var intents = new LinkedHashMap<String, Double>();
        var rejections = new ArrayList<String>();
        for (int tick = 0; tick < STAGGERED_TICKS; tick++) {
            if (random.nextDouble() < chance) {
                double before = account;
                account += Math.exp(logMin + random.nextDouble() * logSpan);
                intents.merge("region-" + random.nextInt(regions), account - before, Double::sum);
            }
            if (intents.isEmpty()) {
                continue;
            }
            var due = new HashSet<String>();
            for (int region = 0; region < regions; region++) {
                if (Math.floorMod(tick - phases[region], WINDOW_TICKS) == 0) {
                    due.add("region-" + region);
                }
            }
            var claims = intents.entrySet().stream().map(entry -> claim(entry.getKey(), entry.getValue())).toList();
            try {
                var duePlan = ReleasedQiAllocation.plan(inventory(account), claims).stream()
                        .filter(claim -> due.contains(claim.sourceId())).toList();
                if (!duePlan.isEmpty()) {
                    account = remaining(execute(inventory(account), duePlan));
                    duePlan.forEach(claim -> intents.remove(claim.sourceId()));
                }
            } catch (MeridianErrors.TransportRejected rejected) {
                rejections.add(regions + " 区 " + range[1] + "/" + chance + " 第 " + tick + " tick：" + rejected.reason());
            }
        }
        return rejections;
    }

    private static void assertDrainsWithinUlp(double account, double small, double large) {
        // 两笔意图凑满账户：小额照原数转，执行后剩余不超过账户的 1 ULP。
        var inventory = inventory(account);
        var plan = ReleasedQiAllocation.plan(inventory, List.of(claim("large", large), claim("small", small)));
        assertEquals(small, plan.get(0).qi().amount(), 0);
        assertTrue(remaining(execute(inventory, plan)) <= Math.ulp(account));
    }

    private static double remaining(QiInventory account) {
        // 扣空的账户没有条目，按零计。
        if (account.entries().isEmpty()) {
            return 0;
        }
        return account.entries().get(0).amount();
    }

    private static QiInventory execute(QiInventory account, List<ReleasedQiAllocation.Claim> plan) {
        // 不使用分配器的内部算术验证自身，经过 Framework 批量取用的公开余额规则。
        for (var claim : plan) {
            var transfer = new QiInventory(List.of(claim.qi()));
            QiAccounting.validateTransferable(transfer);
            assertEquals(transfer, QiAccounting.select(account, transfer));
        }
        var transfers = plan.stream().map(claim -> new QiInventory(List.of(claim.qi()))).toList();
        QiInventory combined = QiInventory.combine(transfers);
        return QiAccounting.debit(account, QiAccounting.select(account, combined));
    }

    private static QiInventory inventory(double amount) {
        // 所有测试使用同一真实相态，避免密度变化影响数量边界。
        return new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, amount, DENSITY)));
    }

    private static ReleasedQiAllocation.Claim claim(String source, double amount) {
        // 区域意图只提供数量，实际密度必须来自共享账户。
        return new ReleasedQiAllocation.Claim(source, inventory(amount).entries().get(0));
    }
}
