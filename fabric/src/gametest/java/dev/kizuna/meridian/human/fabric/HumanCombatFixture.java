package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BreakBlocksIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 在线玩家、真实世界目标与正式协议共用夹具。 */
final class HumanCombatFixture implements AutoCloseable {
    static final double EPSILON = 0.00001;
    static final double TARGET_DISTANCE = 2;
    static final long MILLIS_PER_TICK = 50;
    private static final int PLAYER_HEIGHT = 2;
    final HumanTechniqueTestPlayer human;
    final GameTestHelper context;
    final List<Target> targets = new ArrayList<>();

    HumanCombatFixture(GameTestHelper context) {
        // 每次测试隔离玩家与空间，使用生存模式覆盖实际磨损与做功。
        this.context = context;
        human = new HumanTechniqueTestPlayer(context, UUID.randomUUID(), ObservedPlayer::new);
        var relative = new BlockPos(1, PLAYER_HEIGHT, 1);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        human.player.snapTo(position.x, position.y, position.z, 0, 0);
        human.player.setGameMode(GameType.SURVIVAL);
    }

    Target target(double x, double z) {
        // 目标只观察伤害入口，仍调用原版伤害链并实际进入服务器世界。
        var target = new Target(human.player.level());
        Vec3 position = human.player.position().add(x, 0, z);
        target.snapTo(position.x, position.y, position.z, 0, 0);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        context.assertTrue(context.getLevel().addFreshEntity(target), "真实目标生成失败");
        targets.add(target);
        return target;
    }

    void hold(Item main, Item off) {
        // 两手物品由真实背包提供，宿主每次检查与打击都会重新读取。
        human.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(main));
        human.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(off));
    }

    static TechniqueDefinition definition(String name) {
        // 世界效果使用正式发布目录而非测试重新拼出的算子参数。
        return TechniqueFabric.service().registry().findLatest("meridian_door_breaker:" + name).orElseThrow();
    }

    static HeldItemRules rules(String name) {
        // 物品分类来自与式关联的书，覆盖实际数据包标签。
        TechniqueDefinition definition = definition(name);
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        return TechniqueFabric.service().registry().bookOf(reference).orElseThrow().getHeldItemRules();
    }

    static EffectIntent strike(String name, int ordinal, String castId) {
        // 同式多击保留原始去重组、总目标预算和末击标记。
        TechniqueEffect source = definition(name).getEffectsList().stream()
                .filter(TechniqueEffect::hasWeaponStrike).toList().get(ordinal);
        var strike = WeaponStrikeIntent.newBuilder()
                .setHeldItemRules(rules(name))
                .setStrike(source.getWeaponStrike())
                .build();
        return EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId("strike_" + ordinal)
                .setScheduledMillis(source.getAtMillis())
                .setSelectedHand(TechniqueMessages.Hand.MAIN_HAND)
                .setWeaponStrike(strike)
                .build();
    }

    static EffectIntent doors(String castId) {
        // 破门空间参数、标签和最多门数与正式第二式一致。
        TechniqueEffect source = definition("cleave").getEffectsList().stream()
                .filter(TechniqueEffect::hasBreakBlocks).findFirst().orElseThrow();
        var blocks = BreakBlocksIntent.newBuilder()
                .setHeldItemRules(rules("cleave"))
                .setBlocks(source.getBreakBlocks())
                .build();
        return EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId("doors")
                .setScheduledMillis(source.getAtMillis())
                .setSelectedHand(TechniqueMessages.Hand.MAIN_HAND)
                .setBreakBlocks(blocks)
                .build();
    }

    void submit(EffectIntent effect) {
        // 每次使用新请求号，效果身份相同的重试必须由真实宿主回执去重。
        var request = TechniqueHostRequest.newBuilder()
                .setEntityUuid(human.player.getStringUUID())
                .setRequestId(UUID.randomUUID().toString())
                .setEffect(effect)
                .build();
        var result = TechniqueProtocol.decodeHostResponse(new HumanTechniqueHost().exchange(
                TechniqueProtocol.encodeHost(request)));
        context.assertTrue(result.getEffectAccepted(), "宿主拒绝世界效果：" + result);
    }

    @Override
    public void close() {
        // 即使断言失败也清除实体与真实在线绑定，避免影响同批测试。
        targets.forEach(Target::discard);
        human.close();
    }

    static final class Target extends Cow {
        final List<DamageSource> sources = new ArrayList<>();
        final List<Float> doses = new ArrayList<>();
        final List<Long> hitMillis = new ArrayList<>();

        Target(ServerLevel world) {
            // 不改血量与防御，只固定牛这种无护甲目标的观察入口。
            super(EntityTypes.COW, world);
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            // 原始剂量与实际扣血分别断言，不能把调用伤害当作已经命中；26.2 服务端伤害统一走这里。
            sources.add(source);
            doses.add(amount);
            hitMillis.add(level.getGameTime() * MILLIS_PER_TICK);
            return super.hurtServer(level, source, amount);
        }
    }

    static final class ObservedPlayer extends ServerPlayer {
        final List<VelocityChange> velocities = new ArrayList<>();

        ObservedPlayer(MinecraftServer server, ServerLevel world, GameProfile profile,
                       ClientInformation information) {
            // 只记录原版实体方法调用，所有玩家逻辑仍由父类实现。
            super(server, world, profile, information);
        }

        @Override
        public void setDeltaMovement(Vec3 velocity) {
            // 记录发生时刻而非隔一 tick 采样，避免摩擦掩盖或挪动冲量时序。
            super.setDeltaMovement(velocity);
            if (velocities != null) {
                double movement = getAttributeValue(
                        Attributes.MOVEMENT_SPEED);
                velocities.add(new VelocityChange(level().getGameTime() * MILLIS_PER_TICK, velocity, movement));
            }
        }
    }

    record VelocityChange(long millis, Vec3 velocity, double movement) { }
}
