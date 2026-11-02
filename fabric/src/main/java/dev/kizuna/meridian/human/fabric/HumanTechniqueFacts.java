package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostFacts;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** 从玩家实体一次性采集功法宿主需要的移动与靴子事实。 */
final class HumanTechniqueFacts {
    private HumanTechniqueFacts() {
        // 事实只从当前服务端实体读取，不保存第二份玩家状态。
    }

    static TechniqueHostFacts inspect(ServerPlayer player) {
        // 同一份回执同时覆盖腿法起手条件与鞋的伤害、耐久事实。
        var facts = TechniqueHostFacts.newBuilder()
                .setGrounded(player.onGround())
                .setInWater(player.isInWater())
                .setClimbing(player.onClimbable())
                .setRiding(player.isPassenger())
                .setGliding(player.isFallFlying());
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (!boots.isEmpty()) {
            facts.setBootArmor(armor(boots));
            facts.setBootDurability(remainingDurability(boots));
        }
        return facts.build();
    }

    static ItemStack boots(ServerPlayer player) {
        // 腿击只读取脚部装备，不能把实体总护甲或腿甲混进鞋规则。
        return player.getItemBySlot(EquipmentSlot.FEET);
    }

    static double armor(ItemStack boots) {
        // 用装备自身的 ARMOR 修饰计算鞋护甲，不读取玩家其他装备槽或实体固有护甲。
        var attribute = new AttributeInstance(Attributes.ARMOR,
                ignored -> { /* 临时计算不发布实体属性。 */ });
        attribute.setBaseValue(0);
        boots.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .forEach(EquipmentSlot.FEET, (holder, modifier) -> addArmor(attribute, holder, modifier));
        return attribute.getValue();
    }

    private static void addArmor(AttributeInstance attribute, Holder<Attribute> holder,
                                 net.minecraft.world.entity.ai.attributes.AttributeModifier modifier) {
        // 只接受脚部装备的 ARMOR 修饰，保持原版属性运算顺序。
        if (holder.equals(Attributes.ARMOR)) {
            attribute.addTransientModifier(modifier);
        }
    }

    private static int remainingDurability(ItemStack boots) {
        // 无耐久靴子仍提供成对事实，末击不会凭空消耗物品数量。
        return Math.max(0, boots.getMaxDamage() - boots.getDamageValue());
    }
}
