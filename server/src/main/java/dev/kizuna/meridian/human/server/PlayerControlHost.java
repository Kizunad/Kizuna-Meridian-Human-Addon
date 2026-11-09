package dev.kizuna.meridian.human.server;

/** 可信控制器向玩家宿主交换 ControlCommand / ControlReply；不接受客户端载荷。 */
public interface PlayerControlHost {
    String ENTRYPOINT = "meridian_player_control_host";
    String CALLER = "meridian_humanoid:control";

    byte[] exchange(String caller, byte[] payload);
}
