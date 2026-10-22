package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import java.util.List;
import static dev.kizuna.meridian.human.server.HumanCultivationNumbers.*;

/** 脾的慢速沉淀与储备动员；原料、加工和产物始终使用框架账户。 */
final class HumanReserves {
    static final String ACTION = "human.reserve";

    private HumanReserves() {
        // 生理规则只产生受校验计划，器官加工完成仍由 Framework 时间事件执行。
    }

    static boolean needsAdvance(BodySnapshot body, CultivationState state) {
        // 加工由框架自己的到时事件唤醒；壁满、无原料或器官失能时等待资源变化。
        var spleen = body.organStates().stream().filter(item -> item.id().value().equals("spleen")).findFirst().orElseThrow();
        var abdomen = body.partStates().stream().filter(item -> item.id().value().equals("abdomen")).findFirst().orElseThrow();
        if (spleen.hp() <= 0 || abdomen.hp() <= 0) {
            return false;
        }
        var plan = new HumanQiPlan(body);
        double occupancy = body.meridianStates().stream().mapToDouble(item -> item.lumenQi().volume()).sum()
                / body.structure().lumenVolume();
        // 调度与实际截取共用过滤规则，避免仅有不可转移尾差时持续空转。
        if (occupancy < WITHDRAW_THRESHOLD && body.meridianStates().stream()
                .anyMatch(item -> !plan.available("w:" + item.id().value(), Double.MAX_VALUE,
                        plan.freeLumen(item.id().value())).entries().isEmpty())) {
            return true;
        }
        boolean wallSpace = body.structure().meridians().stream().anyMatch(item ->
                plan.get("w:" + item.id().value()).volume() < item.wallVolume() - 1e-8);
        if (!wallSpace || spleen.process() != null) {
            return false;
        }
        if (state.getReserveProcessing()) {
            return body.structure().meridians().stream().anyMatch(item -> !plan.available("o:spleen",
                    Double.MAX_VALUE, Math.max(0, item.wallVolume() - plan.get("w:" + item.id().value()).volume()))
                    .entries().isEmpty());
        }
        return occupancy >= DEPOSIT_THRESHOLD && spleen.buffer().totalQi() == 0
                && (plan.get("l:left_spleen_meridian").totalQi() > 1e-8
                || plan.get("l:right_spleen_meridian").totalQi() > 1e-8);
    }

    static void advance(HumanQiPlan plan, CultivationState.Builder state, double seconds) {
        // 器官或归属腹部失能时原料留存，不能凭自动规则继续获得产物。
        var spleen = plan.body.organStates().stream().filter(item -> item.id().value().equals("spleen")).findFirst().orElseThrow();
        var abdomen = plan.body.partStates().stream().filter(item -> item.id().value().equals("abdomen")).findFirst().orElseThrow();
        if (spleen.hp() <= 0 || abdomen.hp() <= 0) {
            return;
        }
        if (state.getReserveProcessing() && spleen.process() == null) {
            // 已完成产物按各壁空余体积依次入账，空间不足则继续留在脾缓存。
            for (var definition : plan.body.structure().meridians()) {
                String wall = "w:" + definition.id().value();
                double free = Math.max(0, definition.wallVolume() - plan.get(wall).volume());
                QiInventory output = plan.take("o:spleen", Double.MAX_VALUE, free);
                plan.add(wall, output);
            }
            if (plan.get("o:spleen").entries().isEmpty()) {
                state.setReserveProcessing(false);
            }
        }
        double occupancy = plan.body.meridianStates().stream().mapToDouble(item -> plan.get("l:" + item.id().value()).volume()).sum()
                / plan.body.structure().lumenVolume();
        if (!state.getBreakthrough() && (state.getMobilizing() || occupancy < WITHDRAW_THRESHOLD)) {
            double rate = state.getMobilizing() ? ACTIVE_WITHDRAW_PER_SECOND : PASSIVE_WITHDRAW_PER_SECOND;
            withdraw(plan, rate * seconds, false);
        }
        if (occupancy < DEPOSIT_THRESHOLD || state.getMobilizing() || state.getBreakthrough()
                || state.getReserveProcessing() || spleen.process() != null || plan.get("o:spleen").totalQi() > 0) {
            return;
        }
        double freeWalls = plan.body.structure().meridians().stream()
                .mapToDouble(item -> Math.max(0, item.wallVolume() - plan.get("w:" + item.id().value()).volume())).sum();
        if (freeWalls <= 0) {
            return;
        }
        // 脾经的真实库存进入脾，避免任意远处账户瞬移绕过人体通路。
        QiInventory input = QiInventory.empty();
        for (String side : List.of("left", "right")) {
            String source = "l:" + side + "_spleen_meridian";
            QiInventory selected = plan.take(source, DEPOSIT_BATCH - input.totalQi(), Math.min(100, freeWalls / DEPOSIT_RETENTION) - input.volume());
            input = QiInventory.combine(List.of(input, selected));
        }
        if (input.entries().isEmpty()) {
            return;
        }
        plan.add("o:spleen", input);
        QiInventory output = new QiInventory(input.entries().stream()
                .map(qi -> qi.withAmount(qi.amount() * DEPOSIT_RETENTION)).toList());
        var recipe = BodyMessages.OrganRecipe.newBuilder().setId("human:spleen_reserve").setVersion(1)
                .setOrganKind("human:spleen").setInput(BodyProtobuf.toInventory(input)).setOutput(BodyProtobuf.toInventory(output))
                .setDurationMillis(DEPOSIT_MILLIS).setMinimumRealm(BodyMessages.Realm.WANDERING_QI).build();
        plan.activity.addRecipes(ActivityRecipe.newBuilder().setOrganId("spleen").setRecipe(recipe));
        state.setReserveProcessing(true);
    }

    static double withdraw(HumanQiPlan plan, double maximum, boolean fuel) {
        // 燃料直接消耗；普通动员回本脉内腔，拥挤时保留在外壁而不挤爆。
        double withdrawn = 0;
        for (var definition : plan.body.structure().meridians()) {
            String id = definition.id().value();
            double free = fuel ? Double.MAX_VALUE : plan.freeLumen(id);
            QiInventory cargo = plan.take("w:" + id, maximum - withdrawn, free);
            withdrawn += cargo.totalQi();
            if (fuel) {
                plan.release(cargo.totalQi());
            } else {
                plan.add("l:" + id, cargo);
            }
            if (withdrawn >= maximum - 1e-10) {
                break;
            }
        }
        return withdrawn;
    }
}
