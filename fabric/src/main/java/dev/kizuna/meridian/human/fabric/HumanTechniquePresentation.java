package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanTechniqueAnimations;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.CastHand;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.GuardMotion;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.GuardStyle;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.Playback;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 功法表现的服务端钩子；动画适配器负责将原始起播时刻和停止意图同步到客户端。 */
public final class HumanTechniquePresentation {
    private static final long MILLIS_PER_TICK = 50;
    private record PlayingClip(String id, Playback clock) { }
    private static final Map<UUID, Map<String, PlayingClip>> playing = new HashMap<>();

    static void apply(ServerPlayer player, EffectIntent effect) {
        // 内容命名空间与 PA 剪辑目录分离，未交付剪辑的表现意图仍然成功。
        var presentation = effect.getPresentation();
        String key = effect.getCastId() + "/" + presentation.getId();
        if (presentation.getAction() == PresentationAction.STOP) {
            stopClip(player, key);
            return;
        }
        var input = effect.getDirection();
        var direction = new HumanTechniqueAnimations.Direction(player.getYRot(), input.getX(), input.getZ());
        CastHand hand = effect.getSelectedHand() == Hand.OFF_HAND ? CastHand.OFF : CastHand.MAIN;
        String animationId = HumanTechniqueAnimations.resolve(presentation.getId(), direction, hand);
        String selected;
        if (animationId.equals(GuardStyle.ARMS.clip(GuardMotion.HOLD))) {
            selected = HumanGuard.style(player).clip(GuardMotion.HOLD);
        } else {
            selected = animationId;
        }
        PlayerAnimationAction action = PlayerAnimationAction.find(selected);
        if (action == null || !action.delivered()) {
            return;
        }
        long nowMillis = player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        long lateness = Math.max(0, nowMillis - effect.getScheduledMillis());
        // 没带倍率的旧意图按原速播。
        double speed = 1;
        if (presentation.hasPlaybackSpeedMultiplier()) {
            speed = presentation.getPlaybackSpeedMultiplier();
        }
        var playback = HumanTechniqueAnimations.playback(player.level().getGameTime(), presentation.getStartMillis(),
                lateness, speed);
        ServerPlayerAnimations.playFrom(player, selected, playback.startTick(), playback.rate());
        playing.computeIfAbsent(player.getUUID(), id -> new HashMap<>()).put(key, new PlayingClip(selected, playback));
    }

    static void refreshGuard(ServerPlayer player) {
        // 切槽、缴械和碎裂都从原起播 tick 与倍率续播，保留抬手或呼吸所在的时刻。
        var clips = playing.get(player.getUUID());
        if (clips == null) {
            return;
        }
        for (var entry : clips.entrySet()) {
            PlayingClip current = entry.getValue();
            String id = current.id();
            if (!id.equals(GuardStyle.ARMS.clip(GuardMotion.HOLD))
                    && !id.equals(GuardStyle.WEAPON.clip(GuardMotion.HOLD))) {
                continue;
            }
            String next = HumanGuard.style(player).clip(GuardMotion.HOLD);
            if (!next.equals(id)) {
                var clock = current.clock();
                ServerPlayerAnimations.stop(player, id);
                ServerPlayerAnimations.playFrom(player, next, clock.startTick(), clock.rate());
                entry.setValue(new PlayingClip(next, clock));
            }
        }
    }

    private static void stopClip(ServerPlayer player, String key) {
        // 清理后的重复 STOP 不重新建立空记录，也不会影响其他施放。
        UUID playerId = player.getUUID();
        var clips = playing.get(playerId);
        if (clips == null) {
            return;
        }
        PlayingClip previous = clips.remove(key);
        if (previous != null) {
            ServerPlayerAnimations.stop(player, previous.id());
        }
        if (clips.isEmpty()) {
            playing.remove(playerId);
        }
    }

    static void forget(UUID playerId) {
        // 断开后不保留旧施放与实际方向剪辑的关联。
        playing.remove(playerId);
    }

    static void guardMotion(ServerPlayer player, String castId, GuardMotion motion, GuardStyle style) {
        // 受击只叠加短反馈，不重启保持段；收势片段按施放身份去重。
        String key = castId + "/guard/" + motion.name();
        var clips = playing.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        if (clips.containsKey(castId + "/guard/" + GuardMotion.LOWER.name())) {
            return;
        }
        String animation = style.clip(motion);
        var previous = clips.get(key);
        if (previous != null) {
            String previousId = previous.id();
            if (!previousId.equals(animation)) {
                ServerPlayerAnimations.stop(player, previousId);
            }
        }
        ServerPlayerAnimations.play(player, animation);
        clips.put(key, new PlayingClip(animation, new Playback(player.level().getGameTime(), 1)));
    }

    static void lowerGuard(ServerPlayer player, String castId) {
        // 只在真实收势通知时落手；先停保持和受击，重复阶段通知不能重播落手。
        UUID playerId = player.getUUID();
        if (!HumanTechniqueHost.hasCast(playerId, castId)) {
            return;
        }
        var clips = playing.getOrDefault(playerId, Map.of());
        if (clips.containsKey(castId + "/guard/" + GuardMotion.LOWER.name())) {
            return;
        }
        stopCast(player, castId);
        guardMotion(player, castId, GuardMotion.LOWER, HumanGuard.style(player));
    }

    static void breakGuard(ServerPlayer player, String castId, GuardStyle style) {
        // 收势期间用破防短片替换保持，死亡和卸载仍按本次施放清理。
        UUID playerId = player.getUUID();
        String key = castId + "/guard/break";
        if (!HumanTechniqueHost.hasCast(playerId, castId)
                || playing.getOrDefault(playerId, Map.of()).containsKey(key)) {
            return;
        }
        stopCast(player, castId);
        String clip = style.clip(GuardMotion.HIT);
        ServerPlayerAnimations.play(player, clip);
        var clock = new Playback(player.level().getGameTime(), 1);
        playing.computeIfAbsent(playerId, id -> new HashMap<>()).put(key, new PlayingClip(clip, clock));
    }

    static void stopCast(ServerPlayer player, String castId) {
        // 停实际播过的片段，包含中途选出的受击和收势，不能影响下一次施放。
        UUID playerId = player.getUUID();
        var clips = playing.get(playerId);
        if (clips == null) {
            return;
        }
        for (String key : List.copyOf(clips.keySet())) {
            if (key.startsWith(castId + "/")) {
                ServerPlayerAnimations.stop(player, clips.remove(key).id());
            }
        }
        if (clips.isEmpty()) {
            playing.remove(playerId);
        }
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
