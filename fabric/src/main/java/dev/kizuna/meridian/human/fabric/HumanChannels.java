package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.fabric.ProtobufChannel;
import net.minecraft.resources.Identifier;

/** Human 自己的字节通道；宿主与人体界面附属都从这里取，通道 ID 与限长只有这一份。 */
public final class HumanChannels {
    private static final int CONTROL_MAX_BYTES = 4_096;
    public static final ProtobufChannel CONTROL = ProtobufChannel.unframed(
            Identifier.fromNamespaceAndPath("meridian_human", "control"), CONTROL_MAX_BYTES);
    /** 客户端上报修炼控制边沿；包里只有按键边沿，限长 256 字节。 */
    public static final ProtobufChannel CULTIVATION = ProtobufChannel.unframed(
            Identifier.fromNamespaceAndPath("meridian_human", "cultivation"), 256);

    static {
        // 首次使用本类时登记，宿主与界面谁先用到都能拿到已登记的通道。
        CULTIVATION.registerServerbound();
        CONTROL.registerClientbound();
    }

    private HumanChannels() {
        // 只放通道常量，不实例化。
    }

    static void initialize() {
        // 显式触发类初始化，让静态通道在公共模组初始化期间登记。
    }
}
