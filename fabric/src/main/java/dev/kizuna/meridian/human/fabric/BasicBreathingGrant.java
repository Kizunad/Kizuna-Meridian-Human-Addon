package dev.kizuna.meridian.human.fabric;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;

/** 在线角色从出身获得基础吐纳授权，选定、读档和数据包重载后通知身体宿主。 */
public enum BasicBreathingGrant {
    ABSENT, PRESENT;

    public interface Listener {
        void refresh(ServerPlayer player, BasicBreathingGrant grant);
    }

    public static final Event<Listener> CHANGED = EventFactory.createArrayBacked(Listener.class,
            listeners -> (player, grant) -> {
                // 所有监听者收到同一份已解析的授权。
                for (Listener listener : listeners) {
                    listener.refresh(player, grant);
                }
            });
}
