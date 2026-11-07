package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;

/** 玩家与类人生物共用的远程持物事实及原版弹药适配。 */
public final class HumanProjectileItems {
    private static final TagKey<Item> BOWS = itemTag("bows");
    private static final TagKey<Item> CROSSBOWS = itemTag("crossbows");

    public static HeldItems inspect(LivingEntity actor) {
        // 双手类别和能否射击来自同一次实体采样，不使用弩里原有的上弦弹药。
        ItemStack main = actor.getMainHandItem();
        ItemStack off = actor.getOffhandItem();
        return TechniqueWorldItems.inspect(actor).toBuilder()
                .setMainHandProjectileWeapon(category(main))
                .setOffHandProjectileWeapon(category(off))
                .setCanFindAmmunition(canShoot(actor, InteractionHand.MAIN_HAND)
                        || canShoot(actor, InteractionHand.OFF_HAND))
                .build();
    }

    public static ProjectileWeaponCategory category(ItemStack stack) {
        // 原版子类与内容标签均可声明武器类别，空手不属于任一类别。
        if (stack.isEmpty()) {
            return ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED;
        }
        if (stack.getItem() instanceof BowItem || stack.is(BOWS)) {
            return ProjectileWeaponCategory.BOW;
        }
        if (stack.getItem() instanceof CrossbowItem || stack.is(CROSSBOWS)) {
            return ProjectileWeaponCategory.CROSSBOW;
        }
        return ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED;
    }

    public static boolean canShoot(LivingEntity actor, InteractionHand hand) {
        // 查询不得消耗弹药；创造、无限普通箭与生物使用同一套可发射判定。
        ItemStack weapon = actor.getItemInHand(hand);
        if (category(weapon) == ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED) {
            return false;
        }
        return !find(actor, hand).isEmpty();
    }

    static ItemStack find(LivingEntity actor, InteractionHand hand) {
        // 烟花只从弩的另一只手取；箭依次取副手、主手和背包。
        ItemStack weapon = actor.getItemInHand(hand);
        ItemStack other = actor.getItemInHand(opposite(hand));
        if (category(weapon) == ProjectileWeaponCategory.CROSSBOW && other.is(Items.FIREWORK_ROCKET)) {
            return other;
        }
        for (InteractionHand candidate : new InteractionHand[]{InteractionHand.OFF_HAND, InteractionHand.MAIN_HAND}) {
            ItemStack held = actor.getItemInHand(candidate);
            if (held.is(ItemTags.ARROWS)) {
                return held;
            }
        }
        if (actor instanceof Player player) {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack candidate = player.getInventory().getItem(slot);
                if (candidate.is(ItemTags.ARROWS)) {
                    return candidate;
                }
            }
        }
        ItemStack ordinaryArrow = new ItemStack(Items.ARROW);
        if (!(actor instanceof Player) || actor.hasInfiniteMaterials() || ammoUse(actor, weapon, ordinaryArrow) == 0) {
            return ordinaryArrow;
        }
        return ItemStack.EMPTY;
    }

    static List<ItemStack> draw(LivingEntity actor, ItemStack weapon, ItemStack ammunition) {
        // 多重射击只消耗第一份，额外投射物与无限箭标成不可回收的原版弹药副本。
        if (ammunition.isEmpty()) {
            return List.of();
        }
        int consumption = ammoUse(actor, weapon, ammunition);
        if (consumption > ammunition.getCount()) {
            return List.of();
        }
        int count = 1;
        if (category(weapon) == ProjectileWeaponCategory.CROSSBOW) {
            count = EnchantmentHelper.processProjectileCount((ServerLevel) actor.level(), weapon, actor, 1);
        }
        ItemStack sample = ammunition.copyWithCount(1);
        if (consumption > 0) {
            ammunition.shrink(consumption);
            if (ammunition.isEmpty() && actor instanceof Player player) {
                player.getInventory().removeItem(ammunition);
            }
        }
        List<ItemStack> projectiles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ItemStack projectile = sample.copy();
            if (consumption == 0 || index > 0) {
                projectile.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);
            }
            projectiles.add(projectile);
        }
        return List.copyOf(projectiles);
    }

    static InteractionHand opposite(InteractionHand hand) {
        // 主副手与物理左右手分开，左主手玩家仍按选定手取另一手弹药。
        if (hand == InteractionHand.MAIN_HAND) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    private static int ammoUse(LivingEntity actor, ItemStack weapon, ItemStack ammunition) {
        // 原版附魔谓词决定无限是否适用于这支箭，药箭与光灵箭不会被一并免耗。
        if (!(actor instanceof Player) || actor.hasInfiniteMaterials()) {
            return 0;
        }
        return EnchantmentHelper.processAmmoUse((ServerLevel) actor.level(), weapon, ammunition, 1);
    }

    private static TagKey<Item> itemTag(String path) {
        // 只读内容包的公开物品标签，不在人体包注册行矢数据。
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("meridian_wayfarer", path));
    }

    private HumanProjectileItems() {
        // 弹药与武器状态仅保存在原版实体的物品栏中。
    }
}
