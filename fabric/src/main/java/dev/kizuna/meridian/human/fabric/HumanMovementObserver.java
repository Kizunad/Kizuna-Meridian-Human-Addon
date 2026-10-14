package dev.kizuna.meridian.human.fabric;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/** 服务端从玩家实际位置与疾跑状态识别地面运动，不接收客户端自报的身体动作。 */
final class HumanMovementObserver {
    private Vec3d previousPosition;
    private String dimension;
    private boolean previouslyGrounded;

    HumanPlayerBody.Motion sample(ServerPlayerEntity player) {
        // 首次观测、换维度、乘坐、游泳或飞行均不能把位移当成地面肌肉做功。
        Vec3d position = player.getPos();
        String currentDimension = player.getWorld().getRegistryKey().getValue().toString();
        boolean grounded = player.isAlive() && player.isOnGround() && !player.isSpectator()
                && !player.getAbilities().flying && !player.hasVehicle() && !player.isSwimming() && !player.isFallFlying();
        HumanPlayerBody.Motion motion = HumanPlayerBody.Motion.IDLE;
        if (previousPosition != null && currentDimension.equals(dimension) && grounded && previouslyGrounded) {
            double horizontalDistanceSquared = position.subtract(previousPosition).horizontalLengthSquared();
            // 过滤位置浮点抖动和单 tick 大距离传送；按住疾跑但未实际移动仍为空闲。
            if (horizontalDistanceSquared > 0.000001 && horizontalDistanceSquared < 1.0 && player.hurtTime == 0) {
                motion = player.isSprinting() ? HumanPlayerBody.Motion.SPRINT : HumanPlayerBody.Motion.WALK;
            }
        }
        previousPosition = position;
        dimension = currentDimension;
        previouslyGrounded = grounded;
        return motion;
    }
}
