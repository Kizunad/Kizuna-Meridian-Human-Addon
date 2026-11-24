package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.config.TomlFile;
import org.tomlj.TomlTable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 人体自然年龄的游戏日折算和维度倍率；疲劳、回血与身体活动仍按模拟时间结算。 */
public final class HumanAgingRules {
    public static final String FILE_NAME = "meridian-human-aging.toml";
    public static final double GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND = 72;
    public static final double DEFAULT_OVERWORLD_MULTIPLIER = 100;
    public static final double DEFAULT_NETHER_MULTIPLIER = 200;
    public static final double DEFAULT_END_MULTIPLIER = 200;
    private static final String DIMENSIONS_KEY = "dimensions";
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";
    private static HumanAgingRules configuredRules = defaults();
    private final Map<String, Double> dimensions;

    private HumanAgingRules(Map<String, Double> dimensions) {
        // 服务端启动时固定倍率，单次身体结算不会读到半更新的配置。
        this.dimensions = Map.copyOf(dimensions);
    }

    public static HumanAgingRules defaults() {
        // 内容测试与无 Minecraft 宿主的导出沿用随包默认配置。
        return parse(defaultText());
    }

    public static HumanAgingRules configured() {
        // 宿主启动后读取的服务端规则。
        return configuredRules;
    }

    public static void configure(Path file) {
        // 年龄规则必须在任何身体创建或恢复前固定。
        configuredRules = load(file);
    }

    public static HumanAgingRules load(Path file) {
        // 首次启动写出可编辑配置，损坏配置明确拒绝启动。
        return parse(TomlFile.readOrExport(file, HumanAgingRules.class, "/" + FILE_NAME));
    }

    public static HumanAgingRules parse(String text) {
        // 三个原版维度必须显式存在，其他维度回退主世界倍率。
        var parsed = TomlFile.parse(text, FILE_NAME);
        if (!(parsed.get(DIMENSIONS_KEY) instanceof TomlTable table)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_AGING_RULES);
        }
        Map<String, Double> dimensions = new HashMap<>();
        for (String dimension : table.keySet()) {
            dimensions.put(dimension, multiplier(table.get(List.of(dimension))));
        }
        requireDimension(dimensions, OVERWORLD);
        requireDimension(dimensions, NETHER);
        requireDimension(dimensions, END);
        return new HumanAgingRules(dimensions);
    }

    private static void requireDimension(Map<String, Double> dimensions, String dimension) {
        if (!dimensions.containsKey(dimension)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_AGING_RULES);
        }
    }

    private static double multiplier(Object value) {
        // 倍率只能是有限正数，避免零倍率被配置误用为在线暂停。
        if (!(value instanceof Number number)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_AGING_RULES);
        }
        double multiplier = number.doubleValue();
        if (!Double.isFinite(multiplier) || multiplier <= 0) {
            throw HumanErrors.invalid(HumanErrors.INVALID_AGING_RULES);
        }
        return multiplier;
    }

    private static String defaultText() {
        // 必需资源缺失属于装配错误，不能让不同身体采用不同默认倍率。
        return TomlFile.resource(HumanAgingRules.class, "/" + FILE_NAME);
    }

    public double multiplier(String dimensionId) {
        // 模组维度沿用主世界规则，原版维度可单独配置。
        return dimensions.getOrDefault(dimensionId, dimensions.get(OVERWORLD));
    }

    public double agingRate(String dimensionId) {
        // 一个游戏日对应 72 个生物秒，再乘当前维度倍率。
        return GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND * multiplier(dimensionId);
    }
}
