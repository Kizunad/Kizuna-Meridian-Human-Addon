package dev.kizuna.meridian.human.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.human.pb.OrganRefineMessages.OrganRefineState;
import dev.kizuna.meridian.human.pb.OrganRefineMessages.OrganRefineTask;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityRecipe;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganRecipe;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefineIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.kizuna.meridian.human.server.HumanCultivationNumbers.MIN_TRANSFER_QI;

/** 五脏炼化的人体规则：本脏经取料进本脏，加工完成后产物送回本脏经；守恒与时长由 Framework 结算。 */
public final class HumanOrganRefining {
    public static final String PROGRESS = "human:organ_refine";
    private static final int SCHEMA_VERSION = 1;
    private static final double MILLIS_PER_SECOND = 1000;
    private static final int ACCOUNT_PREFIX_LENGTH = "l:".length();

    private HumanOrganRefining() {
        // 规则只生成受校验的活动计划，不持有任何身体或施放状态。
    }

    /** 一批炼化的结果：接受时给出待提交活动，拒绝时给出结束施放的原因。 */
    public record Batch(BodyActivity activity, String rejection) {
        static Batch accepted(BodyActivity activity) {
            // 重复交付的批次给出空活动，宿主据此跳过提交。
            return new Batch(activity, "");
        }

        static Batch refused(String reason) {
            // 拒绝不改变身体，功法库按原因结束整次施放。
            return new Batch(BodyActivity.getDefaultInstance(), reason);
        }

        public boolean rejected() {
            // 原因非空即拒绝，不再另设布尔字段。
            return !rejection.isEmpty();
        }

        public boolean changesBody() {
            // 空活动只说明这一批已经处理过。
            return !activity.equals(BodyActivity.getDefaultInstance());
        }
    }

    public static OrganRefineState state(BodySnapshot body) {
        // 没有进度时视为没有任务；未知版本不能被当作空任务覆盖。
        Any progress = body.addonProgress().get(PROGRESS);
        if (progress == null) {
            return OrganRefineState.newBuilder().setSchemaVersion(SCHEMA_VERSION).build();
        }
        try {
            OrganRefineState state = progress.unpack(OrganRefineState.class);
            if (state.getSchemaVersion() != SCHEMA_VERSION) {
                throw HumanErrors.invalid(HumanErrors.INVALID_REFINE_PROGRESS);
            }
            return state;
        } catch (InvalidProtocolBufferException invalid) {
            throw HumanErrors.invalid(HumanErrors.INVALID_REFINE_PROGRESS);
        }
    }

    public static boolean refining(BodySnapshot body, String organId) {
        // 任务从第一批起到产物全部送回为止都占用该器官。
        return state(body).getTasksList().stream().anyMatch(task -> task.getOrganId().equals(organId));
    }

    public static boolean casting(BodySnapshot body, String castId) {
        // 施放结束时只有仍挂着本次任务的身体需要结算。
        return state(body).getTasksList().stream().anyMatch(task -> task.getCastId().equals(castId));
    }

    public static Batch batch(BodySnapshot body, EffectIntent effect, HumanRefineRecipes recipes) {
        // 先送回上一批已完成的产物，再按剩余缓存与吞吐为空闲器官起新批。
        OrganRefineState current = state(body);
        if (handled(current, effect)) {
            return Batch.accepted(BodyActivity.getDefaultInstance());
        }
        OrganRefineIntent intent = effect.getOrganRefine();
        List<String> organIds = availableOrgans(body, intent.getOrganIdsList());
        if (organIds.isEmpty()) {
            return Batch.refused(HumanErrors.REFINE_ORGAN_UNAVAILABLE);
        }
        List<Source> sources = sources(body, organIds, intent.getMeridianIdsList());
        if (sources.isEmpty()) {
            return Batch.refused(HumanErrors.REFINE_PATH_MISSING);
        }
        Qi.QiPhase phase = Realm.phaseAtDensity(body.realm().capacityRatio());
        var plan = new HumanQiPlan(body);
        var state = current.toBuilder();
        boolean refined = false;
        for (Source source : sources) {
            Optional<OrganRecipe> recipe = recipes.find(intent.getRecipeFamily(), source.kind(), phase);
            if (recipe.isPresent()) {
                OrganRefineTask.Builder task = task(state, source.organId());
                assign(task, effect, source.meridianIds());
                // 先取料腾出本脏经空间，再送回上一批产物；本批产物要等加工完成才计入待送。
                QiInventory output = startBatch(plan, task, recipe.get(), phase, intent.getBatchMillis());
                returnOutput(plan, task);
                QiInventory pending = QiInventory.combine(List.of(pending(task), output));
                task.setPendingOutput(BodyProtobuf.toInventory(pending));
                refined = true;
            }
        }
        if (!refined) {
            return Batch.refused(HumanErrors.REFINE_RECIPE_MISSING);
        }
        plan.activity.putProgress(PROGRESS, Any.pack(state.build()));
        return Batch.accepted(plan.build());
    }

    static void advance(HumanQiPlan plan) {
        // 周期推进只送回已完成的产物并移除收尾任务；新批只由功法批次触发。
        OrganRefineState current = state(plan.body);
        if (current.getTasksCount() == 0) {
            return;
        }
        var state = current.toBuilder();
        for (OrganRefineTask.Builder task : state.getTasksBuilderList()) {
            returnOutput(plan, task);
        }
        removeFinished(state, plan.body);
        OrganRefineState next = state.build();
        if (!next.equals(current)) {
            plan.activity.putProgress(PROGRESS, Any.pack(next));
        }
    }

    public static boolean needsAdvance(BodySnapshot body) {
        // 与推进共用同一计算：确有产物可送回或任务可移除时才唤醒，加工中只等 Framework 完成事件。
        if (state(body).getTasksCount() == 0) {
            return false;
        }
        var plan = new HumanQiPlan(body);
        advance(plan);
        return plan.activity.containsProgress(PROGRESS);
    }

    public static Optional<BodyActivity> end(BodySnapshot body, String castId) {
        // 施放结束后不再起批；已完成的产物立即送回，仍在加工的那批由周期推进收尾。
        OrganRefineState current = state(body);
        var plan = new HumanQiPlan(body);
        var state = current.toBuilder();
        for (OrganRefineTask.Builder task : state.getTasksBuilderList()) {
            if (task.getCastId().equals(castId)) {
                task.setCastEnded(true);
                returnOutput(plan, task);
            }
        }
        removeFinished(state, body);
        OrganRefineState next = state.build();
        if (next.equals(current)) {
            return Optional.empty();
        }
        plan.activity.putProgress(PROGRESS, Any.pack(next));
        return Optional.of(plan.build());
    }

    private static boolean handled(OrganRefineState state, EffectIntent effect) {
        // 同一施放的同一批只取一次料，重复交付直接视为已处理。
        return state.getTasksList().stream().anyMatch(task -> task.getCastId().equals(effect.getCastId())
                && task.getLastEffectId().equals(effect.getEffectId()));
    }

    private static List<String> availableOrgans(BodySnapshot body, List<String> organIds) {
        // 器官或所属部位 HP 为零时不能加工；成对的脏一侧受伤，另一侧照常炼。
        var available = new ArrayList<String>();
        for (String organId : organIds) {
            AnatomyState.Organ state = organState(body, organId);
            Anatomy.PartId partId = organ(body, organId).partId();
            AnatomyState.Part part = body.partStates().stream()
                    .filter(candidate -> candidate.id().equals(partId))
                    .findFirst()
                    .orElseThrow();
            if (state.hp() > 0 && part.hp() > 0) {
                available.add(organId);
            }
        }
        return available;
    }

    private static List<Source> sources(BodySnapshot body, List<String> organIds, List<String> meridianIds) {
        // 只用与本脏双向相通且未断裂的绑定经脉；表里腑经只付强身费用，不参与炼化。
        var sources = new ArrayList<Source>();
        for (String organId : organIds) {
            List<String> paths = meridianIds.stream()
                    .filter(meridianId -> connected(body, meridianId + ".out", organId + ".in"))
                    .filter(meridianId -> connected(body, organId + ".out", meridianId + ".in"))
                    .filter(meridianId -> !severed(body, meridianId))
                    .toList();
            if (!paths.isEmpty()) {
                sources.add(new Source(organId, organ(body, organId).kind().value(), paths));
            }
        }
        return sources;
    }

    private static OrganRefineTask.Builder task(OrganRefineState.Builder state, String organId) {
        // 一器官一任务；上一施放尚未送完的产物随任务保留给本次一起送回。
        for (OrganRefineTask.Builder task : state.getTasksBuilderList()) {
            if (task.getOrganId().equals(organId)) {
                return task;
            }
        }
        return state.addTasksBuilder().setOrganId(organId);
    }

    private static void assign(OrganRefineTask.Builder task, EffectIntent effect, List<String> meridianIds) {
        // 每批刷新施放身份、窗口与本脏经，供重复交付判断和收尾使用。
        task.setCastId(effect.getCastId())
                .setLastEffectId(effect.getEffectId())
                .clearMeridianIds()
                .addAllMeridianIds(meridianIds)
                .setWindowEndMillis(effect.getOrganRefine().getWindowEndMillis())
                .setCastEnded(false);
    }

    private static QiInventory startBatch(HumanQiPlan plan, OrganRefineTask.Builder task, OrganRecipe recipe,
                                          Qi.QiPhase phase, long batchMillis) {
        // 器官仍在加工时跳过这一批；投料不超过剩余缓存与本批吞吐，只取当前相态的无属性真元；返回本批预计产物。
        if (organState(plan.body, task.getOrganId()).process() != null) {
            return QiInventory.empty();
        }
        String organKey = "o:" + task.getOrganId();
        Anatomy.OrganDefinition organ = organ(plan.body, task.getOrganId());
        double freeVolume = Math.max(0, organ.volumeCapacity() - plan.get(organKey).volume());
        double budget = organ.throughputPerSecond() * batchMillis / MILLIS_PER_SECOND;
        QiInventory input = QiInventory.empty();
        for (String meridianId : task.getMeridianIdsList()) {
            QiInventory taken = takeNone(plan, "l:" + meridianId, phase,
                    budget - input.totalQi(), freeVolume - input.volume());
            input = QiInventory.combine(List.of(input, taken));
        }
        if (input.entries().isEmpty()) {
            return QiInventory.empty();
        }
        plan.add(organKey, input);
        QiInventory output = output(input, recipe);
        // 加工时长取功法排定的批次间隔，慢速施放时与起批节奏一起拉长。
        OrganRecipe batch = recipe.toBuilder()
                .setInput(BodyProtobuf.toInventory(input))
                .setOutput(BodyProtobuf.toInventory(output))
                .setDurationMillis(batchMillis)
                .build();
        ActivityRecipe started = ActivityRecipe.newBuilder()
                .setOrganId(task.getOrganId())
                .setRecipe(batch)
                .build();
        plan.activity.addRecipes(started);
        return output;
    }

    private static QiInventory takeNone(HumanQiPlan plan, String key, Qi.QiPhase phase,
                                        double maximumQi, double maximumVolume) {
        // 本脏经里别的属性或相态不作投料；不足时按实际余额取，尾差留在原账户。
        if (maximumQi <= 0 || maximumVolume <= 0) {
            return QiInventory.empty();
        }
        QiInventory none = new QiInventory(plan.get(key).entries().stream()
                .filter(qi -> qi.attribute().equals(Qi.QiAttribute.NONE) && qi.phase() == phase)
                .toList());
        QiInventory selected = new QiInventory(none.take(maximumQi, maximumVolume).entries().stream()
                .filter(qi -> qi.amount() >= MIN_TRANSFER_QI)
                .toList());
        if (!selected.entries().isEmpty()) {
            plan.put(key, QiAccounting.debit(plan.get(key), selected));
        }
        return selected;
    }

    private static QiInventory output(QiInventory input, OrganRecipe recipe) {
        // 产物按配方的炼化率换成本行属性，保持投料密度；其余由 Framework 在完成时释放回环境。
        double rate = HumanRefineRecipes.rate(recipe);
        Qi.QiAttribute attribute = HumanRefineRecipes.outputAttribute(recipe);
        return new QiInventory(input.entries().stream()
                .map(qi -> new Qi(attribute, qi.amount() * rate, qi.density()))
                .toList());
    }

    private static void returnOutput(HumanQiPlan plan, OrganRefineTask.Builder task) {
        // 本脏加工完成后才送回：先进本脏经内腔，内腔满时存进同一经脉外壁；仍放不下的留在缓存等下一次推进。
        QiInventory pending = pending(task);
        if (pending.entries().isEmpty() || organState(plan.body, task.getOrganId()).process() != null) {
            return;
        }
        String organKey = "o:" + task.getOrganId();
        QiInventory moved = QiInventory.empty();
        for (String account : destinations(plan.body, task.getMeridianIdsList())) {
            QiInventory remaining = remaining(pending, moved, plan.get(organKey));
            QiInventory cargo = select(plan.get(organKey), remaining, freeVolume(plan, account));
            if (!cargo.entries().isEmpty()) {
                plan.put(organKey, QiAccounting.debit(plan.get(organKey), cargo));
                plan.add(account, cargo);
                moved = QiInventory.combine(List.of(moved, cargo));
            }
        }
        QiInventory left = remaining(pending, moved, plan.get(organKey));
        task.setPendingOutput(BodyProtobuf.toInventory(left));
    }

    private static List<String> destinations(BodySnapshot body, List<String> meridianIds) {
        // 各本脏经内腔优先，其次外壁；断裂的经脉两处都不收。
        List<String> intact = meridianIds.stream()
                .filter(meridianId -> !severed(body, meridianId))
                .toList();
        var accounts = new ArrayList<String>();
        intact.forEach(meridianId -> accounts.add("l:" + meridianId));
        intact.forEach(meridianId -> accounts.add("w:" + meridianId));
        return accounts;
    }

    private static double freeVolume(HumanQiPlan plan, String account) {
        // 内腔扣除在途运输的预约；外壁只按声明容量与当前余额计算。
        String meridianId = account.substring(ACCOUNT_PREFIX_LENGTH);
        if (account.startsWith("l:")) {
            return plan.freeLumen(meridianId);
        }
        double wallVolume = plan.body.structure().meridians().stream()
                .filter(meridian -> meridian.id().value().equals(meridianId))
                .findFirst()
                .orElseThrow()
                .wallVolume();
        return Math.max(0, wallVolume - plan.get(account).volume());
    }

    private static QiInventory select(QiInventory buffer, QiInventory wanted, double maximumVolume) {
        // 按缓存里同属性同相态的实际密度取货，体积不超过目标账户的空余体积。
        var cargo = new ArrayList<Qi>();
        double volume = maximumVolume;
        for (Qi qi : wanted.entries()) {
            Optional<Qi> stock = buffer.entries().stream()
                    .filter(entry -> entry.attribute().equals(qi.attribute()) && entry.phase() == qi.phase())
                    .findFirst();
            if (stock.isEmpty() || volume <= 0) {
                continue;
            }
            double density = stock.get().density();
            double amount = Math.min(Math.min(qi.amount(), stock.get().amount()), volume * density);
            if (amount >= MIN_TRANSFER_QI) {
                cargo.add(new Qi(qi.attribute(), amount, density));
                volume -= amount / density;
            }
        }
        return new QiInventory(cargo);
    }

    private static QiInventory remaining(QiInventory pending, QiInventory moved, QiInventory buffer) {
        // 待送回的量以缓存里实际存在的同种真元为上限；低于最小转移量的尾差留在缓存。
        var entries = new ArrayList<Qi>();
        for (Qi qi : pending.entries()) {
            double left = qi.amount() - amountOf(moved, qi);
            double held = Math.min(left, amountOf(buffer, qi));
            if (held >= MIN_TRANSFER_QI) {
                entries.add(qi.withAmount(held));
            }
        }
        return new QiInventory(entries);
    }

    private static double amountOf(QiInventory inventory, Qi kind) {
        // 账户内同属性同相态只有一项明细，按种类求和即为该项余额。
        return inventory.entries().stream()
                .filter(entry -> entry.attribute().equals(kind.attribute()) && entry.phase() == kind.phase())
                .mapToDouble(Qi::amount)
                .sum();
    }

    private static void removeFinished(OrganRefineState.Builder state, BodySnapshot body) {
        // 施放已结束或窗口已过、且本脏不在加工的任务移除；经脉满而送不回的产物留在本脏缓存，不丢失。
        long nowMillis = body.settledAt().elapsedMillis();
        List<OrganRefineTask> kept = state.getTasksList().stream()
                .filter(task -> organState(body, task.getOrganId()).process() != null
                        || (!task.getCastEnded() && nowMillis < task.getWindowEndMillis()))
                .toList();
        state.clearTasks().addAllTasks(kept);
    }

    private static QiInventory pending(OrganRefineTask.Builder task) {
        // 任务里的产物明细沿用 Framework 的库存格式与校验。
        return BodyProtobuf.fromInventory(task.getPendingOutput());
    }

    private static boolean connected(BodySnapshot body, String from, String to) {
        // 通路由人体构型声明；缺连接的经脉不能凭标签瞬移真元。
        return body.structure().connections().stream()
                .anyMatch(connection -> connection.from().value().equals(from) && connection.to().value().equals(to));
    }

    private static boolean severed(BodySnapshot body, String meridianId) {
        // 断裂的经脉既不能取料也不能接收产物；渗漏的经脉照常取送。
        return meridianState(body, meridianId).condition() == MeridianState.Condition.SEVERED;
    }

    private static Anatomy.OrganDefinition organ(BodySnapshot body, String organId) {
        // 绑定来自同一具身体的构型，缺失是装配错误。
        return body.structure().organs().stream()
                .filter(organ -> organ.id().value().equals(organId))
                .findFirst()
                .orElseThrow();
    }

    private static AnatomyState.Organ organState(BodySnapshot body, String organId) {
        // 器官状态与构型一一对应。
        return body.organStates().stream()
                .filter(organ -> organ.id().value().equals(organId))
                .findFirst()
                .orElseThrow();
    }

    private static MeridianState meridianState(BodySnapshot body, String meridianId) {
        // 经脉状态与构型一一对应。
        return body.meridianStates().stream()
                .filter(meridian -> meridian.id().value().equals(meridianId))
                .findFirst()
                .orElseThrow();
    }

    private record Source(String organId, String kind, List<String> meridianIds) {
        // 一个可炼的器官：种类用于选配方，经脉是取料与送回的本脏经。
    }
}
