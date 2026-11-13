package dev.kizuna.meridian.human.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.human.server.HumanCultivation;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 进度触发器 {@code meridian_human_host:cultivation_state}：每秒按玩家身体报一次修炼现状。
 *
 * <p>条件字段都可省：{@code opened_meridians} 已打通的经脉条数范围；{@code foundation} 写 true 时要求中焦突破
 * 所需的八条经脉全开且完好；{@code realm} 是最低境界 ID（如 {@code true_qi}），更高的境界也算。</p>
 */
public final class CultivationStateTrigger extends SimpleCriterionTrigger<CultivationStateTrigger.Instance> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_human_host", "cultivation_state");
    static final CultivationStateTrigger TRIGGER = new CultivationStateTrigger();
    private static final Codec<Realm> REALM_CODEC = Codec.STRING.comapFlatMap(CultivationStateTrigger::realm,
            CultivationStateTrigger::realmId);

    private CultivationStateTrigger() {
        // 全局只有一个实例，由注册表持有。
    }

    static void register() {
        // 触发器类型在注册表冻结前登记，进度文件按 ID 引用。
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, ID, TRIGGER);
    }

    @Override
    public Codec<Instance> codec() {
        // 进度文件里的条件格式。
        return Instance.CODEC;
    }

    void trigger(ServerPlayer player, BodySnapshot body) {
        // 原版只对这名玩家还没完成的进度逐条判断；现状每次从快照现算。
        Facts facts = Facts.of(body);
        trigger(player, instance -> instance.matches(facts));
    }

    private static DataResult<Realm> realm(String id) {
        // 境界 ID 写错时进度文件加载失败，不会静默变成永远不成立。
        return Arrays.stream(Realm.values())
                .filter(realm -> realmId(realm).equals(id))
                .findFirst()
                .map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "没有这个境界：" + id));
    }

    private static String realmId(Realm realm) {
        // 与 /meridian body realm 命令用的写法一致。
        return realm.name().toLowerCase(Locale.ROOT);
    }

    /** 一次判断用到的身体现状。 */
    record Facts(int openedMeridians, boolean foundation, Realm realm) {
        static Facts of(BodySnapshot body) {
            // 已打通指开拓走完；完好与否只在 foundation 里算。
            int opened = (int) body.meridianStates().stream()
                    .filter(state -> state.opening().stage() == MeridianOpening.Stage.OPENED)
                    .count();
            return new Facts(opened, HumanCultivation.openedAndIntact(body), body.realm());
        }
    }

    /** 一条进度条件：可选的玩家谓词、经脉条数、八脉齐全和最低境界。 */
    public record Instance(Optional<ContextAwarePredicate> player, MinMaxBounds.Ints openedMeridians,
                           Optional<Boolean> foundation, Optional<Realm> realm)
            implements SimpleCriterionTrigger.SimpleInstance {
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("opened_meridians", MinMaxBounds.Ints.ANY)
                        .forGetter(Instance::openedMeridians),
                Codec.BOOL.optionalFieldOf("foundation").forGetter(Instance::foundation),
                REALM_CODEC.optionalFieldOf("realm").forGetter(Instance::realm)).apply(instance, Instance::new));

        boolean matches(Facts facts) {
            // 写了的字段都要成立；境界按先后比，到了或过了都算。
            boolean openedMatches = openedMeridians.matches(facts.openedMeridians());
            boolean foundationMatches = foundation.map(required -> required == facts.foundation()).orElse(true);
            boolean realmMatches = realm.map(required -> facts.realm().compareTo(required) >= 0).orElse(true);
            return openedMatches && foundationMatches && realmMatches;
        }
    }
}
