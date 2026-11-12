package dev.kizuna.meridian.human;

import dev.kizuna.meridian.human.server.HumanCultivationRules;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 中焦周天配置：首启写出随包初值，真炁以后的增益和起炼下限按配置读取，坏值拒绝。 */
public final class HumanCultivationRulesTest {
    private static final double EPSILON = 1e-12;

    private static final String GAIN = "true_qi_density_gain = 0.6\n";
    private static final String MINIMUM_BATCH = "true_qi_min_batch = 2.0\n";

    @Test
    public void configFileIsWrittenOnFirstStartAndBadValuesAreRejected() throws Exception {
        // 首启写出随包配置，两项都按配置读取；零、负数、超上限、inf、nan、整数写法和缺项都拒绝。
        var directory = Files.createTempDirectory("human-cultivation");
        var file = directory.resolve(HumanCultivationRules.FILE_NAME);
        var written = HumanCultivationRules.load(file);
        var defaults = HumanCultivationRules.defaults();
        assertTrue(Files.exists(file));
        assertEquals(defaults.trueQiDensityGain(), written.trueQiDensityGain(), EPSILON);
        assertEquals(defaults.trueQiMinimumBatch(), written.trueQiMinimumBatch(), EPSILON);
        var parsed = HumanCultivationRules.parse(GAIN + MINIMUM_BATCH);
        assertEquals(0.6, parsed.trueQiDensityGain(), EPSILON);
        assertEquals(2.0, parsed.trueQiMinimumBatch(), EPSILON);
        for (String badGain : List.of("0.0", "-0.5", "inf", "nan", "1")) {
            String text = "true_qi_density_gain = " + badGain + "\n" + MINIMUM_BATCH;
            assertThrows(IllegalArgumentException.class, () -> HumanCultivationRules.parse(text));
        }
        for (String badBatch : List.of("0.0", "10.5", "inf", "2")) {
            String text = GAIN + "true_qi_min_batch = " + badBatch + "\n";
            assertThrows(IllegalArgumentException.class, () -> HumanCultivationRules.parse(text));
        }
        for (String missing : List.of(GAIN, MINIMUM_BATCH, "")) {
            assertThrows(IllegalArgumentException.class, () -> HumanCultivationRules.parse(missing));
        }
    }
}
