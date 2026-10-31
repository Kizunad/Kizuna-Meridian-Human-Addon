package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft 管理员入口；剂量、预约、版本和原子入账由 Framework 执行。 */
final class HumanQiCommands {
    private HumanQiCommands() {
        // 独立命令树保持主生命周期接线可读。
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         Function<ServerPlayer, HumanPlayerBody> bodies, Consumer<ServerPlayer> changed) {
        // 所有写入分支要求权限等级 2；普通玩家只能查看自己的真元分账。
        var fill = Commands.literal("fill").requires(source -> source.hasPermission(2))
                .executes(context -> apply(context.getSource(), bodies, changed,
                        DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build()))
                .then(target(bodies).executes(context -> apply(context.getSource(), bodies, changed,
                                DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS)
                                        .setMeridianId(StringArgumentType.getString(context, "meridian")).build()))
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(.000001))
                                .executes(context -> apply(context.getSource(), bodies, changed,
                                        DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS)
                                                .setMeridianId(StringArgumentType.getString(context, "meridian"))
                                                .setAmount(DoubleArgumentType.getDouble(context, "amount")).build()))));
        var diffuse = Commands.literal("diffuse").requires(source -> source.hasPermission(2))
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(.000001))
                        .executes(context -> inject(context.getSource(), bodies, changed,
                                DoubleArgumentType.getDouble(context, "amount"), null))
                        .then(target(bodies).executes(context -> inject(context.getSource(), bodies, changed,
                                DoubleArgumentType.getDouble(context, "amount"), StringArgumentType.getString(context, "meridian")))));
        dispatcher.register(Commands.literal("meridian").then(Commands.literal("zhenyuan")
                .executes(context -> inspect(context.getSource(), bodies, null))
                .then(Commands.literal("get").then(target(bodies)
                        .executes(context -> inspect(context.getSource(), bodies, StringArgumentType.getString(context, "meridian")))))
                .then(fill).then(diffuse)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> target(Function<ServerPlayer, HumanPlayerBody> bodies) {
        // Tab 补全使用当前实际身体，不复制人体经脉名单。
        return Commands.argument("meridian", StringArgumentType.word()).suggests((context, builder) -> {
            HumanPlayerBody body = bodies.apply(context.getSource().getPlayerOrException());
            if (body != null) {
                for (Anatomy.MeridianDefinition definition : body.snapshot().structure().meridians()) {
                    if (definition.id().value().startsWith(builder.getRemaining())) {
                        builder.suggest(definition.id().value());
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private static int inject(CommandSourceStack source, Function<ServerPlayer, HumanPlayerBody> bodies,
                              Consumer<ServerPlayer> changed, double amount, String target)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        // 省略入口时选择人体肺出口对应的经脉；模拟的是炼化后入脉及后续扩散。
        HumanPlayerBody body = bodies.apply(source.getPlayerOrException());
        if (body == null) {
            source.sendFailure(Component.literal(HumanErrors.BODY_MISSING));
            return 0;
        }
        String entry = target == null ? body.diffusionEntry() : target;
        if (entry.isEmpty()) {
            source.sendFailure(Component.literal(HumanErrors.DIFFUSION_ENTRY_MISSING));
            return 0;
        }
        return apply(source, bodies, changed, DebugQi.newBuilder().setMode(DebugQiMode.INJECT_FOR_DIFFUSION)
                .setMeridianId(entry).setAmount(amount).build());
    }

    private static int apply(CommandSourceStack source, Function<ServerPlayer, HumanPlayerBody> bodies,
                             Consumer<ServerPlayer> changed, DebugQi request)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        // 执行点再次检查权限，失败只报告业务原因，不把玩家身体标记为故障。
        if (!source.hasPermission(2)) {
            source.sendFailure(Component.literal(HumanErrors.DEBUG_PERMISSION));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        HumanPlayerBody body = bodies.apply(player);
        if (body == null) {
            source.sendFailure(Component.literal(HumanErrors.BODY_MISSING));
            return 0;
        }
        double previous = body.snapshot().lumenQiTotal();
        long now = Math.multiplyExact(player.getServer().overworld().getGameTime(), 50);
        FailureCode failure = body.debugQi(now, request);
        changed.accept(player);
        if (failure != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            source.sendFailure(Component.literal(HumanErrors.debugRejected(failure)));
            return 0;
        }
        String target = request.getMeridianId().isEmpty() ? "全身经脉" : request.getMeridianId();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "真元调试已提交：%s；内腔 %.4f → %.4f 元。", target, previous, body.snapshot().lumenQiTotal())), false);
        return 1;
    }

    private static int inspect(CommandSourceStack source, Function<ServerPlayer, HumanPlayerBody> bodies, String target)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        // 报告分账便于和 UI 比对；查询只读已结算快照，不主动领取资源。
        HumanPlayerBody body = bodies.apply(source.getPlayerOrException());
        if (body == null) {
            source.sendFailure(Component.literal(HumanErrors.BODY_MISSING));
            return 0;
        }
        if (target != null) {
            var state = body.snapshot().meridianStates().stream().filter(item -> item.id().value().equals(target)).findFirst().orElse(null);
            if (state == null) {
                source.sendFailure(Component.literal(HumanErrors.DIFFUSION_ENTRY_MISSING));
                return 0;
            }
            var definition = body.snapshot().structure().meridians().stream().filter(item -> item.id().equals(state.id())).findFirst().orElseThrow();
            double capacityQi = QiDensity.displayedCapacity(definition.lumenVolume(),
                    dev.kizuna.meridian.protocol.BodyProtobuf.toInventory(state.lumenQi()));
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s：内腔 %.4f / 稳定容量 %.4f 元，%s",
                    definition.displayName(), state.lumenQi().totalQi(), capacityQi, state.opening().stage())), false);
        } else {
            double transit = body.snapshot().transits().stream().mapToDouble(item -> item.cargo().totalQi()).sum();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "身体总量 %.4f 元；经脉内腔 %.4f 元；外壁 %.4f 元；在途 %.4f 元。",
                    body.snapshot().totalQi(), body.snapshot().lumenQiTotal(), body.snapshot().wallQiTotal(), transit)), false);
        }
        return 1;
    }
}
