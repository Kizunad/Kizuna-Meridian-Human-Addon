package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModuleKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 使用正式八式的时序、算子与承接，只降低出生身体的资源门槛。 */
public final class HumanCombatTestCatalog implements TechniqueRegistryEntrypoint {
    static final String NAMESPACE = "human_combat_test";
    private static final String SOURCE = "meridian_door_breaker";
    private static final String RESOURCE = "data/meridian_door_breaker/technique_catalog.txtpb";

    @Override
    public TechniqueRegistry registry() {
        // 从运行时真实内容读取，数据包时序变动会直接进入玩家宿主回归。
        TechniqueCatalog original = load();
        var catalog = original.toBuilder()
                .setNamespace(NAMESPACE)
                .clearBooks()
                .clearVolumes()
                .clearDefinitions();
        for (var book : original.getBooksList()) {
            catalog.addBooks(book.toBuilder().setId(rename(book.getId())).build());
        }
        for (var volume : original.getVolumesList()) {
            catalog.addVolumes(volume(volume));
        }
        for (var definition : original.getDefinitionsList()) {
            catalog.addDefinitions(definition(definition));
        }
        return TechniqueRegistry.load(HumanTechniqueTestCatalog.addons(), List.of(catalog.build()));
    }

    private static TechniqueCatalog load() {
        // 缺少必装破门斩资源直接失败，不构造替代目录。
        var stream = HumanCombatTestCatalog.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("玩家战斗测试缺少破门斩目录");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var catalog = TechniqueCatalog.newBuilder();
            TextFormat.getParser().merge(reader, catalog);
            return catalog.build();
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取破门斩测试目录", failure);
        }
    }

    private static TechniqueVolume volume(TechniqueVolume original) {
        // 只有目录身份迁到测试命名空间，卷间前置与书级物品规则保持原样。
        var volume = original.toBuilder()
                .setId(rename(original.getId()))
                .setBookId(rename(original.getBookId()))
                .clearTechniques()
                .clearPrerequisiteVolumeIds();
        for (var reference : original.getTechniquesList()) {
            volume.addTechniques(reference.toBuilder().setId(rename(reference.getId())).build());
        }
        for (String prerequisite : original.getPrerequisiteVolumeIdsList()) {
            volume.addPrerequisiteVolumeIds(rename(prerequisite));
        }
        return volume.build();
    }

    private static TechniqueDefinition definition(TechniqueDefinition original) {
        // 保留真实效果、曲线与时长，身体门槛仅使用一个已开游炁手经。
        var definition = original.toBuilder()
                .setId(rename(original.getId()))
                .setMinimumRealm(Realm.WANDERING_QI)
                .setCost(cost(original.getCost()))
                .clearSlots();
        for (var slot : original.getSlotsList()) {
            if (slot.getModule() == ModuleKind.MERIDIAN) {
                definition.addSlots(slot.toBuilder()
                        .clearTags()
                        .addTags("human:hand_meridian")
                        .setMinimumCount(1)
                        .setMaximumCount(1)
                        .build());
            } else {
                definition.addSlots(slot);
            }
        }
        var follow = original.getFollowUp().toBuilder().clearTransitions();
        for (var transition : original.getFollowUp().getTransitionsList()) {
            var previous = transition.getPrevious().toBuilder()
                    .setId(rename(transition.getPrevious().getId()))
                    .build();
            follow.addTransitions(transition.toBuilder().setPrevious(previous).build());
        }
        if (original.hasFollowUp()) {
            definition.setFollowUp(follow.build());
        }
        return definition.build();
    }

    private static TechniqueCost cost(TechniqueCost original) {
        // 真正扣费与做功仍走权威链，只缩小数量以适应自然出生库存。
        var cost = original.toBuilder().clearQi().clearWork();
        var amount = HumanTechniqueTestCatalog.quantity(HumanTechniqueTestCatalog.COST_QI, Unit.QI);
        for (var qi : original.getQiList()) {
            var reduced = qi.toBuilder()
                    .setAmount(amount)
                    .setPhase(QiPhase.FREE)
                    .build();
            cost.addQi(reduced);
        }
        var force = HumanTechniqueTestCatalog.quantity(1, Unit.NEWTON);
        var power = HumanTechniqueTestCatalog.quantity(1, Unit.WATT);
        for (var work : original.getWorkList()) {
            var reduced = work.toBuilder()
                    .setForce(force)
                    .setPower(power)
                    .build();
            cost.addWork(reduced);
        }
        return cost.build();
    }

    static String rename(String id) {
        // 资源 ID 和人体标签不改名，只有功法目录引用使用本命名空间。
        return id.replace(SOURCE + ":", NAMESPACE + ":");
    }
}
