package dev.kizuna.meridian.human;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.human.server.HumanDamageRules;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageContext;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 使用实际人体构型验证来源系数、容量饱和、开关及死亡边界。 */
public final class HumanDamageSpilloverTest {
    private static final String ARM = "left_upper_arm";
    private static final String PLAYER = "minecraft:player";
    private static final double ARM_HP = 15;
    private static final double ARM_MULTIPLIER = 0.7;
    private static final double ABDOMEN_MULTIPLIER = 1.5;
    private static final double DAMAGE = 10;
    private static final double TOLERANCE = 1e-10;
    private static final double CHEST_MAX = 40;
    private static final double ABDOMEN_MAX = 30;
    private static final double SMALL_HP = 2;
    private static final double NEARLY_DESTROYED = 0.1;
    private static final double SECOND_SATURATION_HP = 3;
    private static final double HEAD_MAX = 20;

    @Test
    public void exactDepletionDoesNotSpillAndOverflowUsesMaximumWeights() {
        // 恰好耗尽不生成蔓延；有余量时按实际构型的上限比例接收。
        BodySnapshot body = body();
        DamageResolution exact = resolve(body, ARM, ARM_HP);
        assertFalse(exact.hasSpillover());
        DamageResolution overflow = resolve(body, ARM, ARM_HP + DAMAGE);
        assertEquals(ARM_HP, partLoss(overflow, ARM), 0);
        assertEquals(DAMAGE, overflow.getSpillover().getOverflowHp(), 0);
        assertEquals(ARM_MULTIPLIER, overflow.getSpillover().getMultiplier(), 0);
        assertEquals(DAMAGE * ARM_MULTIPLIER, totalSpillover(overflow), TOLERANCE);
        double capacity = body.getStructure().getPartsList().stream()
                .filter(part -> !part.getId().equals(ARM)).mapToDouble(part -> part.getMaxHp()).sum();
        assertEquals(DAMAGE * ARM_MULTIPLIER * CHEST_MAX / capacity,
                partLoss(overflow, "chest"), TOLERANCE);
        assertTrue(partLoss(overflow, "head") > 0);
    }

    @Test
    public void destroyedPartSpreadsTheWholeDose() {
        // 已毁肢体不再承受自身部位剂量，但整次伤害仍进入其他活部位。
        DamageResolution result = resolve(withHp(body(), Map.of(ARM, 0.0)), ARM, DAMAGE);
        assertEquals(0, partLoss(result, ARM), 0);
        assertEquals(DAMAGE, result.getSpillover().getOverflowHp(), 0);
        assertEquals(DAMAGE * ARM_MULTIPLIER, totalSpillover(result), TOLERANCE);
    }

    @Test
    public void saturatedRecipientsRedistributeWithoutApplyingTheirCoefficients() {
        // 胸部先饱和，剩余全部给腹部；腹部的 1.5 来源系数不参与接收计算。
        BodySnapshot sparse = onlyParts(Map.of("chest", 1.0, "abdomen", ABDOMEN_MAX));
        DamageResolution result = resolve(sparse, ARM, DAMAGE);
        assertEquals(1, partLoss(result, "chest"), 0);
        assertEquals(DAMAGE * ARM_MULTIPLIER - 1, partLoss(result, "abdomen"), TOLERANCE);
        assertEquals(0, result.getSpillover().getUnabsorbedHp(), 0);
        assertEquals(DAMAGE * ARM_MULTIPLIER, totalSpillover(result), TOLERANCE);
    }

    @Test
    public void redistributionCanSaturateAnotherRecipientOnTheNextRound() {
        // 首轮只耗尽胸部，第二轮才耗尽腹部，最后的余量完整进入头部。
        BodySnapshot sparse = onlyParts(Map.of("chest", NEARLY_DESTROYED,
                "abdomen", SECOND_SATURATION_HP, "head", HEAD_MAX));
        DamageResolution result = resolve(sparse, ARM, DAMAGE);
        assertEquals(NEARLY_DESTROYED, partLoss(result, "chest"), 0);
        assertEquals(SECOND_SATURATION_HP, partLoss(result, "abdomen"), 0);
        double remaining = DAMAGE * ARM_MULTIPLIER - NEARLY_DESTROYED - SECOND_SATURATION_HP;
        assertEquals(remaining, partLoss(result, "head"), TOLERANCE);
        assertEquals(DAMAGE * ARM_MULTIPLIER, totalSpillover(result), TOLERANCE);
    }

    @Test
    public void exactTissueDamageDoesNotSpillIntoParts() {
        // 即使组织剂量超过其上限，精确组织命中仍只修改该独立账户。
        BodySnapshot body = body();
        var tissue = body.getStructure().getTissues(0);
        HealthTarget hit = HealthTarget.newBuilder().setTissueId(tissue.getId()).build();
        double dose = tissue.getMaxHp() + DAMAGE;
        DamageResolution result = resolve(new HumanDamage(), body, hit, dose);
        assertFalse(result.hasSpillover());
        assertEquals(1, result.getLossesCount());
        assertEquals(hit, result.getLosses(0).getTarget());
        assertEquals(dose, result.getLosses(0).getHp(), 0);
    }

    @Test
    public void lastRecipientAndExhaustedBodyAccountForUnabsorbedDamage() {
        // 仅剩一个部位时最多扣完该账户；全毁时保留全部未承受量供回执解释。
        DamageResolution last = resolve(onlyParts(Map.of("abdomen", SMALL_HP)), ARM, DAMAGE);
        assertEquals(SMALL_HP, partLoss(last, "abdomen"), 0);
        assertEquals(DAMAGE * ARM_MULTIPLIER - SMALL_HP, last.getSpillover().getUnabsorbedHp(), TOLERANCE);
        DamageResolution exhausted = resolve(onlyParts(Map.of()), ARM, DAMAGE);
        assertEquals(0, totalSpillover(exhausted), 0);
        assertEquals(DAMAGE * ARM_MULTIPLIER, exhausted.getSpillover().getUnabsorbedHp(), TOLERANCE);
    }

    @Test
    public void allHumanPartsHaveDataCoefficientsAndConserveSpillover() {
        // 逐个实际部位验证数据初值，不从算法实现中复制权重计算。
        HumanDamageRules rules = HumanDamageRules.defaults();
        BodySnapshot body = body();
        for (var part : body.getStructure().getPartsList()) {
            String id = part.getId();
            double expected = 1;
            if (id.endsWith("arm") || id.endsWith("hand")) {
                expected = ARM_MULTIPLIER;
            } else if (id.equals("abdomen")) {
                expected = ABDOMEN_MULTIPLIER;
            }
            assertEquals(id, expected, rules.multiplier(HumanContent.TEMPLATE_ID, id), 0);
            DamageResolution result = resolve(body, id, part.getMaxHp() + DAMAGE);
            assertEquals(id, DAMAGE * expected, totalSpillover(result), TOLERANCE);
        }
    }

    @Test
    public void playerSwitchDoesNotDisableVindicatorOrDifferentTemplate() throws Exception {
        // 实体开关独立于模板，精确组织伤害与没有绑定规则的模板不产生部位蔓延。
        String config = configuration().replace("\"minecraft:player\" = true", "\"minecraft:player\" = false");
        HumanDamageRules rules = HumanDamageRules.parse(config);
        assertFalse(rules.enabled(PLAYER, HumanContent.TEMPLATE_ID));
        assertTrue(rules.enabled("minecraft:vindicator", HumanContent.TEMPLATE_ID));
        assertFalse(rules.enabled(PLAYER, "other:body"));
        BodySnapshot body = withHp(body(), Map.of(ARM, 0.0));
        var handler = new HumanDamage(rules, PLAYER);
        DamageResolution result = resolve(handler, body, ARM, DAMAGE);
        assertFalse(result.hasSpillover());
        assertEquals(DAMAGE, partLoss(result, ARM), 0);
        assertTrue(resolve(new HumanDamage(rules, "minecraft:vindicator"), body, ARM, DAMAGE).hasSpillover());
    }

    @Test
    public void spilloverUsesExistingHeadDeathRule() {
        // 只有蔓延耗尽头部也必须判死；胸部归零本身仍不新增死亡条件。
        BodySnapshot vulnerable = withHp(body(), Map.of(ARM, 0.0, "head", 1.0));
        DamageResolution head = resolve(vulnerable, ARM, CHEST_MAX);
        assertEquals(1, partLoss(head, "head"), 0);
        assertTrue(head.getLethal());
        DamageResolution chest = resolve(body(), "chest", CHEST_MAX);
        assertFalse(chest.getLethal());
    }

    @Test
    public void invalidCoefficientsAndTamperedReceiptsAreRejected() throws Exception {
        // 负数、NaN 和不守恒回执必须在进入身体账本前拒绝。
        String config = configuration();
        assertThrows(IllegalArgumentException.class,
                () -> HumanDamageRules.parse(config.replace("left_upper_arm = 0.7", "left_upper_arm = -0.7")));
        assertThrows(IllegalArgumentException.class,
                () -> HumanDamageRules.parse(config.replace("left_upper_arm = 0.7", "left_upper_arm = nan")));
        DamageResolution valid = resolve(body(), ARM, ARM_HP + DAMAGE);
        var corrupt = valid.getSpillover().toBuilder().setUnabsorbedHp(1).build();
        assertThrows(IllegalArgumentException.class,
                () -> HealthProtobuf.encodeResolution(valid.toBuilder().setSpillover(corrupt).build()));
    }

    private static BodySnapshot body() {
        // 出生使用正式人体内容，结构和健康状态都通过框架协议校验。
        var template = HumanContent.bundle().getTemplates(0);
        TimeCursor time = TimeCursor.newBuilder()
                .setDomain("test:world")
                .setEpoch("spillover")
                .setElapsedMillis(0)
                .build();
        return BodyProtobuf.toMessage(BodyProtobuf.decodeSnapshot(HumanBirth.candidate(template, "spillover", time)));
    }

    private static BodySnapshot withHp(BodySnapshot body, Map<String, Double> hp) {
        // 只准备合法测试快照，不在运行时直接修改权威身体。
        var builder = body.toBuilder();
        for (int index = 0; index < body.getPartStatesCount(); index++) {
            var state = body.getPartStates(index);
            if (hp.containsKey(state.getId())) {
                var updated = state.toBuilder().setHp(hp.get(state.getId())).build();
                builder.setPartStates(index, updated);
            }
        }
        return builder.build();
    }

    private static BodySnapshot onlyParts(Map<String, Double> hp) {
        // 制造多级饱和边界，保留实际人体构型的上限权重。
        var builder = body().toBuilder();
        for (int index = 0; index < builder.getPartStatesCount(); index++) {
            var state = builder.getPartStates(index);
            var updated = state.toBuilder().setHp(hp.getOrDefault(state.getId(), 0.0)).build();
            builder.setPartStates(index, updated);
        }
        return builder.build();
    }

    private static DamageResolution resolve(BodySnapshot body, String part, double hp) {
        // 默认入口与正式玩家宿主使用同一配置和伤害处理器。
        return resolve(new HumanDamage(), body, part, hp);
    }

    private static DamageResolution resolve(HumanDamage handler, BodySnapshot body, String part, double hp) {
        // 部位命中与精确组织命中共用同一可信上下文入口。
        HealthTarget hit = HealthTarget.newBuilder().setPartId(part).build();
        return resolve(handler, body, hit, hp);
    }

    private static DamageResolution resolve(HumanDamage handler, BodySnapshot body, HealthTarget hit, double hp) {
        // 只提交一份命中上下文，返回值是身体事务将执行的完整明确剂量。
        DamageBody damage = DamageBody.newBuilder()
                .setSourceId("test:hit")
                .setDamageType("test:direct")
                .setKind(DamageKind.CUSTOM)
                .setHit(hit)
                .setDamageHp(hp)
                .build();
        DamageContext context = DamageContext.newBuilder().setBody(body).setDamage(damage).build();
        return HealthProtobuf.decodeResolution(handler.resolve(HealthProtobuf.encodeContext(context)));
    }

    private static double partLoss(DamageResolution result, String id) {
        // 只统计部位账户，不把内部组织剂量重复算进总伤害。
        return result.getLossesList().stream().filter(loss -> loss.getTarget().getPartId().equals(id))
                .mapToDouble(loss -> loss.getHp()).sum();
    }

    private static double totalSpillover(DamageResolution result) {
        // 回执中的接收剂量独立于命中部位直接损失。
        return result.getSpillover().getLossesList().stream().mapToDouble(loss -> loss.getHp()).sum();
    }

    private static String configuration() throws Exception {
        // 修改测试私有配置文本，不改安装文件或全局规则。
        try (var input = HumanDamageRules.class.getResourceAsStream("/" + HumanDamageRules.FILE_NAME)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
