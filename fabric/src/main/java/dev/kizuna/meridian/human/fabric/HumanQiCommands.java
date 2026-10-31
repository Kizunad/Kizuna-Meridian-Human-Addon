package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** /meridian qi 的查看与扩散入口；剂量、预约、版本和原子入账由 Framework 执行。补满与设定库存归调试附属。 */
final class HumanQiCommands {
    private HumanQiCommands() {
        // 独立命令树保持主生命周期接线可读。
    }

    /** 查看或改写时取目标玩家；补全在玩家输入时调用，取不到目标就不给建议。 */
    @FunctionalInterface
    private interface Owner {
        ServerPlayer resolve(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }

    static void register(Function<ServerPlayer, HumanPlayerBody> bodies, Consumer<ServerPlayer> changed) {
        // 查看自己的真元分账为权限 0，查看他人另要权限 2；扩散是写入，整条要求权限 2。
        MeridianCommands.register(MeridianCommands.Branch.QI, "get", MeridianCommands.PLAYER, (node, context) ->
                MeridianCommands.optionalEntity(node, (parent, target) -> parent
                        .executes(command -> inspect(command.getSource(), bodies, target.resolve(command), null))
                        .then(meridian(bodies, command -> player(target.resolve(command)))
                                .executes(command -> inspect(command.getSource(), bodies, target.resolve(command),
                                        StringArgumentType.getString(command, "meridian"))))));
        MeridianCommands.register(MeridianCommands.Branch.QI, "diffuse", MeridianCommands.GAMEMASTER, (node, context) ->
                MeridianCommands.optionalPlayers(node, (parent, targets) -> parent
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(.000001))
                                .executes(command -> inject(command.getSource(), bodies, changed,
                                        targets.resolve(command), DoubleArgumentType.getDouble(command, "amount"),
                                        null))
                                .then(meridian(bodies, command -> targets.resolve(command).iterator().next())
                                        .executes(command -> inject(command.getSource(), bodies, changed,
                                                targets.resolve(command),
                                                DoubleArgumentType.getDouble(command, "amount"),
                                                StringArgumentType.getString(command, "meridian")))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> meridian(
            Function<ServerPlayer, HumanPlayerBody> bodies, Owner owner) {
        // Tab 补全使用目标的实际身体，不复制人体经脉名单；目标还解析不出来时不给建议。
        return Commands.argument("meridian", StringArgumentType.word()).suggests((context, builder) -> {
            HumanPlayerBody body;
            try {
                ServerPlayer player = owner.resolve(context);
                body = player == null ? null : bodies.apply(player);
            } catch (CommandSyntaxException unresolved) {
                body = null;
            }
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

    private static ServerPlayer player(Entity target) {
        // 人体只绑定玩家；其他实体在补全阶段视为没有经脉。
        return target instanceof ServerPlayer player ? player : null;
    }

    private static int inject(CommandSourceStack source, Function<ServerPlayer, HumanPlayerBody> bodies,
                              Consumer<ServerPlayer> changed, Collection<ServerPlayer> targets, double amount,
                              String target) {
        // 逐个目标提交；省略入口时选择该人体肺出口对应的经脉，模拟的是炼化后入脉及后续扩散。
        int submitted = 0;
        for (ServerPlayer player : targets) {
            HumanPlayerBody body = bodies.apply(player);
            if (body == null) {
                source.sendFailure(Component.literal(HumanErrors.BODY_MISSING));
                continue;
            }
            String entry = target == null ? body.diffusionEntry() : target;
            if (entry.isEmpty()) {
                source.sendFailure(Component.literal(HumanErrors.DIFFUSION_ENTRY_MISSING));
                continue;
            }
            submitted += apply(source, player, body, changed, DebugQi.newBuilder()
                    .setMode(DebugQiMode.INJECT_FOR_DIFFUSION).setMeridianId(entry).setAmount(amount).build());
        }
        return submitted;
    }

    private static int apply(CommandSourceStack source, ServerPlayer player, HumanPlayerBody body,
                             Consumer<ServerPlayer> changed, DebugQi request) {
        // 执行点再次检查权限，失败只报告业务原因，不把玩家身体标记为故障。
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
            source.sendFailure(Component.literal(HumanErrors.DEBUG_PERMISSION));
            return 0;
        }

        // 提交前后各读一次内腔总量，反馈里给出变化。
        double previous = body.snapshot().lumenQiTotal();
        long now = Math.multiplyExact(player.level().getServer().overworld().getGameTime(), 50);
        FailureCode failure = body.debugQi(now, request);
        changed.accept(player);
        if (failure != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            source.sendFailure(Component.literal(HumanErrors.debugRejected(failure)));
            return 0;
        }
        Component target = request.getMeridianId().isEmpty()
                ? Component.translatable(HumanLang.QI_ALL_MERIDIANS) : Component.literal(request.getMeridianId());
        source.sendSuccess(() -> Component.translatable(HumanLang.QI_SUBMITTED, player.getDisplayName(), target,
                qi(previous), qi(body.snapshot().lumenQiTotal())), false);
        return 1;
    }

    private static int inspect(CommandSourceStack source, Function<ServerPlayer, HumanPlayerBody> bodies, Entity owner,
                               String target) {
        // 报告分账便于和 UI 比对；查询只读已结算快照，不主动领取资源。
        if (!(owner instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable(HumanLang.TARGET_NOT_PLAYER, owner.getDisplayName()));
            return 0;
        }
        HumanPlayerBody body = bodies.apply(player);
        if (body == null) {
            source.sendFailure(Component.literal(HumanErrors.BODY_MISSING));
            return 0;
        }

        // 指定经脉时报告该脉内腔与稳定容量，否则报告全身各账户总量。
        if (target != null) {
            var state = body.snapshot().meridianStates().stream().filter(item -> item.id().value().equals(target)).findFirst().orElse(null);
            if (state == null) {
                source.sendFailure(Component.literal(HumanErrors.DIFFUSION_ENTRY_MISSING));
                return 0;
            }
            var definition = body.snapshot().structure().meridians().stream().filter(item -> item.id().equals(state.id())).findFirst().orElseThrow();
            double capacityQi = QiDensity.displayedCapacity(definition.lumenVolume(),
                    dev.kizuna.meridian.protocol.BodyProtobuf.toInventory(state.lumenQi()));
            source.sendSuccess(() -> Component.translatable(HumanLang.QI_MERIDIAN, definition.displayName(),
                    qi(state.lumenQi().totalQi()), qi(capacityQi), String.valueOf(state.opening().stage())), false);
        } else {
            double transit = body.snapshot().transits().stream().mapToDouble(item -> item.cargo().totalQi()).sum();
            source.sendSuccess(() -> Component.translatable(HumanLang.QI_BODY, qi(body.snapshot().totalQi()),
                    qi(body.snapshot().lumenQiTotal()), qi(body.snapshot().wallQiTotal()), qi(transit)), false);
        }
        return 1;
    }

    private static String qi(double amount) {
        // 真元按元显示四位小数，与原反馈的精度一致；格式化在服务端完成，lang 只放句式。
        return String.format(Locale.ROOT, "%.4f", amount);
    }
}
