package dev.kizuna.meridian.human.server;

import org.tomlj.Toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 人体吐纳的服务端可调项：真炁以后吐纳产物里直接成为气态的份额，和产物里留成本属性的份额。 */
public final class HumanBreathingRules {
    public static final String FILE_NAME = "meridian-human-breathing.toml";
    private static final String GAS_SHARE_KEY = "true_qi_gas_share";
    private static final String NATIVE_SHARE_KEY = "native_share";
    private static HumanBreathingRules configuredRules = defaults();
    private final double trueQiGasShare;
    private final double nativeShare;

    private HumanBreathingRules(double trueQiGasShare, double nativeShare) {
        // 份额在服务端启动时固定，吐纳配方随内容包一次建成。
        this.trueQiGasShare = trueQiGasShare;
        this.nativeShare = nativeShare;
    }

    public static HumanBreathingRules defaults() {
        // 无 Minecraft 宿主的内容导出与测试从随包数据读取初值。
        return parse(defaultText());
    }

    public static HumanBreathingRules configured() {
        // 宿主启动时读过配置就用配置，否则用随包初值。
        return configuredRules;
    }

    public static void configure(Path file) {
        // 在人体内容包建成之前读取一次服务端配置。
        configuredRules = load(file);
    }

    public static HumanBreathingRules load(Path file) {
        // 首次启动写出可编辑配置；损坏配置明确拒绝启动，不静默恢复初值。
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, defaultText());
            }
            return parse(Files.readString(file));
        } catch (IOException failure) {
            throw new IllegalStateException(HumanErrors.BREATHING_LOAD_FAILED, failure);
        }
    }

    public static HumanBreathingRules parse(String text) {
        // 份额必须显式写成 0 到 1 的小数；整数、越界都按配置错误拒绝。气态份额缺项也拒绝；
        // 本属性份额是后加的，旧配置文件里没有这一项就按随包的走。
        var parsed = Toml.parse(text);
        if (parsed.hasErrors()) {
            throw HumanErrors.invalid(HumanErrors.INVALID_BREATHING);
        }
        double nativeShare = parsed.contains(NATIVE_SHARE_KEY) ? share(parsed.get(NATIVE_SHARE_KEY))
                : share(Toml.parse(defaultText()).get(NATIVE_SHARE_KEY));
        return new HumanBreathingRules(share(parsed.get(GAS_SHARE_KEY)), nativeShare);
    }

    private static double share(Object value) {
        // 写成「在区间内」再取反，nan 也按越界拒绝。
        if (!(value instanceof Double share) || !(share >= 0 && share <= 1)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_BREATHING);
        }
        return share;
    }

    private static String defaultText() {
        // 必需资源缺失属于装配错误，不能让导出内容和运行内容采用不同默认值。
        try (var input = HumanBreathingRules.class.getResourceAsStream("/" + FILE_NAME)) {
            if (input == null) {
                throw new IllegalStateException(HumanErrors.BREATHING_LOAD_FAILED);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(HumanErrors.BREATHING_LOAD_FAILED, failure);
        }
    }

    public double trueQiGasShare() {
        // 真炁吐纳配方产物里气态所占的份额。
        return trueQiGasShare;
    }

    public double nativeShare() {
        // 吐纳配方产物里留成本属性（吸的是火就是火行）的份额，其余是无属性。
        return nativeShare;
    }
}
