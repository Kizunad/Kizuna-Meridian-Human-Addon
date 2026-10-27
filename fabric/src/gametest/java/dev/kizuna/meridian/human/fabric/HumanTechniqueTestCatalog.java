package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TemplateReference;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhases;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Category;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModuleKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiDistribution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueSlot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WorkCost;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;

import java.util.List;

/** 仅供游戏测试的最小人体功法；正式内容包不携带具体功法。 */
public final class HumanTechniqueTestCatalog implements TechniqueRegistryEntrypoint {
    static final String TECHNIQUE_ID = "human_test:breath_step";
    static final long WARMUP_MILLIS = 100;
    static final long ACTIVE_MILLIS = 100;
    static final long RECOVERY_MILLIS = 100;
    static final long COOLDOWN_MILLIS = 1000;
    static final double COST_QI = 0.001;
    static final double SPEED_METERS_PER_SECOND = 10;
    private static final double WORK_SECONDS = 0.01;

    @Override
    public TechniqueRegistry registry() {
        // 同正式内容一样通过目录入口完成模板引用、标签及费用校验。
        var template = TemplateReference.newBuilder()
                .setId(HumanContent.TEMPLATE_ID)
                .setVersion(HumanContent.CONTENT_VERSION)
                .build();
        var catalog = TechniqueCatalog.newBuilder()
                .setSchemaVersion(1)
                .setNamespace("human_test")
                .addBindingTemplates(template)
                .addDefinitions(definition())
                .build();
        return TechniqueRegistry.load(addons(), List.of(catalog));
    }

    private static AddonRegistry addons() {
        // 明确列出测试宿主实现的框架能力，不伪造标签或模块定义。
        var host = AddonHostProfile.newBuilder()
                .setHostId("meridian:fabric")
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
        return AddonRegistry.load(registration);
    }

    private static TechniqueDefinition definition() {
        // 选择游炁手经扣费并占用膈肌，与真实吐纳共享同一组织。
        var source = TechniqueSlot.newBuilder()
                .setId("source")
                .setModule(ModuleKind.MERIDIAN)
                .addTags("human:hand_meridian")
                .addTags("human:layer_wandering_qi")
                .setMinimumCount(1)
                .setMaximumCount(1)
                .setRequireOpened(true)
                .build();
        var tissue = TechniqueSlot.newBuilder()
                .setId("breath")
                .setModule(ModuleKind.TISSUE)
                .addTags("human:breathing")
                .setMinimumCount(1)
                .setMaximumCount(1)
                .build();
        var phases = CastPhases.newBuilder()
                .setWarmupMillis(WARMUP_MILLIS)
                .setActiveMillis(ACTIVE_MILLIS)
                .setRecoveryMillis(RECOVERY_MILLIS)
                .setInterruptibleByDamage(true)
                .build();
        return TechniqueDefinition.newBuilder()
                .setId(TECHNIQUE_ID)
                .setVersion(1)
                .setCategory(Category.MOVEMENT)
                .setMinimumRealm(Realm.WANDERING_QI)
                .addSlots(source)
                .addSlots(tissue)
                .setCost(cost())
                .setPhases(phases)
                .setCooldownMillis(COOLDOWN_MILLIS)
                .addEffects(movement())
                .build();
    }

    private static TechniqueCost cost() {
        // 小额真实费用足以覆盖默认账户释放和组织疲劳，不依赖调试造元。
        var qi = QiCost.newBuilder()
                .setSlotId("source")
                .setAttribute("meridian:none")
                .setPhase(QiPhase.FREE)
                .setAmount(quantity(COST_QI, Unit.QI))
                .setDistribution(QiDistribution.EACH)
                .build();
        var work = WorkCost.newBuilder()
                .setSlotId("breath")
                .setForce(quantity(1, Unit.NEWTON))
                .setPower(quantity(1, Unit.WATT))
                .setDuration(quantity(WORK_SECONDS, Unit.SECOND))
                .build();
        return TechniqueCost.newBuilder().addQi(qi).addWork(work).build();
    }

    private static TechniqueEffect movement() {
        // 实际服务在生效边界下发一次输入方向冲量。
        var impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(quantity(SPEED_METERS_PER_SECOND, Unit.METERS_PER_SECOND))
                .build();
        return TechniqueEffect.newBuilder().setAtMillis(WARMUP_MILLIS).setImpulse(impulse).build();
    }

    static Quantity quantity(double value, Unit unit) {
        // 单位必须显式提供，不能靠字段名猜测数量语义。
        return Quantity.newBuilder().setValue(value).setUnit(unit).build();
    }
}
