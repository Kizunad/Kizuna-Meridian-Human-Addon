package dev.kizuna.meridian.human.fabric.client;

import dev.kizuna.meridian.technique.fabric.client.TechniqueCastStatusClient;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastStatus;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** 只从服务端施放投影识别持续守，不预测身体状态或另发按键消息。 */
public final class HumanGuardClient implements ClientModInitializer {
    private static String guardedCast = "";

    @Override
    public void onInitializeClient() {
        // 持续模式只允许守；记住同一次未知结束身份，松开后仍限制完整收势。
        TechniqueCastStatusClient.addListener(new TechniqueCastStatusClient.Listener() {
            @Override
            public void started(CastStatus status) {
                // 新施放不能继承上一次持续守的输入约束。
                guardedCast = "";
                if (status.getEndTimesUnknown()) {
                    guardedCast = status.getCastId();
                }
            }

            @Override
            public void rejected(String technique, TechniqueFailure failure) {
                // 其他按键被拒绝不改变正在维持的施放。
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> guardedCast = "");
    }

    public static boolean blocksSprint() {
        // 同 cast 的收势更新不会重启 started；结束或切换后立即失效。
        var current = TechniqueCastStatusClient.current(0);
        if (current.isEmpty()) {
            guardedCast = "";
            return false;
        }
        CastStatus status = current.get().status();
        if (status.getEndTimesUnknown()) {
            guardedCast = status.getCastId();
        }
        return status.getCastId().equals(guardedCast);
    }
}
