package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 将区域归位意图拆成严格可扣转的实际明细；末笔承接共享账户的舍入余量。 */
final class ReleasedQiAllocation {
    record Kind(Qi.QiPhase phase, Qi.QiAttribute attribute) {
        static Kind of(Qi qi) {
            // 密度已在共享账户混合，匹配键仅使用框架规定的相态与属性。
            return new Kind(qi.phase(), qi.attribute());
        }
    }

    record Claim(String sourceId, Qi qi) { }

    private ReleasedQiAllocation() {
        // 分配器只算候选，验证和执行必须使用同一份候选数量。
    }

    static List<Claim> plan(QiInventory account, List<Claim> claims) {
        // 用精确二进制值求和，避免校验总量和实际拆分各自再累计一次尾差。
        var sorted = claims.stream().sorted(Comparator.comparingDouble(claim -> claim.qi().amount())).toList();
        Kind requestedKind = Kind.of(sorted.get(0).qi());
        Qi available = account.entries().stream()
                .filter(entry -> Kind.of(entry).equals(requestedKind))
                .findFirst().orElseThrow(() -> HumanErrors.hostRejected(FailureCode.INSUFFICIENT_QI));
        BigDecimal requested = BigDecimal.ZERO;
        boolean allTransferable = true;
        for (Claim claim : sorted) {
            requested = requested.add(new BigDecimal(claim.qi().amount()));
            allTransferable &= claim.qi().amount() >= QiAccounting.MIN_TRANSFER_QI;
        }
        BigDecimal difference = new BigDecimal(available.amount()).subtract(requested).abs();
        // 每笔浮点合并和扣转允许一个 ULP 的表示误差；数量级之外的缺账仍然拒绝。
        BigDecimal roundingBound = new BigDecimal(Math.ulp(available.amount()))
                .multiply(BigDecimal.valueOf(sorted.size() + 1L));
        boolean exhaustsAccount = allTransferable && difference.compareTo(roundingBound) <= 0;
        return split(available, sorted, exhaustsAccount ? Allocation.EXHAUST : Allocation.PRESERVE_REMAINDER);
    }

    private enum Allocation { EXHAUST, PRESERVE_REMAINDER }

    private static List<Claim> split(Qi available, List<Claim> sorted, Allocation allocation) {
        // 先转小额、最后转最大份额，避免舍入修正把一笔最小单位变成不可流转尘埃。
        var planned = new ArrayList<Claim>();
        double allocated = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Claim claim = sorted.get(index);
            if (claim.qi().amount() < QiAccounting.MIN_TRANSFER_QI) {
                // 小于单位的真实余额留在原账户与队列，等待同区域后续释放合并。
                continue;
            }
            double amount;
            if (allocation == Allocation.EXHAUST && index == sorted.size() - 1) {
                amount = Math.max(QiAccounting.MIN_TRANSFER_QI, available.amount() - allocated);
            } else {
                amount = claim.qi().amount();
            }
            QiInventory requested = new QiInventory(List.of(available.withAmount(amount)));
            QiInventory selected = QiAccounting.select(new QiInventory(List.of(available)), requested);
            planned.add(new Claim(claim.sourceId(), selected.entries().get(0)));
            allocated += amount;
        }
        if (allocation == Allocation.EXHAUST && allocated != available.amount()) {
            throw HumanErrors.hostRejected(FailureCode.NUMERIC_LIMIT);
        }
        validateBatch(available, planned);
        return List.copyOf(planned);
    }

    private static void validateBatch(Qi available, List<Claim> planned) {
        // 复算 Store 的批量合并、选取和扣款校验；余额或精度不满足时抛出 Framework 拒绝。
        if (planned.isEmpty()) {
            return;
        }
        var inventories = planned.stream().map(claim -> new QiInventory(List.of(claim.qi()))).toList();
        QiInventory account = new QiInventory(List.of(available));
        QiInventory debit = QiAccounting.select(account, QiInventory.combine(inventories));
        // 预检必须实际执行严格扣款；失败会抛出 Framework 的 TransportRejected。
        QiAccounting.debit(account, debit);
    }
}
