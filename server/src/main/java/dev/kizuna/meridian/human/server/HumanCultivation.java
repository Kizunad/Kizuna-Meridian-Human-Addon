package dev.kizuna.meridian.human.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.human.pb.CultivationMessages.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static dev.kizuna.meridian.human.server.HumanCultivationNumbers.*;

/** 中焦周天的可信人体策略；只生成一次原子计划，真元仍由 Framework 保存。 */
public final class HumanCultivation {
    public static final String PROGRESS = "human:cultivation";
    public static final String CIRCUIT = "human-middle-jiao";
    public static final String ACTION = "human.circuit";
    public static final List<String> MERIDIANS = List.of("left_lung_meridian", "left_large_intestine_meridian",
            "right_stomach_meridian", "right_spleen_meridian", "right_lung_meridian", "right_large_intestine_meridian",
            "left_stomach_meridian", "left_spleen_meridian");

    private HumanCultivation() {
        // 路线、功率和突破门槛都属于人体，Framework 不识别这些 ID。
    }

    public static CultivationState state(BodySnapshot body) {
        // 首次访问只产生未启动视图；进度随后随身体事务持久化。
        Any progress = body.addonProgress().get(PROGRESS);
        CultivationState state;
        if (progress == null) {
            state = CultivationState.newBuilder().setSchemaVersion(1)
                    .setLastMillis(body.settledAt().elapsedMillis()).build();
        } else {
            try {
                state = progress.unpack(CultivationState.class);
                if (state.getSchemaVersion() != 1) {
                    throw HumanErrors.invalid(HumanErrors.INVALID_CULTIVATION);
                }
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw HumanErrors.invalid(HumanErrors.INVALID_CULTIVATION);
            }
        }

        // 展示参数随每次读取由当前人体配置重算，旧进度无需迁移也能提供完整 UI 投影。
        return state.toBuilder()
                .setBrainStaminaRatio(brainStaminaRatio(body, 0))
                .setAutomaticMinimumStaminaRatio(AUTOMATIC_MIN_STAMINA_RATIO)
                .setAutomaticEfficiency(AUTOMATIC_EFFICIENCY)
                .setBreathCycleMillis(BREATH_CYCLE_MILLIS)
                .setExhaleStartMillis(EXHALE_START_MILLIS)
                .setGateRetryMillis(GATE_RETRY_MILLIS)
                .build();
    }

    public static BodyActivity update(BodySnapshot body, CultivationInput input) {
        // 输入事件与 250ms 生理边界共用计算，不依赖帧数或客户端报告的经过时长。
        long now = body.settledAt().elapsedMillis();
        var state = state(body).toBuilder();
        boolean wasBreakingThrough = state.getBreakthrough();
        boolean wasMobilizing = state.getMobilizing();
        // 活动期间宿主按250ms边界推进；休眠后余额刚变动时只结当前边界，不追补空闲期。
        double seconds = Math.min(STEP_MILLIS, Math.max(0, now - state.getLastMillis())) / 1000.0;
        var plan = new HumanQiPlan(body);
        state.setLastMillis(now).setExhaling(now % BREATH_CYCLE_MILLIS >= EXHALE_START_MILLIS)
                .setGateWindowMillis(GATE_WINDOW_MILLIS);
        QiTransit transit = body.transits().stream().filter(item -> item.id().equals(CIRCUIT)).findFirst().orElse(null);

        if (state.getRunning() && seconds > 0) {
            if (state.getAutomatic() && brainStaminaRatio(body, 0) < AUTOMATIC_MIN_STAMINA_RATIO) {
                stop(plan, state, transit, "脑耐力低于80%，自动周天已停止");
                transit = null;
            } else if (!available(body, "brain", BRAIN_WATTS, seconds)
                    || !state.getAutomatic() && !attentionAvailable(body, ATTENTION_WATTS, seconds)) {
                stop(plan, state, transit, "脑耐力或精力不足");
                transit = null;
            } else if (!openedAndIntact(body)) {
                stop(plan, state, transit, "周天通路受损");
                transit = null;
            } else {
                effort(plan, "brain", BRAIN_WATTS, seconds, false);
                // 自动只占用脑耐力；主动动员的精力费用仍在下方独立结算。
                if (!state.getAutomatic()) {
                    effort(plan, "attention_network", ATTENTION_WATTS, seconds, true);
                }
                transit = advanceCircuit(plan, state, transit, seconds, now);
                if (state.getAutomatic() && brainStaminaRatio(body, BRAIN_WATTS * seconds) < AUTOMATIC_MIN_STAMINA_RATIO) {
                    stop(plan, state, transit, "脑耐力低于80%，自动周天已停止");
                    transit = null;
                }
            }
        }
        if (input != null && input.getSequence() > state.getLastSequence()) {
            // 序号拒绝重放；按键 tick 仅检查新鲜度，实际命中始终使用当前服务端时刻。
            state.setLastSequence(input.getSequence());
            transit = input(plan, state, transit, input, now);
        }

        if (wasMobilizing && state.getMobilizing() && seconds > 0) {
            if (!available(body, "spleen", MOBILIZE_WATTS, seconds)) {
                // 超载伤脾走既有健康结果，不直接改写器官 HP。
                var spleen = body.organStates().stream().filter(item -> item.id().value().equals("spleen")).findFirst().orElseThrow();
                if (spleen.hp() > 0) {
                    plan.activity.setDamage(HealthMessages.DamageResolution.newBuilder().setLethal(false)
                            .addLosses(HealthMessages.HpLoss.newBuilder().setTarget(BodyMessages.HealthTarget.newBuilder().setOrganId("spleen"))
                                    .setHp(Math.min(spleen.hp(), SPLEEN_OVERLOAD_HP))));
                }
                state.setMobilizing(false);
                failBreakthrough(state, "脾疲劳过载，停止动员");
            } else if (!attentionAvailable(body, (state.getRunning() && !state.getAutomatic() ? ATTENTION_WATTS : 0)
                    + MOBILIZE_ATTENTION_WATTS, seconds)) {
                // 精力不足只停止主动控制，不把注意组织疲劳误算为脾损伤。
                state.setMobilizing(false).setReason("精力不足，停止动员");
                failBreakthrough(state, "精力不足，停止动员");
            } else {
                effort(plan, "spleen", MOBILIZE_WATTS, seconds, false);
                effort(plan, "attention_network", MOBILIZE_ATTENTION_WATTS, seconds, true);
            }
        }
        if (state.getBreakthrough()) {
            breakthrough(plan, state, transit, wasBreakingThrough ? seconds : 0);
        }
        // 新按下动员只建立当前动作，不能追溯提取或扣除之前的空闲时间。
        HumanReserves.advance(plan, state, state.getMobilizing() && !wasMobilizing ? 0 : seconds);
        syncAction(plan, HumanReserves.ACTION, state.getMobilizing(), true);
        syncAction(plan, ACTION, state.getRunning(), !state.getAutomatic());
        plan.activity.putProgress(PROGRESS, Any.pack(state.build()));
        return plan.build();
    }

    public static BodyActivity cancel(BodySnapshot body) {
        // 离线、重置和关闭会话立即撤销活动，不把离线时长追溯算作做功或突破。
        var plan = new HumanQiPlan(body);
        var state = state(body).toBuilder().setLastMillis(body.settledAt().elapsedMillis());
        QiTransit transit = body.transits().stream().filter(item -> item.id().equals(CIRCUIT)).findFirst().orElse(null);
        stop(plan, state, transit, "会话结束");
        syncAction(plan, ACTION, false, true);
        syncAction(plan, HumanReserves.ACTION, false, true);
        plan.activity.putProgress(PROGRESS, Any.pack(state.build()));
        return plan.build();
    }

    public static boolean needsAdvance(BodySnapshot body) {
        // 静止且没有储备交换的身体不轮询；活动和生理阈值变化才安排有限事件。
        CultivationState state = state(body);
        if (state.getRunning() || state.getMobilizing()) {
            return true;
        }
        return HumanReserves.needsAdvance(body, state);
    }

    private static QiTransit input(HumanQiPlan plan, CultivationState.Builder state, QiTransit transit,
                                    CultivationInput input, long now) {
        // 只接受语义明确的控制边沿，不把客户端坐标、密度或判定结果当作输入。
        switch (input.getOperation()) {
            case START_MANUAL, START_AUTOMATIC -> {
                boolean automatic = input.getOperation() == CultivationInput.Operation.START_AUTOMATIC;
                double spentBrainJoules = plan.activity.getEffortsList().stream()
                        .filter(effort -> effort.getOrganId().equals("brain"))
                        .mapToDouble(effort -> effort.getWatts() * effort.getSeconds()).sum();
                // 同一输入可能刚结清旧区间；启动线必须包括本事务已承诺的费用。
                if (automatic && brainStaminaRatio(plan.body, spentBrainJoules) < AUTOMATIC_MIN_STAMINA_RATIO) {
                    state.setReason("脑耐力低于80%，请先休息再开始自动周天");
                    return transit;
                }
                if (state.getRunning()) {
                    // 切换控制方式不重置本圈货物和关口，防止反复切换领取收益。
                    state.setAutomatic(automatic).setPushing(false).setThrust(1);
                    return transit;
                }
                if (!openedAndIntact(plan.body) || plan.body.openingTask() != null && plan.body.openingTask().active()) {
                    state.setReason("需要完整开通中焦八脉，且没有正在开脉");
                    return transit;
                }
                state.setRunning(true).setAutomatic(automatic).setPushing(false).setThrust(1)
                        .setGateIndex(0).setGateHits(0).setGateRetryUntil(0).setReason("");
            }
            case STOP -> {
                stop(plan, state, transit, "已停止周天");
                transit = null;
            }
            case PUSH_ON -> state.setPushing(true);
            case PUSH_OFF -> state.setPushing(false);
            case STRIKE_GATE -> {
                if (transit == null || !state.getRunning() || state.getAutomatic()) {
                    return transit;
                }
                long serverTick = now / 50;
                if (input.getKeyTick() > serverTick || serverTick - input.getKeyTick() > INPUT_MAX_AGE_TICKS) {
                    state.setReason("冲关按键已过期");
                    return transit;
                }
                boolean ready = state.getGateRetryUntil() > 0 && state.getExhaling()
                        && Math.abs(now - state.getGateRetryUntil()) <= GATE_WINDOW_MILLIS;
                if (ready) {
                    state.setGateIndex(state.getGateIndex() + 1).setGateHits(state.getGateHits() + 1)
                            .setPracticedGates(state.getPracticedGates() + 1).setGateRetryUntil(0)
                            .setGateSuccess(true).setFeedbackUntil(now + FEEDBACK_MILLIS).setReason("");
                } else {
                    if (attentionAvailable(plan.body, GATE_MISS_JOULES, 1)) {
                        effort(plan, "attention_network", GATE_MISS_JOULES, 1, true);
                    }
                    double work = Math.max(0, transit.circuit().getWorkMillis() - GATE_KNOCKBACK_MILLIS);
                    transit = withProgress(transit, work, transit.cargo());
                    replace(plan, transit);
                    state.setGateRetryUntil(0).setGateSuccess(false).setFeedbackUntil(now + FEEDBACK_MILLIS).setReason("时机不合，真元撞回");
                }
            }
            case MOBILIZE_ON -> state.setMobilizing(true);
            case MOBILIZE_OFF -> {
                state.setMobilizing(false);
                failBreakthrough(state, "主动动员中断");
            }
            case START_BREAKTHROUGH -> {
                if (state.getBreakthrough()) {
                    // 重复点击保持正在进行的连续计时，不重置已燃烧的这一秒。
                    return transit;
                }
                if (plan.body.realm() != Realm.WANDERING_QI || !state.getRunning() || !openedAndIntact(plan.body)
                        || candidateQi(plan, transit) < CANDIDATE_QI || plan.body.wallQiTotal() < STARTING_WALL_QI) {
                    state.setReason("突破需八脉全开、运转周天、候选至少50元、外壁至少60元");
                    return transit;
                }
                state.setBreakthrough(true).setBreakthroughSeconds(0).setFuelSeconds(0).setMobilizing(true).setReason("");
            }
            case UNSPECIFIED, UNRECOGNIZED -> state.setReason("未知周天操作");
        }
        return transit;
    }

    private static QiTransit advanceCircuit(HumanQiPlan plan, CultivationState.Builder state, QiTransit transit,
                                             double seconds, long now) {
        // 等已有普通运输自然完成后才取得整圈吞吐，不提前兑现或删掉其他运输。
        if (transit == null) {
            return launch(plan, state, now);
        }
        double thrust = state.getAutomatic() ? 1 : Math.max(0, Math.min(2,
                state.getThrust() + seconds * (state.getPushing() ? PUSH_GAIN_PER_SECOND : -PUSH_DECAY_PER_SECOND)));
        state.setThrust(thrust);
        int segment = Math.min(7, (int) (transit.circuit().getWorkMillis() / transit.circuit().getDurationMillis() * 8));
        String segmentId = circuitMeridians(plan.body, transit).get(segment);
        boolean distal = segmentId.contains("lung_") || segmentId.contains("stomach_");
        double safeMin = distal ? DISTAL_SAFE_MIN : SAFE_MIN;
        double safeMax = distal ? DISTAL_SAFE_MAX : SAFE_MAX;
        QiInventory cargo = transit.cargo();
        if (thrust > safeMax) {
            String damaged = circuitMeridians(plan.body, transit).get(segment);
            plan.activity.addMeridianInjuries(HealthMessages.MeridianInjury.newBuilder().setMeridianId(damaged)
                    .setCondition(BodyMessages.MeridianCondition.LEAKING));
            stop(plan, state, transit, "推力过猛，经脉渗漏");
            return null;
        }
        if (thrust < safeMin) {
            double retained = Math.max(0, 1 - LEAK_FRACTION_PER_SECOND * seconds);
            cargo = new QiInventory(cargo.entries().stream().map(qi -> qi.withAmount(qi.amount() * retained)).toList());
            plan.release(transit.cargo().totalQi() - cargo.totalQi());
        }
        double work = Math.min(transit.circuit().getDurationMillis(), transit.circuit().getWorkMillis() + seconds * 1000 * thrust);
        List<Integer> gateSegments = new ArrayList<>();
        List<String> modules = circuitMeridians(plan.body, transit);
        for (int index = 0; index < modules.size(); index++) {
            if (modules.get(index).contains("large_intestine_") || modules.get(index).contains("spleen_")) {
                gateSegments.add(index + 1);
            }
        }
        double gateWork = state.getGateIndex() < 4
                ? transit.circuit().getDurationMillis() * gateSegments.get(state.getGateIndex()) / 8.0
                : transit.circuit().getDurationMillis();
        if (state.getGateIndex() < 4 && work >= gateWork) {
            work = gateWork;
            if (state.getGateRetryUntil() == 0) {
                state.setGateRetryUntil(now + GATE_RETRY_MILLIS);
            }
            if (state.getAutomatic()) {
                // 熟练后的自动节奏省去手动判定，提纯系数仍只有基础上限的一半。
                state.setGateIndex(state.getGateIndex() + 1).setGateRetryUntil(0);
            } else if (now > state.getGateRetryUntil() + GATE_WINDOW_MILLIS) {
                state.setGateRetryUntil(now + GATE_RETRY_MILLIS);
            }
        }
        transit = withProgress(transit, work, cargo);
        if (state.getGateIndex() >= 4 && work >= transit.circuit().getDurationMillis()) {
            double efficiency = AUTOMATIC_EFFICIENCY + (1 - AUTOMATIC_EFFICIENCY) * Math.min(4, state.getGateHits()) / 4.0;
            QiInventory purified = purify(cargo, efficiency);
            plan.release(cargo.totalQi() - purified.totalQi());
            double oldDensity = cargo.totalQi() / cargo.volume();
            state.setDensityGain(purified.totalQi() / purified.volume() - oldDensity)
                    .setLaps(state.getLaps() + 1).setGateIndex(0).setGateHits(0).setGateRetryUntil(0);
            // 同一独立批次持续循环，不在每圈临时回填而与低密度库存混合。
            transit = withProgress(transit, 0, purified);
            double duration = CircuitTransport.durationMillis(plan.body.structure(), transit.circuit().getConnectionIdsList(),
                    transit.circuit().getInternalDistanceMeters(), purified.totalQi(), transit.throughputPerSecond());
            transit = new QiTransit(transit.id(), transit.connectionId(), transit.cargo(), now + (long) duration,
                    transit.throughputPerSecond(), transit.status(), transit.circuit().toBuilder().setDurationMillis(duration).build());
        }
        replace(plan, transit);
        return transit;
    }

    public static QiInventory purify(QiInventory cargo, double efficiency) {
        // 使用实际密度增量算损耗；抵达上限后继续运转不再扣提纯损耗。
        var refined = new ArrayList<Qi>();
        for (Qi qi : cargo.entries()) {
            if (qi.density() >= MAX_DENSITY) {
                refined.add(qi);
                continue;
            }
            double x = StrictMath.log10(qi.density() / .01);
            double requestedGain = DENSITY_GAIN * (1 - DENSITY_SLOWDOWN * x) * Math.max(0, Math.min(1, efficiency));
            double density = Math.min(MAX_DENSITY, qi.density() * StrictMath.pow(10, requestedGain));
            double actualGain = StrictMath.log10(density / qi.density());
            double amount = qi.amount() * StrictMath.pow(10, -LOSS_EXPONENT * actualGain);
            refined.add(new Qi(qi.attribute(), amount, density));
        }
        return new QiInventory(refined);
    }

    private static QiTransit launch(HumanQiPlan plan, CultivationState.Builder state, long now) {
        // 优先有货的经脉作为闭环起终点，取走的体积原位预约，满身也能安全开环。
        if (plan.body.transits().stream().anyMatch(item -> item.circuit() == null)) {
            state.setReason("等待已有运输抵达");
            return null;
        }
        String origin = MERIDIANS.stream().max(Comparator.comparingDouble(id -> plan.get("l:" + id).totalQi())).orElseThrow();
        // 先检查可启动批次再扣源，停止启动不能遗失尚未进入在途的微量余额。
        if (plan.available("l:" + origin, BATCH_QI, Double.MAX_VALUE).totalQi() < 1e-6) {
            state.setRunning(false).setReason("内腔没有可运转真元");
            return null;
        }
        QiInventory cargo = plan.take("l:" + origin, BATCH_QI, Double.MAX_VALUE);
        List<String> route = route(plan.body, origin);
        double duration = CircuitTransport.durationMillis(plan.body.structure(), route, INTERNAL_METERS, cargo.totalQi(), THROUGHPUT);
        var circuit = BodyMessages.ControlledCircuit.newBuilder().addAllConnectionIds(route)
                .setInternalDistanceMeters(INTERNAL_METERS).setDurationMillis(duration).build();
        var transit = new QiTransit(CIRCUIT, new Anatomy.ConnectionId(route.get(7)), cargo,
                now + (long) duration, THROUGHPUT, QiTransit.Status.MOVING, circuit);
        state.setReason("");
        replace(plan, transit);
        return transit;
    }

    private static void breakthrough(HumanQiPlan plan, CultivationState.Builder state, QiTransit transit, double seconds) {
        // 外壁60元仅在启动检查；之后每个整数秒先检查再扣1元，最后一秒不会误要求仍有60元。
        if (!state.getRunning() || !state.getMobilizing() || transit == null || candidateQi(plan, transit) + 1e-9 < CANDIDATE_QI) {
            failBreakthrough(state, "周天中断、候选不足或动员中断");
            return;
        }
        double fuelSeconds = state.getFuelSeconds() + seconds;
        while (fuelSeconds >= 1 && state.getBreakthroughSeconds() < BREAKTHROUGH_SECONDS) {
            double wallQi = plan.body.meridianStates().stream().mapToDouble(item -> plan.get("w:" + item.id().value()).totalQi()).sum();
            if (wallQi + 1e-9 < FUEL_PER_SECOND) {
                failBreakthrough(state, "外壁燃料断供");
                return;
            }
            HumanReserves.withdraw(plan, FUEL_PER_SECOND, true);
            fuelSeconds -= 1;
            state.setBreakthroughSeconds(state.getBreakthroughSeconds() + 1);
        }
        state.setFuelSeconds(fuelSeconds);
        if (state.getBreakthroughSeconds() < BREAKTHROUGH_SECONDS) {
            return;
        }
        for (MeridianState meridian : plan.body.meridianStates()) {
            String key = "l:" + meridian.id().value();
            plan.put(key, phaseChange(plan.get(key)));
        }
        replace(plan, withProgress(transit, transit.circuit().getWorkMillis(), phaseChange(transit.cargo())));
        plan.activity.setRealm(BodyMessages.Realm.TRUE_QI);
        state.setBreakthrough(false).setMobilizing(false).setReason("突破成功：真炁");
    }

    private static QiInventory phaseChange(QiInventory inventory) {
        // 同属性的相变候选归并到气态，原有低密度条目仍保留游离态。
        return QiInventory.combine(inventory.entries().stream().map(qi -> new QiInventory(List.of(
                qi.density() >= CANDIDATE_DENSITY && qi.density() < .1 ? new Qi(qi.attribute(), qi.amount(), .1) : qi))).toList());
    }

    private static double candidateQi(HumanQiPlan plan, QiTransit transit) {
        // 候选只统计内腔和这一批周天在途，外壁与普通在途均不重复加入。
        double candidates = plan.body.meridianStates().stream().flatMap(item -> plan.get("l:" + item.id().value()).entries().stream())
                .filter(qi -> qi.density() >= CANDIDATE_DENSITY).mapToDouble(Qi::amount).sum();
        if (transit != null) {
            candidates += transit.cargo().entries().stream().filter(qi -> qi.density() >= CANDIDATE_DENSITY).mapToDouble(Qi::amount).sum();
        }
        return candidates;
    }

    private static void failBreakthrough(CultivationState.Builder state, String reason) {
        // 清零进度不退燃料，普通周天可以继续；未在突破时保留既有反馈。
        if (state.getBreakthrough()) {
            state.setBreakthrough(false).setBreakthroughSeconds(0).setFuelSeconds(0).setReason(reason);
        }
    }

    private static void stop(HumanQiPlan plan, CultivationState.Builder state, QiTransit transit, String reason) {
        // 取消循环时归还唯一货物；预留保证返回容量，伤脉也不会凭空吞掉余额。
        if (transit != null) {
            String origin = CircuitTransport.meridian(plan.body.structure(),
                    CircuitTransport.connection(plan.body.structure(), transit.connectionId().value()).to()).value();
            plan.add("l:" + origin, transit.cargo());
            plan.activity.clearCircuits().addCircuits(CircuitChange.newBuilder().setId(CIRCUIT));
        }
        failBreakthrough(state, reason);
        state.setRunning(false).setPushing(false).setMobilizing(false).setGateRetryUntil(0).setReason(reason);
    }

    private static void replace(HumanQiPlan plan, QiTransit transit) {
        // 一步内的前进、冲关、相变可能连续更新同一批次，最终只提交一个替换。
        plan.activity.clearCircuits();
        plan.activity.addCircuits(CircuitChange.newBuilder().setId(CIRCUIT).setReplacement(BodyProtobuf.toTransit(transit)));
    }

    private static QiTransit withProgress(QiTransit transit, double work, QiInventory cargo) {
        // 原路线和起始运输服务事实固定；操作只推进本圈的等效时间与货物。
        return new QiTransit(transit.id(), transit.connectionId(), cargo, transit.arrivalMillis(), transit.throughputPerSecond(),
                transit.status(), transit.circuit().toBuilder().setWorkMillis(work).build());
    }

    private static List<String> route(BodySnapshot body, String origin) {
        // 可以从环上任意有货的经脉起步，拓扑仍严格沿人体八字闭环。
        var route = new ArrayList<String>();
        int start = MERIDIANS.indexOf(origin);
        for (int index = 0; index < 8; index++) {
            String from = MERIDIANS.get((start + index) % 8) + ".out";
            String to = MERIDIANS.get((start + index + 1) % 8) + ".in";
            route.add(body.structure().connections().stream().filter(item -> item.from().value().equals(from) && item.to().value().equals(to))
                    .findFirst().orElseThrow().id().value());
        }
        return route;
    }

    private static List<String> circuitMeridians(BodySnapshot body, QiTransit transit) {
        // 运行段与真实旋转后的线路一致，不能假设每次都从左肺开始。
        return transit.circuit().getConnectionIdsList().stream().map(id -> CircuitTransport.meridian(body.structure(),
                CircuitTransport.connection(body.structure(), id).from()).value()).toList();
    }

    public static boolean openedAndIntact(BodySnapshot body) {
        // 未解锁或受损的任一环节都不能凭客户端路线越过。
        return MERIDIANS.stream().allMatch(id -> body.meridianStates().stream().anyMatch(item -> item.id().value().equals(id)
                && item.opening().stage() == MeridianOpening.Stage.OPENED && item.condition() == MeridianState.Condition.INTACT));
    }

    private static boolean available(BodySnapshot body, String id, double watts, double seconds) {
        // 与框架器官负载的健康、功率和能量约束一致，失败先停机再提交状态。
        var definition = body.structure().organs().stream().filter(item -> item.id().value().equals(id)).findFirst().orElseThrow();
        var state = body.organStates().stream().filter(item -> item.id().equals(definition.id())).findFirst().orElseThrow();
        var part = body.structure().parts().stream().filter(item -> item.id().equals(definition.partId())).findFirst().orElseThrow();
        double hp = body.partStates().stream().filter(item -> item.id().equals(part.id())).findFirst().orElseThrow().hp();
        double health = Math.min(state.hp() / definition.maxHp(), hp / part.maxHp());
        double work = (definition.endurance().enduranceJoules() - state.fatigueJoules()) * health;
        double power = definition.endurance().maximumWatts() * work / definition.endurance().enduranceJoules();
        return power >= watts && work >= watts * seconds;
    }

    private static boolean attentionAvailable(BodySnapshot body, double watts, double seconds) {
        // 精力沿用注意组织的功率和可做功预算，不额外维护法力式精力池。
        var capacity = TissueCapabilities.available(body, new Anatomy.TissueId("attention_network"));
        return capacity.burstWatts() >= watts && capacity.enduranceJoules() >= watts * seconds;
    }

    private static double brainStaminaRatio(BodySnapshot body, double spentJoules) {
        // 健康会折减有效耐力；扣除本区间做功后立即检查80%线，不多运行一个边界。
        var definition = body.structure().organs().stream().filter(item -> item.id().value().equals("brain")).findFirst().orElseThrow();
        var state = body.organStates().stream().filter(item -> item.id().equals(definition.id())).findFirst().orElseThrow();
        var part = body.structure().parts().stream().filter(item -> item.id().equals(definition.partId())).findFirst().orElseThrow();
        double hp = body.partStates().stream().filter(item -> item.id().equals(part.id())).findFirst().orElseThrow().hp();
        double health = Math.min(state.hp() / definition.maxHp(), hp / part.maxHp());
        double capacity = definition.endurance().enduranceJoules();
        return ((capacity - state.fatigueJoules()) * health - spentJoules) / capacity;
    }

    private static void effort(HumanQiPlan plan, String id, double watts, double seconds, boolean tissue) {
        // 单位明确为瓦和秒，重复组织负载由 Framework 顺序累计校验。
        var effort = ActivityEffort.newBuilder().setWatts(watts).setSeconds(seconds);
        if (tissue) {
            effort.setTissueId(id);
        } else {
            effort.setOrganId(id);
        }
        plan.activity.addEfforts(effort);
    }

    private static void syncAction(HumanQiPlan plan, String instance, boolean active, boolean usesAttention) {
        // 资源占用与活动开关一起提交；停止后才允许既有指数恢复规则工作。
        var previous = plan.body.actions().stream().filter(item -> item.instanceId().equals(instance)).findFirst().orElse(null);
        boolean changed = previous != null && previous.definition().tissueIds()
                .contains(new Anatomy.TissueId("attention_network")) != usesAttention;
        if (previous != null && (!active || changed)) {
            plan.activity.addStopActions(instance);
        }
        if (active && (previous == null || changed)) {
            var action = BodyMessages.ActionDefinition.newBuilder().setVersion(1);
            if (usesAttention) {
                action.addTissueIds("attention_network");
            }
            if (instance.equals(ACTION)) {
                action.setId("human:middle_jiao").setDisplayName("中焦周天").addOrganIds("brain").addAllGuidedMeridianIds(MERIDIANS);
            } else {
                action.setId("human:mobilize").setDisplayName("脾主动动员").addOrganIds("spleen");
            }
            plan.activity.addStartActions(ActivityAction.newBuilder().setInstanceId(instance).setDefinition(action));
        }
    }
}
