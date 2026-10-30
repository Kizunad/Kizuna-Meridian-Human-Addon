package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.UUID;

/** 正式破门斩数据经 Human 协议执行后，核对真实实体和世界副作用。 */
public final class HumanCombatWorldGameTest implements FabricGameTest {
    private static final double HOOK_MULTIPLIER = 1.2;
    private static final double KNOCK_FIRST_MULTIPLIER = 0.3;
    private static final int KNOCK_LAST_STRIKE = 2;
    private static final double CLEAVE_TWO_HAND_DAMAGE = 27;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int SHIELD_AXE_TICKS = 100;
    private static final int SHIELD_SWORD_TICKS = 40;
    private static final int JOIN_INVULNERABILITY_TICKS = 60;
    private static final int SWEEP_TARGET_LIMIT = 5;
    private static final int UNBREAKING_LEVEL = 3;
    private static final int WEAR_SAMPLES = 30;
    private static final int CLEAVE_WEAR = 3;
    private static final double IRON_AXE_DAMAGE = 9;
    private static final double IRON_SWORD_SCALED_DAMAGE = 4.8;
    private static final double OTHER_ITEM_DAMAGE = 0.5;
    private static final double TARGET_SPACING = 0.3;
    private static final int STANDARD_WEAR_MULTIPLIER = 2;
    private static final int OTHER_WEAR_MULTIPLIER = 3;
    private static final float FACING_ATTACKER_YAW = 180;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void damageCategoriesDoseAndKnockBluntUseRealDamageChain(TestContext context) {
        // 新玩家使每个剂量样本都从健康未疲劳手臂起步，额外核对实际扣血。
        List<DamageCase> cases = List.of(new DamageCase(Items.IRON_AXE, IRON_AXE_DAMAGE, "cutting"),
                new DamageCase(Items.IRON_SWORD, IRON_SWORD_SCALED_DAMAGE, "cutting"),
                new DamageCase(Items.DIRT, 1, "blunt"), new DamageCase(Items.TORCH, OTHER_ITEM_DAMAGE, "blunt"));
        for (DamageCase sample : cases) {
            assertDose(context, sample, "hook", HOOK_MULTIPLIER, sample.kind());
            assertDose(context, sample, "knock", KNOCK_FIRST_MULTIPLIER, "blunt");
        }
        context.complete();
    }

    private static void assertDose(TestContext context, DamageCase sample, String technique,
                                   double multiplier, String kind) {
        // 入口剂量与伤害类型共同防止分类正确但身体侧或倍率错误。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(sample.item(), Items.SHIELD);
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            float before = target.getHealth();
            fixture.submit(HumanCombatFixture.strike(technique, 0, "dose"));
            context.assertTrue(target.doses.size() == 1, "真实目标未恰好受击一次");
            double expected = sample.base() * multiplier;
            near(context, target.doses.get(0), expected, "原始剂量");
            context.assertTrue(target.getHealth() < before, "实际伤害链没有扣血");
            String damageId = target.sources.get(0).getTypeRegistryEntry().getKey().orElseThrow()
                    .getValue().toString();
            context.assertTrue(damageId.equals("meridian_door_breaker:" + kind + "_main_hand"),
                    "伤害类型或手臂错误：" + damageId);
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void changedCategoryTagsChangeDamageWithoutItemSpecialCases(TestContext context) {
        // 替换规则引用的真实标签，证明类别由数据驱动而非硬编码铁斧。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.IRON_AXE, Items.SHIELD);
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            EffectIntent original = HumanCombatFixture.strike("hook", 0, "retagged");
            var rules = original.getWeaponStrike().getHeldItemRules().toBuilder();
            for (int index = 0; index < rules.getCategoriesCount() - 1; index++) {
                var category = rules.getCategories(index).toBuilder()
                        .clearItemTags()
                        .addItemTags("minecraft:flowers")
                        .build();
                rules.setCategories(index, category);
            }
            var strike = original.getWeaponStrike().toBuilder().setHeldItemRules(rules.build()).build();
            fixture.submit(original.toBuilder().setWeaponStrike(strike).build());
            near(context, target.doses.get(0), HOOK_MULTIPLIER * OTHER_ITEM_DAMAGE, "标签改类后的剂量");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void durabilityMultipliersBreakageUnbreakingAndCreativeMatchVanilla(TestContext context) {
        // 空挥也消耗末击耐久，分别覆盖四档材料、原版附魔与损坏。
        Item[] items = {Items.IRON_AXE, Items.DIAMOND_AXE, Items.IRON_SWORD, Items.DIAMOND_SWORD};
        int[] multipliers = {1, STANDARD_WEAR_MULTIPLIER, STANDARD_WEAR_MULTIPLIER, OTHER_WEAR_MULTIPLIER};
        try (var fixture = new HumanCombatFixture(context)) {
            for (int index = 0; index < items.length; index++) {
                fixture.hold(items[index], Items.SHIELD);
                fixture.submit(HumanCombatFixture.strike("cleave", 0, "wear_" + index));
                context.assertTrue(fixture.human.player.getMainHandStack().getDamage()
                        == CLEAVE_WEAR * multipliers[index], "耐久倍率错误：" + items[index]);
            }
            fixture.hold(Items.IRON_AXE, Items.SHIELD);
            var breaking = fixture.human.player.getMainHandStack();
            breaking.setDamage(breaking.getMaxDamage() - 1);
            fixture.submit(HumanCombatFixture.strike("cleave", 0, "break"));
            context.assertTrue(breaking.isEmpty(), "耐久耗尽没有损坏");
            assertUnbreaking(context, fixture);
            fixture.human.player.changeGameMode(GameMode.CREATIVE);
            fixture.hold(Items.IRON_AXE, Items.AIR);
            fixture.submit(HumanCombatFixture.strike("cleave", 0, "creative"));
            context.assertTrue(fixture.human.player.getMainHandStack().getDamage() == 0, "创造模式扣了耐久");
        }
        context.complete();
    }

    private static void assertUnbreaking(TestContext context, HumanCombatFixture fixture) {
        // 固定随机种子后采样多次，能识别绕开原版耐久减免的实现。
        fixture.hold(Items.IRON_AXE, Items.SHIELD);
        ItemStack enchanted = fixture.human.player.getMainHandStack();
        enchanted.addEnchantment(Enchantments.UNBREAKING, UNBREAKING_LEVEL);
        fixture.human.player.getRandom().setSeed(1);
        for (int index = 0; index < WEAR_SAMPLES; index++) {
            fixture.submit(HumanCombatFixture.strike("cleave", 0, "enchanted_" + index));
        }
        context.assertTrue(enchanted.getDamage() > 0 && enchanted.getDamage() < WEAR_SAMPLES * CLEAVE_WEAR,
                "耐久附魔没有走原版概率减免");
        enchanted.getOrCreateNbt().putBoolean("Unbreakable", true /* unbreakable */);
        int damage = enchanted.getDamage();
        fixture.submit(HumanCombatFixture.strike("cleave", 0, "unbreakable"));
        context.assertTrue(enchanted.getDamage() == damage, "无法破坏物品扣了耐久");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void disposableLastStrikeConsumesOnceEvenOnMissAndRetry(TestContext context) {
        // 三刀只在最后一次挥空时扣一个，新请求号重放同一效果不能再扣。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.DIRT, Items.AIR);
            fixture.human.player.getMainHandStack().setCount(2);
            fixture.submit(HumanCombatFixture.strike("chain", 0, "chain"));
            fixture.submit(HumanCombatFixture.strike("chain", 1, "chain"));
            context.assertTrue(fixture.human.player.getMainHandStack().getCount() == 2, "未到末击提前消耗");
            EffectIntent last = HumanCombatFixture.strike("chain", 2, "chain");
            fixture.submit(last);
            fixture.submit(last);
            context.assertTrue(fixture.human.player.getMainHandStack().getCount() == 1, "末击重试重复消耗");
            fixture.submit(HumanCombatFixture.strike("knock", 0, "last_item"));
            fixture.submit(HumanCombatFixture.strike("knock", 1, "last_item"));
            context.assertTrue(fixture.human.player.getMainHandStack().getCount() == 1, "叩门前两击消耗物品");
            fixture.submit(HumanCombatFixture.strike("knock", KNOCK_LAST_STRIKE, "last_item"));
            context.assertTrue(fixture.human.player.getMainHandStack().isEmpty(), "最后一个物品未清空手");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void twoHandDoseAndOffhandDamageChargeCorrectArms(TestContext context) {
        // 双手类型让 Damage 分摊两臂；副手类型必须只消耗左臂。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.IRON_AXE, Items.AIR);
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            fixture.submit(HumanCombatFixture.strike("cleave", 0, "both"));
            near(context, target.doses.get(0), CLEAVE_TWO_HAND_DAMAGE, "双手剂量");
            context.assertTrue(armFatigue(fixture, "left") > 0 && armFatigue(fixture, "right") > 0,
                    "双手没有给两臂做功");
        }
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.AIR, Items.IRON_AXE);
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            var effect = HumanCombatFixture.strike("hook", 0, "offhand").toBuilder()
                    .setSelectedHand(TechniqueMessages.Hand.OFF_HAND)
                    .build();
            fixture.submit(effect);
            near(context, target.doses.get(0), IRON_AXE_DAMAGE * HOOK_MULTIPLIER, "副手单手剂量");
            context.assertTrue(armFatigue(fixture, "left") > 0 && armFatigue(fixture, "right") == 0,
                    "副手做功手臂错误");
        }
        context.complete();
    }

    static double armFatigue(HumanCombatFixture fixture, String side) {
        // 只计对应侧手臂肌肉，避免把躯干或腿的疲劳误当作挥手做功。
        var body = BodyProtobuf.toMessage(fixture.human.body().snapshot());
        var ids = body.getStructure().getTissuesList().stream()
                .filter(tissue -> tissue.getTagsList().contains("human:arm_muscle") && tissue.getId().contains(side))
                .map(tissue -> tissue.getId()).toList();
        fixture.context.assertTrue(!ids.isEmpty(), "没有找到对应侧手臂肌肉");
        return body.getTissueStatesList().stream().filter(state -> ids.contains(state.getId()))
                .mapToDouble(state -> state.getFatigue()).sum();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void sweepHitsBothHalvesOnceAndSharesFiveTargetBudget(TestContext context) {
        // 前半圈四个、后半圈四个，重叠目标转身后仍不能再次占用预算。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.DIRT, Items.AIR);
            for (int index = 0; index < 4; index++) {
                fixture.target(index * TARGET_SPACING, HumanCombatFixture.TARGET_DISTANCE);
                fixture.target(-index * TARGET_SPACING, -HumanCombatFixture.TARGET_DISTANCE);
            }
            fixture.submit(HumanCombatFixture.strike("sweep", 0, "sweep"));
            var repeatedGroup = HumanCombatFixture.strike("sweep", 0, "sweep").toBuilder()
                    .setEffectId("overlapping_same_group")
                    .build();
            fixture.submit(repeatedGroup);
            fixture.submit(HumanCombatFixture.strike("sweep", 1, "sweep"));
            long hit = fixture.targets.stream().filter(target -> !target.doses.isEmpty()).count();
            context.assertTrue(hit == SWEEP_TARGET_LIMIT, "横扫没有共用五目标预算：" + hit);
            context.assertTrue(fixture.targets.stream().allMatch(target -> target.doses.size() <= 1),
                    "横扫同目标重复命中");
            context.assertTrue(fixture.targets.stream().anyMatch(target -> target.getZ()
                    < fixture.human.player.getZ() && !target.doses.isEmpty()), "后半圈未命中");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "human-shield-axe")
    public void axeBreaksShieldBeforeDamage(TestContext context) {
        // 斧必须先让盾失效，本击直接进入真实伤害链。
        shield(context, Items.IRON_AXE, SHIELD_AXE_TICKS);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "human-shield-sword")
    public void swordBlocksThenCoolsShield(TestContext context) {
        // 剑保留本击格挡，随后才给盾两秒冷却。
        shield(context, Items.IRON_SWORD, SHIELD_SWORD_TICKS);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "human-shield-block")
    public void blockItemDoesNotDisableShield(TestContext context) {
        // 方块击中举盾玩家不会产生破盾冷却。
        shield(context, Items.DIRT, 0);
    }

    private static void shield(TestContext context, Item item, int cooldownTicks) {
        // 等待原版举盾生效，再通过真实 Human 宿主执行一次破盾式。
        var attacker = new HumanCombatFixture(context);
        var defender = new HumanTechniqueTestPlayer(context);
        var server = context.getWorld().getServer();
        boolean originalPvp = server.isPvpEnabled();
        server.setPvpEnabled(true /* pvpEnabled */);
        attacker.hold(item, Items.SHIELD);
        Vec3d pos = attacker.human.player.getPos().add(0, 0, HumanCombatFixture.TARGET_DISTANCE);
        defender.player.refreshPositionAndAngles(pos.x, pos.y, pos.z, FACING_ATTACKER_YAW, 0);
        defender.player.changeGameMode(GameMode.SURVIVAL);
        defender.player.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.player.setCurrentHand(Hand.OFF_HAND);
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            defender.player.playerTick();
        }
        context.waitAndRun(JOIN_INVULNERABILITY_TICKS + 1, () -> {
            try (attacker; defender) {
                context.assertTrue(defender.player.isBlocking(), "真实盾没有进入格挡");
                float before = defender.player.getHealth();
                attacker.submit(HumanCombatFixture.strike("hook", 0, "shield"));
                if (item == Items.IRON_AXE) {
                    context.assertTrue(defender.player.getHealth() < before, "斧破盾当击没有伤害");
                } else {
                    near(context, defender.player.getHealth(), before, "盾应挡住当击");
                }
                assertShieldCooldown(context, defender, cooldownTicks);
                context.complete();
            } finally {
                server.setPvpEnabled(originalPvp);
            }
        });
    }

    private static void assertShieldCooldown(TestContext context, HumanTechniqueTestPlayer defender, int ticks) {
        // 原版冷却器逐 tick 推进，最后一 tick 之前和之后分别断言边界。
        var cooldown = defender.player.getItemCooldownManager();
        context.assertTrue(cooldown.isCoolingDown(Items.SHIELD) == (ticks > 0), "盾冷却状态错误");
        for (int tick = 1; tick < ticks; tick++) {
            cooldown.update();
        }
        if (ticks > 0) {
            context.assertTrue(cooldown.isCoolingDown(Items.SHIELD), "盾冷却提前结束");
            cooldown.update();
            context.assertTrue(!cooldown.isCoolingDown(Items.SHIELD), "盾冷却时长错误");
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void woodenDoorsDropAndIronAndNonToolsRefuse(TestContext context) {
        // 对真实方块逐项执行劈门，检查木门掉落与类别限制。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.IRON_AXE, Items.AIR);
            for (Block block : List.of(Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE)) {
                assertDoor(context, fixture, block, DoorResult.BROKEN);
            }
            assertDoor(context, fixture, Blocks.IRON_DOOR, DoorResult.KEPT);
            for (Item item : List.of(Items.DIRT, Items.TORCH)) {
                fixture.hold(item, Items.AIR);
                assertDoor(context, fixture, Blocks.OAK_DOOR, DoorResult.KEPT);
            }
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void offhandAxeBreaksDoorWithoutMovingItemsOrExtraWear(TestContext context) {
        // 主手剑不能阻止副手斧劈门，两个手位和末击之外的耐久保持不变。
        var offHand = TechniqueMessages.Hand.OFF_HAND;
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.IRON_SWORD, Items.IRON_AXE);
            ItemStack main = fixture.human.player.getMainHandStack();
            ItemStack off = fixture.human.player.getOffHandStack();
            fixture.human.player.changeGameMode(GameMode.CREATIVE);
            assertDoor(context, fixture, Blocks.OAK_DOOR, DoorResult.BROKEN, offHand);
            fixture.human.player.changeGameMode(GameMode.SURVIVAL);
            assertDoor(context, fixture, Blocks.OAK_DOOR, DoorResult.BROKEN, offHand);
            context.assertTrue(fixture.human.player.getMainHandStack() == main
                    && fixture.human.player.getOffHandStack() == off, "劈门上下文改变了真实背包手位");
            context.assertTrue(main.getDamage() == 0 && off.getDamage() == 0, "挖掘链重复磨损了工具");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void playerDoorRuleDisablesBreaking(TestContext context) {
        // 服主总开关经过真实 gamerule 命令切换，测试结束恢复原设置。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.IRON_AXE, Items.AIR);
            var server = context.getWorld().getServer();
            var commands = server.getCommandManager();
            var source = server.getCommandSource();
            int original = commands.executeWithPrefix(source, "gamerule meridianTechniquePlayerBreakBlocks");
            commands.executeWithPrefix(source, "gamerule meridianTechniquePlayerBreakBlocks false");
            try {
                assertDoor(context, fixture, Blocks.OAK_DOOR, DoorResult.KEPT);
            } finally {
                String restore = "false";
                if (original != 0) {
                    restore = "true";
                }
                commands.executeWithPrefix(source, "gamerule meridianTechniquePlayerBreakBlocks " + restore);
            }
        }
        context.complete();
    }

    private static void assertDoor(TestContext context, HumanCombatFixture fixture, Block block, DoorResult expected) {
        // 普通门用例默认沿用主手，副手用例显式传入选手。
        assertDoor(context, fixture, block, expected,
                TechniqueMessages.Hand.MAIN_HAND);
    }

    private static void assertDoor(TestContext context, HumanCombatFixture fixture, Block block, DoorResult expected,
                                   TechniqueMessages.Hand selectedHand) {
        // 双格门上下半都实际放置，计数和掉落不能把两半误认为两扇。
        BlockPos pos = doorPosition(fixture);
        var world = context.getWorld();
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(pos, block.getDefaultState());
        if (block instanceof DoorBlock) {
            world.setBlockState(pos.up(), block.getDefaultState().with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        var area = new Box(pos).expand(1);
        world.getEntitiesByClass(ItemEntity.class, area, entity -> true).forEach(ItemEntity::discard);
        EffectIntent effect = HumanCombatFixture.doors(UUID.randomUUID().toString()).toBuilder()
                .setSelectedHand(selectedHand)
                .build();
        fixture.submit(effect);
        if (expected == DoorResult.BROKEN) {
            context.assertTrue(world.getBlockState(pos).isAir(), "木制门没有劈开：" + block);
            context.assertTrue(!world.getEntitiesByClass(ItemEntity.class, area,
                    entity -> entity.getStack().isOf(block.asItem())).isEmpty(), "门没有原版掉落：" + block);
        } else {
            context.assertTrue(world.getBlockState(pos).isOf(block), "权限或物品限制没有阻止劈门");
        }
        world.setBlockState(pos.up(), Blocks.AIR.getDefaultState());
        world.setBlockState(pos, Blocks.AIR.getDefaultState());
    }

    private static BlockPos doorPosition(HumanCombatFixture fixture) {
        // 位于视线前方一格，落在第二式二格距离和一格宽度内。
        return fixture.human.player.getBlockPos().south();
    }

    private static void near(TestContext context, double actual, double expected, String label) {
        // 浮点剂量只允许数值舍入误差。
        context.assertTrue(Math.abs(actual - expected) < HumanCombatFixture.EPSILON,
                label + "：预期 " + expected + "，实际 " + actual);
    }

    private enum DoorResult { BROKEN, KEPT }
    private record DamageCase(Item item, double base, String kind) { }
}
