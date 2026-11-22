package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.protocol.ProgressionStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Human 提供的进阶宿主契约；进阶附属通过 Fabric entrypoint 实现这些窄操作。 */
public interface HumanProgressionHost {
    String ENTRYPOINT = "meridian_human_progression";

    /** 把人体唯一进度存取接到当前服务器实例。 */
    void installStore(MinecraftServer server, ProgressionStore store);

    /** 身体状态改变后发布权威进度投影。 */
    void publishProgress(ServerPlayer player);

    /** 查询当前身体是否允许内观，失败时必须明确抛出宿主接线错误。 */
    boolean canInspect(ServerPlayer player);
}
