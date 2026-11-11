package dev.kizuna.meridian.human;

import dev.kizuna.meridian.human.server.HumanBreathingRules;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganRecipe;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 真炁吐纳配方：产物按配置份额分成气态与游离态，配置首启写出，坏值拒绝。 */
public final class HumanBreathingRulesTest {
    private static final String FIRE = "meridian:fire";
    private static final double BREATH_OUTPUT_QI = 0.01;
    private static final double EPSILON = 1e-12;

    @Test
    public void trueQiBreathSplitsItsOutputByTheConfiguredGasShare() {
        // 份额 0.25：0.01 元产物里 0.0025 是气态、0.0075 仍是游离态；基础配方不变。
        var bundle = HumanContent.bundle(HumanBreathingRules.parse("true_qi_gas_share = 0.25"));
        OrganRecipe trueQi = recipe(bundle.getOrganRecipesList(), HumanContent.trueQiBreathRecipeId(FIRE));
        assertEquals(Realm.TRUE_QI, trueQi.getMinimumRealm());
        assertEquals(0.0075, amount(trueQi.getOutput().getEntriesList(), QiPhase.FREE), EPSILON);
        assertEquals(0.0025, amount(trueQi.getOutput().getEntriesList(), QiPhase.GAS), EPSILON);
        OrganRecipe base = recipe(bundle.getOrganRecipesList(), HumanContent.breathRecipeId(FIRE));
        assertEquals(Realm.WANDERING_QI, base.getMinimumRealm());
        assertEquals(BREATH_OUTPUT_QI, amount(base.getOutput().getEntriesList(), QiPhase.FREE), EPSILON);
        assertTrue(HumanContent.breathing().getRecipeIdsList().contains(trueQi.getId()));

        // 份额 1 只留气态一行。
        var allGas = HumanContent.bundle(HumanBreathingRules.parse("true_qi_gas_share = 1.0"));
        OrganRecipe gasOnly = recipe(allGas.getOrganRecipesList(), HumanContent.trueQiBreathRecipeId(FIRE));
        assertEquals(1, gasOnly.getOutput().getEntriesCount());
        assertEquals(BREATH_OUTPUT_QI, amount(gasOnly.getOutput().getEntriesList(), QiPhase.GAS), EPSILON);
    }

    @Test
    public void configFileIsWrittenOnFirstStartAndBadSharesAreRejected() throws Exception {
        // 首启写出随包配置；越界、NaN、整数写法和缺项都拒绝。
        var directory = Files.createTempDirectory("human-breathing");
        var file = directory.resolve(HumanBreathingRules.FILE_NAME);
        double share = HumanBreathingRules.load(file).trueQiGasShare();
        assertTrue(Files.exists(file));
        assertEquals(HumanBreathingRules.defaults().trueQiGasShare(), share, EPSILON);
        for (String bad : List.of("true_qi_gas_share = 1.5", "true_qi_gas_share = -0.1",
                "true_qi_gas_share = nan", "true_qi_gas_share = 1", "")) {
            assertThrows(IllegalArgumentException.class, () -> HumanBreathingRules.parse(bad));
        }
    }

    private static OrganRecipe recipe(List<OrganRecipe> recipes, String id) {
        // 按 ID 取一条配方。
        return recipes.stream()
                .filter(recipe -> recipe.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static double amount(List<Qi> entries, QiPhase phase) {
        // 某一相态的产物合计。
        return entries.stream()
                .filter(entry -> QiDensity.phase(entry.getDensity()) == phase)
                .mapToDouble(Qi::getAmount)
                .sum();
    }
}
