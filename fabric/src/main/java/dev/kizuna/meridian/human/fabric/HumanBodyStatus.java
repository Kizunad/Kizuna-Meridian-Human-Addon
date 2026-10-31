package dev.kizuna.meridian.human.fabric;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.human.server.HumanErrors;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;

/**
 * /meridian body status 的公开扩展入口。Human 是该三级节点的唯一归属，玩家目标由 Human 自己显示；
 * 其他实体的身体视图（类人生物）和 full 完整明细由对应附属在初始化时登记，指令树建立时读取。
 */
public final class HumanBodyStatus {
    private static final List<View> VIEWS = new CopyOnWriteArrayList<>();
    private static volatile Detail detail;

    private HumanBodyStatus() {
        // 只保存附属登记的入口，不持有实体或身体状态。
    }

    /** 非玩家目标的身体视图；目标不归本视图时返回 false，交给下一个视图。 */
    @FunctionalInterface
    public interface View {
        boolean show(CommandSourceStack source, Entity target) throws CommandSyntaxException;
    }

    /** body status [&lt;target&gt;] full 的执行入口，玩家和非玩家目标都交给它。 */
    @FunctionalInterface
    public interface Detail {
        int full(CommandSourceStack source, Entity target) throws CommandSyntaxException;
    }

    public static void registerView(View view) {
        // 多个生物附属可以各自提供视图，按登记顺序询问。
        VIEWS.add(Objects.requireNonNull(view, HumanErrors.STATUS_VIEW_REQUIRED));
    }

    public static synchronized void registerDetail(Detail full) {
        // full 是同一个字面量，只能有一个归属；重复登记在附属初始化时失败。
        Objects.requireNonNull(full, HumanErrors.STATUS_VIEW_REQUIRED);
        if (detail != null) {
            throw new IllegalStateException(HumanErrors.STATUS_DETAIL_DUPLICATE);
        }
        detail = full;
    }

    static boolean showOther(CommandSourceStack source, Entity target) throws CommandSyntaxException {
        // 依次询问登记的视图，第一个认领目标的视图负责输出。
        for (View view : VIEWS) {
            if (view.show(source, target)) {
                return true;
            }
        }
        return false;
    }

    static void attachDetail(ArgumentBuilder<CommandSourceStack, ?> parent, MeridianCommands.Targets<Entity> target) {
        // 没有附属登记完整明细时不出现 full 字面量；登记了则挂在省略目标和指定目标两条分支下，要求权限 2。
        Detail full = detail;
        if (full == null) {
            return;
        }
        parent.then(Commands.literal("full")
                .requires(source -> source.hasPermission(MeridianCommands.GAMEMASTER))
                .executes(context -> full.full(context.getSource(), target.resolve(context))));
    }
}
