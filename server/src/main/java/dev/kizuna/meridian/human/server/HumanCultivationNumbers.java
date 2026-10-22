package dev.kizuna.meridian.human.server;

/** 游炁后半段的可调占位参数，时间单位为模拟秒，资源单位为元。 */
public final class HumanCultivationNumbers {
    public static final double MAX_DENSITY = .099;
    public static final double CANDIDATE_DENSITY = .09;
    public static final double CANDIDATE_QI = 50;
    public static final double STARTING_WALL_QI = 60;
    public static final double BREAKTHROUGH_SECONDS = 60;
    public static final double FUEL_PER_SECOND = 1;
    public static final double BATCH_QI = 10;
    public static final double THROUGHPUT = 1;
    public static final double INTERNAL_METERS = 6.6; // 加上八条连接 2.4 米，共 9 米。
    public static final double DENSITY_GAIN = .2;
    public static final double DENSITY_SLOWDOWN = .7;
    public static final double LOSS_EXPONENT = .66;
    public static final double AUTOMATIC_EFFICIENCY = .5;
    public static final double AUTOMATIC_MIN_STAMINA_RATIO = .8;
    public static final double BRAIN_WATTS = 1;
    public static final double ATTENTION_WATTS = .5;
    public static final double MOBILIZE_WATTS = 2;
    public static final double MOBILIZE_ATTENTION_WATTS = .5;
    public static final double PUSH_GAIN_PER_SECOND = .5;
    public static final double PUSH_DECAY_PER_SECOND = .25;
    public static final double SAFE_MIN = .65;
    public static final double SAFE_MAX = 1.35;
    public static final double DISTAL_SAFE_MIN = .8;
    public static final double DISTAL_SAFE_MAX = 1.2;
    public static final double LEAK_FRACTION_PER_SECOND = .005;
    public static final long GATE_WINDOW_MILLIS = 450;
    public static final long GATE_RETRY_MILLIS = 1000;
    public static final long GATE_KNOCKBACK_MILLIS = 500;
    public static final long FEEDBACK_MILLIS = 700;
    public static final long BREATH_CYCLE_MILLIS = 8000;
    public static final long EXHALE_START_MILLIS = 4000;
    public static final long INPUT_MAX_AGE_TICKS = 20;
    public static final double GATE_MISS_JOULES = 2;
    public static final double DEPOSIT_THRESHOLD = .9;
    public static final double WITHDRAW_THRESHOLD = .5;
    public static final double DEPOSIT_BATCH = .1;
    public static final double MIN_TRANSFER_QI = 1e-8; // 元；更小的单项尾差留在源账户等待合并。
    public static final long DEPOSIT_MILLIS = 2000;
    public static final double DEPOSIT_RETENTION = .8;
    public static final double PASSIVE_WITHDRAW_PER_SECOND = .02;
    public static final double ACTIVE_WITHDRAW_PER_SECOND = 1;
    public static final double SPLEEN_OVERLOAD_HP = .1;
    public static final long STEP_MILLIS = 250;

    private HumanCultivationNumbers() {
        // 集中占位数值，调整节奏不需要修改原子账本或网络协议。
    }
}
