package dev.kizuna.meridian.human.protocol;

/** Human 身体内保存的进度标识与现有 Progression 统一路由身份。 */
public final class HumanProgressionProtocol {
    public static final int PROGRESS_SCHEMA = 1;
    public static final String PROGRESS_KEY = "meridian_progression:progress";
    public static final String PROGRESSION_EVENT = "meridian_progression:event";

    private HumanProgressionProtocol() {}
}
