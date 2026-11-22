package dev.kizuna.meridian.human.protocol;

import dev.kizuna.meridian.progression.pb.ProgressionMessages.PlayerProgress;
import java.util.UUID;

/** 人体宿主将进度读写到当前玩家身体，进阶实现不得建立另一份持久化状态。 */
public interface ProgressionStore {
    /** 新身体返回 schema_version=1、未读进度；未知版本必须拒绝。 */
    PlayerProgress read(UUID player);

    /** 成功返回前原子替换身体进度并标记世界存档。 */
    void write(UUID player, PlayerProgress progress);
}
