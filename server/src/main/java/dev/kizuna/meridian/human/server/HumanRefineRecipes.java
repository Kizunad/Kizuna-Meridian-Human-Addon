package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganRecipe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** 炼化配方族索引：按配方族命名空间取内容包的器官配方，再按器官种类与身体相态选出一条。 */
public final class HumanRefineRecipes {
    private final Function<String, List<OrganRecipe>> source;
    private final Map<String, List<OrganRecipe>> namespaces = new HashMap<>();

    public HumanRefineRecipes(Function<String, List<OrganRecipe>> source) {
        // 内容包由宿主按命名空间读取并经 Framework 校验，本类只做查找与缓存。
        this.source = Objects.requireNonNull(source);
    }

    public void validate(String family) {
        // 开服时按功法声明的配方族预读内容包，形状错误与重复配方在首次施放前暴露。
        var kinds = new HashSet<String>();
        for (OrganRecipe recipe : family(family)) {
            rate(recipe);
            if (!kinds.add(recipe.getOrganKind() + "@" + inputPhase(recipe))) {
                throw HumanErrors.invalid(HumanErrors.INVALID_REFINE_RECIPE);
            }
        }
        if (kinds.isEmpty()) {
            throw HumanErrors.invalid(HumanErrors.INVALID_REFINE_RECIPE);
        }
    }

    Optional<OrganRecipe> find(String family, String organKind, Qi.QiPhase phase) {
        // 同一器官种类与相态有多条时视为内容错误；没有对应相态时由调用方按境界拒绝。
        List<OrganRecipe> matching = family(family).stream()
                .filter(recipe -> recipe.getOrganKind().equals(organKind))
                .filter(recipe -> inputPhase(recipe) == phase)
                .toList();
        if (matching.size() > 1) {
            throw HumanErrors.invalid(HumanErrors.INVALID_REFINE_RECIPE);
        }
        return matching.stream().findFirst();
    }

    private List<OrganRecipe> family(String family) {
        // 配方 ID 以「配方族_」开头，内容包按配方族的命名空间读取一次。
        String namespace = family.substring(0, family.indexOf(':'));
        String prefix = family + "_";
        return namespaces.computeIfAbsent(namespace, source).stream()
                .filter(recipe -> recipe.getId().startsWith(prefix))
                .toList();
    }

    static double rate(OrganRecipe recipe) {
        // 炼化率是产物与投料的元数之比；投料和产物都只有一项明细。
        return output(recipe).amount() / input(recipe).amount();
    }

    static Qi.QiAttribute outputAttribute(OrganRecipe recipe) {
        // 产物属性即本行，投料固定为无属性真元。
        return output(recipe).attribute();
    }

    private static Qi.QiPhase inputPhase(OrganRecipe recipe) {
        // 相态由投料密度给出，与身体境界的基准密度同一边界。
        return input(recipe).phase();
    }

    private static Qi input(OrganRecipe recipe) {
        // 炼化只接受一项无属性投料，其余形状是内容错误而不是可忽略的配方。
        QiInventory input = BodyProtobuf.fromInventory(recipe.getInput());
        if (input.entries().size() != 1 || !input.entries().getFirst().attribute().equals(Qi.QiAttribute.NONE)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_REFINE_RECIPE);
        }
        return input.entries().getFirst();
    }

    private static Qi output(OrganRecipe recipe) {
        // 产物只有一项本行真元，属性不能仍是无属性。
        QiInventory output = BodyProtobuf.fromInventory(recipe.getOutput());
        if (output.entries().size() != 1 || output.entries().getFirst().attribute().equals(Qi.QiAttribute.NONE)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_REFINE_RECIPE);
        }
        return output.entries().getFirst();
    }
}
