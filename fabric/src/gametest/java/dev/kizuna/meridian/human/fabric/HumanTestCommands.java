package dev.kizuna.meridian.human.fabric;

import net.minecraft.commands.CommandSourceStack;

/** GameTest 里按聊天栏方式执行指令并取回结果。 */
final class HumanTestCommands {
    private static final int FAILED = 0;

    private HumanTestCommands() {
        // 只放指令执行的测试辅助。
    }

    static int run(CommandSourceStack source, String command) {
        // 与聊天栏执行相同；26.2 的执行入口不再返回结果，改由来源的结果回调取得，解析失败或执行失败都返回 0。
        int[] result = {FAILED};
        source.getServer().getCommands().performPrefixedCommand(
                source.withCallback((success, value) -> result[0] = success ? value : FAILED), command);
        return result[0];
    }
}
