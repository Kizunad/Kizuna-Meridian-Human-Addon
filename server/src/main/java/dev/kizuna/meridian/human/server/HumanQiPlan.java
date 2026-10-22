package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一次人体操作的临时候选；仅生成 Protobuf 意图，不发布身体状态。 */
final class HumanQiPlan {
    final BodySnapshot body;
    final BodyActivity.Builder activity = BodyActivity.newBuilder();
    private final Map<String, QiInventory> original = new LinkedHashMap<>();
    private final Map<String, QiInventory> balances = new LinkedHashMap<>();
    private double released;

    HumanQiPlan(BodySnapshot body) {
        // 候选账户与身体明细一一对应，同一步多次触及账户最终只提交一项变更。
        this.body = body;
        for (MeridianState state : body.meridianStates()) {
            original.put("l:" + state.id().value(), state.lumenQi());
            original.put("w:" + state.id().value(), state.wallQi());
        }
        body.organStates().forEach(state -> original.put("o:" + state.id().value(), state.buffer()));
        balances.putAll(original);
    }

    QiInventory get(String key) {
        // ID 来自人体构型，遗漏模块视为装配错误，不生成空账户兜底。
        return java.util.Objects.requireNonNull(balances.get(key));
    }

    void put(String key, QiInventory inventory) {
        // 只能替换已存在的受控账户，最终体积和总账由 Framework 重验。
        get(key);
        balances.put(key, inventory);
    }

    QiInventory take(String key, double amount, double volume) {
        // 每种明细先过滤浮点尾差再扣源，不能因合计够大就搬运其中不可计量的小项。
        QiInventory selected = available(key, amount, volume);
        if (selected.totalQi() > 0) {
            put(key, QiAccounting.debit(get(key), selected));
        }
        return selected;
    }

    QiInventory available(String key, double amount, double volume) {
        // 查询与执行共用最小转移量；不足部分保留原密度与余额，不删除、不当作损耗。
        return new QiInventory(get(key).take(amount, volume).entries().stream()
                .filter(qi -> qi.amount() >= HumanCultivationNumbers.MIN_TRANSFER_QI).toList());
    }

    void add(String key, QiInventory inventory) {
        // 跨账户合并使用框架的体积守恒公式，不在附属重写混合算法。
        if (inventory.totalQi() > 0) {
            put(key, QiAccounting.credit(get(key), inventory));
        }
    }

    double freeLumen(String id) {
        // 普通运输和闭环返回都已占位，不能用它们暂时空出的空间沉淀后再塞回。
        var definition = body.structure().meridians().stream().filter(item -> item.id().value().equals(id)).findFirst().orElseThrow();
        var ports = definition.ports().stream().map(Anatomy.Port::id).toList();
        var incoming = body.structure().connections().stream().filter(item -> ports.contains(item.to()))
                .map(Anatomy.Connection::id).toList();
        double reserved = body.transits().stream().filter(item -> incoming.contains(item.connectionId()))
                .mapToDouble(item -> item.cargo().volume()).sum();
        return Math.max(0, definition.lumenVolume() - get("l:" + id).volume() - reserved);
    }

    void release(double amount) {
        // 杂气和燃料统一回环境；这份明细与资源扣账一起提交。
        released += amount;
    }

    BodyActivity build() {
        // 保持稳定的构型顺序，便于回执复算和跨语言比较。
        for (var entry : balances.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue().equals(original.get(key))) {
                continue;
            }
            var change = QiAccountChange.newBuilder().setExpected(BodyProtobuf.toInventory(original.get(key)))
                    .setReplacement(BodyProtobuf.toInventory(entry.getValue()));
            switch (key.charAt(0)) {
                case 'l' -> change.setLumenId(key.substring(2));
                case 'w' -> change.setWallId(key.substring(2));
                case 'o' -> change.setOrganId(key.substring(2));
                default -> throw HumanErrors.invalid(HumanErrors.INVALID_CULTIVATION);
            }
            activity.addAccounts(change);
        }
        if (released > 0) {
            activity.setReleasedQi(BodyProtobuf.toInventory(new QiInventory(java.util.List.of(new Qi(Qi.QiAttribute.NONE, released, .01)))));
        }
        return activity.build();
    }
}
