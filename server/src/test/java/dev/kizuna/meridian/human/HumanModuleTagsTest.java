package dev.kizuna.meridian.human;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonHostProfile;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonRegistration;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.server.AddonRegistry;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** 人体公开标签经过真实内容注册，并与模块身份、左右及开脉分层一致。 */
public final class HumanModuleTagsTest {
    @Test
    public void registeredTemplateHasExactSymmetricModuleTags() {
        // 使用正式宿主能力画像安装模板，避免仅检查尚未通过框架校验的候选。
        var host = AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE)
                .build();
        var registration = AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(HumanContent.bundle())
                .build();
        var registry = AddonRegistry.load(registration);
        var structure = registry.findTemplate(HumanContent.TEMPLATE_ID, HumanContent.CONTENT_VERSION)
                .orElseThrow().getStructure();
        assertMeridians(structure);
        assertTissuesAndOrgans(structure);
    }

    private static void assertMeridians(BodyStructure structure) {
        // 奇经没有手足标签；游炁层严格使用开脉规则的同一分层。
        Map<String, List<String>> tags = new HashMap<>();
        structure.getMeridiansList().forEach(item -> tags.put(item.getId(), item.getTagsList()));
        assertEquals(bilateral("lung_meridian", "large_intestine_meridian", "heart_meridian",
                "small_intestine_meridian", "pericardium_meridian", "triple_burner_meridian"),
                matching(tags, "human:hand_meridian"));
        assertEquals(bilateral("stomach_meridian", "spleen_meridian", "kidney_meridian",
                "bladder_meridian", "gallbladder_meridian", "liver_meridian"),
                matching(tags, "human:foot_meridian"));
        Set<String> wandering = tags.keySet().stream()
                .filter(id -> HumanOpening.minimumRealm(id) == Realm.WANDERING_QI)
                .collect(Collectors.toSet());
        assertFalse(wandering.isEmpty());
        assertEquals(wandering, matching(tags, "human:layer_wandering_qi"));
        assertSymmetry(tags);
    }

    private static void assertTissuesAndOrgans(BodyStructure structure) {
        // 标签限定实际肌肉与呼吸模块，不把同名部位或骨组一起选中。
        Map<String, List<String>> tags = new HashMap<>();
        structure.getTissuesList().forEach(item -> tags.put(item.getId(), item.getTagsList()));
        structure.getOrgansList().forEach(item -> tags.put(item.getId(), item.getTagsList()));
        assertEquals(bilateral("biceps_brachii", "triceps_brachii", "brachioradialis"),
                matching(tags, "human:arm_muscle"));
        assertEquals(bilateral("rectus_femoris", "gastrocnemius", "gluteus_maximus"),
                matching(tags, "human:leg_muscle"));
        assertEquals(Set.of("diaphragm", "left_lung", "right_lung"), matching(tags, "human:breathing"));
        assertSymmetry(tags);
    }

    private static Set<String> bilateral(String... modules) {
        // 预期值按明确的模块名单展开，独立于正式模板的标签生成代码。
        return List.of(modules).stream()
                .flatMap(id -> List.of("left_" + id, "right_" + id).stream())
                .collect(Collectors.toSet());
    }

    private static Set<String> matching(Map<String, List<String>> tags, String tag) {
        // 比较完整命中集合，防止漏标和误标同时被非空断言掩盖。
        return tags.entrySet().stream().filter(entry -> entry.getValue().contains(tag))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private static void assertSymmetry(Map<String, List<String>> tags) {
        // 左右同名模块必须具有完全相同的标签集合。
        tags.forEach((id, values) -> {
            if (id.startsWith("left_")) {
                assertEquals(id, Set.copyOf(values), Set.copyOf(tags.get("right_" + id.substring("left_".length()))));
            }
        });
    }
}
