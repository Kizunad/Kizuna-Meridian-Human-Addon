package dev.kizuna.meridian.human.server;

/** 原版空气中箭矢的离散弹道；单位为格和 tick，不参与身体结算。 */
public final class HumanProjectileBallistics {
    public static final double GRAVITY = 0.05;
    public static final double AIR_DRAG = 0.99;
    public static final double LOB_ANGLE_DEGREES = 50;
    public static final double MIN_CONVERGE_ANGLE_DEGREES = 45;
    public static final int MAX_FLIGHT_TICKS = 240;
    private static final int ROOT_ITERATIONS = 48;
    private static final double MIN_TIME = 0.001;
    private static final double DRAG_LOSS = 1 - AIR_DRAG;

    public record Vector(double x, double y, double z) {
        public double length() {
            // 用 hypot 避免把有限分量平方后溢出。
            return Math.hypot(Math.hypot(x, z), y);
        }
    }

    public enum Path { LOB, CONVERGED, FALLBACK }
    public record Shot(Vector velocity, double flightTicks, Path path) { }
    public record LobProfile(double elevationDegrees, double maximumSpeed) { }

    public static Vector position(Vector velocity, double ticks) {
        // 每 tick 先位移，再乘空气阻力并扣重力，末尾不足一 tick 时只插值位移。
        Motion motion = motion(ticks);
        return new Vector(velocity.x() * motion.travel(),
                velocity.y() * motion.travel() - motion.drop(), velocity.z() * motion.travel());
    }

    public static Vector velocityTo(Vector displacement, double ticks) {
        // 离散积分对初速保持线性，使用同一组系数反解三轴速度。
        Motion motion = motion(ticks);
        return new Vector(displacement.x() / motion.travel(),
                (displacement.y() + motion.drop()) / motion.travel(), displacement.z() / motion.travel());
    }

    public static Shot converge(Vector displacement, double ticks, double minimumElevation, LobProfile profile,
                                Vector inherited) {
        // 同时到达必须满足高抛角度与初速上限，否则按普通固定仰角抛射。
        if (ticks >= 1 && ticks <= MAX_FLIGHT_TICKS) {
            Vector velocity = launchVelocityTo(displacement, ticks, inherited);
            double elevation = Math.toDegrees(Math.atan2(velocity.y(), Math.hypot(velocity.x(), velocity.z())));
            if (elevation >= minimumElevation && velocity.length() <= profile.maximumSpeed()) {
                return new Shot(velocity, ticks, Path.CONVERGED);
            }
        }
        Shot lob = lob(displacement, profile, inherited);
        return new Shot(lob.velocity(), lob.flightTicks(), Path.FALLBACK);
    }

    public static Shot lob(Vector displacement, LobProfile profile, Vector inherited) {
        // 固定动画仰角，找下降段落点的飞行时间；够不到时保持仰角并夹到速度上限。
        double previousTime = MIN_TIME;
        double previousResidual = elevationResidual(displacement, previousTime, profile.elevationDegrees(), inherited);
        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            double residual = elevationResidual(displacement, tick, profile.elevationDegrees(), inherited);
            if (previousResidual <= 0 && residual >= 0) {
                double time = lobTime(displacement, previousTime, tick, profile.elevationDegrees(), inherited);
                Vector velocity = launchVelocityTo(displacement, time, inherited);
                if (velocity.length() <= profile.maximumSpeed()) {
                    return new Shot(velocity, time, Path.LOB);
                }
                break;
            }
            previousTime = tick;
            previousResidual = residual;
        }
        return new Shot(lobVelocity(displacement, profile), 0, Path.FALLBACK);
    }

    public static double directFlightTicks(Vector displacement, double speed, Vector inherited) {
        // 最早达到给定距离的解作为平射飞行时间；无解时返回有界的最远搜索时间。
        double previousTime = MIN_TIME;
        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            if (launchVelocityTo(displacement, tick, inherited).length() <= speed) {
                double lower = previousTime;
                double upper = tick;
                for (int iteration = 0; iteration < ROOT_ITERATIONS; iteration++) {
                    double middle = (lower + upper) / 2;
                    if (launchVelocityTo(displacement, middle, inherited).length() > speed) {
                        lower = middle;
                    } else {
                        upper = middle;
                    }
                }
                return upper;
            }
            previousTime = tick;
        }
        return MAX_FLIGHT_TICKS;
    }

    private static Motion motion(double ticks) {
        // 等比和直接给出整 tick 的离散积分，反解扫描不再为每个候选重跑全部 tick。
        if (!Double.isFinite(ticks) || ticks < 0) {
            throw HumanErrors.invalid(HumanErrors.INVALID_FLIGHT_TIME);
        }
        double wholeTicks = Math.floor(ticks);
        double fraction = ticks - wholeTicks;
        double drag = Math.pow(AIR_DRAG, wholeTicks);
        double wholeTravel = (1 - drag) / DRAG_LOSS;
        double travel = wholeTravel + drag * fraction;
        // 不足一 tick 只插值本 tick 位移，重力与阻力仍在整 tick 结束后结算。
        double drop = GRAVITY * ((wholeTicks - wholeTravel) / DRAG_LOSS + wholeTravel * fraction);
        return new Motion(travel, drop);
    }

    private static double elevationResidual(Vector displacement, double ticks, double elevation, Vector inherited) {
        // 仰角差改写成竖直速度差，避免近垂直方向反三角函数的不稳定。
        Vector velocity = launchVelocityTo(displacement, ticks, inherited);
        double horizontal = Math.hypot(velocity.x(), velocity.z());
        return velocity.y() - horizontal * Math.tan(Math.toRadians(elevation));
    }

    private static double lobTime(Vector displacement, double start, double end, double elevation, Vector inherited) {
        // 已由扫描夹住下降段零点，用二分保持结果确定且独立于服务器随机源。
        double lower = start;
        double upper = end;
        for (int iteration = 0; iteration < ROOT_ITERATIONS; iteration++) {
            double middle = (lower + upper) / 2;
            if (elevationResidual(displacement, middle, elevation, inherited) < 0) {
                lower = middle;
            } else {
                upper = middle;
            }
        }
        return (lower + upper) / 2;
    }

    private static Vector lobVelocity(Vector displacement, LobProfile profile) {
        // 不可达目标保持沿目标的水平朝向，正下方或正上方则垂直发射。
        double horizontal = Math.hypot(displacement.x(), displacement.z());
        double speed = profile.maximumSpeed();
        if (horizontal == 0) {
            return new Vector(0, speed, 0);
        }
        double angle = Math.toRadians(profile.elevationDegrees());
        double scale = speed * Math.cos(angle) / horizontal;
        return new Vector(displacement.x() * scale, speed * Math.sin(angle), displacement.z() * scale);
    }

    private record Motion(double travel, double drop) { }

    private static Vector launchVelocityTo(Vector displacement, double ticks, Vector inherited) {
        // 先反解世界初速，再扣除射手惯性；后续原版发射仍会把同一惯性加回。
        Vector total = velocityTo(displacement, ticks);
        return new Vector(total.x() - inherited.x(), total.y() - inherited.y(), total.z() - inherited.z());
    }

    private HumanProjectileBallistics() {
        // 无状态计算器，玩家与类人生物共用。
    }
}
