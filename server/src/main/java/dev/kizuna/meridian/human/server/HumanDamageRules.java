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

/** 人体模板的蔓延系数与宿主实体开关；不保存身体运行状态。 */
public final class HumanDamageRules {
    public static final String FILE_NAME = "meridian-human-damage.toml";
    private final Map<String, Boolean> entities;
    private final Map<String, Template> templates;

    private HumanDamageRules(Map<String, Boolean> entities, Map<String, Template> templates) {
        // 配置在服务端启动时固定，不让一次事务中途读到修改后的映射。
        this.entities = Map.copyOf(entities);
        this.templates = Map.copyOf(templates);
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
        return new HumanDamageRules(switches, rules);
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

    private record Template(double defaultMultiplier, Map<String, Double> parts) { }
}
