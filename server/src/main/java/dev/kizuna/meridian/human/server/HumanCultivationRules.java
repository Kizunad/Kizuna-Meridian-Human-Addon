package dev.kizuna.meridian.human.server;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 中焦周天的服务端可调项：真炁以后每圈的提纯增益和一批货的起炼下限。 */
public final class HumanCultivationRules {
    public static final String FILE_NAME = "meridian-human-cultivation.toml";
    private static final String TRUE_QI_GAIN_KEY = "true_qi_density_gain";
    private static final String TRUE_QI_MINIMUM_BATCH_KEY = "true_qi_min_batch";
    private static HumanCultivationRules configuredRules = defaults();
    private final double trueQiDensityGain;
    private final double trueQiMinimumBatch;

    private HumanCultivationRules(double trueQiDensityGain, double trueQiMinimumBatch) {
        // 两项在服务端启动时固定，真炁以后的周天按它们提纯和判断停下。
        this.trueQiDensityGain = trueQiDensityGain;
        this.trueQiMinimumBatch = trueQiMinimumBatch;
    }

    public static HumanCultivationRules defaults() {
        // 无 Minecraft 宿主的测试与内容导出从随包数据读取初值。
        return parse(defaultText());
    }

    public static HumanCultivationRules configured() {
        // 宿主启动时读过配置就用配置，否则用随包初值。
        return configuredRules;
    }

    public static void configure(Path file) {
        // 在任何身体宿主运转周天之前读取一次服务端配置。
        configuredRules = load(file);
    }

    public static HumanCultivationRules load(Path file) {
        // 首次启动写出可编辑配置；损坏配置明确拒绝启动，不静默恢复初值。
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, defaultText());
            }
            return parse(Files.readString(file));
        } catch (IOException failure) {
            throw new IllegalStateException(HumanErrors.CULTIVATION_RULES_LOAD_FAILED, failure);
        }
    }

    public static HumanCultivationRules parse(String text) {
        // 两项都必须显式写成区间内的有限小数；整数写法、零、负数、超上限、inf、nan 或缺项都按配置错误拒绝。
        var parsed = Toml.parse(text);
        if (parsed.hasErrors()) {
            throw HumanErrors.invalid(HumanErrors.INVALID_CULTIVATION_RULES);
        }
        double gain = positiveDecimal(parsed, TRUE_QI_GAIN_KEY, Double.MAX_VALUE);
        double minimumBatch = positiveDecimal(parsed, TRUE_QI_MINIMUM_BATCH_KEY, HumanCultivationNumbers.BATCH_QI);
        return new HumanCultivationRules(gain, minimumBatch);
    }

    private static double positiveDecimal(TomlParseResult parsed, String key, double maximum) {
        // 取大于 0、不超过上限的有限小数。写成「在区间内」再取反，nan 也按越界拒绝。
        if (!(parsed.get(key) instanceof Double value)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_CULTIVATION_RULES);
        }
        boolean inRange = Double.isFinite(value) && value > 0 && value <= maximum;
        if (!inRange) {
            throw HumanErrors.invalid(HumanErrors.INVALID_CULTIVATION_RULES);
        }
        return value;
    }

    private static String defaultText() {
        // 必需资源缺失属于装配错误，不能让测试和运行内容采用不同默认值。
        try (var input = HumanCultivationRules.class.getResourceAsStream("/" + FILE_NAME)) {
            if (input == null) {
                throw new IllegalStateException(HumanErrors.CULTIVATION_RULES_LOAD_FAILED);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(HumanErrors.CULTIVATION_RULES_LOAD_FAILED, failure);
        }
    }

    public double trueQiDensityGain() {
        // 真炁以后周天每圈的提纯增益。
        return trueQiDensityGain;
    }

    public double trueQiMinimumBatch() {
        // 真炁以后一批货的起炼下限（元）：凑不够就停下周天。
        return trueQiMinimumBatch;
    }
}
