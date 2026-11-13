package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** 玩家施放拳脚时的宿主差别：敌我与玩家近战一致，靴子来自脚部装备栏。 */
record HumanPlayerLimbHost(ServerPlayer player) implements HumanLimbStrikes.Host {
    @Override
    public boolean hittable(LivingEntity target) {
        // 玩家不打原版同伴（同队、自己驯服的动物），和持物打击同一条规则。
        return HumanControl.allowsTarget(player, target);
    }

    @Override
    public Optional<LivingEntity> declaredTarget(EffectIntent effect, double reach) {
        // 玩家的单体拳脚按准星取目标，不认请求里带的目标。
        return Optional.empty();
    }

    @Override
    public double bootArmor() {
        // 腿法剂量读当前脚部装备的护甲点数，光脚为 0。
        return HumanTechniqueFacts.armor(HumanTechniqueFacts.boots(player));
    }

    @Override
    public void wearBoots(int durabilityCost) {
        // 末击只磨脚部装备；创造模式和光脚都不改变物品栏。
        if (player.isCreative()) {
            return;
        }
        ItemStack boots = HumanTechniqueFacts.boots(player);
        if (boots.isEmpty() || boots.getMaxDamage() == 0) {
            return;
        }
        boots.hurtAndBreak(durabilityCost, player, EquipmentSlot.FEET);
    }

    @Override
    public SoundSource soundSource() {
        // 玩家的拳脚声归玩家类别。
        return SoundSource.PLAYERS;
    }
}
