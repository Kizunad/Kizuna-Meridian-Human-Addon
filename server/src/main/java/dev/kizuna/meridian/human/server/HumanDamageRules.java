package dev.kizuna.meridian.human.server;

import org.tomlj.Toml;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 人体模板的蔓延系数、宿主实体开关，和没绑身体的目标挨「麻」时的原版退路；不保存身体运行状态。 */
public final class HumanDamageRules {
    public static final String FILE_NAME = "meridian-human-damage.toml";
    private static final String BODILESS_NUMB = "bodiless_numb";
    /** 原版状态等级的上限（等级 = 放大值 + 1，放大值存一个字节）。 */
    private static final int MAX_EFFECT_LEVEL = 256;
    private final Map<String, Boolean> entities;
    private final Map<String, Template> templates;
    private final BodilessNumb bodilessNumb;

    private HumanDamageRules(Map<String, Boolean> entities, Map<String, Template> templates,
                             BodilessNumb bodilessNumb) {
        // 配置在服务端启动时固定，不让一次事务中途读到修改后的映射。
        this.entities = Map.copyOf(entities);
        this.templates = Map.copyOf(templates);
        this.bodilessNumb = bodilessNumb;
    }

    public static HumanDamageRules defaults() {
        // 无 Minecraft 宿主的内容测试同样从随包数据读取初值。
        return parse(defaultText());
    }

    public static HumanDamageRules load(Path file) {
        // 首次启动写出可编辑配置；损坏配置明确拒绝启动，不静默恢复初值。
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, defaultText());
            }
            return parse(Files.readString(file));
        } catch (IOException failure) {
            throw new IllegalStateException(HumanErrors.SPILLOVER_LOAD_FAILED, failure);
        }
    }

    private static String defaultText() {
        // 必需资源缺失属于装配错误，不能让玩家和类人采用不同默认规则。
        try (var input = HumanDamageRules.class.getResourceAsStream("/" + FILE_NAME)) {
            if (input == null) {
                throw new IllegalStateException(HumanErrors.SPILLOVER_LOAD_FAILED);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(HumanErrors.SPILLOVER_LOAD_FAILED, failure);
        }
    }

    public static HumanDamageRules parse(String text) {
        // 模板与实体类型是独立维度，玩家开关不会影响使用同一模板的类人。
        var parsed = Toml.parse(text);
        if (parsed.hasErrors() || !(parsed.get("entities") instanceof TomlTable entities)
                || !(entities.get("default_enabled") instanceof Boolean)
                || !(parsed.get("templates") instanceof TomlTable templates)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        Map<String, Boolean> switches = entitySwitches(entities);
        Map<String, Template> rules = templateRules(templates);
        return new HumanDamageRules(switches, rules, bodilessNumb(parsed.getTable(BODILESS_NUMB)));
    }

    private static BodilessNumb bodilessNumb(TomlTable table) {
        // 这一节是后加的：老配置文件里没有，就按随包初值算，不逼着服主重新生成文件。
        TomlTable numb = table;
        if (numb == null) {
            numb = Toml.parse(defaultText()).getTable(BODILESS_NUMB);
        }
        if (numb == null) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        return new BodilessNumb(whole(numb.get("ticks"), 1, Integer.MAX_VALUE),
                whole(numb.get("slowness_level"), 0, MAX_EFFECT_LEVEL),
                whole(numb.get("weakness_level"), 0, MAX_EFFECT_LEVEL));
    }

    private static int whole(Object value, int least, int most) {
        // 只收范围内的整数；小数、字符串、越界都拒绝，不悄悄取整。
        if (!(value instanceof Long number) || number < least || number > most) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        return number.intValue();
    }

    private static Map<String, Boolean> entitySwitches(TomlTable entities) {
        // 每个实体开关必须显式声明布尔值，不能将错误类型解释为关闭。
        Map<String, Boolean> switches = new HashMap<>();
        for (String id : entities.keySet()) {
            Object value = entities.get(List.of(id));
            if (!(value instanceof Boolean enabled)) {
                throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
            }
            switches.put(id, enabled);
        }
        return switches;
    }

    private static Map<String, Template> templateRules(TomlTable templates) {
        // 人体模板必须存在，其他模板仅在配置明确登记后参与蔓延。
        Map<String, Template> rules = new HashMap<>();
        for (String id : templates.keySet()) {
            rules.put(id, template(templates.getTable(List.of(id))));
        }
        if (!rules.containsKey(HumanContent.TEMPLATE_ID)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        return rules;
    }

    private static Template template(TomlTable table) {
        // 每个已知部位可单独改系数，新部位使用同模板明确声明的默认系数。
        if (table == null || !(table.get("parts") instanceof TomlTable parts)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        double defaultMultiplier = multiplier(table.get("default_multiplier"));
        Map<String, Double> coefficients = new HashMap<>();
        for (String part : parts.keySet()) {
            coefficients.put(part, multiplier(parts.get(List.of(part))));
        }
        return new Template(defaultMultiplier, Map.copyOf(coefficients));
    }

    private static double multiplier(Object value) {
        // 允许零系数关闭某部位的蔓延，拒绝负数、无穷与非数字。
        if (!(value instanceof Number number)) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        double coefficient = number.doubleValue();
        if (!Double.isFinite(coefficient) || coefficient < 0) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        return coefficient;
    }

    public boolean enabled(String entityType, String templateId) {
        // 没有挂规则的模板不参与人体蔓延。
        return entities.getOrDefault(entityType, entities.get("default_enabled")) && templates.containsKey(templateId);
    }

    public double multiplier(String templateId, String partId) {
        // 调用方已经确认模板开启，缺失模板是接线错误。
        Template template = templates.get(templateId);
        if (template == null) {
            throw HumanErrors.invalid(HumanErrors.INVALID_SPILLOVER);
        }
        return template.parts().getOrDefault(partId, template.defaultMultiplier());
    }

    public BodilessNumb bodilessNumb() {
        // 没绑身体的目标挨了带「麻」的一下，拿什么原版状态顶替。
        return bodilessNumb;
    }

    /** 没绑身体的目标挨「麻」时给的原版缓慢、虚弱：持续多少 tick、各几级；等级 0 表示那一样不给。 */
    public record BodilessNumb(int ticks, int slownessLevel, int weaknessLevel) { }

    private record Template(double defaultMultiplier, Map<String, Double> parts) { }

    static final class TemplateDefaults {
        private final Map<String, Template> additions = new HashMap<>();
        private boolean installed;

        synchronized void register(String target, String bundledSource) {
            // 来源只查随包数据，服主对来源模板的改动只用于该模板自身。
            if (installed) {
                throw new IllegalStateException(HumanErrors.SPILLOVER_ALREADY_INSTALLED);
            }
            var bundled = defaults().templates;
            if (bundledSource == null || !bundled.containsKey(bundledSource)) {
                throw HumanErrors.invalid(HumanErrors.SPILLOVER_UNKNOWN_SOURCE + bundledSource);
            }
            if (target == null || target.isBlank() || bundled.containsKey(target)
                    || additions.putIfAbsent(target, bundled.get(bundledSource)) != null) {
                throw HumanErrors.invalid(HumanErrors.SPILLOVER_INVALID_TARGET + target);
            }
        }

        synchronized HumanDamageRules install(Path file) {
            // 服主配置的整段优先。合并只在内存中进行，已有文件按原样保留。
            var configured = load(file);
            var merged = new HashMap<>(additions);
            merged.putAll(configured.templates);
            var rules = new HumanDamageRules(configured.entities, merged, configured.bodilessNumb);
            installed = true;
            return rules;
        }
    }
}
