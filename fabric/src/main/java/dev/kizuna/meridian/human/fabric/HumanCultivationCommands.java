package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 仅 OP 的开脉完成与境界调试，始终进入 Framework 权威事务。 */
final class HumanCultivationCommands {
    private static final String ALL_MERIDIANS = "all";
    private static final List<Realm> REALMS = List.of(Realm.WANDERING_QI, Realm.TRUE_QI);

    private HumanCultivationCommands() {
        // 调试不暴露为客户端能力，也不授予残卷进度。
    }

    static void register(Function<ServerPlayer, HumanPlayerBody> bodies, Consumer<ServerPlayer> changed) {
        // /meridian opening complete [<targets>] <meridian|all>：省略目标时完成执行者本人的开拓；控制台需指定目标。
        MeridianCommands.register(MeridianCommands.Branch.OPENING, "complete", MeridianCommands.GAMEMASTER,
                (node, context) -> MeridianCommands.optionalPlayers(node, (parent, targets) -> parent.then(
                        Commands.argument("meridian", StringArgumentType.word())
                                .suggests((command, builder) -> {
                                    // 补全 all 与第一个目标实际身体的经脉；目标还解析不出来时只给 all。
                                    builder.suggest(ALL_MERIDIANS);
                                    HumanPlayerBody body;
                                    try {
                                        body = bodies.apply(targets.resolve(command).iterator().next());
                                    } catch (CommandSyntaxException unresolved) {
                                        body = null;
                                    }
                                    if (body != null) {
                                        for (var meridian : body.snapshot().structure().meridians()) {
                                            if (meridian.id().value().startsWith(builder.getRemaining())) {
                                                builder.suggest(meridian.id().value());
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(command -> complete(command.getSource(), bodies, changed,
                                        targets.resolve(command),
                                        StringArgumentType.getString(command, "meridian"))))));
        registerRealm(bodies, changed);
    }

    private static void registerRealm(Function<ServerPlayer, HumanPlayerBody> bodies, Consumer<ServerPlayer> changed) {
        // /meridian body realm [<targets>] <wandering_qi|true_qi>：人体只有中焦突破这一层，命令只能由游炁升到真炁。
        MeridianCommands.register(MeridianCommands.Branch.BODY, "realm", MeridianCommands.GAMEMASTER,
                (node, context) -> MeridianCommands.optionalPlayers(node, (parent, targets) -> parent.then(
                        Commands.argument("realm", StringArgumentType.word())
                                .suggests((command, builder) -> {
                                    // 补全人体能到的两层境界。
                                    for (Realm realm : REALMS) {
                                        String name = realm.name().toLowerCase(Locale.ROOT);
                                        if (name.startsWith(builder.getRemaining())) {
                                            builder.suggest(name);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(command -> realm(command.getSource(), bodies, changed,
                                        targets.resolve(command),
                                        StringArgumentType.getString(command, "realm"))))));
    }

    private static int realm(CommandSourceStack source, Function<ServerPlayer, HumanPlayerBody> bodies,
                             Consumer<ServerPlayer> changed, Collection<ServerPlayer> targets, String text) {
        // 逐个目标改境界；已在该境界算成功，往下走或越级说明原因，没有身体的目标跳过。
        Realm wanted = REALMS.stream().filter(realm -> realm.name().equalsIgnoreCase(text)).findFirst().orElse(null);
        if (wanted == null) {
            source.sendFailure(Component.translatable(HumanLang.REALM_UNKNOWN, text));
            return 0;
        }
        int done = 0;
        for (ServerPlayer player : targets) {
            HumanPlayerBody body = bodies.apply(player);
            if (body == null) {
                continue;
            }
            Realm current = Realm.valueOf(body.snapshot().realm().name());
            if (wanted == Realm.TRUE_QI && current == Realm.WANDERING_QI) {
                current = body.ascend(now(player));
                changed.accept(player);
            }
            if (current == wanted) {
                Realm reached = current;
                source.sendSuccess(() -> Component.translatable(HumanLang.REALM_SET, player.getDisplayName(),
                        realmName(reached)), true);
                done++;
            } else {
                source.sendFailure(Component.translatable(HumanLang.REALM_ONE_WAY, player.getDisplayName(),
                        realmName(current)));
            }
        }
        return done;
    }

    private static Component realmName(Realm realm) {
        // 境界名走语言文件；人体到不了的更高境界没有文案时直接写命令里的英文名。
        String name = realm.name().toLowerCase(Locale.ROOT);
        return Component.translatableWithFallback(HumanLang.REALM_NAME + name, name);
    }

    private static int complete(CommandSourceStack source, Function<ServerPlayer, HumanPlayerBody> bodies,
                                Consumer<ServerPlayer> changed, Collection<ServerPlayer> targets, String meridian) {
        // 逐个目标完成开拓；all 是该身体全部经脉。没有身体的目标跳过，不计入成功数。
        int completed = 0;
        for (ServerPlayer player : targets) {
            HumanPlayerBody body = bodies.apply(player);
            if (body == null) {
                continue;
            }
            FailureCode result = body.completeOpening(now(player), meridian);
            changed.accept(player);
            source.sendSuccess(() -> Component.translatable(HumanLang.OPENING_COMPLETE, player.getDisplayName(),
                    String.valueOf(result)), false);
            if (result == FailureCode.FAILURE_CODE_UNSPECIFIED) {
                completed++;
            }
        }
        return completed;
    }

    private static long now(ServerPlayer player) {
        // 使用宿主世界模拟时钟，不能按系统时间跳过消耗。
        return Math.multiplyExact(player.level().getServer().overworld().getGameTime(), 50);
    }
}
