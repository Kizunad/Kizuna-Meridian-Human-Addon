package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.human.server.HumanDamageRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Numb;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeAttachment;
import dev.kizuna.meridian.technique.server.TechniqueLimbStrikes;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 肢击的直接路：这一下指定了部位、带透劲或带命中附带，而且目标绑了身体。
 * 走伤害附属的可信指定部位入口，不吃无敌帧、不击退；盾由调用方先查；打中以后补原版的红闪、受伤动作和受伤声。
 * 目标没绑身体时走原版受伤入口，在那上面补两样退路：透劲只剩护甲打折，麻退成原版的缓慢和虚弱。
 */
final class HumanDirectStrikes {
    private static final String MUSCLE_KIND = "human:muscle";
    private static final String ARM_MUSCLE = "human:arm_muscle";
    private static final String FOREARM = "_forearm";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";
    private static final String NUMB_ID_PREFIX = "meridian_human:numb_";
    /** 没身体的目标挨透劲时，临时挂在护甲和护甲韧性上的打折修正；这一下打完就摘。 */
    static final Identifier ARMOR_DISCOUNT = Identifier.fromNamespaceAndPath("meridian_human", "strike_armor_discount");

    private HumanDirectStrikes() {
        // 只有静态换算，不保留施放状态。
    }

    static boolean applies(LimbStrike strike, LivingEntity target) {
        // 目录声明了直接路要用的任意一样，而且目标有身体。
        return TechniqueLimbStrikes.direct(strike) && VanillaDamageFabric.inspectBody(target).isPresent();
    }

    static boolean hit(LivingEntity target, DamageSource source, LimbStrike strike, double vanillaDose) {
        // 定部位、组附带、按身体 HP 提交；选不出部位或那个部位已经归零时这一下落空。
        BodyMessages.BodySnapshot body = VanillaDamageFabric.inspectBody(target).orElseThrow();
        Optional<String> part = part(target, source, strike, body);
        if (part.isEmpty()) {
            return false;
        }
        OptionalDouble organShare = OptionalDouble.empty();
        double armorMultiplier = 1;
        if (strike.hasPenetration()) {
            organShare = OptionalDouble.of(strike.getPenetration().getOrganShare().getValue());
            armorMultiplier = strike.getPenetration().getArmorMultiplier().getValue();
        }
        var extras = new VanillaDamageFabric.DirectExtras(organShare, armorMultiplier,
                modifiers(body, part.get(), strike.getAttachmentsList()));
        double bodyHp = VanillaDamageFabric.bodyHp(vanillaDose);
        boolean damaged = VanillaDamageFabric.damageDirect(
                new VanillaDamageFabric.DirectDamage(target, source, part.get(), bodyHp, extras)).isPresent();
        if (damaged) {
            // 这条路不经原版受伤入口，挨打的人不红、不晃、不出声；拳脚打在身上要有，和普通路的打击感一致。
            VanillaDamageFabric.presentHurt(target, source);
        }
        return damaged;
    }

    static boolean hurtBodiless(LivingEntity target, DamageSource source, LimbStrike strike, float vanillaDose) {
        // 没绑身体的目标：盾、无敌帧照旧归原版受伤入口管。声明了透劲的，这一下里护甲和韧性按声明的倍数算；
        // 带麻的打中以后给一小段原版缓慢和虚弱（多久、几级读人体伤害配置）。两样都没声明时和普通一拳完全一样。
        ServerLevel level = (ServerLevel) target.level();
        boolean damaged;
        if (strike.hasPenetration()) {
            damaged = hurtThroughArmor(level, target, source, strike, vanillaDose);
        } else {
            damaged = target.hurtServer(level, source, vanillaDose);
        }
        if (damaged && target.isAlive() && strike.getAttachmentsList().stream().anyMatch(StrikeAttachment::hasNumb)) {
            HumanDamageRules.BodilessNumb numb = HumanDamage.configuredRules().bodilessNumb();
            give(target, source, MobEffects.SLOWNESS, numb.slownessLevel(), numb.ticks());
            give(target, source, MobEffects.WEAKNESS, numb.weaknessLevel(), numb.ticks());
        }
        return damaged;
    }

    private static void give(LivingEntity target, DamageSource source, Holder<MobEffect> effect, int level,
                             int ticks) {
        // 配置里的等级从 1 起算，原版的放大值从 0 起算；等级 0 是「这一样不给」。
        if (level > 0) {
            // 我们给的状态效果不出粒子，图标照常显示。
            target.addEffect(new MobEffectInstance(effect, ticks, level - 1, false /* ambient */,
                    false /* showParticles */, true /* showIcon */), source.getEntity());
        }
    }

    private static boolean hurtThroughArmor(ServerLevel level, LivingEntity target, DamageSource source,
                                            LimbStrike strike, float vanillaDose) {
        // 原版按全身护甲值和韧性减伤：打之前给两项各挂一条「乘倍数」的临时修正，打完不论成没成都摘掉。
        double multiplier = strike.getPenetration().getArmorMultiplier().getValue();
        var discount = new AttributeModifier(ARMOR_DISCOUNT, multiplier - 1,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        List<AttributeInstance> discounted = new ArrayList<>();
        for (Holder<Attribute> attribute : List.of(Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS)) {
            AttributeInstance instance = target.getAttribute(attribute);
            if (instance != null && !instance.hasModifier(ARMOR_DISCOUNT)) {
                instance.addTransientModifier(discount);
                discounted.add(instance);
            }
        }
        try {
            return target.hurtServer(level, source, vanillaDose);
        } finally {
            discounted.forEach(instance -> instance.removeModifier(ARMOR_DISCOUNT));
        }
    }

    static void disarm(LivingEntity target) {
        // 玩家：攻击冷却清零、正在用的物品被打断，东西不掉；别的活物：主手的东西掉在脚下。
        if (target instanceof Player player) {
            player.resetAttackStrengthTicker();
            player.stopUsingItem();
            return;
        }
        ItemStack held = target.getMainHandItem();
        if (!held.isEmpty() && target.level() instanceof ServerLevel level) {
            target.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            target.spawnAtLocation(level, held);
        }
    }

    private static Optional<String> part(LivingEntity target, DamageSource source, LimbStrike strike,
                                         BodyMessages.BodySnapshot body) {
        // 目录点名的部位这具身体上没有（不是人形）时，退回伤害类型的部位组。
        Optional<String> named = switch (strike.getPartCase()) {
            case FIXED_PART_ID -> Optional.of(strike.getFixedPartId());
            case ARMED_FOREARM -> Optional.of(armedSide(target) + FOREARM);
            case PART_NOT_SET -> Optional.empty();
        };
        boolean present = named.isPresent() && body.getStructure().getPartsList().stream()
                .anyMatch(part -> part.getId().equals(named.get()));
        if (present) {
            return named;
        }
        return VanillaDamageFabric.aimedPart(target, source);
    }

    private static String armedSide(LivingEntity target) {
        // 拿着东西的那只手在哪一侧：主手有东西或两手都空取主手侧，只有副手有东西取另一侧。
        boolean rightHanded = target.getMainArm() == HumanoidArm.RIGHT;
        boolean offHandOnly = target.getMainHandItem().isEmpty() && !target.getOffhandItem().isEmpty();
        return rightHanded != offHandOnly ? RIGHT : LEFT;
    }

    private static List<BodyMessages.CapabilityModifier> modifiers(BodyMessages.BodySnapshot body, String partId,
                                                                    List<StrikeAttachment> attachments) {
        // 现在只有麻：打中部位上的肌肉；whole_arm 时换成这一侧手臂的全部手臂肌肉。
        var modifiers = new ArrayList<BodyMessages.CapabilityModifier>();
        for (StrikeAttachment attachment : attachments) {
            if (!attachment.hasNumb()) {
                continue;
            }
            Numb numb = attachment.getNumb();
            for (BodyMessages.TissueDefinition tissue : body.getStructure().getTissuesList()) {
                if (numbed(tissue, partId, numb.getWholeArm())) {
                    modifiers.add(numbModifier(tissue.getId(), numb));
                }
            }
        }
        return modifiers;
    }

    private static boolean numbed(BodyMessages.TissueDefinition tissue, String partId, boolean wholeArm) {
        // 肌肉才会麻；整条手臂按「同一侧、带手臂肌肉标签」认，不按名字。
        if (!tissue.getKind().equals(MUSCLE_KIND)) {
            return false;
        }
        if (tissue.getPartId().equals(partId)) {
            return true;
        }
        String side = partId.startsWith(LEFT) ? LEFT : RIGHT;
        boolean arm = partId.startsWith(LEFT) || partId.startsWith(RIGHT);
        return wholeArm && arm && tissue.getTagsList().contains(ARM_MUSCLE) && tissue.getPartId().startsWith(side);
    }

    private static BodyMessages.CapabilityModifier numbModifier(String tissueId, Numb numb) {
        // 力量和功率乘同一个倍数，持续时间内不变；每块肌肉一条，同一块再麻一次是续期。来源和开始时刻由下游填。
        double multiplier = numb.getMultiplier().getValue();
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId(NUMB_ID_PREFIX + tissueId)
                .setTissueId(tissueId)
                .addCurve(BodyMessages.CurvePoint.newBuilder().setOffsetMillis(0).setValue(1))
                .addCurve(BodyMessages.CurvePoint.newBuilder().setOffsetMillis(numb.getDurationMillis()).setValue(1))
                .setStrengthPeak(multiplier)
                .setBurstPeak(multiplier)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .build();
    }
}
