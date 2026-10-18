package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.*;
import com.google.protobuf.Any;

import java.util.ArrayList;
import java.util.List;

/** 人体分层开脉参数，执行与存档由 Framework 持有。 */
public final class HumanOpening {
    public static final String ACTION_ID = "human:open_meridian";
    public static final String POLICY_ID = "human:meridian_opening";
    public static final int RULE_VERSION = 5;
    public static final double CAPACITY_GAIN = 16.25;
    public static final double PRESSURE_QI = 22;

    private HumanOpening() {
        // 内容生成不读取或修改任何玩家状态。
    }

    public static String ruleId(String meridianId) {
        // 每条经脉对应可精确引用的固定规则，任意选择不依赖列表序号。
        return "human:open/" + meridianId;
    }

    public static Realm minimumRealm(String meridianId) {
        // 起步覆盖四肢：每侧先开放两条手经、两条足经，后续逐层补全其他正经与奇经。
        // 展示导出和权威条件共用此表，保证看见的经脉与当前可拓宽的范围一致。
        return switch (meridianId) {
            case "left_lung_meridian", "right_lung_meridian",
                    "left_large_intestine_meridian", "right_large_intestine_meridian",
                    "left_spleen_meridian", "right_spleen_meridian",
                    "left_stomach_meridian", "right_stomach_meridian" -> Realm.WANDERING_QI;
            case "left_heart_meridian", "right_heart_meridian",
                    "left_small_intestine_meridian", "right_small_intestine_meridian",
                    "left_kidney_meridian", "right_kidney_meridian",
                    "left_bladder_meridian", "right_bladder_meridian" -> Realm.TRUE_QI;
            case "left_pericardium_meridian", "right_pericardium_meridian",
                    "left_triple_burner_meridian", "right_triple_burner_meridian",
                    "left_liver_meridian", "right_liver_meridian",
                    "left_gallbladder_meridian", "right_gallbladder_meridian" -> Realm.ESSENCE_TRANSFORMATION;
            case "left_yin_qiao", "left_yang_qiao", "left_yin_wei", "left_yang_wei",
                    "right_yin_qiao", "right_yang_qiao", "right_yin_wei", "right_yang_wei" -> Realm.ESSENCE_CONDENSATION;
            case "ren", "du", "chong", "dai" -> Realm.HEAVENLY_PATTERN;
            default -> throw HumanErrors.invalid(HumanErrors.INVALID_OPENING_POLICY);
        };
    }

    public static List<MeridianOpeningRule> definitions(BodyStructure structure) {
        // 首条组合储量为 22 元；后续每条增加 16.75＋已开条数，稳定容量仍各增加 16.25 元。
        var conditions = HumanOpeningConditions.newBuilder().setAdditionalMillisPerOpened(10000)
                .setMinimumResilience(1).setBaseDurationMillis(10000).setMinimumBodyLumenFraction(.8)
                .setStrictLumenThreshold(false)
                .setGroupPressureGrowth(GroupPressureGrowth.newBuilder().setBaseIncrementQi(16.75).setStepIncrementQi(1))
                .addIntakes(IntakeRequirement.newBuilder().addOrganIds("left_lung").addOrganIds("right_lung")
                        .setMinimumQiPerSecond(.005).setWindowMillis(10000));
        for (MeridianDefinition meridian : structure.getMeridiansList()) {
            conditions.addThresholds(PressureThreshold.newBuilder()
                    .setMeridianId(meridian.getId()).setQi(PRESSURE_QI));
        }
        var rules = new ArrayList<MeridianOpeningRule>();
        for (MeridianDefinition meridian : structure.getMeridiansList()) {
            rules.add(MeridianOpeningRule.newBuilder().setId(ruleId(meridian.getId())).setVersion(RULE_VERSION)
                    .setDisplayName("开拓" + meridian.getDisplayName()).setTargetMeridianId(meridian.getId())
                    .setMinimumLumenQi(QiInventory.newBuilder().addEntries(Qi.newBuilder()
                            .setPhase(QiPhase.FREE).setAttribute("meridian:none").setAmount(PRESSURE_QI)))
                    .setActionId(ACTION_ID).setActionVersion(1)
                    .setPolicyId(POLICY_ID).setPolicyConfig(Any.pack(conditions.clone()
                            .setMinimumRealm(minimumRealm(meridian.getId())).build()))
                    .setContinuousHold(true).setLumenCapacityGain(CAPACITY_GAIN)
                    .setGuidanceQiPerSecond(1).setGuidanceBatchQi(.25)
                    // 凝聚需要持续供给；目标补到门槛上方 0.2 元，适应期间每秒耗散 0.1 元。
                    .setHoldQiPerSecond(.1).setGuidanceBufferQi(.2)
                    // 开脉独立付出脑耐力和精力，吐纳的膈肌与注意费用照常由其自身负载结算。
                    .addEfforts(OpeningEffort.newBuilder().setOrganId("brain").setWatts(1))
                    .addEfforts(OpeningEffort.newBuilder().setTissueId("attention_network").setWatts(.25)).build());
        }
        return List.copyOf(rules);
    }
}
