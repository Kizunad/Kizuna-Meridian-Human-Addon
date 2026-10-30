package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageSpillover;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只计算部位明确剂量；最终扣血与死亡仍由同一个 Framework 身体事务提交。 */
final class HumanDamageSpillover {
    private HumanDamageSpillover() {
        // 规则计算不保存跨伤害事件的中间状态。
    }

    static DamageSpillover distribute(BodySnapshot body, HealthTarget source, double overflow, double multiplier) {
        // 来源系数只乘一次，后续饱和重新分配不再使用接收部位的系数。
        double amount = overflow * multiplier;
        if (!Double.isFinite(amount)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        List<Receiver> receivers = receivers(body, source.getPartId());
        Map<String, Double> losses = new LinkedHashMap<>();
        double unabsorbed = allocate(receivers, amount, losses);
        return DamageSpillover.newBuilder()
                .setSource(source)
                .setOverflowHp(overflow)
                .setMultiplier(multiplier)
                .addAllLosses(losses.entrySet().stream().map(entry -> loss(entry.getKey(), entry.getValue())).toList())
                .setUnabsorbedHp(unabsorbed)
                .build();
    }

    private static List<Receiver> receivers(BodySnapshot body, String sourceId) {
        // 权重来自当前实际构型上限，已毁部位不接收，但仍可作为下一次命中的来源。
        Map<String, Double> hp = new LinkedHashMap<>();
        body.getPartStatesList().forEach(state -> hp.put(state.getId(), state.getHp()));
        List<Receiver> receivers = new ArrayList<>();
        for (var part : body.getStructure().getPartsList()) {
            double remaining = hp.get(part.getId());
            if (!part.getId().equals(sourceId) && remaining > 0) {
                receivers.add(new Receiver(part.getId(), part.getMaxHp(), remaining));
            }
        }
        return receivers;
    }

    private static double allocate(List<Receiver> receivers, double amount, Map<String, Double> losses) {
        // 每轮先扣满会饱和的接收方；至少移除一个部位，避免微小余量造成无限循环。
        double remaining = amount;
        while (remaining > 0 && !receivers.isEmpty()) {
            double maximum = receivers.stream().mapToDouble(Receiver::maximum).max().orElseThrow();
            double weight = receivers.stream().mapToDouble(receiver -> receiver.maximum() / maximum).sum();
            List<Receiver> saturated = new ArrayList<>();
            for (Receiver receiver : receivers) {
                double share = remaining * (receiver.maximum() / maximum / weight);
                if (share >= receiver.hp()) {
                    saturated.add(receiver);
                }
            }
            if (saturated.isEmpty()) {
                distributeRemaining(receivers, remaining, maximum, weight, losses);
                return 0;
            }
            for (Receiver receiver : saturated) {
                losses.put(receiver.id(), receiver.hp());
                remaining = Math.max(0, remaining - receiver.hp());
            }
            receivers.removeAll(saturated);
        }
        return remaining;
    }

    private static void distributeRemaining(List<Receiver> receivers, double remaining,
                                             double maximum, double weight, Map<String, Double> losses) {
        // 正常一轮按上限比例分完；下溢至零的剂量不进入只接受正数的协议。
        for (Receiver receiver : receivers) {
            double share = remaining * (receiver.maximum() / maximum / weight);
            if (share > 0) {
                losses.put(receiver.id(), share);
            }
        }
    }

    static HpLoss loss(String partId, double hp) {
        // 目标先独立构造，保证每个部位只出现一份最终剂量。
        HealthTarget target = HealthTarget.newBuilder().setPartId(partId).build();
        return HpLoss.newBuilder().setTarget(target).setHp(hp).build();
    }

    private record Receiver(String id, double maximum, double hp) { }
}
