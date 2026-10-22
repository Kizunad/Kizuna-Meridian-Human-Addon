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
    public static final int RULE_VERSION = 6;
    public static final double LUMEN_VOLUME_GAIN = 1625;

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
        // 目标脉按当前稳定体积的占用比例检查；第 N 条的连续时长为 10 + 5N 秒。
        var conditions = HumanOpeningConditions.newBuilder().setAdditionalMillisPerOpened(5000)
                .setMinimumResilience(1).setBaseDurationMillis(15000)
                .setBodyStartFraction(.8).setTargetStartFraction(.8)
                .setBodyHoldFraction(.6).setTargetHoldFraction(.6)
                .addIntakes(IntakeRequirement.newBuilder().addOrganIds("left_lung").addOrganIds("right_lung")
                        .setMinimumQiPerSecond(.005).setWindowMillis(10000));
        var rules = new ArrayList<MeridianOpeningRule>();
        for (MeridianDefinition meridian : structure.getMeridiansList()) {
            rules.add(MeridianOpeningRule.newBuilder().setId(ruleId(meridian.getId())).setVersion(RULE_VERSION)
                    .setDisplayName("开拓" + meridian.getDisplayName()).setTargetMeridianId(meridian.getId())
                    .setActionId(ACTION_ID).setActionVersion(1)
                    .setPolicyId(POLICY_ID).setPolicyConfig(Any.pack(conditions.clone()
                            .setMinimumRealm(minimumRealm(meridian.getId())).build()))
                    .setContinuousHold(true).setLumenVolumeGain(LUMEN_VOLUME_GAIN)
                    .setGuidanceQiPerSecond(1).setGuidanceBatchQi(.25)
                    // 维持费用按全身内腔实际占用体积分摊，不能制造临时容量。
                    .setHoldQiPerSecond(.1)
                    // 开脉独立付出脑耐力和精力，吐纳的膈肌与注意费用照常由其自身负载结算。
                    .addEfforts(OpeningEffort.newBuilder().setOrganId("brain").setWatts(1))
                    .addEfforts(OpeningEffort.newBuilder().setTissueId("attention_network").setWatts(.25)).build());
        }
        return List.copyOf(rules);
    }
}
