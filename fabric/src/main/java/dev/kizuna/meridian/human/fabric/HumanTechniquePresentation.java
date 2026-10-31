package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.CastHand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 功法表现的服务端钩子；动画适配器负责将原始起播时刻和停止意图同步到客户端。 */
public final class HumanTechniquePresentation {
    private static final long MILLIS_PER_TICK = 50;
    private static final Map<UUID, Map<String, String>> playing =
            new HashMap<>();

    static void apply(ServerPlayer player, EffectIntent effect) {
        // 内容命名空间与 PA 剪辑目录分离，未交付剪辑的表现意图仍然成功。
        var presentation = effect.getPresentation();
        String key = effect.getCastId() + "/" + presentation.getId();
        var clips = playing.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        if (presentation.getAction() == PresentationAction.STOP) {
            String previous = clips.remove(key);
            if (previous != null) {
                ServerPlayerAnimations.stop(player, previous);
            }
            return;
        }
        var input = effect.getDirection();
        var direction = new HumanTechniqueAnimations.Direction(player.getYRot(), input.getX(), input.getZ());
        CastHand hand = effect.getSelectedHand() == Hand.OFF_HAND ? CastHand.OFF : CastHand.MAIN;
        String animationId = HumanTechniqueAnimations.resolve(presentation.getId(), direction, hand);
        PlayerAnimationAction action = PlayerAnimationAction.find(animationId);
        if (action == null || !action.delivered()) {
            return;
        }
        long nowMillis = player.getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        long lateness = Math.max(0, nowMillis - effect.getScheduledMillis());
        long offsetTicks = (presentation.getStartMillis() + lateness) / MILLIS_PER_TICK;
        long startTick = Math.max(0, player.level().getGameTime() - offsetTicks);
        ServerPlayerAnimations.playFrom(player, animationId, startTick);
        clips.put(key, animationId);
    }

    static void forget(UUID playerId) {
        // 断开后不保留旧施放与实际方向剪辑的关联。
        playing.remove(playerId);
    }

    static void clear() {
        // 服务器生命周期结束时释放表现引用。
        playing.clear();
    }

    @FunctionalInterface
    public interface Listener {
        /** 主线程通知；重试须按 requestId 幂等，异常会保留效果供功法服务重试。 */
        void apply(ServerPlayer player, String requestId, EffectIntent effect);
    }

    public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class,
            listeners -> (player, requestId, effect) -> {
                // 完整保留 cast/effect 身份、START/PLAY_ONCE/STOP 和剪辑起播毫秒。
                for (Listener listener : listeners) {
                    listener.apply(player, requestId, effect);
                }
            });

    private HumanTechniquePresentation() {
        // 静态接线只保存施放与实际片段的关联，身体状态仍由 Framework 持有。
    }
}
