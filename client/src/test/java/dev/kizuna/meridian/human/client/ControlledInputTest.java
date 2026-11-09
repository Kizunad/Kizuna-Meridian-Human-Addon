package dev.kizuna.meridian.human.client;

import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlIntent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ControlledInputTest {
    private static final long SERVER_TICK = 100;
    private static final long LOCAL_TICK = 20;
    private static final long TTL = 4;
    private static final float YAW = 45;
    private static final float PITCH = -20;

    @Test
    public void mapsIntentAndStopsAfterStreamExpires() {
        // 服务器和客户端时钟不同，仍按本地四 tick 窗口映射移动与视角。
        var input = new ControlledInput();
        var intent = intent("session", 1);
        assertTrue(input.receive(intent, LOCAL_TICK));
        assertEquals(intent, input.sample(LOCAL_TICK));
        var stopped = input.sample(LOCAL_TICK + TTL);
        assertEquals(0, stopped.getForward(), 0);
        assertFalse(stopped.getJump());
        assertFalse(stopped.getSprint());
        assertEquals(YAW, stopped.getYaw(), 0);
        assertTrue(input.active());
    }

    @Test
    public void endResetsAndDoesNotReplayOldInput() {
        // 结束后旧意图重放不得重新激活，客户端输入完全归零。
        var input = new ControlledInput();
        var moving = intent("session", 1);
        input.receive(moving, LOCAL_TICK);
        var end = moving.toBuilder()
                .setSequence(2)
                .setEnded(true /* ended */)
                .build();
        assertTrue(input.receive(end, LOCAL_TICK + 1));
        assertFalse(input.active());
        assertEquals(0, input.sample(LOCAL_TICK + 1).getForward(), 0);
        assertFalse(input.receive(moving, LOCAL_TICK + 2));
    }

    @Test
    public void rejectsOutOfOrderAndInvalidIntent() {
        // 乱序、无限视角和超长期限不能延长最近一次有效输入。
        var input = new ControlledInput();
        var recent = intent("session", 2);
        assertTrue(input.receive(recent, LOCAL_TICK));
        assertFalse(input.receive(intent("session", 1), LOCAL_TICK + 1));
        var invalid = recent.toBuilder()
                .setSequence(3)
                .setYaw(Float.NaN)
                .build();
        assertFalse(input.receive(invalid, LOCAL_TICK + 1));
        assertEquals(recent, input.sample(LOCAL_TICK + 1));
        input.reset();
        assertFalse(input.active());
        assertTrue(input.receive(intent("new", 1), 0));
    }

    @Test
    public void oldEndCannotCancelNewSession() {
        // 另一会话的结束包即使迟到，也不能让新控制归还主动输入。
        var input = new ControlledInput();
        var current = intent("current", 1);
        assertTrue(input.receive(current, LOCAL_TICK));
        var foreignEnd = intent("old", 2).toBuilder()
                .setServerTick(SERVER_TICK + 1)
                .setEnded(true /* ended */)
                .build();
        assertFalse(input.receive(foreignEnd, LOCAL_TICK + 1));
        assertTrue(input.active());
        assertEquals(current, input.sample(LOCAL_TICK + 1));
    }

    @Test
    public void controlAllowsObservationAndChatButBlocksActions() {
        // 观察键全程可用，动作键只有控制结束才恢复。
        var input = new ControlledInput();
        input.receive(intent("session", 1), LOCAL_TICK);
        for (String key : new String[] {"key.chat", "key.command", "key.screenshot", "key.togglePerspective",
                "key.playerlist", "key.fullscreen"}) {
            assertTrue(key, input.allowsKey(key));
        }
        for (String key : new String[] {"key.forward", "key.attack", "key.use", "key.inventory", "key.drop",
                "key.swapOffhand", "key.hotbar.1", "key.meridian_combat.cast"}) {
            assertFalse(key, input.allowsKey(key));
        }
        input.reset();
        assertTrue(input.allowsKey("key.attack"));
    }

    private static ControlIntent intent(String session, long sequence) {
        // 一份包含行走、视角、跳和疾跑的真实跨端协议。
        return ControlIntent.newBuilder()
                .setSessionId(session)
                .setSequence(sequence)
                .setServerTick(SERVER_TICK)
                .setValidUntilTick(SERVER_TICK + TTL)
                .setForward(1)
                .setSideways(-1)
                .setYaw(YAW)
                .setPitch(PITCH)
                .setJump(true /* jump */)
                .setSprint(true /* sprint */)
                .build();
    }
}
