package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.util.function.Consumer;
import java.util.function.Function;

/** 仅 OP 的开脉完成和精确密度调试，始终进入 Framework 权威事务。 */
final class HumanCultivationCommands {
    private HumanCultivationCommands() {
        // 调试不暴露为客户端能力，也不授予残卷进度。
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                         Function<ServerPlayerEntity, HumanPlayerBody> bodies, Consumer<ServerPlayerEntity> changed) {
        // 参数明确指定玩家，服务端控制台也可调试；all 是该账户全身总量。
        dispatcher.register(CommandManager.literal("meridian").then(CommandManager.literal("opening")
                .then(CommandManager.literal("complete").requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("meridian", StringArgumentType.word()).executes(context -> {
                                    var player = EntityArgumentType.getPlayer(context, "player");
                                    var body = bodies.apply(player);
                                    if (body == null) {
                                        return 0;
                                    }
                                    FailureCode result = body.completeOpening(now(player), StringArgumentType.getString(context, "meridian"));
                                    changed.accept(player);
                                    context.getSource().sendFeedback(() -> Text.literal("开脉完成：" + result), false);
                                    return result == FailureCode.FAILURE_CODE_UNSPECIFIED ? 1 : 0;
                                }))))));
        var set = CommandManager.literal("set").requires(source -> source.hasPermissionLevel(2));
        var player = CommandManager.argument("player", EntityArgumentType.player());
        for (String account : java.util.List.of("lumen", "wall")) {
            player.then(CommandManager.literal(account)
                    .then(CommandManager.argument("meridian", StringArgumentType.word())
                            .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                                    .then(CommandManager.argument("density", DoubleArgumentType.doubleArg(.01)).executes(context -> {
                                        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
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
                                        context.getSource().sendFeedback(() -> Text.literal("密度调试：" + result), false);
                                        return result == FailureCode.FAILURE_CODE_UNSPECIFIED ? 1 : 0;
                                    })))));
        }
        dispatcher.register(CommandManager.literal("meridian").then(CommandManager.literal("purity").then(set.then(player))));
    }

    private static long now(ServerPlayerEntity player) {
        // 使用宿主世界模拟时钟，不能按系统时间跳过消耗。
        return Math.multiplyExact(player.getServer().getOverworld().getTime(), 50);
    }
}
