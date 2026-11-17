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

/** 吐纳配方：真炁的产物按配置份额分成气态与游离态；五行各留一份本属性、送进本行的经。配置首启写出，坏值拒绝。 */
public final class HumanBreathingRulesTest {
    private static final String FIRE = "meridian:fire";
    private static final String NONE = "meridian:none";
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

        // 份额 1 只留气态：无属性、本属性各一行，没有游离态的行。
        var allGas = HumanContent.bundle(HumanBreathingRules.parse("true_qi_gas_share = 1.0"));
        OrganRecipe gasOnly = recipe(allGas.getOrganRecipesList(), HumanContent.trueQiBreathRecipeId(FIRE));
        assertEquals(2, gasOnly.getOutput().getEntriesCount());
        assertEquals(0, amount(gasOnly.getOutput().getEntriesList(), QiPhase.FREE), 0);
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

    @Test
    public void breathKeepsAQuarterInItsOwnElementAndSendsItToThatElementsMeridians() {
        // 随包配置：四分之一留成本属性、四分之三无属性，总量还是 0.01；本属性那一样写了去处，是本行的经。
        // 真炁那条两种相态各自照同一个比例分。
        var bundle = HumanContent.bundle(HumanBreathingRules.parse("true_qi_gas_share = 0.5"));
        for (String element : List.of("metal", "wood", "water", "fire", "earth")) {
            String attribute = "meridian:" + element;
            OrganRecipe base = recipe(bundle.getOrganRecipesList(), HumanContent.breathRecipeId(attribute));
            assertEquals(0.0075, amount(base.getOutput().getEntriesList(), NONE, QiPhase.FREE), EPSILON);
            assertEquals(0.0025, amount(base.getOutput().getEntriesList(), attribute, QiPhase.FREE), EPSILON);
            assertEquals(BREATH_OUTPUT_QI, base.getOutput().getEntriesList().stream().mapToDouble(Qi::getAmount).sum(),
                    EPSILON);
            OrganRecipe trueQi = recipe(bundle.getOrganRecipesList(), HumanContent.trueQiBreathRecipeId(attribute));
            assertEquals(0.00375, amount(trueQi.getOutput().getEntriesList(), NONE, QiPhase.GAS), EPSILON);
            assertEquals(0.00125, amount(trueQi.getOutput().getEntriesList(), attribute, QiPhase.GAS), EPSILON);
            assertEquals(0.00125, amount(trueQi.getOutput().getEntriesList(), attribute, QiPhase.FREE), EPSILON);
            for (OrganRecipe recipe : List.of(base, trueQi)) {
                assertEquals(1, recipe.getOutputRoutesCount());
                assertEquals(attribute, recipe.getOutputRoutes(0).getAttribute());
                assertEquals("human:meridian_" + element, recipe.getOutputRoutes(0).getMeridianTag());
            }
        }
    }

    @Test
    public void nativeShareZeroLeavesBreathAllPlainAndOldFilesRunOnTheBundledShare() {
        // 写 0 就和原来一样：只出无属性、不写去处。旧配置文件没有这一项，按随包的 0.25 走；坏值拒绝。
        var plain = HumanContent.bundle(HumanBreathingRules.parse("true_qi_gas_share = 1.0\nnative_share = 0.0"));
        OrganRecipe base = recipe(plain.getOrganRecipesList(), HumanContent.breathRecipeId(FIRE));
        assertEquals(1, base.getOutput().getEntriesCount());
        assertEquals(BREATH_OUTPUT_QI, amount(base.getOutput().getEntriesList(), NONE, QiPhase.FREE), EPSILON);
        assertEquals(0, base.getOutputRoutesCount());
        assertEquals(0.25, HumanBreathingRules.defaults().nativeShare(), EPSILON);
        assertEquals(0.25, HumanBreathingRules.parse("true_qi_gas_share = 1.0").nativeShare(), EPSILON);
        for (String bad : List.of("native_share = 1.5", "native_share = -0.1", "native_share = nan",
                "native_share = 1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> HumanBreathingRules.parse("true_qi_gas_share = 1.0\n" + bad));
        }
    }

    private static double amount(List<Qi> entries, String attribute, QiPhase phase) {
        // 某一属性、某一相态的产物合计。
        return entries.stream()
                .filter(entry -> entry.getAttribute().equals(attribute))
                .filter(entry -> QiDensity.phase(entry.getDensity()) == phase)
                .mapToDouble(Qi::getAmount)
                .sum();
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
