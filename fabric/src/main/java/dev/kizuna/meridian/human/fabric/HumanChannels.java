package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.fabric.ProtobufChannel;
import net.minecraft.resources.Identifier;

/** Human 自己的字节通道；宿主与人体界面附属都从这里取，通道 ID 与限长只有这一份。 */
public final class HumanChannels {
    /** 客户端上报修炼控制边沿；包里只有按键边沿，限长 256 字节。 */
    public static final ProtobufChannel CULTIVATION = ProtobufChannel.unframed(
            Identifier.fromNamespaceAndPath("meridian_human", "cultivation"), 256);

    static {
        // 首次使用本类时登记，宿主与界面谁先用到都能拿到已登记的通道。
        CULTIVATION.registerServerbound();
    }

    private HumanChannels() {
        // 只放通道常量，不实例化。
    }
}
