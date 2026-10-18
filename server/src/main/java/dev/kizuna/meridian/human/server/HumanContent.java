package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.AddonProtobuf;
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

    private HumanContent() {
        // 所有内容都是可重复生成的不可变消息，不缓存玩家运行状态。
    }

    public static AddonBundle bundle() {
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
                    .setId("human:breath_" + attribute.substring(attribute.indexOf(':') + 1))
                    .setVersion(CONTENT_VERSION).setOrganKind("human:lung")
                    .setInput(inventory(attribute, .05)).setOutput(inventory("meridian:none", .01))
                    .setDurationMillis(100).setMinimumRealm(Realm.WANDERING_QI));
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
        var profile = BreathingProfile.newBuilder().setRespiratoryTissueId("diaphragm")
                .setAttentionTissueId("attention_network").setRespiratoryWatts(30).setAttentionWatts(4)
                .setBodyAction(BreathingActionReference.newBuilder().setId("human:breathing").setVersion(1))
                .setParticleVolumeBlocks(1);
        for (String side : List.of("left", "right")) {
            profile.addOrgans(BreathingOrgan.newBuilder().setOrganId(side + "_lung")
                    .setAirCapacityLiters(2.5).setAirflowLitersPerSecond(.625));
        }
        for (String attribute : INPUT_ATTRIBUTES) {
            profile.addRecipeIds("human:breath_" + attribute.substring(attribute.indexOf(':') + 1));
        }
        return profile.build();
    }

    private static QiInventory inventory(String attribute, double amount) {
        // 起步配方只处理游离态；高阶相态不能通过低阶配方悄悄降级或凭空转化。
        return QiInventory.newBuilder().addEntries(Qi.newBuilder().setPhase(QiPhase.FREE)
                .setAttribute(attribute).setAmount(amount)).build();
    }
}
