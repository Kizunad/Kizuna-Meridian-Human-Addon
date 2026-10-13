package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;

import java.util.List;
import java.util.TreeSet;

/** 人体包的唯一公开内容入口；其他附属可以声明对 human 修订 1 的直接依赖。 */
public final class HumanContent {
    public static final String NAMESPACE = "human";
    public static final String TEMPLATE_ID = "human:adult";
    public static final int CONTENT_VERSION = 1;
    public static final String BUNDLE_RESOURCE = "META-INF/kizuna_meridian/addons/human.pb";
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
                .addSupportedHosts("meridian:java_reference")
                .addSupportedHosts("meridian:fabric")
                .addSupportedHosts("bong:server");
        BodyStructure structure = HumanAnatomy.structure();
        var template = BodyTemplate.newBuilder().setReference(TemplateReference.newBuilder()
                .setId(TEMPLATE_ID).setVersion(CONTENT_VERSION)).setStructure(structure).build();
        var bundle = AddonBundle.newBuilder().setSchemaVersion(1).setManifest(manifest).addTemplates(template);

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
                    .setDurationMillis(REFINING_MILLIS).setMinimumRealm(Realm.AWAKENING));
        }
        AddonBundle result = bundle.build();
        AddonProtobuf.validateBundle(result);
        return result;
    }

    public static byte[] payload() {
        // JAR 资源与跨语言宿主取得同一份有界 Protobuf 内容，不要求对方运行 Java 定义代码。
        return AddonProtobuf.encodeBundle(bundle());
    }

    private static QiInventory inventory(String attribute, double amount) {
        // 起步配方只处理游离态；高阶相态不能通过低阶配方悄悄降级或凭空转化。
        return QiInventory.newBuilder().addEntries(Qi.newBuilder().setPhase(QiPhase.FREE)
                .setAttribute(attribute).setAmount(amount)).build();
    }
}
