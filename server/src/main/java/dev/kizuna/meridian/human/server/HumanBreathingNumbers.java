package dev.kizuna.meridian.human.server;

/** 自动吐纳首轮占位数值；能力归人体内容，资源由框架统一结算。 */
public final class HumanBreathingNumbers {
    public static final double AUTOMATIC_MIN_STAMINA_RATIO = 0.8;
    public static final double AUTOMATIC_RESUME_STAMINA_RATIO = 0.9;
    public static final double AUTOMATIC_CAPTURE_EFFICIENCY = 0.5;
    public static final double RESPIRATORY_WATTS = 30;
    public static final double ATTENTION_WATTS = 4;
    public static final double LUNG_AIR_CAPACITY_LITERS = 2.5;
    public static final double LUNG_AIRFLOW_LITERS_PER_SECOND = 0.625;

    private HumanBreathingNumbers() {
        // 平衡调整只改变可信内容参数，客户端不能覆盖。
    }
}
