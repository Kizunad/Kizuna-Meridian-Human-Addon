package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ConsumeHeldItemIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemConsumptionStrategy;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import java.util.UUID;

import static dev.kizuna.meridian.human.fabric.HumanGameTestTicks.DEFAULT_TIMEOUT;

/** 测试 Human 宿主对功法库整叠持物消耗意图的执行和回执去重。 */
public final class HumanHeldItemConsumptionGameTest {
    private static final int STACK_SIZE = 3;
    private static final int REPLACEMENT_STACK_SIZE = 4;
    private static final int ENCHANTMENT_LEVEL = 1;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void consumeHeldItemClearsEntireSelectedStack(GameTestHelper context) {
        try (HumanTechniqueTestPlayer fixture = new HumanTechniqueTestPlayer(context)) {
            ItemStack stack = new ItemStack(Items.GOLDEN_SWORD, STACK_SIZE);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            submit(context, fixture, consumeEffect("consume-stack", "consume-stack-effect"));
            context.assertTrue(fixture.player.getMainHandItem().isEmpty(), "整叠主手物品没有被清空");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void consumeHeldItemClearsEnchantedComponentStack(GameTestHelper context) {
        try (HumanTechniqueTestPlayer fixture = new HumanTechniqueTestPlayer(context)) {
            ItemStack stack = new ItemStack(Items.IRON_SWORD, 2);
            stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
            var enchantment = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.UNBREAKING);
            stack.enchant(enchantment, ENCHANTMENT_LEVEL);
            context.assertTrue(
                    stack.has(DataComponents.UNBREAKABLE) && !stack.getEnchantments().isEmpty(),
                    "测试物品未带无法破坏组件和附魔");
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            submit(context, fixture, consumeEffect("consume-enchanted", "consume-enchanted-effect"));
            context.assertTrue(fixture.player.getMainHandItem().isEmpty(), "带组件附魔的整叠物品没有被清空");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void consumeHeldItemRetryDoesNotClearReplacementStack(GameTestHelper context) {
        try (HumanTechniqueTestPlayer fixture = new HumanTechniqueTestPlayer(context)) {
            ItemStack original = new ItemStack(Items.GOLDEN_SWORD, STACK_SIZE);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, original);
            EffectIntent effect = consumeEffect("consume-retry", "consume-retry-effect");
            submit(context, fixture, effect);
            ItemStack replacement = new ItemStack(Items.IRON_SWORD, REPLACEMENT_STACK_SIZE);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, replacement);
            submit(context, fixture, effect);
            ItemStack remaining = fixture.player.getMainHandItem();
            context.assertTrue(remaining.getItem() == Items.IRON_SWORD
                    && remaining.getCount() == REPLACEMENT_STACK_SIZE,
                    "同一效果 ID 重试清除了替换后的物品");
        }
        context.succeed();
    }

    private static void submit(GameTestHelper context, HumanTechniqueTestPlayer fixture, EffectIntent effect) {
        TechniqueHostRequest request = TechniqueHostRequest.newBuilder()
                .setEntityUuid(fixture.player.getStringUUID())
                .setRequestId(UUID.randomUUID().toString())
                .setEffect(effect)
                .build();
        byte[] payload = TechniqueProtocol.encodeHost(request);
        byte[] responsePayload = new HumanTechniqueHost().exchange(payload);
        TechniqueHostResponse response = TechniqueProtocol.decodeHostResponse(responsePayload);
        context.assertTrue(response.getEffectAccepted(), "Human 宿主拒绝整叠消耗：" + response);
    }

    private static EffectIntent consumeEffect(String castId, String effectId) {
        ConsumeHeldItemIntent consume = ConsumeHeldItemIntent.newBuilder()
                .setStrategy(HeldItemConsumptionStrategy.CONSUME_SELECTED_STACK)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId(effectId)
                .setSelectedHand(Hand.MAIN_HAND)
                .setConsumeHeldItem(consume)
                .build();
    }
}
