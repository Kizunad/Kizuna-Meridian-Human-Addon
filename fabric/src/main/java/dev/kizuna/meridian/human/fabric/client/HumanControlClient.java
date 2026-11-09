package dev.kizuna.meridian.human.fabric.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.human.client.ControlledInput;
import dev.kizuna.meridian.human.fabric.HumanChannels;
import dev.kizuna.meridian.human.fabric.mixin.ControlInputAccess;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlIntent;
import dev.kizuna.meridian.human.server.HumanErrors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** 独立受控模式只接管输入，不依赖战斗 UI 的架势、键位或表现。 */
public final class HumanControlClient implements ClientModInitializer {
    private static final ControlledInput input = new ControlledInput();
    private static long tick;

    @Override
    public void onInitializeClient() {
        // Framework 通道只接收本玩家的有界意图，网络回调在客户端线程执行。
        ClientPlayNetworking.registerGlobalReceiver(HumanChannels.CONTROL.type(), (packet, context) -> {
            if (!HumanChannels.CONTROL.accepts(packet)) {
                return;
            }
            try {
                ControlIntent intent = ControlIntent.parseFrom(packet.bytes());
                boolean wasActive = input.active();
                if (input.receive(intent, tick) && (intent.getEnded() || !wasActive)) {
                    release();
                    var gui = Minecraft.getInstance().gui;
                    if (input.active() && gui.screen() instanceof AbstractContainerScreen<?>) {
                        gui.setScreen(null);
                    }
                }
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(HumanErrors.INVALID_CONTROL_INTENT_PROTOCOL, invalid);
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> tick++);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            input.reset();
            tick = 0;
            release();
        });
    }

    public static boolean active() {
        // 所有客户端门禁读取同一个模式身份。
        return input.active();
    }

    public static ControlIntent sample() {
        // 本地 tick 超时由纯输入状态机统一处理。
        return input.sample(tick);
    }

    public static boolean allows(KeyMapping key) {
        // 只放行聊天和命令键，暂停由原版 ESC 菜单直接处理。
        var options = Minecraft.getInstance().options;
        return !active() || key == options.keyChat || key == options.keyCommand;
    }

    private static void release() {
        // 结束当帧复位按键、移动向量和疾跑，不等待下一次键盘事件。
        KeyMapping.releaseAll();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.input.keyPresses = Input.EMPTY;
            ((ControlInputAccess) player.input).meridian$moveVector(Vec2.ZERO);
            player.setSprinting(false /* sprinting */);
            player.setJumping(false /* jumping */);
        }
    }
}
