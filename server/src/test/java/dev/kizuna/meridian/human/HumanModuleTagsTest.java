package dev.kizuna.meridian.human;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonHostProfile;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonRegistration;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
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
    private static final double MUSCLE_TRAINING_MAXIMUM_GROWTH = 0.5;
    private static final double MUSCLE_TRAINING_HALF_LIFE_SECONDS = 600;
    private static final double MUSCLE_TRAINING_WORK_JOULES_PER_GROWTH = 3_125_000;
    private static final double ORGAN_RECOVERY_HALF_LIFE_SECONDS = 30;
    private static final double ZANG_PATH_DISTANCE_METERS = 0.1;

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
        assertElements(structure);
        assertZangEndurance(structure);
        assertZangPaths(structure);
    }

    private static void assertElements(BodyStructure structure) {
        // 五行标签只落在本脏与本行表里经上，左右成对的脏各自带标签。
        Map<String, List<String>> tags = new HashMap<>();
        structure.getOrgansList().forEach(item -> tags.put(item.getId(), item.getTagsList()));
        structure.getMeridiansList().forEach(item -> tags.put(item.getId(), item.getTagsList()));
        assertEquals(bilateral("lung"), matching(tags, "human:zang_metal"));
        assertEquals(Set.of("spleen"), matching(tags, "human:zang_earth"));
        assertEquals(Set.of("heart"), matching(tags, "human:zang_fire"));
        assertEquals(bilateral("kidney"), matching(tags, "human:zang_water"));
        assertEquals(Set.of("liver"), matching(tags, "human:zang_wood"));
        assertEquals(bilateral("lung_meridian", "large_intestine_meridian"), matching(tags, "human:meridian_metal"));
        assertEquals(bilateral("spleen_meridian", "stomach_meridian"), matching(tags, "human:meridian_earth"));
        assertEquals(bilateral("heart_meridian", "small_intestine_meridian"), matching(tags, "human:meridian_fire"));
        assertEquals(bilateral("kidney_meridian", "bladder_meridian"), matching(tags, "human:meridian_water"));
        assertEquals(bilateral("liver_meridian", "gallbladder_meridian"), matching(tags, "human:meridian_wood"));
        List<String> lungTags = tags.get("left_lung");
        assertEquals("human:zang_metal", lungTags.getLast());
    }

    private static void assertZangEndurance(BodyStructure structure) {
        // 心、肝、脾与成对的肺、肾都有独立器官耐力；数值按设计表的占位。
        Map<String, List<Double>> expected = Map.of(
                "heart", List.of(800.0, 30.0),
                "liver", List.of(600.0, 20.0),
                "spleen", List.of(600.0, 20.0),
                "left_lung", List.of(400.0, 15.0),
                "right_lung", List.of(400.0, 15.0),
                "left_kidney", List.of(400.0, 15.0),
                "right_kidney", List.of(400.0, 15.0));
        expected.forEach((id, values) -> {
            var endurance = structure.getOrgansList().stream().filter(organ -> organ.getId().equals(id))
                    .findFirst().orElseThrow().getEndurance();
            assertEquals(id, values.getFirst(), endurance.getEnduranceJoules(), 0);
            assertEquals(id, values.getLast(), endurance.getMaximumWatts(), 0);
            assertEquals(id, ORGAN_RECOVERY_HALF_LIFE_SECONDS, endurance.getRecoveryHalfLifeSeconds(), 0);
        });
    }

    private static void assertZangPaths(BodyStructure structure) {
        // 本脏经到本脏取料、本脏回本脏经送回；肺仍只有原摄入出口，不重复建边。
        Set<String> intake = Set.of(
                "left_heart_meridian>heart", "right_heart_meridian>heart",
                "left_liver_meridian>liver", "right_liver_meridian>liver",
                "left_spleen_meridian>spleen", "right_spleen_meridian>spleen",
                "left_lung_meridian>left_lung", "right_lung_meridian>right_lung",
                "left_kidney_meridian>left_kidney", "right_kidney_meridian>right_kidney");
        Set<String> giveBack = Set.of(
                "heart>left_heart_meridian", "heart>right_heart_meridian",
                "liver>left_liver_meridian", "liver>right_liver_meridian",
                "spleen>left_spleen_meridian", "spleen>right_spleen_meridian",
                "left_lung>left_lung_meridian", "right_lung>right_lung_meridian",
                "left_kidney>left_kidney_meridian", "right_kidney>right_kidney_meridian");
        Set<String> organs = structure.getOrgansList().stream().map(BodyMessages.OrganDefinition::getId)
                .collect(Collectors.toSet());
        Set<String> meridians = structure.getMeridiansList().stream().map(BodyMessages.MeridianDefinition::getId)
                .collect(Collectors.toSet());
        Set<String> toOrgans = edges(structure, meridians, organs);
        Set<String> toMeridians = edges(structure, organs, meridians);
        assertEquals(intake, toOrgans);
        assertEquals(giveBack, toMeridians);
        for (var connection : structure.getConnectionsList()) {
            String from = connection.getFrom().substring(0, connection.getFrom().lastIndexOf('.'));
            if (organs.contains(from) || organs.contains(connection.getTo().replace(".in", ""))) {
                assertFalse(connection.getId(), connection.hasPassiveQiFlow());
                assertEquals(connection.getId(), ZANG_PATH_DISTANCE_METERS, connection.getDistance(), 0);
            }
        }
    }

    private static Set<String> edges(BodyStructure structure, Set<String> sources, Set<String> targets) {
        // 端口名去掉 .out / .in 后得到模块身份，只比较两类模块之间的有向边。
        return structure.getConnectionsList().stream()
                .map(connection -> connection.getFrom().replace(".out", "") + ">"
                        + connection.getTo().replace(".in", ""))
                .filter(edge -> sources.contains(edge.substring(0, edge.indexOf('>')))
                        && targets.contains(edge.substring(edge.indexOf('>') + 1)))
                .collect(Collectors.toSet());
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
        Set<String> trained = structure.getTissuesList().stream()
                .filter(BodyMessages.TissueDefinition::hasTraining)
                .map(BodyMessages.TissueDefinition::getId)
                .collect(Collectors.toSet());
        assertEquals(bilateral("biceps_brachii", "triceps_brachii", "brachioradialis",
                "rectus_femoris", "gastrocnemius", "gluteus_maximus"), trained);
        for (var tissue : structure.getTissuesList()) {
            if (!tissue.hasTraining()) {
                continue;
            }
            var training = tissue.getTraining();
            assertEquals(MUSCLE_TRAINING_MAXIMUM_GROWTH, training.getMaximumGrowth(), 0);
            assertEquals(MUSCLE_TRAINING_HALF_LIFE_SECONDS, training.getConsolidationHalfLifeSeconds(), 0);
            assertEquals(MUSCLE_TRAINING_WORK_JOULES_PER_GROWTH, training.getWorkJoulesPerGrowth(), 0);
        }
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
