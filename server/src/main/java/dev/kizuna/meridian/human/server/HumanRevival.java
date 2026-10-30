package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.HealthMessages.ReviveBody;
import org.tomlj.Toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 重生与调试复活共用人体内容费用，不保存独立寿元账户。 */
public final class HumanRevival {
    public static final String RESOURCE = "/meridian-human-revival.toml";
    private static final double COST_YEARS = loadCostYears();

    private HumanRevival() {
        // 费用只读自人体数据，真正扣款由 Framework 的健康事务执行。
    }

    public static ReviveBody request(String sourceId) {
        // 调用方只提供可信来源，不能在调试路径省略或降低复活费用。
        return ReviveBody.newBuilder()
                .setSourceId(sourceId)
                .setLifespanSeconds(COST_YEARS * HumanBirth.SECONDS_PER_YEAR)
                .build();
    }

    public static String message() {
        // 提示随内容费用变化，初值使用自然的中文年数。
        String years;
        if (COST_YEARS == 1) {
            years = "一年";
        } else {
            years = String.format(Locale.ROOT, "%.1f 年", COST_YEARS);
        }
        return HumanErrors.REVIVAL_MESSAGE.formatted(years);
    }

    private static double loadCostYears() {
        // 缺失或损坏的必需数据明确报错，不让两个入口使用不同默认值。
        try (var input = HumanRevival.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw HumanErrors.invalid(HumanErrors.INVALID_REVIVAL);
            }
            var data = Toml.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            Object value = data.get("lifespan_cost_years");
            if (data.hasErrors() || !(value instanceof Number number)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_REVIVAL);
            }
            double years = number.doubleValue();
            double seconds = years * HumanBirth.SECONDS_PER_YEAR;
            if (!Double.isFinite(seconds) || seconds <= 0) {
                throw HumanErrors.invalid(HumanErrors.INVALID_REVIVAL);
            }
            return years;
        } catch (IOException failure) {
            throw new IllegalStateException(HumanErrors.INVALID_REVIVAL, failure);
        }
    }
}
