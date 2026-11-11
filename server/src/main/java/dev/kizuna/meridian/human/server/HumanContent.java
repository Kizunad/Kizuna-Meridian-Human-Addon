package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;

import java.util.List;
import java.util.TreeSet;

/** 人体包的唯一公开内容入口；其他附属可以声明对 human 修订 1 的直接依赖。 */
public final class HumanContent {
    public static final String NAMESPACE = "human";
    public static final String TEMPLATE_ID = "human:adult";
    public static final int CONTENT_VERSION = 1;
    public static final String BUNDLE_RESOURCE = "META-INF/kizuna_meridian/addons/human.pb";
    public static final String BREATHING_RESOURCE = "META-INF/kizuna_meridian/human-breathing.pb";
    public static final List<String> INPUT_ATTRIBUTES = List.of("meridian:metal", "meridian:wood",
            "meridian:water", "meridian:fire", "meridian:earth");
    public static final double RAW_QI_PER_BATCH = 1;
    public static final double REFINED_QI_PER_BATCH = 0.2;
    public static final long REFINING_MILLIS = 1000;
    /** 真炁吐纳配方 ID 的后缀，接在 human:breath_<属性> 之后。 */
    public static final String TRUE_QI_BREATH_SUFFIX = "_true_qi";
    private static final String NONE = "meridian:none";
    private static final double BREATH_INPUT_QI = 0.05;
    private static final double BREATH_OUTPUT_QI = 0.01;
    private static final long BREATH_MILLIS = 100;

    private HumanContent() {
        // 所有内容都是可重复生成的不可变消息，不缓存玩家运行状态。
    }

    public static AddonBundle bundle() {
        // 运行时取服务端配置的吐纳份额；内容导出与无宿主测试取随包初值。
        return bundle(HumanBreathingRules.configured());
    }

    public static AddonBundle bundle(HumanBreathingRules breathingRules) {
        // 框架本身不是内容包；前置用 API 和通用能力表达，human 没有其他生物包前置。
        var manifest = AddonManifest.newBuilder().setNamespace(NAMESPACE).setContentVersion(CONTENT_VERSION)
                .setApiVersions(ContentVersionRange.newBuilder().setMinimum(1).setMaximum(1))
                .addRequiredCapabilities("meridian:body_templates_v1")
                .addRequiredCapabilities("meridian:organ_recipes_v1")
                .addRequiredCapabilities("meridian:organ_intake_v1")
                .addRequiredCapabilities("meridian:actions_v1")
                .addRequiredCapabilities("meridian:tissue_recovery_v2")
                .addRequiredCapabilities("meridian:passive_qi_flow_v1")
                .addRequiredCapabilities("meridian:opening_definitions_v1")
                .addRequiredCapabilities("meridian:opening_runtime_v1")
                .addRequiredCapabilities("meridian:organ_endurance_v1")
                .addSupportedHosts("meridian:java_reference")
                .addSupportedHosts("meridian:fabric")
                .addSupportedHosts("bong:server");
        BodyStructure structure = HumanAnatomy.structure();
        var template = BodyTemplate.newBuilder().setReference(TemplateReference.newBuilder()
                .setId(TEMPLATE_ID).setVersion(CONTENT_VERSION)).setStructure(structure).build();
        var bundle = AddonBundle.newBuilder().setSchemaVersion(1).setManifest(manifest).addTemplates(template)
                .addAllActions(HumanActions.definitions()).addAllOpeningRules(HumanOpening.definitions(structure));

        // 先登记模板实际使用的类型；两肺共享 lung 类型，但局部器官 ID 各自独立。
        var tissueKinds = new TreeSet<String>();
        structure.getTissuesList().forEach(tissue -> tissueKinds.add(tissue.getKind()));
        for (String kind : tissueKinds) {
            bundle.addSymbols(ContentSymbol.newBuilder().setId(kind).setKind(ContentSymbolKind.TISSUE_KIND));
        }
        var organKinds = new TreeSet<String>();
        structure.getOrgansList().forEach(organ -> organKinds.add(organ.getKind()));
        for (String kind : organKinds) {
            bundle.addSymbols(ContentSymbol.newBuilder().setId(kind).setKind(ContentSymbolKind.ORGAN_KIND));
        }

        // 五行各自一条固定小批配方，避免要求某区域同时拥有特定比例的五行。
        for (String attribute : INPUT_ATTRIBUTES) {
            bundle.addOrganRecipes(OrganRecipe.newBuilder()
                    .setId("human:lung_refine_" + attribute.substring(attribute.indexOf(':') + 1))
                    .setVersion(CONTENT_VERSION).setOrganKind("human:lung")
                    .setInput(inventory(attribute, RAW_QI_PER_BATCH))
                    .setOutput(inventory("meridian:none", REFINED_QI_PER_BATCH))
                    .setDurationMillis(REFINING_MILLIS).setMinimumRealm(Realm.WANDERING_QI));
            // 手动接触摄入读取此配方的转换比例，实际剂量由环境浓度和粒子采样体积决定。
            bundle.addOrganRecipes(OrganRecipe.newBuilder()
                    .setId(breathRecipeId(attribute))
                    .setVersion(CONTENT_VERSION)
                    .setOrganKind("human:lung")
                    .setInput(inventory(attribute, BREATH_INPUT_QI))
                    .setOutput(inventory(NONE, BREATH_OUTPUT_QI))
                    .setDurationMillis(BREATH_MILLIS)
                    .setMinimumRealm(Realm.WANDERING_QI));
            // 真炁以后同一口气的产物一部分直接是气态；框架吐纳先用境界要求高的这一条。
            bundle.addOrganRecipes(OrganRecipe.newBuilder()
                    .setId(trueQiBreathRecipeId(attribute))
                    .setVersion(CONTENT_VERSION)
                    .setOrganKind("human:lung")
                    .setInput(inventory(attribute, BREATH_INPUT_QI))
                    .setOutput(trueQiBreathOutput(breathingRules.trueQiGasShare()))
                    .setDurationMillis(BREATH_MILLIS)
                    .setMinimumRealm(Realm.TRUE_QI));
        }
        AddonBundle result = bundle.build();
        AddonProtobuf.validateBundle(result);
        return result;
    }

    public static byte[] payload() {
        // JAR 资源与跨语言宿主取得同一份有界 Protobuf 内容，不要求对方运行 Java 定义代码。
        return AddonProtobuf.encodeBundle(bundle());
    }

    public static BreathingProfile breathing() {
        // 空气容量与流速是人体内容；双肺总计 5 升、每秒 1.25 升，一次完整吸气约 4 秒。
        var automatic = AutomaticBreathing.newBuilder()
                .setMinimumStaminaRatio(HumanBreathingNumbers.AUTOMATIC_MIN_STAMINA_RATIO)
                .setResumeStaminaRatio(HumanBreathingNumbers.AUTOMATIC_RESUME_STAMINA_RATIO)
                .setCaptureEfficiency(HumanBreathingNumbers.AUTOMATIC_CAPTURE_EFFICIENCY)
                .setBodyAction(breathingAction("human:automatic_breathing"))
                .build();
        var profile = BreathingProfile.newBuilder()
                .setRespiratoryTissueId("diaphragm")
                .setAttentionTissueId("attention_network")
                .setRespiratoryWatts(HumanBreathingNumbers.RESPIRATORY_WATTS)
                .setAttentionWatts(HumanBreathingNumbers.ATTENTION_WATTS)
                .setBodyAction(breathingAction("human:breathing"))
                .setAutomatic(automatic)
                .setParticleVolumeBlocks(1);
        for (String side : List.of("left", "right")) {
            var organ = BreathingOrgan.newBuilder()
                    .setOrganId(side + "_lung")
                    .setAirCapacityLiters(HumanBreathingNumbers.LUNG_AIR_CAPACITY_LITERS)
                    .setAirflowLitersPerSecond(HumanBreathingNumbers.LUNG_AIRFLOW_LITERS_PER_SECOND)
                    .build();
            profile.addOrgans(organ);
        }
        for (String attribute : INPUT_ATTRIBUTES) {
            profile.addRecipeIds(breathRecipeId(attribute));
            profile.addRecipeIds(trueQiBreathRecipeId(attribute));
        }
        return profile.build();
    }

    public static String breathRecipeId(String attribute) {
        // 吐纳配方按灵气属性各一条，ID 取属性名的路径部分。
        return "human:breath_" + attribute.substring(attribute.indexOf(':') + 1);
    }

    public static String trueQiBreathRecipeId(String attribute) {
        // 真炁吐纳配方与同属性的基础配方成对。
        return breathRecipeId(attribute) + TRUE_QI_BREATH_SUFFIX;
    }

    private static QiInventory trueQiBreathOutput(double gasShare) {
        // 产物总量与基础配方相同，按份额分成气态与游离态两行；份额为 0 或 1 时只留一行。
        double gasQi = BREATH_OUTPUT_QI * gasShare;
        double freeQi = BREATH_OUTPUT_QI - gasQi;
        var output = QiInventory.newBuilder();
        if (freeQi > 0) {
            output.addEntries(qi(NONE, freeQi, QiDensity.FREE));
        }
        if (gasQi > 0) {
            output.addEntries(qi(NONE, gasQi, QiDensity.baseline(QiPhase.GAS)));
        }
        return output.build();
    }

    private static Qi qi(String attribute, double amount, double density) {
        // 单行真元明细。
        return Qi.newBuilder()
                .setAttribute(attribute)
                .setDensity(density)
                .setAmount(amount)
                .build();
    }

    private static BreathingActionReference breathingAction(String id) {
        // 手动和自动各自绑定内容版本对应的组织占用动作。
        return BreathingActionReference.newBuilder()
                .setId(id)
                .setVersion(CONTENT_VERSION)
                .build();
    }

    private static QiInventory inventory(String attribute, double amount) {
        // 起步配方只处理游离态；高阶相态不能通过低阶配方悄悄降级或凭空转化。
        return QiInventory.newBuilder().addEntries(Qi.newBuilder().setDensity(.01)
                .setAttribute(attribute).setAmount(amount)).build();
    }
}
