package dev.kizuna.meridian.human.client;

import dev.kizuna.meridian.progression.pb.ProgressionMessages.PlayerProgress;

/** Human 客户端使用的只读进度投影；具体进阶 UI 由进阶附属实现。 */
public interface HumanProgressionClient {
    String ENTRYPOINT = "meridian_human_progression_client";

    boolean synchronizedWithServer();

    PlayerProgress requireProgress();

    boolean canCultivate();

    boolean canGather();

    boolean canGatherDetails();

    boolean canInspect();

    boolean canUseMiddleJiao();
}
