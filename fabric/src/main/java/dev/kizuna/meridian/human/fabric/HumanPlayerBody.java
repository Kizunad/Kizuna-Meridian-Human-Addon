package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.human.server.HumanCultivationNumbers;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput;
import dev.kizuna.meridian.protocol.pb.ActivityMessages;
import dev.kizuna.meridian.protocol.pb.OpeningMessages;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.GatheringSession;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.OpeningSession;
import dev.kizuna.meridian.progression.ProgressionProtocol;
import dev.kizuna.meridian.progression.pb.ProgressionMessages.PlayerProgress;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** 单玩家的可信运动编排；账本、占用与恢复只由框架命令处理器修改。 */
final class HumanPlayerBody {
    static final String MOVEMENT_INSTANCE = "human.minecraft.locomotion";
    static final double WALK_ENDURANCE_SECONDS = 300;
    static final double SPRINT_ENDURANCE_SECONDS = 75;
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
    private TimeAdvanceMode timeMode = TimeAdvanceMode.ACTIVE;
    private String sourceId;
    private GatheringSession<String> gathering;
    private OpeningSession<String> openingSession;
    private Consumer<GatheringResponse> gatheringOutput;
    private final Set<String> breathingActions = new HashSet<>();
    private boolean debugAuthorized;
    private BreathingView lastBreathing = BreathingView.getDefaultInstance();

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
        BodyTimeSource<String> clock = (caller, body) -> TimeAdvancePlan.newBuilder()
                .setTarget(BodyProtobuf.toTime(body.settledAt()).toBuilder().setElapsedMillis(targetMillis))
                .setBiologicalSecondsPerSimulationSecond(1).setMode(timeMode).build();
        commands = new BodyCommandHandler<>((caller, command) -> {
            // 仅内部调度身份可提交命令；网络入口不暴露这些资源变更请求。
            if (caller.equals(id.value()) && command.getBodyId().equals(id.value())) {
                return Optional.of(caller);
            }
            return Optional.empty();
        }, (caller, command, body) -> true, clock,
                store, (caller, receipt, before, after) -> { /* 保存由宿主在事件完成后统一标脏。 */ },
                CONTENT.recipes(), (caller, body, plan) -> Optional.ofNullable(sourceId), CONTENT.actions(), CONTENT.openings(),
                caller -> debugAuthorized && caller.equals(id.value()), new HumanDamage());
        if (!saved.addonProgress().containsKey(ProgressionProtocol.PROGRESS_KEY)) {
            // 新身体与尚无进度字段的存档都从未读开始，并写入同一身体事务。
            writeProgress(PlayerProgress.newBuilder().setSchemaVersion(ProgressionProtocol.PROGRESS_SCHEMA).build());
        }
    }

    PlayerProgress progress() {
        // 只读取身体内的唯一事实；未知类型或版本不能被解释成已解锁。
        try {
            PlayerProgress progress = snapshot().addonProgress().get(ProgressionProtocol.PROGRESS_KEY)
                    .unpack(PlayerProgress.class);
            if (progress.getSchemaVersion() != ProgressionProtocol.PROGRESS_SCHEMA) {
                throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
            }
            return progress;
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
        }
    }

    boolean canIntrospect(String bodyId) {
        // 内观订阅同时校验身体归属和已读事实，客户端隐藏入口不能代替服务端授权。
        return id.value().equals(bodyId) && progress().getQiNotesRead();
    }

    void writeProgress(PlayerProgress progress) {
        // 重置先终止会话与占用；同一服务器线程随后提交进度，不允许旧输入继续执行。
        if (progress.getSchemaVersion() != ProgressionProtocol.PROGRESS_SCHEMA) {
            throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
        }
        PlayerProgress previous = snapshot().addonProgress().containsKey(ProgressionProtocol.PROGRESS_KEY)
                ? progress() : PlayerProgress.getDefaultInstance();
        if (previous.getQiNotesRead() && !progress.getQiNotesRead()
                || previous.getGatheringScrollRead() && !progress.getGatheringScrollRead()
                || previous.getMiddleJiaoScrollRead() && !progress.getMiddleJiaoScrollRead()) {
            // 只在撤权时结束旧活动；阅读新卷不会断开已经接好的吐纳会话。
            disconnectGathering(targetMillis);
        }
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(id.value()).setExpectedRevision(snapshot().revision())
                        .setSetAddonProgress(SetAddonProgress.newBuilder().setId(ProgressionProtocol.PROGRESS_KEY)
                                .setProgress(com.google.protobuf.Any.pack(progress)))).build();
        ResponseEnvelope response = MeridianProtocol.decodeResponse(commands.handleAddonProgress(id.value(), request.toByteArray()));
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
        // 离线区间仅按世界模拟时间推进年龄，暂停的单机世界没有额外现实时间恢复。
        targetMillis = millis;
        timeMode = TimeAdvanceMode.LIFESPAN_ONLY;
        advanceTime();
        timeMode = TimeAdvanceMode.ACTIVE;
        accountedMillis = millis;
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
        boolean stoppedBreathing = moved && lastBreathing.getActive();
        if (stoppedBreathing) {
            // 与界面结束按钮使用同一个 END_BREATHING 意图，先结清气流并解除呼吸占用。
            GatheringResponse stopped = gather(millis, GatheringRequest.newBuilder().setProtocolVersion(1)
                    .setRequestId(UUID.randomUUID().toString()).setBodyId(id.value())
                    .setBreath(BreathControl.newBuilder().setAction(BreathAction.END_BREATHING)
                            .setSequence(lastBreathing.getSequence() + 1)).build());
            if (stopped.hasFailure()) {
                throw HumanErrors.hostRejected(stopped.getFailure().getCode());
            }
            gatheringOutput.accept(stopped);
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
        if (observed != motion) {
            // 先结清旧动作整个区间，再停止旧占用和建立新占用，防止边走边恢复。
            if (hasMovementAction()) {
                execute(BodyCommand.newBuilder().setStopAction(StopAction.newBuilder().setInstanceId(MOVEMENT_INSTANCE)));
            }
            if (observed != Motion.IDLE) {
                execute(BodyCommand.newBuilder().setStartAction(StartAction.newBuilder().setInstanceId(MOVEMENT_INSTANCE)
                        .setActionId(observed.actionId).setActionVersion(1)));
            }
            motion = observed;
        }
        scheduleNextBoundary();
        return true;
    }

    boolean breathingActive() {
        // 缓存只来自权威会话回执，避免每 tick 重建整个身体展示快照。
        return lastBreathing.getActive();
    }

    void settle(long millis) {
        // 长区间按活动边界逐步结算，不能把60秒燃料一次检查或在结束后补扣疲劳。
        while (HumanCultivation.needsAdvance(snapshot()) && !snapshot().vitality().dead()
                && Math.max(accountedMillis, snapshot().settledAt().elapsedMillis()) + HumanCultivationNumbers.STEP_MILLIS < millis) {
            // 吐纳或开脉会先推进身体游标，运动账尚未结清时也不能回拨身体时钟。
            settleInterval(Math.max(accountedMillis, snapshot().settledAt().elapsedMillis()) + HumanCultivationNumbers.STEP_MILLIS);
        }
        settleInterval(millis);
    }

    private void settleInterval(long millis) {
        // 同一时刻的观察和查询不能重复扣费；时间来自服务端主世界累计 tick。
        if (millis < accountedMillis) {
            throw HumanErrors.invalid(HumanErrors.INVALID_MOVEMENT_TIME);
        }
        double elapsedSeconds = (millis - accountedMillis) / 1000.0;
        targetMillis = millis;
        advanceTime();
        if (elapsedSeconds > 0 && motion != Motion.IDLE && !snapshot().vitality().dead()) {
            // 行走按基础耐力的 1/300 每秒、疾跑按 1/75 每秒申请做功；伤势和疲劳仍限制实际能力。
            double enduranceSeconds = motion == Motion.SPRINT ? SPRINT_ENDURANCE_SECONDS : WALK_ENDURANCE_SECONDS;
            BodySnapshot body = snapshot();
            var loads = ExertTissues.newBuilder();
            var action = CONTENT.actions().find(motion.actionId, 1).orElseThrow();
            for (String tissueId : action.getTissueIdsList()) {
                Anatomy.TissueDefinition definition = body.structure().tissues().stream()
                        .filter(tissue -> tissue.id().value().equals(tissueId)).findFirst().orElseThrow();
                Anatomy.TissueCapacity available = TissueCapabilities.available(body, definition.id());
                double desiredWatts = definition.capacity().enduranceJoules() / enduranceSeconds;
                double payableWatts = Math.min(desiredWatts, available.burstWatts());
                payableWatts = Math.min(payableWatts, available.enduranceJoules() / elapsedSeconds);
                if (payableWatts > 0) {
                    loads.addLoads(ExertTissue.newBuilder().setTissueId(tissueId).setForceNewtons(0)
                            .setPowerWatts(payableWatts).setDurationSeconds(elapsedSeconds));
                }
            }
            if (loads.getLoadsCount() > 0) {
                execute(BodyCommand.newBuilder().setExertTissues(loads));
            }
        }
        accountedMillis = millis;
        boolean cultivationDue = !snapshot().addonProgress().containsKey(HumanCultivation.PROGRESS)
                || HumanCultivation.state(snapshot()).getLastMillis() < snapshot().settledAt().elapsedMillis();
        // 同tick的健康读取与提交必须看到同一版本；没有新的活动时间就不能空提交进度。
        if (!snapshot().vitality().dead() && cultivationDue && HumanCultivation.needsAdvance(snapshot())) {
            applyActivity(HumanCultivation.update(snapshot(), null));
        }
        scheduleNextBoundary();
    }

    BodySnapshot snapshot() {
        // 暴露不可变权威事实供保存和展示，不提供外部写入入口。
        return store.find(id).orElseThrow();
    }

    List<CommandReceipt> receipts() {
        // 去重事实与身体放进同一份世界持久化记录。
        return store.receipts().stream().filter(receipt -> receipt.getCommand().getBodyId().equals(id.value())).toList();
    }

    void selectEnvironment(String sourceId, long millis) {
        // 跨区域先结清旧位置的时间，不能用新区域浓度追溯领取上一段灵气。
        if (!java.util.Objects.equals(this.sourceId, sourceId)) {
            settle(millis);
            this.sourceId = sourceId;
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
                        .setBiologicalSecondsPerSimulationSecond(1).setMode(timeMode).build(),
                body -> sourceId == null ? Optional.empty() : store.environmentSource(sourceId), CONTENT.recipes());
        gathering.enableBreathing(HumanContent.breathing());
        gatheringOutput = output;
    }

    GatheringResponse gather(long millis, GatheringRequest request) {
        // 请求只有输入方向与序号；玩家、时钟、环境和能力由当前宿主绑定。
        if (!progress().getQiNotesRead() || !progress().getGatheringScrollRead()) {
            return GatheringResponse.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId())
                    .setFailure(RequestFailure.newBuilder().setCode(FailureCode.ACCESS_DENIED)).build();
        }
        targetMillis = millis;
        gathering.synchronizeBodyRevision();
        Set<String> previous = actionIds();
        try {
            GatheringResponse response = GatheringResponse.parseFrom(gathering.handle(request.toByteArray()));
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

    boolean advanceGathering(long millis) {
        // 只在会话边界执行气流；普通玩家静止且未吐纳时没有这项周期计算。
        targetMillis = millis;
        if (gathering == null || gathering.nextBoundaryMillis() == 0 || millis < gathering.nextBoundaryMillis()) {
            return false;
        }
        gathering.synchronizeBodyRevision();
        Set<String> previous = actionIds();
        boolean changed = gathering.advance();
        rememberBreathingActions(previous);
        if (changed) {
            publishGathering();
            scheduleNextBoundary();
        }
        return changed;
    }

    void publishGathering() {
        // 恢复或气流改变后推送同一身体投影，使关闭吐纳后的余力也跟随实际恢复。
        if (gathering != null && gatheringOutput != null && progress().getQiNotesRead()) {
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
            gathering.close();
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

    private void applyActivity(ActivityMessages.BodyActivity plan) {
        // 可信人体只提交计划，Framework 再验守恒、容量、疲劳并原子保存环境损耗。
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(id.value()).setExpectedRevision(snapshot().revision())
                        .setExecuteActivity(plan)).build();
        ResponseEnvelope response = MeridianProtocol.decodeResponse(commands.handleActivity(id.value(), request.toByteArray()));
        if (response.hasFailure()) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
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
        ResponseEnvelope response = MeridianProtocol.decodeResponse(commands.handle(id.value(), request.toByteArray()));
        scheduleNextBoundary();
        publishGathering();
        return response.hasFailure() ? response.getFailure().getCode() : FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    ResponseEnvelope applyHealth(long millis, RequestEnvelope request) {
        // 独立伤害附属保留自己的事件身份与读取版本，宿主不能替它刷新版本绕过并发校验。
        settle(millis);
        ResponseEnvelope response = MeridianProtocol.decodeResponse(
                commands.handleHealthEffect(id.value(), MeridianProtocol.encodeRequest(request)));
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
        targetMillis = millis;
        if (openingSession == null) {
            openingSession = new OpeningSession<>(id.value(), id, commands, store,
                    (caller, requested) -> caller.equals(id.value()) && requested.equals(id) && progress().getQiNotesRead());
        }
        ResponseEnvelope response = openingSession.handle(request);
        scheduleNextBoundary();
        publishGathering();
        return response;
    }

    Set<String> breathingActions() {
        // 保存宿主自己的占用身份，重连不会误删其他附属启动的动作。
        return Set.copyOf(breathingActions);
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
        boolean eventDue = BodyTimeAdvancement.nextEventBoundary(body, 1, mode)
                .map(boundary -> boundary.elapsedMillis() <= targetMillis).orElse(false);
        if (targetMillis != body.settledAt().elapsedMillis() || eventDue) {
            execute(BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        }
    }

    private void execute(BodyCommand.Builder command) {
        // 每笔内部意图有独立身份和当前版本；收到明确成功后才继续下一步编排。
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(command.setBodyId(id.value()).setExpectedRevision(snapshot().revision())).build();
        ResponseEnvelope response = MeridianProtocol.decodeResponse(commands.handle(id.value(), request.toByteArray()));
        if (response.hasFailure()) {
            throw HumanErrors.hostRejected(response.getFailure().getCode());
        }
    }

    private void scheduleNextBoundary() {
        // 满恢复的静止身体只等待已有事件或真实动作变化，不安排恢复轮询。
        BodySnapshot body = snapshot();
        if (body.vitality().dead()) {
            nextBoundaryMillis = Long.MAX_VALUE;
            return;
        }
        long existingEvent = BodyTimeAdvancement.nextEventBoundary(body, 1, BodyTimeAdvancement.Mode.ACTIVE)
                .map(boundary -> boundary.elapsedMillis()).orElse(Long.MAX_VALUE);
        boolean hasFatigue = body.tissueStates().stream().anyMatch(tissue -> tissue.fatigue() > 0)
                || body.organStates().stream().anyMatch(organ -> organ.fatigueJoules() > 0);
        nextBoundaryMillis = existingEvent;
        if (motion != Motion.IDLE || hasFatigue) {
            nextBoundaryMillis = Math.min(existingEvent, accountedMillis + 1000);
        }
        if (HumanCultivation.needsAdvance(body)) {
            nextBoundaryMillis = Math.min(nextBoundaryMillis, accountedMillis + HumanCultivationNumbers.STEP_MILLIS);
        }
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
