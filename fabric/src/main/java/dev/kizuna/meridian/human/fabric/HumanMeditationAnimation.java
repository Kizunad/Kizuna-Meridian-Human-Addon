package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanErrors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 可选动画 API 的兼容边界；动画模组负责 Protobuf 转发与实体追踪同步。 */
final class HumanMeditationAnimation {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_human_animation");
    private final Set<UUID> playing = new HashSet<>();
    private Method play;
    private Method stop;
    private boolean checked;
    private boolean unavailable;

    void synchronize(ServerPlayerEntity player, boolean active) {
        // 仅在权威状态切换时播放或停止，身体数值推送不会重新开始同一个动画。
        boolean wasPlaying = playing.contains(player.getUuid());
        if (active == wasPlaying || unavailable) {
            return;
        }
        if (!checked) {
            loadApi();
        }
        if (unavailable) {
            return;
        }

        // 反射仅用于可选 Java 依赖的链接；实际调用公开 API，不复制动画网络协议。
        try {
            if (active) {
                play.invoke(null, player, "meditate");
                playing.add(player.getUuid());
            } else {
                stop.invoke(null, player, "meditate");
                playing.remove(player.getUuid());
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            // 展示失败不能暂停身体结算；保留已播放身份，让离线清理仍可尝试停止。
            LOG.warn(HumanErrors.ANIMATION_FAILED, failure);
            unavailable = true;
        }
    }

    void stop(ServerPlayerEntity player) {
        // 只清理本宿主登记的 meditate，不覆盖功法附属正在播放的其他动画。
        if (!playing.remove(player.getUuid())) {
            return;
        }
        try {
            stop.invoke(null, player, "meditate");
        } catch (ReflectiveOperationException | LinkageError failure) {
            LOG.warn(HumanErrors.ANIMATION_FAILED, failure);
        }
    }

    void clear() {
        // 服务器结束后释放身份；同一 JVM 新开世界重新检测可选能力。
        playing.clear();
        checked = false;
        unavailable = false;
        play = null;
        stop = null;
    }

    private void loadApi() {
        // 缺少动画模组时正常降级；旧版没有服务端 API 时只记录一次诊断。
        checked = true;
        if (!FabricLoader.getInstance().isModLoaded("kizuna_players_animation")) {
            unavailable = true;
            return;
        }
        try {
            Class<?> api = Class.forName("dev.kizuna.playeranimation.server.ServerPlayerAnimations");
            play = api.getMethod("play", ServerPlayerEntity.class, String.class);
            stop = api.getMethod("stop", ServerPlayerEntity.class, String.class);
        } catch (ReflectiveOperationException | LinkageError failure) {
            LOG.warn(HumanErrors.ANIMATION_API_UNAVAILABLE, failure);
            unavailable = true;
        }
    }
}
