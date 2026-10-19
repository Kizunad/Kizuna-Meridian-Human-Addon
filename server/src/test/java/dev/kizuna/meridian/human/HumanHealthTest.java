package dev.kizuna.meridian.human;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.List;
import java.util.EnumMap;
import java.util.Optional;

import static org.junit.Assert.*;

/** 整体回归：人体内容通过公开健康接口执行，框架只提交附属裁定。 */
public class HumanHealthTest {
    @Test
    public void skinBoneMuscleOrganAndPartHaveIndependentHealth() {
        // 使用实际导出的模板出生，保证骨组声明、状态和内容协议一致。
        BodySnapshot initial = body();
        assertEquals(60, initial.structure().tissues().size());
        assertEquals(23, initial.structure().tissues().stream()
                .filter(tissue -> tissue.kind().value().equals("human:bone")).count());
        assertEquals(initial.structure().tissues().size(), initial.tissueStates().size());
        for (var tissue : initial.structure().tissues()) {
            double hp = initial.tissueStates().stream().filter(state -> state.id().equals(tissue.id()))
                    .findFirst().orElseThrow().hp();
            assertEquals(tissue.maxHp(), hp, 0);
        }

        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store);
        List<HealthTarget> targets = List.of(
                HealthTarget.newBuilder().setTissueId("left_upper_arm_skin").build(),
                HealthTarget.newBuilder().setTissueId("bone_left_humerus").build(),
                HealthTarget.newBuilder().setTissueId("left_biceps_brachii").build(),
                HealthTarget.newBuilder().setOrganId("left_lung").build(),
                HealthTarget.newBuilder().setPartId("left_upper_arm").build());
        for (int index = 0; index < targets.size(); index++) {
            // 每次伤后完整治疗，再比较完整快照；相邻账户、真元和疲劳不得连带变化。
            HealthTarget target = targets.get(index);
            commit(handler, store, "damage-" + index, damage(target, 1));
            BodySnapshot hurt = current(store);
            assertFalse(hurt.vitality().dead());
            assertEquals(initial.totalQi(), hurt.totalQi(), 0);
            commit(handler, store, "heal-" + index, BodyCommand.newBuilder().setHealBody(
                    HealBody.newBuilder().setSourceId("test:medicine")
                            .addDoses(Healing.newBuilder().setTarget(target).setHp(1))));
            assertEquals(BodyProtobuf.toMessage(initial).toBuilder().setRevision(current(store).revision()).build(),
                    BodyProtobuf.toMessage(current(store)));
        }

        // 普通器官失能不等于死亡，也不能暗中损伤胸部或另一侧肺。
        commit(handler, store, "organ-disabled", damage(HealthTarget.newBuilder().setOrganId("left_lung").build(), 1000));
        BodySnapshot disabled = current(store);
        assertEquals(0, disabled.organStates().stream().filter(organ -> organ.id().value().equals("left_lung"))
                .findFirst().orElseThrow().hp(), 0);
        assertFalse(disabled.vitality().dead());
        assertEquals(initial.partStates(), disabled.partStates());
    }

    @Test
    public void humanDeathUsesHeadAndStrictTwentyPercentBoundary() {
        // 恰好 20% 必须存活；一次真实伤害低于该边界才交由框架清账。
        var boundary = BodyProtobuf.toMessage(body()).toBuilder();
        for (int index = 0; index < boundary.getPartStatesCount(); index++) {
            var part = boundary.getPartStates(index);
            double maximum = boundary.getStructure().getPartsList().stream()
                    .filter(definition -> definition.getId().equals(part.getId())).findFirst().orElseThrow().getMaxHp();
            boundary.setPartStates(index, part.toBuilder().setHp(maximum * 0.2));
        }
        BodySnapshot initial = BodyProtobuf.fromMessage(boundary.build());
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store);
        commit(handler, store, "boundary", damage(HealthTarget.newBuilder().setTissueId("chest_skin").build(), 1));
        assertFalse(current(store).vitality().dead());
        commit(handler, store, "below-boundary", damage(HealthTarget.newBuilder().setPartId("chest").build(), 1));
        assertTrue(current(store).vitality().dead());
        assertEquals(0, current(store).totalQi(), 0);
        assertEquals(initial.totalQi(), store.environmentQi().totalQi(), 1e-9);

        // 头部归零独立致死，不要求全身其余部位也先跌到阈值。
        var headStore = new InMemoryBodyStore(List.of(body()), List.of());
        commit(handler(headStore), headStore, "head-destroyed",
                damage(HealthTarget.newBuilder().setPartId("head").build(), 20));
        assertTrue(current(headStore).vitality().dead());
    }

    @Test
    public void damageKindsAffectDifferentLocalLayersWithoutDuplicatingOverallHp() {
        // 每类伤害均走真实模板、Human 策略与框架事务，比较可见结果而不是复刻分配公式。
        BodySnapshot initial = body();
        var bodies = new EnumMap<DamageKind, BodySnapshot>(DamageKind.class);
        List<DamageKind> kinds = List.of(DamageKind.BLUNT, DamageKind.CUTTING, DamageKind.PIERCING,
                DamageKind.HEAT, DamageKind.COLD, DamageKind.ELECTRIC, DamageKind.CORROSIVE, DamageKind.TOXIC);
        HealthTarget chest = HealthTarget.newBuilder().setPartId("chest").build();
        for (DamageKind kind : kinds) {
            var store = new InMemoryBodyStore(List.of(initial), List.of());
            var command = damage(chest, 4);
            command.setDamageBody(command.getDamageBody().toBuilder().setKind(kind));
            commit(handler(store), store, "typed", command);
            BodySnapshot hurt = current(store);
            bodies.put(kind, hurt);
            assertEquals(4, initial.partStates().stream().mapToDouble(part -> part.hp()).sum()
                    - hurt.partStates().stream().mapToDouble(part -> part.hp()).sum(), 1e-9);
            assertFalse(hurt.vitality().dead());
            assertEquals(initial.totalQi(), hurt.totalQi(), 0);
            assertEquals(tissueHp(initial, "left_biceps_brachii"), tissueHp(hurt, "left_biceps_brachii"), 0);
        }

        // 钝击偏骨与肌肉，切割偏皮肤；穿刺、电击和毒性比浅表热伤更容易损害器官。
        assertTrue(tissueHp(bodies.get(DamageKind.CUTTING), "chest_skin")
                < tissueHp(bodies.get(DamageKind.BLUNT), "chest_skin"));
        assertTrue(tissueHp(bodies.get(DamageKind.BLUNT), "bone_sternum")
                < tissueHp(bodies.get(DamageKind.CUTTING), "bone_sternum"));
        assertEquals(tissueHp(initial, "bone_sternum"), tissueHp(bodies.get(DamageKind.CUTTING), "bone_sternum"), 0);
        assertTrue(organHp(bodies.get(DamageKind.PIERCING), "left_lung")
                < organHp(bodies.get(DamageKind.CUTTING), "left_lung"));
        assertTrue(organHp(bodies.get(DamageKind.ELECTRIC), "heart")
                < organHp(bodies.get(DamageKind.HEAT), "heart"));
        assertTrue(tissueHp(bodies.get(DamageKind.COLD), "left_pectoralis_major")
                < tissueHp(bodies.get(DamageKind.HEAT), "left_pectoralis_major"));
        assertTrue(tissueHp(bodies.get(DamageKind.CORROSIVE), "chest_skin")
                < tissueHp(bodies.get(DamageKind.HEAT), "chest_skin"));
        assertEquals(tissueHp(initial, "chest_skin"), tissueHp(bodies.get(DamageKind.TOXIC), "chest_skin"), 0);
        assertTrue(organHp(bodies.get(DamageKind.TOXIC), "left_lung") < organHp(initial, "left_lung"));

        // 四肢缺少内脏时只重分配给当地组织，不能把穿刺分额暗中转给肺或肝。
        var limbStore = new InMemoryBodyStore(List.of(initial), List.of());
        var limb = damage(HealthTarget.newBuilder().setPartId("left_upper_arm").build(), 1);
        limb.setDamageBody(limb.getDamageBody().toBuilder().setKind(DamageKind.PIERCING));
        commit(handler(limbStore), limbStore, "limb", limb);
        assertEquals(initial.organStates(), current(limbStore).organStates());

        // 未指定部位也走同一人体策略；随机选点和局部反应一起固定在回执中。
        var randomStore = new InMemoryBodyStore(List.of(initial), List.of());
        var random = damage(chest, 1);
        random.setDamageBody(random.getDamageBody().toBuilder().clearHit().setKind(DamageKind.BLUNT));
        commit(handler(randomStore), randomStore, "random", random);
        assertTrue(randomStore.receipts().get(0).getHealth().getDamageResolution().getLosses(0).getTarget().hasPartId());
        assertEquals(1, initial.partStates().stream().mapToDouble(part -> part.hp()).sum()
                - current(randomStore).partStates().stream().mapToDouble(part -> part.hp()).sum(), 1e-9);
    }

    private static double tissueHp(BodySnapshot body, String id) {
        // 断言读取实际账户，不从所属部位或展示模型反推局部 HP。
        return body.tissueStates().stream().filter(tissue -> tissue.id().value().equals(id))
                .findFirst().orElseThrow().hp();
    }

    private static double organHp(BodySnapshot body, String id) {
        // 器官保持独立账户，损伤后的加工失能由框架既有规则消费。
        return body.organStates().stream().filter(organ -> organ.id().value().equals(id))
                .findFirst().orElseThrow().hp();
    }

    private static BodySnapshot body() {
        // 内容与出生候选均经过正式 Protobuf 往返，不手建另一套人体构型。
        var template = AddonProtobuf.decodeBundle(HumanContent.payload()).getTemplates(0);
        var time = BodyMessages.TimeCursor.newBuilder().setDomain("test:world").setEpoch("health")
                .setElapsedMillis(0).build();
        return BodyProtobuf.decodeSnapshot(HumanBirth.candidate(template, "health-human", time));
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store) {
        // 宿主显式装配人体裁决；健康命令仍使用框架的权限、时间和事务校验。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true,
                (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(body.settledAt()))
                        .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(0).build(), store,
                (caller, receipt, before, after) -> { /* 测试直接验证提交快照。 */ },
                new OrganRecipeCatalog(List.of()), (caller, body, time) -> Optional.empty(),
                new ActionCatalog(List.of()), new MeridianOpeningCatalog(List.of()), caller -> false, new HumanDamage());
    }

    private static BodyCommand.Builder damage(HealthTarget target, double hp) {
        // 使用自定义分类验证精确目标的独立 HP；分层反应由另一条整体流程覆盖。
        return BodyCommand.newBuilder().setDamageBody(DamageBody.newBuilder().setSourceId("test:attack")
                .setDamageType("test:direct").setKind(DamageKind.CUSTOM).setHit(target).setDamageHp(hp));
    }

    private static BodySnapshot current(InMemoryBodyStore store) {
        // 每次读取唯一权威账本，避免测试误用之前的快照。
        return store.find(new BodyId("health-human")).orElseThrow();
    }

    private static void commit(BodyCommandHandler<String> handler, InMemoryBodyStore store,
                               String id, BodyCommand.Builder command) {
        // 正式可信入口回传成功才继续断言，不把无异常视为已执行。
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(id)
                .setBodyCommand(command.setBodyId("health-human").setExpectedRevision(current(store).revision())).build();
        var response = MeridianProtocol.decodeResponse(handler.handleHealthEffect("test-host", MeridianProtocol.encodeRequest(request)));
        assertTrue(response.toString(), response.hasCommandCommitted());
    }
}
