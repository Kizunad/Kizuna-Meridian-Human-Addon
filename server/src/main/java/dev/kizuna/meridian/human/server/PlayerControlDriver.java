package dev.kizuna.meridian.human.server;

/** Human 提供实时 ControlRequest，决策层只返回有序且短时有效的 ControlIntent。 */
public interface PlayerControlDriver {
    String ENTRYPOINT = "meridian_player_control_driver";
    String CALLER = "meridian_human:control";

    byte[] exchange(String caller, byte[] payload);
}
