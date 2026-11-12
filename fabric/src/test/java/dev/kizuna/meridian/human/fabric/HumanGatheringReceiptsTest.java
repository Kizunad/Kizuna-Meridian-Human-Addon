package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringView;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 吐纳回执不带身体；只是余量在变的推送每 5 tick 最多一份，攒着的最新一份到时补发。 */
public final class HumanGatheringReceiptsTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final int FIRST_TICK = 100;
    private static final int NEXT_TICK = FIRST_TICK + 1;
    private static final int DUE_TICK = FIRST_TICK + HumanGatheringReceipts.INTERVAL_TICKS;
    private static final double AIR_LITERS = 1.5;
    private static final double MORE_AIR_LITERS = 2;

    @Test
    public void amountOnlyPushWaitsFiveTicksAndCarriesNoBody() {
        // 第一份立刻发且去掉身体；下一 tick 只是肺里空气变了的推送攒着，满 5 tick 补发最新一份。
        var receipts = new HumanGatheringReceipts();
        GatheringResponse first = receipts.offer(PLAYER, push(AIR_LITERS), FIRST_TICK).orElseThrow();
        assertFalse(first.getView().hasBody());
        assertTrue(receipts.offer(PLAYER, push(MORE_AIR_LITERS), NEXT_TICK).isEmpty());
        assertTrue(receipts.due(PLAYER, DUE_TICK - 1).isEmpty());
        GatheringResponse flushed = receipts.due(PLAYER, DUE_TICK).orElseThrow();
        assertEquals(MORE_AIR_LITERS, flushed.getView().getBreathing().getAirLiters(), 0);
        assertFalse(flushed.getView().hasBody());
    }

    @Test
    public void failureReplyKeepsTheHeldPush() {
        // 只带失败码的回复立刻发，但不换客户端手里的吐纳：攒着的推送照旧到时补发。
        var receipts = new HumanGatheringReceipts();
        receipts.offer(PLAYER, push(AIR_LITERS), FIRST_TICK);
        receipts.offer(PLAYER, push(MORE_AIR_LITERS), NEXT_TICK);
        assertTrue(receipts.offer(PLAYER, failure(), NEXT_TICK).isPresent());
        GatheringResponse flushed = receipts.due(PLAYER, DUE_TICK).orElseThrow();
        assertEquals(MORE_AIR_LITERS, flushed.getView().getBreathing().getAirLiters(), 0);
    }

    private static GatheringResponse push(double airLiters) {
        // 宿主自己推的吐纳回执，带着身体，阶段不变、只有肺里空气在变。
        BodySnapshot body = BodySnapshot.newBuilder()
                .setId(PLAYER.toString())
                .build();
        BreathingView breathing = BreathingView.newBuilder()
                .setActive(true /* active */)
                .setAirLiters(airLiters)
                .build();
        GatheringView view = GatheringView.newBuilder()
                .setBody(body)
                .setBreathing(breathing)
                .build();
        return GatheringResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(HumanGatheringReceipts.STATE_REQUEST_ID)
                .setView(view)
                .build();
    }

    private static GatheringResponse failure() {
        // 对客户端请求的拒绝，只带失败码。
        RequestFailure failure = RequestFailure.newBuilder()
                .setCode(FailureCode.ACCESS_DENIED)
                .build();
        return GatheringResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setFailure(failure)
                .build();
    }
}
