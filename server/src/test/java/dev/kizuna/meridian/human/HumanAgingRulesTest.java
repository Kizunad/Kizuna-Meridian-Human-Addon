package dev.kizuna.meridian.human;

import dev.kizuna.meridian.human.server.HumanAgingRules;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 自然年龄按游戏日和维度倍率计算，配置缺失或非法时拒绝加载。 */
public final class HumanAgingRulesTest {
    private static final double EPSILON = 1e-12;
    private static final double SECONDS_PER_GAME_DAY = 86_400;
    private static final double CUSTOM_DIMENSION_MULTIPLIER = 150;
    private static final String DEFAULT_DIMENSIONS = "[dimensions]\n"
            + "\"minecraft:overworld\" = 100.0\n"
            + "\"minecraft:the_nether\" = 200.0\n"
            + "\"minecraft:the_end\" = 200.0\n";

    @Test
    public void defaultsUseGameDayAndDimensionFallback() {
        HumanAgingRules rules = HumanAgingRules.defaults();

        assertEquals(HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER,
                rules.multiplier("minecraft:overworld"), EPSILON);
        assertEquals(HumanAgingRules.DEFAULT_NETHER_MULTIPLIER,
                rules.multiplier("minecraft:the_nether"), EPSILON);
        assertEquals(HumanAgingRules.DEFAULT_END_MULTIPLIER,
                rules.multiplier("minecraft:the_end"), EPSILON);
        assertEquals(HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER,
                rules.multiplier("test:dimension"), EPSILON);
        assertEquals(HumanAgingRules.GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND
                        * HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER,
                rules.agingRate("minecraft:overworld"), EPSILON);
        assertEquals(HumanAgingRules.GAME_DAY_AGE_SECONDS_PER_SIMULATION_SECOND
                        * HumanAgingRules.DEFAULT_NETHER_MULTIPLIER,
                rules.agingRate("minecraft:the_nether"), EPSILON);
    }

    @Test
    public void configFileIsWrittenAndInvalidDimensionsAreRejected() throws Exception {
        var directory = Files.createTempDirectory("human-aging");
        var file = directory.resolve(HumanAgingRules.FILE_NAME);
        HumanAgingRules loaded = HumanAgingRules.load(file);

        assertTrue(Files.exists(file));
        assertEquals(HumanAgingRules.DEFAULT_OVERWORLD_MULTIPLIER,
                loaded.multiplier("minecraft:overworld"), EPSILON);
        for (String overworldMultiplier : List.of("0", "-1", "nan")) {
            String bad = "[dimensions]\n"
                    + "\"minecraft:overworld\" = " + overworldMultiplier + "\n"
                    + "\"minecraft:the_nether\" = 200\n"
                    + "\"minecraft:the_end\" = 200\n";
            assertThrows(IllegalArgumentException.class, () -> HumanAgingRules.parse(bad));
        }
        String missingDimension = "[dimensions]\n"
                + "\"minecraft:overworld\" = 100\n"
                + "\"minecraft:the_nether\" = 200\n";
        assertThrows(IllegalArgumentException.class, () -> HumanAgingRules.parse(missingDimension));
        assertEquals(CUSTOM_DIMENSION_MULTIPLIER, HumanAgingRules.parse(DEFAULT_DIMENSIONS
                        + "\"test:dimension\" = 150.0\n")
                .multiplier("test:dimension"), EPSILON);
    }
}
