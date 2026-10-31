package dev.kizuna.meridian.human.fabric;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** 服务端从玩家实际位置与疾跑状态识别地面运动，不接收客户端自报的身体动作。 */
final class HumanMovementObserver {
    private static final int STOP_CONFIRMATION_TICKS = 2;
    private Vec3 previousPosition;
    private String dimension;
    private boolean previouslyGrounded;
    private boolean moved;
    private int stationaryTicks;
    private HumanPlayerBody.Motion previousMotion = HumanPlayerBody.Motion.IDLE;

    HumanPlayerBody.Motion sample(ServerPlayer player) {
        // 首次观测、换维度、乘坐、游泳或飞行均不能把位移当成地面肌肉做功。
        Vec3 position = player.position();
        String currentDimension = player.level().dimension().location().toString();
        boolean grounded = player.isAlive() && player.onGround() && !player.isSpectator()
                && !player.getAbilities().flying && !player.isPassenger() && !player.isSwimming() && !player.isFallFlying();
        return sample(position, currentDimension, grounded, player.isSprinting(), player.hurtTime > 0);
    }

    HumanPlayerBody.Motion sample(Vec3 position, String currentDimension, boolean grounded,
                                 boolean sprinting, boolean hurt) {
        // 位移包不保证逐 tick 到达；短暂缺包维持旧动作，连续静止三次才确认停步。
        moved = previousPosition != null && currentDimension.equals(dimension)
                && position.distanceToSqr(previousPosition) > 0.000001;
        HumanPlayerBody.Motion motion = HumanPlayerBody.Motion.IDLE;
        stationaryTicks++;
        if (previousPosition != null && currentDimension.equals(dimension) && grounded && previouslyGrounded) {
            double horizontalDistanceSquared = position.subtract(previousPosition).horizontalDistanceSqr();
            // 过滤位置浮点抖动和单 tick 大距离传送；按住疾跑但未实际移动仍为空闲。
            if (horizontalDistanceSquared > 0.000001 && horizontalDistanceSquared < 1.0 && !hurt) {
                motion = sprinting ? HumanPlayerBody.Motion.SPRINT : HumanPlayerBody.Motion.WALK;
                stationaryTicks = 0;
            } else if (horizontalDistanceSquared <= 0.000001 && !hurt
                    && stationaryTicks <= STOP_CONFIRMATION_TICKS) {
                motion = previousMotion;
            }
        }
        previousPosition = position;
        dimension = currentDimension;
        previouslyGrounded = grounded;
        previousMotion = motion;
        return motion;
    }

    boolean moved() {
        // 返回本次真实位置采样，不把鼠标转头当成移动。
        return moved;
    }
}
