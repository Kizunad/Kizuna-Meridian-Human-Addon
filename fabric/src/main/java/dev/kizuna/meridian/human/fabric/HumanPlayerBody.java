package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.human.server.HumanCultivationNumbers;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.human.server.HumanAgingRules;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.human.server.HumanOrganRefining;
import dev.kizuna.meridian.human.server.HumanRefineRecipes;
import dev.kizuna.meridian.human.server.HumanRevival;
import dev.kizuna.meridian.human.protocol.HumanProgressionProtocol;
import dev.kizuna.meridian.progression.pb.ProgressionMessages.PlayerProgress;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityMessages;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonHostProfile;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonRegistration;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.AdvanceTime;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissues;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FillModuleTags;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.SetAddonProgress;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.StartAction;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.StopAction;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.protocol.pb.OpeningMessages;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.BreathingModifiers;
import dev.kizuna.meridian.server.GatheringSession;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.OpeningSession;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice.Settlement;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.server.TechniqueBodyStamp;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueState;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** 单玩家的可信运动编排；账本、占用与恢复只由框架命令处理器修改。 */
final class HumanPlayerBody {
    static final String MOVEMENT_INSTANCE = "human.minecraft.locomotion";
    static final double WALK_ENDURANCE_SECONDS = 300;
    static final double SPRINT_ENDURANCE_SECONDS = 75;
    private static final long PRACTICE_BILLING_MILLIS = 1000;
    private static final double MILLIS_PER_SECOND = 1000;
    private static final long MILLIS_PER_TICK = 50;
    /** 主世界年龄倍率；测试和出生前的身体默认使用主世界规则。 */
    static final double AGING_RATE = HumanAgingRules.GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND
            * HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER;
    private static final int GATHERING_PROTOCOL_VERSION = 1;
    private static final AddonRegistry CONTENT = installContent();

    enum Motion {
        IDLE("", "静止"), WALK("human:walk", "行走"), SPRINT("human:run", "疾跑");

        final String actionId;
        final String displayName;

        Motion(String actionId, String displayName) {
            // Minecraft 适配只识别三种当前运动状态，实际占用来自注册的内容定义。
            this.actionId = actionId;
            this.displayName = displayName;
        }
    }

    private final BodyId id;
    private final InMemoryBodyStore store;
    private final BodyCommandHandler<String> commands;
    private Motion motion = Motion.IDLE;
    private long targetMillis;
    private long accountedMillis;
    private long nextBoundaryMillis;
    private double agingRate = AGING_RATE;
    private String dimensionId;
    private TimeAdvanceMode timeMode = TimeAdvanceMode.ACTIVE;
    private String sourceId;
    private GatheringSession<String> gathering;
    private OpeningSession<String> openingSession;
    private Consumer<GatheringResponse> gatheringOutput;
    private BasicBreathingGrant basicBreathingGrant = BasicBreathingGrant.ABSENT;
    private Consumer<QiInventory> releaseSink = released -> { /* 独立身体测试无需世界路由。 */ };
    private DoubleSupplier movementLoad = () -> HumanMovementLoads.UNLOADED;
    private Runnable practiceEnd = () -> { /* 独立身体没有原版实体图标。 */ };
    private final Set<String> breathingActions = new HashSet<>();
    private boolean debugAuthorized;
    private String trustedActivityCaller;
    private Function<BodySnapshot, Optional<TechniqueHost.Submission>> pendingGuardRelease =
            ignored -> Optional.empty();
    private BreathingView lastBreathing = BreathingView.getDefaultInstance();
    private BodySnapshot cultivationChecked;
    private boolean cultivationNeeded;
    private BodySnapshot lifespanChecked;
    private long lifespanEndMillis;

    HumanPlayerBody(BodySnapshot saved, List<CommandReceipt> receipts, QiInventory releasedQi) {
        // 同时恢复身体、成功回执及释放账，不能在登录时重建疲劳或重发历史释放。
        this(saved, new InMemoryBodyStore(List.of(saved), receipts, releasedQi), Set.of());
    }

    HumanPlayerBody(BodySnapshot saved, InMemoryBodyStore store, Set<String> breathingActions) {
        // 世界中所有玩家共享同一个事务 Store，同区域灵气不会被每个玩家各复制一份。
        id = saved.id();
        accountedMillis = saved.settledAt().elapsedMillis();
        targetMillis = accountedMillis;
        this.store = store;
        this.breathingActions.addAll(breathingActions);
        BodyTimeSource<String> clock = (caller, body) -> timeAdvancePlan(body);
        commands = new BodyCommandHandler<>((caller, command) -> {
            // 仅内部调度身份可提交命令；网络入口不暴露这些资源变更请求。
            if (caller.equals(id.value()) && command.getBodyId().equals(id.value())) {
                return Optional.of(caller);
            }
            if ((caller.equals(TechniqueHost.CALLER_ID) || caller.equals(trustedActivityCaller))
                    && command.hasExecuteActivity()
                    && command.getBodyId().equals(id.value())) {
                return Optional.of(caller);
            }
            return Optional.empty();
        }, (caller, command, body) -> true, clock,
                store, (caller, receipt, before, after) -> { /* 保存由宿主在事件完成后统一标脏。 */ },
                CONTENT.recipes(), (caller, body, plan) -> Optional.ofNullable(sourceId),
                CONTENT.actions(), CONTENT.openings(),
                caller -> debugAuthorized && caller.equals(id.value()), new HumanDamage("minecraft:player"));
        migrateModuleTags();
        if (!saved.addonProgress().containsKey(HumanProgressionProtocol.PROGRESS_KEY)) {
            // 新身体与尚无进度字段的存档都从未读开始，并写入同一身体事务。
            PlayerProgress initialProgress = PlayerProgress.newBuilder()
                    .setSchemaVersion(HumanProgressionProtocol.PROGRESS_SCHEMA)
                    .build();
            writeProgress(initialProgress);
        }
    }

    private TimeAdvancePlan timeAdvancePlan(BodySnapshot body) {
        // 时钟保留身体的时域与世代，只推进当前宿主记录的目标时刻。
        var target = BodyProtobuf.toTime(body.settledAt()).toBuilder()
                .setElapsedMillis(targetMillis)
                .build();
        return TimeAdvancePlan.newBuilder()
                .setTarget(target)
                .setBiologicalSecondsPerSimulationSecond(agingRate)
                .setMode(timeMode)
                .build();
    }

    private void migrateModuleTags() {
        // 读档与首次登录共用可信入口；冲突不能覆盖，回执与其他身体状态一起保留。
        BodySnapshot current = snapshot();
        FillModuleTags tags = HumanModuleTagMigration.missingTags(current);
        if (tags.getModulesCount() == 0) {
            return;
        }
        var command = BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(current.revision())
                .setFillModuleTags(tags)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId("human_module_tags_" + UUID.randomUUID())
                .setBodyCommand(command)
                .build();
        var response = MeridianProtocol.decodeResponse(
                commands.handleModuleTags(id.value(), MeridianProtocol.encodeRequest(request)));
        if (response.hasFailure()) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
    }

    PlayerProgress progress() {
        // 只读取身体内的唯一事实；未知类型或版本不能被解释成已解锁。
        try {
            PlayerProgress progress = snapshot().addonProgress().get(HumanProgressionProtocol.PROGRESS_KEY)
                    .unpack(PlayerProgress.class);
            if (progress.getSchemaVersion() != HumanProgressionProtocol.PROGRESS_SCHEMA) {
                throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
            }
            return progress;
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
        }
    }

    PlayerProgress progressProjection() {
        // 当前出身授权随权威进度同步，但不把在线派生状态写入身体存档。
        return progress().toBuilder()
                .setBasicBreathingGranted(hasBasicBreathingGrant())
                .build();
    }

    boolean canIntrospect(String bodyId) {
        // 内观订阅同时校验身体归属和已读事实，客户端隐藏入口不能代替服务端授权。
        PlayerProgress progress = progress();
        return id.value().equals(bodyId) && progress.getQiNotesRead() && progress.getInnerVisionScrollRead();
    }

    void writeProgress(PlayerProgress progress) {
        // 重置先终止会话与占用；同一服务器线程随后提交进度，不允许旧输入继续执行。
        if (progress.getSchemaVersion() != HumanProgressionProtocol.PROGRESS_SCHEMA) {
            throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
        }
        BodySnapshot current = snapshot();
        PlayerProgress previous = current.addonProgress().containsKey(HumanProgressionProtocol.PROGRESS_KEY)
                ? progress() : PlayerProgress.getDefaultInstance();
        if (previous.getQiNotesRead() && !progress.getQiNotesRead()
                || previous.getGatheringScrollRead() && !progress.getGatheringScrollRead()
                || previous.getMiddleJiaoScrollRead() && !progress.getMiddleJiaoScrollRead()) {
            // 只在撤权时结束旧活动；阅读新卷不会断开已经接好的吐纳会话。
            disconnectGathering(targetMillis);
        }
        PlayerProgress savedProgress = progress.toBuilder()
                .clearBasicBreathingGranted()
                .build();
        SetAddonProgress update = SetAddonProgress.newBuilder()
                .setId(HumanProgressionProtocol.PROGRESS_KEY)
                .setProgress(com.google.protobuf.Any.pack(savedProgress))
                .build();
        BodySnapshot commandSnapshot = snapshot();
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(commandSnapshot.revision())
                .setSetAddonProgress(update)
                .build();
        RequestEnvelope request = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        ResponseEnvelope response = MeridianProtocol.decodeResponse(captureRelease(
                () -> commands.handleAddonProgress(id.value(), request.toByteArray())));
        if (response.hasFailure()) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
    }

    static HumanPlayerBody born(UUID playerId, String epoch, long millis) {
        // 首次出现该玩家时才使用出生候选；世界存档保留时钟世代和 UUID 绑定。
        var store = new InMemoryBodyStore(List.of(), List.of(), QiInventory.empty());
        return born(playerId, epoch, millis, store);
    }

    static HumanPlayerBody born(UUID playerId, String epoch, long millis, InMemoryBodyStore store) {
        // 出生只登记新身份，其他身体和环境账户保持在同一 Store 中。
        var time = BodyMessages.TimeCursor.newBuilder().setDomain("human:minecraft")
                .setEpoch(epoch).setElapsedMillis(millis).build();
        byte[] candidate = HumanBirth.candidate(CONTENT.findTemplate(HumanContent.TEMPLATE_ID, 1).orElseThrow(),
                playerId.toString(), time);
        BodySnapshot body = BodyProtobuf.decodeSnapshot(candidate);
        store.registerBody(body);
        return new HumanPlayerBody(body, store, Set.of());
    }

    void resume(long millis) {
        resume(millis, "minecraft:overworld");
    }

    void resume(long millis, String selectedDimensionId) {
        // 离线区间只顺延冻结的身体事件，不把缺席时间计入自然年龄。
        long savedMillis = snapshot().settledAt().elapsedMillis();
        abandonPractice();
        targetMillis = millis;
        agingRate = 0;
        dimensionId = null;
        lifespanChecked = null;
        timeMode = TimeAdvanceMode.LIFESPAN_ONLY;
        advanceTime();
        timeMode = TimeAdvanceMode.ACTIVE;
        dimensionId = selectedDimensionId;
        agingRate = HumanAgingRules.configured().agingRate(selectedDimensionId);
        lifespanChecked = null;
        accountedMillis = millis;
        preserveGuardDelay(savedMillis, millis);
        if (!snapshot().vitality().dead() && (HumanCultivation.state(snapshot()).getRunning()
                || HumanCultivation.state(snapshot()).getMobilizing())) {
            applyActivity(HumanCultivation.cancel(snapshot()));
        }
        for (String instance : Set.copyOf(breathingActions)) {
            // 世界保存可能发生在按住吸气时；重连只清理本宿主记下的旧会话占用。
            if (snapshot().actions().stream().anyMatch(action -> action.instanceId().equals(instance))) {
                execute(BodyCommand.newBuilder().setStopAction(StopAction.newBuilder().setInstanceId(instance)));
            }
        }
        breathingActions.clear();
        if (!snapshot().vitality().dead() && hasMovementAction()) {
            // 异常退出的旧移动实例不能在重新登录后永久占用下肢。
            execute(BodyCommand.newBuilder().setStopAction(StopAction.newBuilder().setInstanceId(MOVEMENT_INSTANCE)));
        }
        motion = Motion.IDLE;
        scheduleNextBoundary();
    }

    boolean observe(long millis, Motion observed) {
        // 非 Minecraft 调用方沿用动作变化作为移动信号。
        return observe(millis, observed, observed != Motion.IDLE);
    }

    boolean observe(long millis, Motion observed, boolean moved) {
        // 每 tick 只比较动作和下一边界；持续做功及空闲恢复最多每秒结算一次。
        boolean stoppedBreathing = moved && lastBreathing.getActive() && !lastBreathing.getAutomatic();
        if (stoppedBreathing) {
            stopBreathing(millis);
        }
        if (observed == motion && millis < nextBoundaryMillis && !stoppedBreathing) {
            return false;
        }
        settle(millis);
        if (snapshot().vitality().dead()) {
            motion = Motion.IDLE;
            nextBoundaryMillis = Long.MAX_VALUE;
            return true;
        }
        if (observed != motion || (observed != Motion.IDLE && !hasMovementAction())) {
            // 先结清旧动作整个区间，再停止旧占用和建立新占用，防止边走边恢复。
            if (hasMovementAction()) {
                StopAction stop = StopAction.newBuilder().setInstanceId(MOVEMENT_INSTANCE).build();
                execute(BodyCommand.newBuilder().setStopAction(stop));
            }
            if (observed != Motion.IDLE) {
                startMovement(observed);
            }
            motion = observed;
        }
        scheduleNextBoundary();
        return true;
    }

    private void stopBreathing(long millis) {
        // 移动和肺炼都与界面结束按钮使用同一个意图，先结清气流并解除呼吸占用。
        BreathControl control = BreathControl.newBuilder()
                .setAction(BreathAction.END_BREATHING)
                .setSequence(lastBreathing.getSequence() + 1)
                .build();
        GatheringRequest request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(id.value())
                .setBreath(control)
                .build();
        GatheringResponse stopped = gather(millis, request);
        if (stopped.hasFailure()) {
            throw HumanErrors.hostRejected(stopped.getFailure().getCode());
        }
        gatheringOutput.accept(stopped);
    }

    private void startMovement(Motion observed) {
        // 肢体损毁是动作不可用，不能因此暂停身体；其他拒绝仍交给宿主诊断。
        StartAction action = StartAction.newBuilder()
                .setInstanceId(MOVEMENT_INSTANCE)
                .setActionId(observed.actionId)
                .setActionVersion(1)
                .build();
        ResponseEnvelope response = executeCommand(BodyCommand.newBuilder().setStartAction(action));
        if (response.hasFailure() && response.getFailure().getCode() != FailureCode.MODULE_UNAVAILABLE) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
    }

    boolean breathingActive() {
        // 缓存只来自权威会话回执，避免每 tick 重建整个身体展示快照。
        return lastBreathing.getActive();
    }

    boolean breathingAutomatic() {
        // 动画展示读取服务端确认的模式，自动吐纳移动时优先显示真实移动动作。
        return lastBreathing.getAutomatic();
    }

    void settle(long millis) {
        // 先用当刻状态同步守式占用，长区间再逐个经过起手、耗尽和修炼边界。
        BodySnapshot before = snapshot();
        long now = before.settledAt().elapsedMillis();
        if (millis > now && !before.vitality().dead()) {
            pendingGuardRelease.apply(before).ifPresent(release -> {
                targetMillis = now;
                var command = BodyCommand.newBuilder()
                        .setBodyId(id.value())
                        .setExpectedRevision(release.expectedRevision())
                        .setExecuteActivity(release.activity())
                        .build();
                submitRecovery(release.requestId(), command);
            });
        }
        settleGuard();
        long boundary;
        do {
            boundary = settlementBoundary(millis);
            settleInterval(boundary);
            settleGuard();
        } while (boundary < millis);
    }

    private void settleGuard() {
        // 取同一个版本算游标、费用、占用与撤窗，任何拒绝均保留待结区间。
        BodySnapshot body = snapshot();
        HumanGuardStamina.holding(body).flatMap(guard -> HumanGuardStamina.payment(body, guard))
                .ifPresent(this::applyActivity);
    }

    void bindGuardRelease(Function<BodySnapshot, Optional<TechniqueHost.Submission>> pending) {
        // 在线施放会话持有首次松手请求，身体只在即将推进到下一时刻时查看。
        pendingGuardRelease = pending;
    }

    private void preserveGuardDelay(long savedMillis, long nowMillis) {
        // 离线只走寿元，登录后还须等待离开时剩余的禁守时间。
        BodySnapshot body = snapshot();
        if (body.vitality().dead() || nowMillis <= savedMillis
                || !body.addonProgress().containsKey(TechniqueState.KEY)) {
            return;
        }
        var progress = TechniqueState.read(body);
        var updated = progress.toBuilder();
        progress.getGuardBreakEndsMap().forEach((id, deadline) -> {
            if (deadline > savedMillis) {
                updated.putGuardBreakEnds(id, Math.addExact(nowMillis, deadline - savedMillis));
            }
        });
        var shifted = updated.build();
        if (!shifted.equals(progress)) {
            var payload = TechniqueState.pack(shifted);
            applyActivity(ActivityMessages.BodyActivity.newBuilder().putProgress(TechniqueState.KEY, payload).build());
        }
    }

    private long settlementBoundary(long millis) {
        // 迟到推进先停在费用能覆盖的最后边界，再让其后的吸呼按普通费率运行。
        var body = snapshot();
        if (body.vitality().dead()) {
            return millis;
        }
        long next = TechniqueBreathingPractice.expiresAt(body).orElse(millis);
        long step = nextStepMillis();
        if (step < millis && needsCultivationAdvance(body)) {
            next = Math.min(next, step);
        }
        long now = body.settledAt().elapsedMillis();
        long target = Math.clamp(next, Math.min(millis, now), millis);
        var guard = HumanGuardStamina.holding(body);
        if (guard.isEmpty()) {
            return target;
        }
        long guardBoundary = HumanGuardStamina.boundary(body, guard.orElseThrow(), target);
        if (hasMovementAction() || !body.modifiers().isEmpty()) {
            return now + Math.min(MILLIS_PER_TICK, guardBoundary - now);
        }
        return guardBoundary;
    }

    private void settleInterval(long millis) {
        // 同一时刻的观察和查询不能重复扣费；时间来自服务端主世界累计 tick。
        if (millis < accountedMillis) {
            throw HumanErrors.invalid(HumanErrors.INVALID_MOVEMENT_TIME);
        }
        double elapsedSeconds = (millis - accountedMillis) / MILLIS_PER_SECOND;
        targetMillis = millis;
        advanceTime();
        settlePractice(Settlement.MAINTAIN);
        if (elapsedSeconds > 0 && hasMovementAction() && !snapshot().vitality().dead()) {
            exertMovement(elapsedSeconds);
        }
        accountedMillis = millis;
        boolean cultivationDue = !snapshot().addonProgress().containsKey(HumanCultivation.PROGRESS)
                || HumanCultivation.state(snapshot()).getLastMillis() < snapshot().settledAt().elapsedMillis();
        // 同tick的健康读取与提交必须看到同一版本；没有新的活动时间就不能空提交进度。
        if (!snapshot().vitality().dead() && cultivationDue && needsCultivationAdvance(snapshot())) {
            applyActivity(HumanCultivation.update(snapshot(), null));
            // 这一步挪了真元（储备回补内腔等），经脉之间可能当场有了落差，被动扩散此刻就能发运。
            // 当场发掉：留给下一次结算的话，同一毫秒里先读版本、后提交的伤害会撞上这一笔，报 REVISION_CONFLICT。
            advanceTime();
        }
        scheduleNextBoundary();
    }

    private void exertMovement(double elapsedSeconds) {
        // 仅已建立的运动占用收费，伤势和疲劳共同限制每块肌肉的实际做功。
        double enduranceSeconds = motion == Motion.SPRINT ? SPRINT_ENDURANCE_SECONDS : WALK_ENDURANCE_SECONDS;
        // 负荷倍率每个结算区间读取一次，只放大目标功率，仍受下方爆发与剩余耐力截断。
        double loadMultiplier = movementLoad.getAsDouble();
        BodySnapshot body = snapshot();
        var loads = ExertTissues.newBuilder();
        var action = CONTENT.actions().find(motion.actionId, 1).orElseThrow();
        for (String tissueId : action.getTissueIdsList()) {
            Anatomy.TissueDefinition definition = body.structure().tissues().stream()
                    .filter(tissue -> tissue.id().value().equals(tissueId)).findFirst().orElseThrow();
            Anatomy.TissueCapacity available = TissueCapabilities.available(body, definition.id());
            double desiredWatts = definition.capacity().enduranceJoules() / enduranceSeconds * loadMultiplier;
            double burstLimitedWatts = Math.min(desiredWatts, available.burstWatts());
            double payableWatts = Math.min(burstLimitedWatts, available.enduranceJoules() / elapsedSeconds);
            if (payableWatts > 0) {
                ExertTissue load = ExertTissue.newBuilder()
                        .setTissueId(tissueId)
                        .setForceNewtons(0)
                        .setPowerWatts(payableWatts)
                        .setDurationSeconds(elapsedSeconds)
                        .build();
                loads.addLoads(load);
            }
        }
        if (loads.getLoadsCount() > 0) {
            ExertTissues exertion = loads.build();
            execute(BodyCommand.newBuilder().setExertTissues(exertion));
        }
    }

    public BodySnapshot snapshot() {
        // 暴露不可变权威事实供保存和展示，不提供外部写入入口。
        return store.find(id).orElseThrow();
    }

    /**
     * 不结清、不提交时报出的身体戳：已提交的版本，以及此刻 settle(millis) 以后身体时钟会停在哪一毫秒。
     * 结清会失败或停在别处时为空：身体已死，millis 早于已记账的时间或身体时钟，寿元在 millis 或之前用完。
     */
    Optional<TechniqueBodyStamp> bodyStamp(long millis) {
        BodySnapshot body = snapshot();
        if (body.vitality().dead()) {
            return Optional.empty();
        }
        if (millis < accountedMillis || millis < body.settledAt().elapsedMillis()) {
            // settle 会拒绝倒退的时间，inspect 答不出身体。
            return Optional.empty();
        }
        if (lifespanEndMillis(body) <= millis) {
            // 结清会停在寿元终点并提交死亡，时钟不是 millis；判定与 Framework 结清一致，终点等于目标也算死亡。
            return Optional.empty();
        }
        return Optional.of(new TechniqueBodyStamp(body.revision(), millis));
    }

    private long lifespanEndMillis(BodySnapshot body) {
        // 快照不可变：同一份快照只算一次寿元终点，每 tick 的读数不重复解寿元。
        if (body != lifespanChecked) {
            lifespanEndMillis = BodyTimeAdvancement.nextLifespanBoundary(body, agingRate)
                    .map(TimeCursor::elapsedMillis)
                    .orElse(Long.MAX_VALUE);
            lifespanChecked = body;
        }
        return lifespanEndMillis;
    }

    List<CommandReceipt> receipts() {
        // 去重事实与身体放进同一份世界持久化记录；Store 按身体分开存，直接取这一具的，不用遍历全体玩家再过滤。
        return store.receipts(id);
    }

    void selectEnvironment(String sourceId, long millis) {
        // 兼容无维度信息的身体测试，默认按主世界年龄倍率。
        selectEnvironment(sourceId, "minecraft:overworld", millis);
    }

    void selectEnvironment(String sourceId, String selectedDimensionId, long millis) {
        // 区域或维度切换先结清旧段，不能用新规则追溯上一段时间。
        double selectedRate = HumanAgingRules.configured().agingRate(selectedDimensionId);
        boolean sourceChanged = !java.util.Objects.equals(this.sourceId, sourceId);
        boolean dimensionChanged = !java.util.Objects.equals(dimensionId, selectedDimensionId);
        if (sourceChanged || dimensionChanged) {
            settle(millis);
            this.sourceId = sourceId;
            dimensionId = selectedDimensionId;
            agingRate = selectedRate;
            lifespanChecked = null;
            scheduleNextBoundary();
        }
    }

    void connectGathering(long millis, Consumer<GatheringResponse> output) {
        // 同一个真实身体仅保留一个连接会话，窗口开关不会创建演示身体。
        disconnectGathering(millis);
        targetMillis = millis;
        gathering = new GatheringSession<>(id.value(), id, commands, store,
                (caller, requested) -> caller.equals(id.value()) && requested.equals(id),
                (caller, body) -> TimeAdvancePlan.newBuilder()
                        .setTarget(BodyProtobuf.toTime(body.settledAt()).toBuilder().setElapsedMillis(targetMillis))
                        .setBiologicalSecondsPerSimulationSecond(agingRate)
                        .setMode(timeMode)
                        .build(),
                body -> sourceId == null ? Optional.empty() : store.environmentSource(sourceId), CONTENT.recipes());
        gathering.enableBreathing(HumanContent.breathing());
        gatheringOutput = output;
    }

    boolean hasBasicBreathingGrant() {
        // 只有在线出身来源可以授予基础吐纳，离线快照只保存真实的阅读记录。
        return basicBreathingGrant == BasicBreathingGrant.PRESENT;
    }

    boolean gatheringConnected() {
        // 撤销残卷会关闭旧会话，在线连接需要接收新的输入。
        return gathering != null;
    }

    void updateBasicBreathingGrant(BasicBreathingGrant grant) {
        // 进服、改换出身及资源重载时替换当前会话的授权。
        basicBreathingGrant = grant;
    }

    private boolean learnedGathering() {
        // 这两本残卷一起开放完整的聚气配方和环境详情。
        var reading = progress();
        return reading.getQiNotesRead() && reading.getGatheringScrollRead();
    }

    GatheringResponse visibleGathering(GatheringResponse receipt) {
        // 专有授权只允许读呼吸状态，环境详情和旧聚气配方仍需要原来的阅读进度。
        if (!receipt.hasView() || !hasBasicBreathingGrant() || learnedGathering()) {
            return receipt;
        }
        var view = receipt.getView().toBuilder()
                .clearEnvironment()
                .clearChoices()
                .clearProgress()
                .build();
        return receipt.toBuilder().setView(view).build();
    }

    GatheringResponse gather(long millis, GatheringRequest request) {
        // 请求只有输入方向与序号；玩家、时钟、环境和能力由当前宿主绑定。
        if (!learnedGathering() && (request.hasStart() || !hasBasicBreathingGrant())) {
            return gatheringFailure(request, FailureCode.ACCESS_DENIED);
        }
        if (startsBreathing(request) && refiningBreathingOrgan()) {
            // 肺炼期间肺忙于炼化，开始吐纳与纳气都要等炼化收尾；结束与查看照常。
            return gatheringFailure(request, FailureCode.ORGAN_BUSY);
        }
        settleBeforeSession(millis);
        targetMillis = millis;
        gathering.synchronizeBodyRevision();
        Set<String> previous = actionIds();
        try {
            GatheringResponse response = GatheringResponse.parseFrom(captureRelease(
                    () -> gathering.handle(request.toByteArray())));
            if (response.hasView()) {
                lastBreathing = response.getView().getBreathing();
            }
            return response;
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw HumanErrors.invalidGatheringResponse(failure);
        } finally {
            rememberBreathingActions(previous);
            scheduleNextBoundary();
        }
    }

    private static GatheringResponse gatheringFailure(GatheringRequest request, FailureCode code) {
        // 吐纳入口的拒绝只带失败码，不推进身体也不改吐纳会话。
        var failure = RequestFailure.newBuilder()
                .setCode(code)
                .build();
        return GatheringResponse.newBuilder()
                .setProtocolVersion(GATHERING_PROTOCOL_VERSION)
                .setRequestId(request.getRequestId())
                .setFailure(failure)
                .build();
    }

    private static boolean startsBreathing(GatheringRequest request) {
        // 只拦截会让肺开始工作的请求；吸、呼、屏息在没有吐纳时本就无效。
        if (request.hasStart()) {
            return true;
        }
        if (!request.hasBreath()) {
            return false;
        }
        BreathAction action = request.getBreath().getAction();
        return action == BreathAction.BEGIN_BREATHING || action == BreathAction.SET_AUTOMATIC_BREATHING;
    }

    private boolean refiningBreathingOrgan() {
        // 吐纳器官来自人体内容声明，任一侧肺仍有炼化任务都算占用。
        return breathingOrganIds().stream()
                .anyMatch(organId -> HumanOrganRefining.refining(snapshot(), organId));
    }

    private static List<String> breathingOrganIds() {
        // 吐纳与肺炼共用人体内容声明的呼吸器官，不在宿主另写器官名。
        return HumanContent.breathing().getOrgansList().stream()
                .map(organ -> organ.getOrganId())
                .toList();
    }

    HumanOrganRefining.Batch refine(long millis, EffectIntent effect, HumanRefineRecipes recipes) {
        // 起批前结清身体时间，使上一批在同一毫秒完成；肺炼与吐纳互斥，先停下吐纳再取料。
        settle(millis);
        boolean usesLungs = effect.getOrganRefine().getOrganIdsList().stream()
                .anyMatch(breathingOrganIds()::contains);
        if (usesLungs && (lastBreathing.getActive() || lastBreathing.getAutomatic())) {
            stopBreathing(millis);
        }
        HumanOrganRefining.Batch batch = HumanOrganRefining.batch(snapshot(), effect, recipes);
        if (batch.changesBody()) {
            applyActivity(batch.activity());
        }
        scheduleNextBoundary();
        return batch;
    }

    boolean endRefine(long millis, String castId) {
        // 只有本次施放仍占用器官时才结算；之后不再起批，已完成的产物立即送回，仍在加工的一批由周期推进收尾。
        if (!HumanOrganRefining.casting(snapshot(), castId)) {
            return false;
        }
        settle(millis);
        HumanOrganRefining.end(snapshot(), castId).ifPresent(this::applyActivity);
        scheduleNextBoundary();
        return true;
    }

    boolean advanceGathering(long millis) {
        // 只在会话边界执行气流；普通玩家静止且未吐纳时没有这项周期计算。
        targetMillis = millis;
        if (gathering == null || gathering.nextBoundaryMillis() == 0 || millis < gathering.nextBoundaryMillis()) {
            return false;
        }
        settleBeforeSession(millis);
        gathering.synchronizeBodyRevision();
        Set<String> previous = actionIds();
        boolean changed = captureRelease(() -> gathering.advance());
        rememberBreathingActions(previous);
        if (changed) {
            publishGathering();
            scheduleNextBoundary();
        }
        return changed;
    }

    void publishGathering() {
        // 恢复或气流改变后推送同一身体投影，使关闭吐纳后的余力也跟随实际恢复。
        if (gathering != null && gatheringOutput != null && (progress().getQiNotesRead()
                || hasBasicBreathingGrant())) {
            GatheringView view = gathering.view();
            lastBreathing = view.getBreathing();
            gatheringOutput.accept(GatheringResponse.newBuilder().setProtocolVersion(1)
                    .setRequestId("state").setView(view).build());
        }
    }

    void disconnectGathering(long millis) {
        // 先解除旧会话占用再清掉连接；已经进入器官或在途的真元留在权威身体中。
        openingSession = null;
        targetMillis = millis;
        if (!snapshot().vitality().dead() && (HumanCultivation.state(snapshot()).getRunning()
                || HumanCultivation.state(snapshot()).getMobilizing())) {
            settle(millis);
            applyActivity(HumanCultivation.cancel(snapshot()));
        }
        if (gathering != null) {
            settleBeforeSession(millis);
            captureRelease(() -> {
                // 关闭也可能推进身体时间，先收集本次释放再销毁会话。
                gathering.close();
                return null;
            });
            gathering = null;
            lastBreathing = BreathingView.getDefaultInstance();
            gatheringOutput = null;
            breathingActions.clear();
            scheduleNextBoundary();
        }
        // 关闭场景、换维度和断线结束连续尝试，动画与身体动作各按自己的来源清理。
        if (snapshot().openingTask() != null && snapshot().openingTask().active()) {
            pauseOpening(millis);
        }
    }

    FailureCode startOpening(long millis, String meridianId) {
        // 服务端绑定当前玩家，先结清时间再提交选择规则的 Protobuf 意图。
        if (!progress().getQiNotesRead()) {
            return FailureCode.ACCESS_DENIED;
        }
        settle(millis);
        return submitCommand(BodyCommand.newBuilder().setStartMeridianOpening(OpeningMessages.StartMeridianOpening.newBuilder()
                .setRuleId(HumanOpening.ruleId(meridianId)).setRuleVersion(HumanOpening.RULE_VERSION)));
    }

    FailureCode debugQi(long millis, DebugQi request) {
        // 仅由已检查管理员权限的宿主命令调用；临时授权不会泄漏到普通网络处理器。
        settle(millis);
        debugAuthorized = true;
        try {
            return submitCommand(BodyCommand.newBuilder().setDebugQi(request));
        } finally {
            debugAuthorized = false;
        }
    }

    FailureCode cultivate(long millis, CultivationInput input) {
        // 所有普通操作都要求残卷授权；OP 资源命令通过独立权限入口执行。
        if (!progress().getQiNotesRead() || !progress().getMiddleJiaoScrollRead()) {
            return FailureCode.ACCESS_DENIED;
        }
        if (snapshot().vitality().dead()) {
            return FailureCode.BODY_DEAD;
        }
        settle(millis);
        applyActivity(HumanCultivation.update(snapshot(), input));
        scheduleNextBoundary();
        return FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    FailureCode completeOpening(long millis, String target) {
        // 管理命令只给出目标；成长数值来自人体，Framework 共用正常完成与持久化路径。
        settle(millis);
        var plan = ActivityMessages.BodyActivity.newBuilder();
        var targets = snapshot().structure().meridians().stream()
                .filter(item -> target.equals("all") || item.id().value().equals(target)).toList();
        if (targets.isEmpty()) {
            return FailureCode.MODULE_NOT_FOUND;
        }
        for (var definition : targets) {
            plan.addCompletions(ActivityMessages.CompleteMeridian.newBuilder().setMeridianId(definition.id().value())
                    .setAddedVolume(HumanOpening.LUMEN_VOLUME_GAIN));
        }
        applyActivity(plan.build());
        scheduleNextBoundary();
        return FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    BodyMessages.Realm ascend(long millis) {
        // 管理命令把游炁直接推到真炁，走中焦突破成功时同一次相变与境界写入；已是真炁或更高时不提交。
        settle(millis);
        if (snapshot().realm() != Realm.WANDERING_QI) {
            return BodyMessages.Realm.valueOf(snapshot().realm().name());
        }
        applyActivity(HumanCultivation.ascend(snapshot()));
        scheduleNextBoundary();
        return BodyMessages.Realm.TRUE_QI;
    }

    private void applyActivity(ActivityMessages.BodyActivity plan) {
        // 可信人体只提交计划，Framework 再验守恒、容量、疲劳并原子保存环境损耗。
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(id.value()).setExpectedRevision(snapshot().revision())
                        .setExecuteActivity(plan)).build();
        ResponseEnvelope response = MeridianProtocol.decodeResponse(captureRelease(
                () -> commands.handleActivity(id.value(), request.toByteArray())));
        if (response.hasFailure()) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
    }

    ResponseEnvelope applyTechnique(long millis, TechniqueHostRequest request) {
        // 固定可信身份保留功法原始请求与预期版本，释放沿现有世界归位回调处理。
        settle(millis);
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(request.getExpectedRevision())
                .setExecuteActivity(request.getActivity())
                .build();
        RequestEnvelope envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(request.getRequestId())
                .setBodyCommand(command)
                .build();
        byte[] payload = MeridianProtocol.encodeRequest(envelope);
        ResponseEnvelope response = MeridianProtocol.decodeResponse(captureRelease(
                () -> commands.handleActivity(TechniqueHost.CALLER_ID, payload)));
        scheduleNextBoundary();
        return response;
    }

    ResponseEnvelope applyAddonActivity(long millis, String callerId, RequestEnvelope request) {
        // 来源只在可信进程内调用的动态范围内授权，网络入口不能借用此身份。
        if (trustedActivityCaller != null) {
            throw HumanErrors.hostRejected(FailureCode.ACCESS_DENIED);
        }
        settle(millis);
        trustedActivityCaller = callerId;
        try {
            byte[] payload = MeridianProtocol.encodeRequest(request);
            byte[] result = captureRelease(() -> commands.handleActivity(callerId, payload));
            return MeridianProtocol.decodeResponse(result);
        } finally {
            trustedActivityCaller = null;
            scheduleNextBoundary();
        }
    }

    public long submitRecovery(String requestId, BodyCommand command) {
        // 恢复只接收功法服务生成的命令，可信进度与活动仍经过 Framework 授权和版本校验。
        RequestEnvelope envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        byte[] payload = MeridianProtocol.encodeRequest(envelope);
        byte[] result;
        if (command.hasSetAddonProgress()) {
            result = commands.handleAddonProgress(id.value(), payload);
        } else {
            result = captureRelease(() -> commands.handleActivity(TechniqueHost.CALLER_ID, payload));
        }
        ResponseEnvelope response = MeridianProtocol.decodeResponse(result);
        if (response.hasFailure()) {
            throw new TechniqueErrors.HostRejected(response.getFailure().getCode());
        }
        scheduleNextBoundary();
        return response.getCommandCommitted().getRevision();
    }

    String diffusionEntry() {
        // 默认注入肺出口实际连接的经脉，依据人体内容寻址，不向 Framework 写入人类特例。
        BodySnapshot body = snapshot();
        var organs = HumanContent.breathing().getOrgansList().stream().map(organ -> organ.getOrganId()).toList();
        var ports = body.structure().organs().stream().filter(organ -> organs.contains(organ.id().value()))
                .flatMap(organ -> organ.ports().stream()).map(Anatomy.Port::id).toList();
        var targets = body.structure().connections().stream().filter(route -> ports.contains(route.from()))
                .map(Anatomy.Connection::to).toList();
        return body.structure().meridians().stream()
                .filter(meridian -> meridian.ports().stream().anyMatch(port -> targets.contains(port.id())))
                .map(meridian -> meridian.id().value()).findFirst().orElse("");
    }

    FailureCode pauseOpening(long millis) {
        // 暂停基于权威任务目标，客户端不能指定另一具身体或自报余额。
        settle(millis);
        var task = snapshot().openingTask();
        if (task == null || !task.active()) {
            return FailureCode.OPENING_NOT_ACTIVE;
        }
        return submitCommand(BodyCommand.newBuilder().setPauseMeridianOpening(OpeningMessages.PauseMeridianOpening.newBuilder()
                .setMeridianId(task.rule().targetMeridianId().value())));
    }

    private FailureCode submitCommand(BodyCommand.Builder command) {
        // 业务拒绝供玩家调整储量或资源，不应把整个人体标记为宿主故障。
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command.setBodyId(id.value()).setExpectedRevision(snapshot().revision())).build();
        ResponseEnvelope response = MeridianProtocol.decodeResponse(captureRelease(
                () -> commands.handle(id.value(), request.toByteArray())));
        scheduleNextBoundary();
        publishGathering();
        return response.hasFailure() ? response.getFailure().getCode() : FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    ResponseEnvelope applyExertion(long millis, RequestEnvelope request) {
        // 身体身份已由宿主核对；普通命令保留原请求与版本。
        // 能力不足时直接返回业务拒绝。
        settle(millis);
        byte[] payload = MeridianProtocol.encodeRequest(request);
        ResponseEnvelope response = MeridianProtocol.decodeResponse(captureRelease(
                () -> commands.handle(id.value(), payload)));
        scheduleNextBoundary();
        publishGathering();
        return response;
    }

    void revive(long millis) {
        // 死态不推进生理时间；复活命令直接使用当前宿主时刻并只扣一次寿元。
        var command = BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(snapshot().revision())
                .setReviveBody(HumanRevival.request("human:respawn"))
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command)
                .build();
        ResponseEnvelope response = applyHealth(millis, request);
        if (response.hasFailure()) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
    }

    ResponseEnvelope applyHealth(long millis, RequestEnvelope request) {
        // 独立伤害附属保留自己的事件身份与读取版本，宿主不能替它刷新版本绕过并发校验。
        settle(millis);
        ResponseEnvelope response = MeridianProtocol.decodeResponse(
                captureRelease(() -> commands.handleHealthEffect(id.value(), MeridianProtocol.encodeRequest(request))));
        if (response.hasCommandCommitted() && snapshot().vitality().dead()) {
            abandonPractice();
        }
        scheduleNextBoundary();
        publishGathering();
        return response;
    }

    dev.kizuna.meridian.protocol.pb.HealthMessages.HealthResult healthReceipt(String requestId) {
        // 从同一身体的幂等索引读取固定事实，避免扫描全世界回执或重新执行伤害策略。
        return store.transact(id, transaction -> transaction.receipt(id.value(), requestId)
                .orElseThrow().getHealth());
    }

    ResponseEnvelope open(long millis, OpeningMessages.MeridianOpeningRequest request) {
        // 连接内的意图使用框架编排；不把客户端版本替换成当前版本来绕过冲突检查。
        if (!progress().getQiNotesRead()) {
            return ResponseEnvelope.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId())
                    .setFailure(RequestFailure.newBuilder().setCode(FailureCode.ACCESS_DENIED)).build();
        }
        settleBeforeSession(millis);
        targetMillis = millis;
        if (openingSession == null) {
            openingSession = new OpeningSession<>(id.value(), id, commands, store,
                    (caller, requested) -> caller.equals(id.value()) && requested.equals(id) && progress().getQiNotesRead());
        }
        ResponseEnvelope response = captureRelease(() -> openingSession.handle(request));
        scheduleNextBoundary();
        publishGathering();
        return response;
    }

    Set<String> breathingActions() {
        // 保存宿主自己的占用身份，重连不会误删其他附属启动的动作。
        return Set.copyOf(breathingActions);
    }

    private void settleBeforeSession(long millis) {
        // 吐纳和开脉能直接推进框架时钟，持守时也要先经过相同的成架和耗尽边界。
        var body = snapshot();
        if (HumanGuardStamina.holding(body).isPresent() || TechniqueBreathingPractice.active(body).isPresent()) {
            settle(millis);
        }
    }

    private Set<String> actionIds() {
        // 记录会话执行前的身份集，用差集追踪本次新增占用。
        return snapshot().actions().stream().map(action -> action.instanceId())
                .collect(java.util.stream.Collectors.toSet());
    }

    private void rememberBreathingActions(Set<String> previous) {
        // 服务器串行执行期间只可能由该会话新增动作，结束后的身份及时移除。
        Set<String> current = actionIds();
        breathingActions.retainAll(current);
        current.removeAll(previous);
        breathingActions.addAll(current);
    }

    QiInventory releasedQi() {
        // 寿尽释放保留唯一账本，宿主后续可按世界环境规则接收。
        return store.environmentQi();
    }

    void bindReleaseSink(Consumer<QiInventory> sink) {
        // 世界宿主在恢复或执行前绑定真实位置路由；绑定本身不重发共享账本中的历史释放。
        releaseSink = java.util.Objects.requireNonNull(sink);
    }

    void bindMovementLoad(DoubleSupplier load) {
        // 世界宿主按实时玩家实体读取负荷；独立身体测试保持无负荷倍率。
        movementLoad = java.util.Objects.requireNonNull(load);
    }

    void bindPracticeEnd(Runnable callback) {
        // 同一玩家更换实体后，撤效总是落在当前实体上。
        practiceEnd = java.util.Objects.requireNonNull(callback);
    }

    boolean beginPractice(long millis, String castId, TechniqueDefinition definition) {
        // 切换后的首次接触用新摄入倍率，截止切换时刻的膈肌负担用旧费率。
        settle(millis);
        var body = snapshot();
        if (body.vitality().dead() || !TechniqueBreathingPractice.canPay(body)) {
            return false;
        }
        var change = TechniqueBreathingPractice.begin(body, castId, definition.getId(),
                definition.getMaintainedBreathing());
        if (change.isEmpty()) {
            return false;
        }
        applyActivity(change.orElseThrow());
        finishBreathingInterval(BreathingModifiers.read(body));
        scheduleNextBoundary();
        publishGathering();
        return true;
    }

    boolean endPractice(long millis) {
        // 断线、外部清效和主动收势可能重合，按身体中正在维持的身份结束一次。
        if (TechniqueBreathingPractice.active(snapshot()).isEmpty()) {
            return false;
        }
        settle(millis);
        settlePractice(Settlement.END);
        scheduleNextBoundary();
        return true;
    }


    private void settlePractice(Settlement mode) {
        // 帐款、付讫游标和撤效是一笔活动。
        // 随后结清已付时间的肌肉负载，同刻粒子读取新倍率。
        var body = snapshot();
        if (!body.addonProgress().containsKey(TechniqueBreathingPractice.KEY)) {
            return;
        }
        if (body.vitality().dead()) {
            abandonPractice();
            return;
        }
        var plan = TechniqueBreathingPractice.settle(body, mode);
        if (plan.isEmpty()) {
            return;
        }
        var activity = plan.orElseThrow();
        applyActivity(activity);
        if (activity.containsProgress(BreathingModifiers.KEY)) {
            practiceEnd.run();
            finishBreathingInterval(BreathingModifiers.read(body));
            publishGathering();
        }
    }

    private void finishBreathingInterval(BreathingModifiers previous) {
        // 守住会话动作占用、释放回调以及同一步内的输入和空气状态。
        if (gathering != null && !snapshot().vitality().dead()) {
            var actions = actionIds();
            captureRelease(() -> {
                gathering.settleBreathingModifierBoundary(previous);
                return null;
            });
            rememberBreathingActions(actions);
        }
    }

    private void abandonPractice() {
        // 死体允许可信进度清理；异常重启先撤旧因子，再推进离线时钟。
        var body = snapshot();
        var abandoned = TechniqueBreathingPractice.abandoned(body);
        if (!BreathingModifiers.NORMAL.equals(BreathingModifiers.read(body))) {
            replacePracticeProgress(BreathingModifiers.KEY, BreathingModifiers.NORMAL.pack());
        }
        abandoned.ifPresent(state -> replacePracticeProgress(TechniqueBreathingPractice.KEY, state));
        if (abandoned.isPresent()) {
            practiceEnd.run();
        }
    }

    private void replacePracticeProgress(String key, com.google.protobuf.Any value) {
        // 每次重新读取版本，重读档在两次写之间中断后可继续清理。
        var change = SetAddonProgress.newBuilder()
                .setId(key)
                .setProgress(value)
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(snapshot().revision())
                .setSetAddonProgress(change)
                .build();
        submitRecovery(UUID.randomUUID().toString(), command);
    }

    private <T> T captureRelease(Supplier<T> operation) {
        // 串行宿主按实际命令或会话划定增量，即使后续步骤失败也归位已经提交的释放。
        QiInventory before = store.environmentQi();
        try {
            return operation.get();
        } finally {
            var entries = new java.util.ArrayList<Qi>();
            for (Qi after : store.environmentQi().entries()) {
                Qi previous = before.entries().stream().filter(entry -> entry.phase() == after.phase()
                        && entry.attribute().equals(after.attribute())).findFirst().orElse(null);
                double amount = after.amount() - (previous == null ? 0 : previous.amount());
                if (amount > 0) {
                    // 合并账本会改变密度，必须同时扣除历史体积才能还原本次释放。
                    double volume = after.volume() - (previous == null ? 0 : previous.volume());
                    double density = previous == null || previous.density() == after.density()
                            ? after.density() : amount / volume;
                    density = dev.kizuna.meridian.protocol.QiDensity.clampToPhase(density,
                            BodyMessages.QiPhase.valueOf(after.phase().name()));
                    entries.add(new Qi(after.attribute(), amount, density));
                }
            }
            QiInventory released = new QiInventory(entries);
            if (!released.entries().isEmpty()) {
                releaseSink.accept(released);
            }
        }
    }

    Motion motion() {
        // 显示已接受的服务端运动状态，不能从客户端按键猜测。
        return motion;
    }

    private boolean hasMovementAction() {
        // 只管理 Minecraft 移动自己的实例，保留吐纳与功法的其他占用。
        return snapshot().actions().stream().anyMatch(action -> action.instanceId().equals(MOVEMENT_INSTANCE));
    }

    private void advanceTime() {
        // 同毫秒的新资源变化也可能启动被动扩散；只有确有事件时才额外结算。
        BodySnapshot body = snapshot();
        if (body.vitality().dead()) {
            return;
        }
        BodyTimeAdvancement.Mode mode = BodyTimeAdvancement.Mode.ACTIVE;
        if (timeMode == TimeAdvanceMode.LIFESPAN_ONLY) {
            mode = BodyTimeAdvancement.Mode.LIFESPAN_ONLY;
        }
        boolean eventDue = BodyTimeAdvancement.nextEventBoundary(body, agingRate, mode)
                .map(boundary -> boundary.elapsedMillis() <= targetMillis).orElse(false);
        if (targetMillis != body.settledAt().elapsedMillis() || eventDue) {
            execute(BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        }
    }

    private void execute(BodyCommand.Builder command) {
        // 每笔内部意图有独立身份和当前版本；收到明确成功后才继续下一步编排。
        ResponseEnvelope response = executeCommand(command);
        if (response.hasFailure()) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
    }

    private ResponseEnvelope executeCommand(BodyCommand.Builder command) {
        // 先固定身体版本，再提交一次；业务拒绝由对应动作决定如何处理。
        BodyCommand operation = command
                .setBodyId(id.value())
                .setExpectedRevision(snapshot().revision())
                .build();
        RequestEnvelope request = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(operation)
                .build();
        return MeridianProtocol.decodeResponse(captureRelease(
                () -> commands.handle(id.value(), request.toByteArray())));
    }

    private void scheduleNextBoundary() {
        // 满恢复的静止身体只等待已有事件或真实动作变化，不安排恢复轮询。
        BodySnapshot body = snapshot();
        if (body.vitality().dead()) {
            nextBoundaryMillis = Long.MAX_VALUE;
            return;
        }
        long existingEvent = BodyTimeAdvancement.nextEventBoundary(body, agingRate, BodyTimeAdvancement.Mode.ACTIVE)
                .map(boundary -> boundary.elapsedMillis()).orElse(Long.MAX_VALUE);
        boolean hasFatigue = body.tissueStates().stream().anyMatch(tissue -> tissue.fatigue() > 0)
                || body.organStates().stream().anyMatch(organ -> organ.fatigueJoules() > 0);
        nextBoundaryMillis = existingEvent;
        if (motion != Motion.IDLE || hasFatigue) {
            nextBoundaryMillis = Math.min(existingEvent, accountedMillis + 1000);
        }
        if (needsCultivationAdvance(body)) {
            nextBoundaryMillis = Math.min(nextBoundaryMillis, accountedMillis + HumanCultivationNumbers.STEP_MILLIS);
        }
        var expires = TechniqueBreathingPractice.expiresAt(body);
        if (expires.isPresent()) {
            long nextPayment = Math.min(expires.getAsLong(), accountedMillis + PRACTICE_BILLING_MILLIS);
            nextBoundaryMillis = Math.min(nextBoundaryMillis, nextPayment);
        }
    }

    private long nextStepMillis() {
        // 从身体游标和运动账较晚的一个起再走一步修炼步长。
        return Math.max(accountedMillis, snapshot().settledAt().elapsedMillis()) + HumanCultivationNumbers.STEP_MILLIS;
    }

    private boolean needsCultivationAdvance(BodySnapshot body) {
        // 快照不可变：同一个快照只算一次要不要推进中焦和储备，结算、排下一边界接连问时不再重建真元计划。
        if (body != cultivationChecked) {
            cultivationNeeded = HumanCultivation.needsAdvance(body);
            cultivationChecked = body;
        }
        return cultivationNeeded;
    }

    private static AddonRegistry installContent() {
        // Fabric 与参考宿主使用同一清单校验，无人体内容的框架仍可独立运行。
        var host = AddonHostProfile.newBuilder().setHostId("meridian:fabric").setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES).addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE).addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY).addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS).addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE);
        return AddonRegistry.load(AddonRegistration.newBuilder().setProtocolVersion(1).setHost(host)
                .addBundles(HumanContent.bundle()).build());
    }
}
