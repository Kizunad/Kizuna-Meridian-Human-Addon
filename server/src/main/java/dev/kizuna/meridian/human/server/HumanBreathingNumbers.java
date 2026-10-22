package dev.kizuna.meridian.human.server;

/** 自动吐纳首轮占位数值；能力归人体内容，资源由框架统一结算。 */
public final class HumanBreathingNumbers {
    public static final double AUTOMATIC_MIN_STAMINA_RATIO = .8;
    public static final double AUTOMATIC_CAPTURE_EFFICIENCY = .5;

    private HumanBreathingNumbers() {
        // 平衡调整只改变可信内容参数，客户端不能覆盖。
    }
}
