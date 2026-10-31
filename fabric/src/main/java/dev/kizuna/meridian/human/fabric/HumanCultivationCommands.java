package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 仅 OP 的开脉完成调试，始终进入 Framework 权威事务。 */
final class HumanCultivationCommands {
    private static final String ALL_MERIDIANS = "all";

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
