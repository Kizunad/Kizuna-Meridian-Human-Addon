package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanLimbTempo;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 将人体动作速度投影到原版属性，曲线与期限仍由 Framework 保存。 */
final class HumanTempo {
    private static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath("meridian_human", "body_tempo");
    private static final double MIN_MULTIPLIER_CHANGE = 0.001;
    private static final Map<UUID, HumanLimbTempo> muscles = new HashMap<>();

    private HumanTempo() {
        // 缓存仅包含构型和肌肉 ID，不保存身体账户或能力值。
    }

    static void initialize() {
        // 玩家离线或服务器结束时释放构型索引。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> muscles.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> muscles.clear());
    }

    static void update(ServerPlayer player, BodySnapshot body, long millis) {
        // 构型未变时直接按 ID 取当前曲线，左右腿分别取最慢后平均。
        HumanLimbTempo cached = muscles.get(player.getUUID());
        HumanLimbTempo indexed;
        if (cached == null || !cached.matches(body)) {
            indexed = new HumanLimbTempo(body.structure());
            muscles.put(player.getUUID(), indexed);
        } else {
            indexed = cached;
        }
        HumanLimbTempo.Side side = HumanLimbTempo.Side.RIGHT;
        if (player.getMainArm() == HumanoidArm.LEFT) {
            side = HumanLimbTempo.Side.LEFT;
        }
        var multipliers = indexed.at(body, side, millis);
        double arm = multipliers.arm();
        apply(player, Attributes.ATTACK_SPEED, arm);
        apply(player, Attributes.BLOCK_BREAK_SPEED, arm);
        apply(player, Attributes.MOVEMENT_SPEED, multipliers.movement());
    }

    private static void apply(ServerPlayer player, Holder<Attribute> type, double multiplier) {
        // 微小变化累计到阈值再更新，由原版同步脏属性；恢复单位倍率时立即移除。
        var attribute = player.getAttribute(type);
        AttributeModifier previous = attribute.getModifier(MODIFIER_ID);
        double amount = multiplier - 1;
        double previousAmount = 0;
        if (previous != null) {
            previousAmount = previous.amount();
        }
        if (amount == 0 && previous == null
                || amount != 0 && Math.abs(previousAmount - amount) < MIN_MULTIPLIER_CHANGE) {
            return;
        }
        attribute.removeModifier(MODIFIER_ID);
        if (amount != 0) {
            attribute.addTransientModifier(new AttributeModifier(MODIFIER_ID, amount,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

}
