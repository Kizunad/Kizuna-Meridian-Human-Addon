package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.TissueCapabilities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 将人体动作速度投影到原版属性，曲线与期限仍由 Framework 保存。 */
final class HumanTempo {
    private static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath("meridian_human", "body_tempo");
    private static final double LEG_COUNT = 2;
    private static final double MIN_MULTIPLIER_CHANGE = 0.001;
    private static final String MUSCLE_KIND = "human:muscle";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";
    private static final Set<String> LEFT_ARM_PARTS = armParts(LEFT);
    private static final Set<String> RIGHT_ARM_PARTS = armParts(RIGHT);
    private static final Set<String> LEFT_LEG_PARTS = legParts(LEFT);
    private static final Set<String> RIGHT_LEG_PARTS = legParts(RIGHT);
    private static final String LEFT_GLUTEUS = LEFT + "_gluteus_maximus";
    private static final String RIGHT_GLUTEUS = RIGHT + "_gluteus_maximus";
    private static final Map<UUID, Muscles> muscles = new HashMap<>();

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
        Muscles cached = muscles.get(player.getUUID());
        Muscles indexed;
        if (cached == null || !cached.structure().equals(body.structure())) {
            indexed = index(body.structure());
            muscles.put(player.getUUID(), indexed);
        } else {
            indexed = cached;
        }
        List<Anatomy.TissueId> armIds = indexed.rightArm();
        if (player.getMainArm() == HumanoidArm.LEFT) {
            armIds = indexed.leftArm();
        }
        double arm = minimum(body, armIds, millis);
        double leftLeg = minimum(body, indexed.leftLeg(), millis);
        double rightLeg = minimum(body, indexed.rightLeg(), millis);
        double movement = (leftLeg + rightLeg) / LEG_COUNT;
        apply(player, Attributes.ATTACK_SPEED, arm);
        apply(player, Attributes.BLOCK_BREAK_SPEED, arm);
        apply(player, Attributes.MOVEMENT_SPEED, movement);
    }

    private static Muscles index(BodyStructure structure) {
        // 仅在构型变化时遍历一次组织；臀大肌虽属骨盆，仍归入同侧腿。
        var leftArm = new ArrayList<Anatomy.TissueId>();
        var rightArm = new ArrayList<Anatomy.TissueId>();
        var leftLeg = new ArrayList<Anatomy.TissueId>();
        var rightLeg = new ArrayList<Anatomy.TissueId>();
        for (Anatomy.TissueDefinition tissue : structure.tissues()) {
            if (!tissue.kind().value().equals(MUSCLE_KIND)) {
                continue;
            }
            String part = tissue.partId().value();
            Anatomy.TissueId id = tissue.id();
            if (LEFT_ARM_PARTS.contains(part)) {
                leftArm.add(id);
            } else if (RIGHT_ARM_PARTS.contains(part)) {
                rightArm.add(id);
            } else if (LEFT_LEG_PARTS.contains(part) || id.value().equals(LEFT_GLUTEUS)) {
                leftLeg.add(id);
            } else if (RIGHT_LEG_PARTS.contains(part) || id.value().equals(RIGHT_GLUTEUS)) {
                rightLeg.add(id);
            }
        }
        return new Muscles(structure, List.copyOf(leftArm), List.copyOf(rightArm),
                List.copyOf(leftLeg), List.copyOf(rightLeg));
    }

    private static Set<String> armParts(String side) {
        // 固定人体部位名只在初始化时组装，tick 中不拼接字符串。
        return Set.of(side + "_upper_arm", side + "_forearm", side + "_hand");
    }

    private static Set<String> legParts(String side) {
        // 臀肌另外按组织 ID 识别，其余腿肌按实际部位归类。
        return Set.of(side + "_thigh", side + "_lower_leg", side + "_foot");
    }

    private static double minimum(BodySnapshot body, List<Anatomy.TissueId> ids, long millis) {
        // 同侧最慢肌肉限制整条肢体，空肢体没有额外倍率。
        double minimum = Double.POSITIVE_INFINITY;
        for (Anatomy.TissueId id : ids) {
            minimum = Math.min(minimum, TissueCapabilities.tempoMultiplierAt(body, id, millis));
        }
        if (minimum == Double.POSITIVE_INFINITY) {
            return 1;
        }
        return minimum;
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

    private record Muscles(BodyStructure structure, List<Anatomy.TissueId> leftArm,
                           List<Anatomy.TissueId> rightArm, List<Anatomy.TissueId> leftLeg,
                           List<Anatomy.TissueId> rightLeg) { }
}
