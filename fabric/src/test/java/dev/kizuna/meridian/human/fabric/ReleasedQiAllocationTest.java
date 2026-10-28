package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import org.junit.Test;

import java.util.List;

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
