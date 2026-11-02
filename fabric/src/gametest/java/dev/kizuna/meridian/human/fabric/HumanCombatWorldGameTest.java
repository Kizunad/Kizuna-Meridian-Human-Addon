package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 正式破门斩数据经 Human 协议执行后，核对真实实体和世界副作用。 */
public final class HumanCombatWorldGameTest {
    private static final double HOOK_MULTIPLIER = 1.2;
    private static final double KNOCK_FIRST_MULTIPLIER = 0.3;
    private static final int KNOCK_LAST_STRIKE = 2;
    private static final double CLEAVE_TWO_HAND_DAMAGE = 27;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int SHIELD_AXE_TICKS = 100;
    private static final int SHIELD_SWORD_TICKS = 40;
    private static final int JOIN_INVULNERABILITY_TICKS = 60;
    private static final String BREAK_BLOCKS_RULE = "gamerule meridian_human:technique_player_break_blocks";
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
    private static final int DIAMOND_BOOT_DAMAGE = 7;
    private static final double DIAMOND_BOOT_ARMOR = 3;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void damageCategoriesDoseAndKnockBluntUseRealDamageChain(GameTestHelper context) {
        // 新玩家使每个剂量样本都从健康未疲劳手臂起步，额外核对实际扣血。
        List<DamageCase> cases = List.of(new DamageCase(Items.IRON_AXE, IRON_AXE_DAMAGE, "cutting"),
                new DamageCase(Items.IRON_SWORD, IRON_SWORD_SCALED_DAMAGE, "cutting"),
                new DamageCase(Items.DIRT, 1, "blunt"), new DamageCase(Items.TORCH, OTHER_ITEM_DAMAGE, "blunt"));
        for (DamageCase sample : cases) {
            assertDose(context, sample, "hook", HOOK_MULTIPLIER, sample.kind());
            assertDose(context, sample, "knock", KNOCK_FIRST_MULTIPLIER, "blunt");
        }
        context.succeed();
    }

    private static void assertDose(GameTestHelper context, DamageCase sample, String technique,
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
            String damageId = target.sources.get(0).typeHolder().unwrapKey().orElseThrow()
                    .identifier().toString();
            context.assertTrue(damageId.equals("meridian_door_breaker:" + kind + "_main_hand"),
                    "伤害类型或手臂错误：" + damageId);
        }
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void changedCategoryTagsChangeDamageWithoutItemSpecialCases(GameTestHelper context) {
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
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void durabilityMultipliersBreakageUnbreakingAndCreativeMatchVanilla(GameTestHelper context) {
        // 空挥也消耗末击耐久，分别覆盖四档材料、原版附魔与损坏。
        Item[] items = {Items.IRON_AXE, Items.DIAMOND_AXE, Items.IRON_SWORD, Items.DIAMOND_SWORD};
        int[] multipliers = {1, STANDARD_WEAR_MULTIPLIER, STANDARD_WEAR_MULTIPLIER, OTHER_WEAR_MULTIPLIER};
        try (var fixture = new HumanCombatFixture(context)) {
            for (int index = 0; index < items.length; index++) {
                fixture.hold(items[index], Items.SHIELD);
                fixture.submit(HumanCombatFixture.strike("cleave", 0, "wear_" + index));
                context.assertTrue(fixture.human.player.getMainHandItem().getDamageValue()
                        == CLEAVE_WEAR * multipliers[index], "耐久倍率错误：" + items[index]);
            }
            fixture.hold(Items.IRON_AXE, Items.SHIELD);
            var breaking = fixture.human.player.getMainHandItem();
            breaking.setDamageValue(breaking.getMaxDamage() - 1);
            fixture.submit(HumanCombatFixture.strike("cleave", 0, "break"));
            context.assertTrue(breaking.isEmpty(), "耐久耗尽没有损坏");
            assertUnbreaking(context, fixture);
            fixture.human.player.setGameMode(GameType.CREATIVE);
            fixture.hold(Items.IRON_AXE, Items.AIR);
            fixture.submit(HumanCombatFixture.strike("cleave", 0, "creative"));
            context.assertTrue(fixture.human.player.getMainHandItem().getDamageValue() == 0, "创造模式扣了耐久");
        }
        context.succeed();
    }

    private static void assertUnbreaking(GameTestHelper context, HumanCombatFixture fixture) {
        // 固定随机种子后采样多次，能识别绕开原版耐久减免的实现。
        fixture.hold(Items.IRON_AXE, Items.SHIELD);
        ItemStack enchanted = fixture.human.player.getMainHandItem();
        var unbreaking = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.UNBREAKING);
        enchanted.enchant(unbreaking, UNBREAKING_LEVEL);
        fixture.human.player.getRandom().setSeed(1);
        for (int index = 0; index < WEAR_SAMPLES; index++) {
            fixture.submit(HumanCombatFixture.strike("cleave", 0, "enchanted_" + index));
        }
        context.assertTrue(enchanted.getDamageValue() > 0 && enchanted.getDamageValue() < WEAR_SAMPLES * CLEAVE_WEAR,
                "耐久附魔没有走原版概率减免");
        enchanted.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        int damage = enchanted.getDamageValue();
        fixture.submit(HumanCombatFixture.strike("cleave", 0, "unbreakable"));
        context.assertTrue(enchanted.getDamageValue() == damage, "无法破坏物品扣了耐久");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void disposableLastStrikeConsumesOnceEvenOnMissAndRetry(GameTestHelper context) {
        // 三刀只在最后一次挥空时扣一个，新请求号重放同一效果不能再扣。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.DIRT, Items.AIR);
            fixture.human.player.getMainHandItem().setCount(2);
            fixture.submit(HumanCombatFixture.strike("chain", 0, "chain"));
            fixture.submit(HumanCombatFixture.strike("chain", 1, "chain"));
            context.assertTrue(fixture.human.player.getMainHandItem().getCount() == 2, "未到末击提前消耗");
            EffectIntent last = HumanCombatFixture.strike("chain", 2, "chain");
            fixture.submit(last);
            fixture.submit(last);
            context.assertTrue(fixture.human.player.getMainHandItem().getCount() == 1, "末击重试重复消耗");
            fixture.submit(HumanCombatFixture.strike("knock", 0, "last_item"));
            fixture.submit(HumanCombatFixture.strike("knock", 1, "last_item"));
            context.assertTrue(fixture.human.player.getMainHandItem().getCount() == 1, "叩门前两击消耗物品");
            fixture.submit(HumanCombatFixture.strike("knock", KNOCK_LAST_STRIKE, "last_item"));
            context.assertTrue(fixture.human.player.getMainHandItem().isEmpty(), "最后一个物品未清空手");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void twoHandDoseAndOffhandDamageChargeCorrectArms(GameTestHelper context) {
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
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void legInspectReadsGroundAndBootFacts(GameTestHelper context) {
        // 腿法起手事实来自同一次正式 inspect，钻石靴的护甲和剩余耐久也必须成对回传。
        try (var fixture = new HumanCombatFixture(context)) {
            ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
            boots.setDamageValue(DIAMOND_BOOT_DAMAGE);
            fixture.human.player.setItemSlot(EquipmentSlot.FEET, boots);
            fixture.human.player.setOnGround(true);
            var facts = fixture.inspect().getInspectFacts();
            context.assertTrue(facts.getGrounded(), "inspect 没有回传着地");
            context.assertTrue(!facts.getInWater() && !facts.getClimbing()
                    && !facts.getRiding() && !facts.getGliding(), "inspect 移动事实错误");
            near(context, facts.getBootArmor(), DIAMOND_BOOT_ARMOR, "靴子护甲");
            context.assertTrue(facts.getBootDurability() == boots.getMaxDamage() - DIAMOND_BOOT_DAMAGE,
                    "靴子剩余耐久错误");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void legStrikeReadsDeclaredDamageAndWearsBootOnlyInSurvival(GameTestHelper context) {
        // 腿击不看双手物品，直接使用目录声明的跨命名空间伤害类型，
        // 末击只磨一次生存靴子。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.SHIELD, Items.DIRT);
            ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
            fixture.human.player.setItemSlot(EquipmentSlot.FEET, boots);
            var target = fixture.target(0, HumanCombatFixture.TARGET_DISTANCE);
            float before = target.getHealth();
            fixture.submit(HumanCombatFixture.legStrike("leg_survival"));
            context.assertTrue(target.doses.size() == 1, "腿击没有恰好命中一次");
            context.assertTrue(target.getHealth() < before, "腿击没有进入真实伤害链");
            String damageId = target.sources.get(0).typeHolder().unwrapKey().orElseThrow()
                    .identifier().toString();
            context.assertTrue(damageId.equals("meridian_human_test:leg_strike"),
                    "腿击没有读取目录伤害类型：" + damageId);
            context.assertTrue(boots.getDamageValue() == 1, "生存末击没有磨靴子");

            fixture.human.player.setGameMode(GameType.CREATIVE);
            fixture.submit(HumanCombatFixture.legStrike("leg_creative"));
            context.assertTrue(boots.getDamageValue() == 1, "创造模式末击磨了靴子");
        }
        context.succeed();
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

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void sweepHitsBothHalvesOnceAndSharesFiveTargetBudget(GameTestHelper context) {
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
        context.succeed();
    }

    @GameTest(environment = "meridian_human_test:shield_axe", maxTicks = DEFAULT_TIMEOUT)
    public void axeBreaksShieldBeforeDamage(GameTestHelper context) {
        // 斧必须先让盾失效，本击直接进入真实伤害链。
        shield(context, Items.IRON_AXE, SHIELD_AXE_TICKS);
    }

    @GameTest(environment = "meridian_human_test:shield_sword", maxTicks = DEFAULT_TIMEOUT)
    public void swordBlocksThenCoolsShield(GameTestHelper context) {
        // 剑保留本击格挡，随后才给盾两秒冷却。
        shield(context, Items.IRON_SWORD, SHIELD_SWORD_TICKS);
    }

    @GameTest(environment = "meridian_human_test:shield_block", maxTicks = DEFAULT_TIMEOUT)
    public void blockItemDoesNotDisableShield(GameTestHelper context) {
        // 方块击中举盾玩家不会产生破盾冷却。
        shield(context, Items.DIRT, 0);
    }

    @GameTest(environment = "meridian_human_test:allied_players", maxTicks = DEFAULT_TIMEOUT)
    public void sameTeamPlayerIsNotStruckUntilTeamsSplit(GameTestHelper context) {
        // 敌我由 Human 玩家谓词决定：同队玩家不受功法伤害，分队后同一式照常命中。
        // 原版队伍默认允许友伤，所以第一击打不中只能来自宿主谓词。
        var attacker = new HumanCombatFixture(context);
        var defender = new HumanTechniqueTestPlayer(context);
        var server = context.getLevel().getServer();
        boolean originalPvp = server.getGameRules().get(GameRules.PVP);
        server.getGameRules().set(GameRules.PVP, true, server);
        var scoreboard = server.getScoreboard();
        var team = scoreboard.addPlayerTeam("allies_" + attacker.human.player.getStringUUID());
        scoreboard.addPlayerToTeam(attacker.human.player.getScoreboardName(), team);
        scoreboard.addPlayerToTeam(defender.player.getScoreboardName(), team);
        attacker.hold(Items.IRON_AXE, Items.AIR);
        Vec3 pos = attacker.human.player.position().add(0, 0, HumanCombatFixture.TARGET_DISTANCE);
        defender.player.snapTo(pos.x, pos.y, pos.z, FACING_ATTACKER_YAW, 0);
        defender.player.setGameMode(GameType.SURVIVAL);
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS + 1, () -> {
            try (attacker; defender) {
                float before = defender.player.getHealth();
                attacker.submit(HumanCombatFixture.strike("hook", 0, "allied"));
                near(context, defender.player.getHealth(), before, "同队玩家被功法打中");
                scoreboard.removePlayerFromTeam(defender.player.getScoreboardName(), team);
                attacker.submit(HumanCombatFixture.strike("hook", 0, "split"));
                context.assertTrue(defender.player.getHealth() < before, "分队后功法没有命中");
                context.succeed();
            } finally {
                scoreboard.removePlayerTeam(team);
                server.getGameRules().set(GameRules.PVP, originalPvp, server);
            }
        });
    }

    private static void shield(GameTestHelper context, Item item, int cooldownTicks) {
        // 等待原版举盾生效，再通过真实 Human 宿主执行一次破盾式。
        var attacker = new HumanCombatFixture(context);
        var defender = new HumanTechniqueTestPlayer(context);
        var server = context.getLevel().getServer();
        // 26.2 的 PVP 开关改为游戏规则，测试结束恢复原设置。
        boolean originalPvp = server.getGameRules().get(GameRules.PVP);
        server.getGameRules().set(GameRules.PVP, true, server);
        attacker.hold(item, Items.SHIELD);
        Vec3 pos = attacker.human.player.position().add(0, 0, HumanCombatFixture.TARGET_DISTANCE);
        defender.player.snapTo(pos.x, pos.y, pos.z, FACING_ATTACKER_YAW, 0);
        defender.player.setGameMode(GameType.SURVIVAL);
        defender.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.player.startUsingItem(InteractionHand.OFF_HAND);
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            defender.player.doTick();
        }
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS + 1, () -> {
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
                context.succeed();
            } finally {
                server.getGameRules().set(GameRules.PVP, originalPvp, server);
            }
        });
    }

    private static void assertShieldCooldown(GameTestHelper context, HumanTechniqueTestPlayer defender, int ticks) {
        // 原版冷却器逐 tick 推进，最后一 tick 之前和之后分别断言边界。
        var cooldown = defender.player.getCooldowns();
        var shield = new ItemStack(Items.SHIELD);
        context.assertTrue(cooldown.isOnCooldown(shield) == (ticks > 0), "盾冷却状态错误");
        for (int tick = 1; tick < ticks; tick++) {
            cooldown.tick();
        }
        if (ticks > 0) {
            context.assertTrue(cooldown.isOnCooldown(shield), "盾冷却提前结束");
            cooldown.tick();
            context.assertTrue(!cooldown.isOnCooldown(shield), "盾冷却时长错误");
        }
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void woodenDoorsDropAndIronAndNonToolsRefuse(GameTestHelper context) {
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
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void offhandAxeBreaksDoorWithoutMovingItemsOrExtraWear(GameTestHelper context) {
        // 主手剑不能阻止副手斧劈门，两个手位和末击之外的耐久保持不变。
        var offHand = TechniqueMessages.Hand.OFF_HAND;
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.IRON_SWORD, Items.IRON_AXE);
            ItemStack main = fixture.human.player.getMainHandItem();
            ItemStack off = fixture.human.player.getOffhandItem();
            fixture.human.player.setGameMode(GameType.CREATIVE);
            assertDoor(context, fixture, Blocks.OAK_DOOR, DoorResult.BROKEN, offHand);
            fixture.human.player.setGameMode(GameType.SURVIVAL);
            assertDoor(context, fixture, Blocks.OAK_DOOR, DoorResult.BROKEN, offHand);
            context.assertTrue(fixture.human.player.getMainHandItem() == main
                    && fixture.human.player.getOffhandItem() == off, "劈门上下文改变了真实背包手位");
            context.assertTrue(main.getDamageValue() == 0 && off.getDamageValue() == 0, "挖掘链重复磨损了工具");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void playerDoorRuleDisablesBreaking(GameTestHelper context) {
        // 服主总开关经过真实 gamerule 命令切换，测试结束恢复原设置；26.2 规则名须为小写命名空间 ID。
        try (var fixture = new HumanCombatFixture(context)) {
            fixture.hold(Items.IRON_AXE, Items.AIR);
            var source = context.getLevel().getServer().createCommandSourceStack();
            int original = HumanTestCommands.run(source, BREAK_BLOCKS_RULE);
            HumanTestCommands.run(source, BREAK_BLOCKS_RULE + " false");
            try {
                assertDoor(context, fixture, Blocks.OAK_DOOR, DoorResult.KEPT);
            } finally {
                String restore = "false";
                if (original != 0) {
                    restore = "true";
                }
                HumanTestCommands.run(source, BREAK_BLOCKS_RULE + " " + restore);
            }
        }
        context.succeed();
    }

    private static void assertDoor(GameTestHelper context, HumanCombatFixture fixture, Block block, DoorResult expected) {
        // 普通门用例默认沿用主手，副手用例显式传入选手。
        assertDoor(context, fixture, block, expected,
                TechniqueMessages.Hand.MAIN_HAND);
    }

    private static void assertDoor(GameTestHelper context, HumanCombatFixture fixture, Block block, DoorResult expected,
                                   TechniqueMessages.Hand selectedHand) {
        // 双格门上下半都实际放置，计数和掉落不能把两半误认为两扇。
        BlockPos pos = doorPosition(fixture);
        var world = context.getLevel();
        world.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(pos, block.defaultBlockState());
        if (block instanceof DoorBlock) {
            world.setBlockAndUpdate(pos.above(), block.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        var area = new AABB(pos).inflate(1);
        world.getEntitiesOfClass(ItemEntity.class, area, entity -> true).forEach(ItemEntity::discard);
        EffectIntent effect = HumanCombatFixture.doors(UUID.randomUUID().toString()).toBuilder()
                .setSelectedHand(selectedHand)
                .build();
        fixture.submit(effect);
        if (expected == DoorResult.BROKEN) {
            context.assertTrue(world.getBlockState(pos).isAir(), "木制门没有劈开：" + block);
            context.assertTrue(!world.getEntitiesOfClass(ItemEntity.class, area,
                    entity -> entity.getItem().is(block.asItem())).isEmpty(), "门没有原版掉落：" + block);
        } else {
            context.assertTrue(world.getBlockState(pos).is(block), "权限或物品限制没有阻止劈门");
        }
        world.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    private static BlockPos doorPosition(HumanCombatFixture fixture) {
        // 位于视线前方一格，落在第二式二格距离和一格宽度内。
        return fixture.human.player.blockPosition().south();
    }

    private static void near(GameTestHelper context, double actual, double expected, String label) {
        // 浮点剂量只允许数值舍入误差。
        context.assertTrue(Math.abs(actual - expected) < HumanCombatFixture.EPSILON,
                label + "：预期 " + expected + "，实际 " + actual);
    }

    private enum DoorResult { BROKEN, KEPT }
    private record DamageCase(Item item, double base, String kind) { }
}
