package dev.kizuna.meridian.human.server;

/** 玩家与类人共用的人体功法表现映射，不保存播放或身体状态。 */
public final class HumanTechniqueAnimations {
    public record Direction(double yawDegrees, double x, double z) { }
    public enum CastHand { MAIN, OFF }
    /** 交给 PA 的起播时刻（世界 tick）和播放倍率。 */
    public record Playback(long startTick, float rate) { }

    private static final long MILLIS_PER_TICK = 50;
    private static final float FULL_RATE = 1;

    public static String resolve(String presentationId, Direction direction, CastHand selectedHand) {
        // 出招手来自起手快照；左右惯用手的镜像由 PA 在客户端处理。
        String path = presentationId.substring(presentationId.indexOf(':') + 1);
        // 破门斩八式和行矢各式都分主手、副手两套剪辑。
        boolean handed = presentationId.startsWith("meridian_door_breaker:door_breaker_")
                || presentationId.startsWith("meridian_wayfarer:wayfarer_");
        if (handed && selectedHand == CastHand.OFF) {
            return path + "_off";
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

    /**
     * 表现效果现在该从哪个 tick 起播、播多快。
     * 功法的时间轴按身体节奏整体放慢时，动作用同一个倍率放慢，两边才对得上；PA 只能放慢，节奏比原速快时按原速播。
     * 效果迟到了多久（latenessMillis，真实时间）、从剪辑的第几毫秒开播（clipStartMillis，剪辑时间）都折进起播时刻：
     * 剪辑此刻应当走到 clipStartMillis + 迟到 × 倍率，折回真实时间就是 clipStartMillis ÷ 倍率 + 迟到。
     */
    public static Playback playback(long nowTick, long clipStartMillis, long latenessMillis, double speedMultiplier) {
        float rate = FULL_RATE;
        if (Double.isFinite(speedMultiplier) && speedMultiplier > 0 && speedMultiplier < FULL_RATE) {
            rate = (float) speedMultiplier;
        }
        long elapsedTicks = (long) ((clipStartMillis / rate + latenessMillis) / MILLIS_PER_TICK);
        return new Playback(Math.max(0, nowTick - elapsedTicks), rate);
    }

    private HumanTechniqueAnimations() {
        // 仅提供无 Minecraft 依赖的公开内容映射。
    }
}
