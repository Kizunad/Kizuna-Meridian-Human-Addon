package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 仅 OP 的开脉完成和精确密度调试，始终进入 Framework 权威事务。 */
final class HumanCultivationCommands {
    private HumanCultivationCommands() {
        // 调试不暴露为客户端能力，也不授予残卷进度。
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         Function<ServerPlayer, HumanPlayerBody> bodies, Consumer<ServerPlayer> changed) {
        // 参数明确指定玩家，服务端控制台也可调试；all 是该账户全身总量。
        dispatcher.register(Commands.literal("meridian").then(Commands.literal("opening")
                .then(Commands.literal("complete").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("meridian", StringArgumentType.word()).executes(context -> {
                                    var player = EntityArgument.getPlayer(context, "player");
                                    var body = bodies.apply(player);
                                    if (body == null) {
                                        return 0;
                                    }
                                    FailureCode result = body.completeOpening(now(player), StringArgumentType.getString(context, "meridian"));
                                    changed.accept(player);
                                    context.getSource().sendSuccess(() -> Component.literal("开脉完成：" + result), false);
                                    return result == FailureCode.FAILURE_CODE_UNSPECIFIED ? 1 : 0;
                                }))))));
        var set = Commands.literal("set").requires(source -> source.hasPermission(2));
        var player = Commands.argument("player", EntityArgument.player());
        for (String account : java.util.List.of("lumen", "wall")) {
            player.then(Commands.literal(account)
                    .then(Commands.argument("meridian", StringArgumentType.word())
                            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                    .then(Commands.argument("density", DoubleArgumentType.doubleArg(.01)).executes(context -> {
                                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                        HumanPlayerBody body = bodies.apply(target);
                                        if (body == null) {
                                            return 0;
                                        }
                                        String id = StringArgumentType.getString(context, "meridian");
                                        DebugQi request = DebugQi.newBuilder().setMode(DebugQiMode.SET_INVENTORY)
                                                .setMeridianId(id.equals("all") ? "" : id).setWall(account.equals("wall"))
                                                .setAmount(DoubleArgumentType.getDouble(context, "amount"))
                                                .setDensity(DoubleArgumentType.getDouble(context, "density")).build();
                                        FailureCode result = body.debugQi(now(target), request);
                                        changed.accept(target);
                                        context.getSource().sendSuccess(() -> Component.literal("密度调试：" + result), false);
                                        return result == FailureCode.FAILURE_CODE_UNSPECIFIED ? 1 : 0;
                                    })))));
        }
        dispatcher.register(Commands.literal("meridian").then(Commands.literal("purity").then(set.then(player))));
    }

    private static long now(ServerPlayer player) {
        // 使用宿主世界模拟时钟，不能按系统时间跳过消耗。
        return Math.multiplyExact(player.getServer().overworld().getGameTime(), 50);
    }
}
