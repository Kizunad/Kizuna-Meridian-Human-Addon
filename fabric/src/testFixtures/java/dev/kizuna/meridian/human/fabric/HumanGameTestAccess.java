package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** 仅测试装配使用：操作已登录玩家的正式吐纳会话并读取已提交回执。 */
public final class HumanGameTestAccess {
    private static final long MILLIS_PER_TICK = 50;

    private HumanGameTestAccess() {
        // 不替换身体、时钟或环境，服务器 tick 正常推进吐纳。
    }

    public static GatheringView breathe(ServerPlayer player) {
        // 阅读进度走既有身体操作，随后按公开吐纳请求启动自动气流。
        HumanPlayerBody body = HumanTechniqueHost.state().find(player.getUUID());
        var progress = body.progress().toBuilder()
                .setQiNotesRead(true /* read */)
                .setGatheringScrollRead(true /* read */)
                .build();
        body.writeProgress(progress);
        var control = BreathControl.newBuilder()
                .setAction(BreathAction.BEGIN_BREATHING)
                .setAutomatic(true /* automatic */)
                .setSequence(1)
                .build();
        var request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("human-test-breathe")
                .setBodyId(player.getStringUUID())
                .setBreath(control)
                .build();
        long now = player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        var response = body.gather(now, request);
        if (response.hasFailure()) {
            throw new IllegalStateException("真实吐纳启动失败：" + response.getFailure());
        }
        return response.getView();
    }

    public static List<CommandReceipt> captures(ServerPlayer player) {
        // 接触回执包含真实环境扣账和采样体积，不能以器官倍率查询代替摄入证据。
        return HumanTechniqueHost.state().find(player.getUUID()).receipts().stream()
                .filter(CommandReceipt::hasQiCapture).toList();
    }

    public static void openMeridians(ServerPlayer player, String target) {
        // 整体回归沿管理命令的开脉入口准备施放条件，仍由原身体服务校验并原子提交。
        HumanPlayerBody body = HumanTechniqueHost.state().find(player.getUUID());
        long now = player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        FailureCode result = body.completeOpening(now, target);
        if (result != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            throw new IllegalStateException("测试开脉失败：" + result);
        }
    }
}
