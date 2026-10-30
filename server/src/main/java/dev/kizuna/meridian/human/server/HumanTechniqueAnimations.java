package dev.kizuna.meridian.human.server;

/** 玩家与类人共用的人体功法表现映射，不保存播放或身体状态。 */
public final class HumanTechniqueAnimations {
    public record Direction(double yawDegrees, double x, double z) { }
    public enum CastHand { MAIN, OFF }

    public static String resolve(String presentationId, Direction direction, CastHand selectedHand) {
        // 出招手来自起手快照；左右惯用手的镜像由 PA 在客户端处理。
        String path = presentationId.substring(presentationId.indexOf(':') + 1);
        if (presentationId.startsWith("meridian_door_breaker:door_breaker_")) {
            return doorBreaker(path, selectedHand);
        }
        if (!path.equals("technique_dash")) {
            return path;
        }
        double yaw = Math.toRadians(direction.yawDegrees());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double x = direction.x();
        double z = direction.z();
        double along = x * forwardX + z * forwardZ;
        double across = x * forwardZ - z * forwardX;
        if (Math.abs(along) >= Math.abs(across)) {
            if (along < 0) {
                return "technique_dash_back";
            }
            return "technique_dash_forward";
        }
        if (across < 0) {
            return "technique_dash_right";
        }
        return "technique_dash_left";
    }

    private static String doorBreaker(String path, CastHand selectedHand) {
        // 已交付三式使用整段动画，其余五式沿用对应出招手的挥砍。
        switch (path) {
            case "door_breaker_knock", "door_breaker_cleave", "door_breaker_ram" -> {
                if (selectedHand == CastHand.OFF) {
                    return path + "_off";
                }
                return path;
            }
            default -> {
                if (selectedHand == CastHand.OFF) {
                    return "attack_off";
                }
                return "attack_main";
            }
        }
    }

    private HumanTechniqueAnimations() {
        // 仅提供无 Minecraft 依赖的公开内容映射。
    }
}
