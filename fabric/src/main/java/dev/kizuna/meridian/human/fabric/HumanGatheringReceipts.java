package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 发给客户端的吐纳回执：不带身体，连续推送限频。
 *
 * <p>身体走限频的身体视图通道，回执只换吐纳、阶段、环境和配方组合。请求的回复、吐纳状态翻转、粒子走了一步
 * 和阶段变化立刻发；只是余量在变的推送每名玩家最多每 5 tick 一份，攒下的最新一份到时补发。
 */
final class HumanGatheringReceipts {
    /** 自动吐纳每 250 毫秒（5 tick）走一步，只是余量在变的推送和它同频。 */
    static final int INTERVAL_TICKS = 5;
    /** 宿主自己推的回执用这个请求 ID，其余都是对客户端请求的回复。 */
    static final String STATE_REQUEST_ID = "state";
    private final Map<UUID, GatheringView> sentViews = new HashMap<>();
    private final Map<UUID, Integer> sentTicks = new HashMap<>();
    private final Map<UUID, GatheringResponse> pending = new HashMap<>();

    Optional<GatheringResponse> offer(UUID playerId, GatheringResponse response, int tick) {
        // 返回现在要发的回执；不到时候的推送先攒着，只留最新一份。
        GatheringResponse receipt = withoutBody(response);
        if (receipt.getRequestId().equals(STATE_REQUEST_ID) && receipt.hasView() && !changesStage(playerId, receipt)
                && tick - sentTicks.get(playerId) < INTERVAL_TICKS) {
            pending.put(playerId, receipt);
            return Optional.empty();
        }
        return Optional.of(sent(playerId, receipt, tick));
    }

    Optional<GatheringResponse> due(UUID playerId, int tick) {
        // 攒下的推送满 5 tick 补发，客户端最终总能看到最后的余量。
        GatheringResponse receipt = pending.get(playerId);
        if (receipt == null || tick - sentTicks.get(playerId) < INTERVAL_TICKS) {
            return Optional.empty();
        }
        return Optional.of(sent(playerId, receipt, tick));
    }

    void forget(UUID playerId) {
        // 断线、换会话后从头开始，第一份回执不等。
        sentViews.remove(playerId);
        sentTicks.remove(playerId);
        pending.remove(playerId);
    }

    void clear() {
        // 服务器停止时丢掉全部玩家。
        sentViews.clear();
        sentTicks.clear();
        pending.clear();
    }

    private GatheringResponse sent(UUID playerId, GatheringResponse receipt, int tick) {
        // 带视图的这份取代攒着的旧推送；只带失败码的回复不换客户端手里的吐纳，攒着的照旧到时补发。
        if (receipt.hasView()) {
            pending.remove(playerId);
            sentTicks.put(playerId, tick);
            sentViews.put(playerId, receipt.getView());
        }
        return receipt;
    }

    private boolean changesStage(UUID playerId, GatheringResponse receipt) {
        // 和上一份比，除了余量还有没有别的变化；第一份总算有变化。
        GatheringView previous = sentViews.get(playerId);
        if (previous == null) {
            return true;
        }
        BreathingView before = previous.getBreathing();
        BreathingView after = receipt.getView().getBreathing();
        boolean sameBreathing = before.getAvailable() == after.getAvailable()
                && before.getActive() == after.getActive()
                && before.getAutomatic() == after.getAutomatic()
                && before.getAction() == after.getAction()
                && before.getSequence() == after.getSequence()
                && before.getFailure() == after.getFailure()
                && before.getStatus().equals(after.getStatus())
                && before.getParticlesList().equals(after.getParticlesList());
        return !sameBreathing || !previous.getProgress().equals(receipt.getView().getProgress());
    }

    private static GatheringResponse withoutBody(GatheringResponse response) {
        // 身体由身体视图通道推送，回执里不再带。
        if (!response.hasView()) {
            return response;
        }
        GatheringView view = response.getView().toBuilder()
                .clearBody()
                .build();
        return response.toBuilder()
                .setView(view)
                .build();
    }
}
