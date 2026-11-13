package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.server.TechniqueBodyStamp;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 玩家身体戳：不结清就报出的版本和时钟要与随后真正结清的结果一致，结清会停在别处或失败时答不出。 */
public class HumanPlayerBodyStampTest {
    private static final long SETTLED_MILLIS = 2_000;
    private static final long LATER_MILLIS = 2_500;
    private static final double SECONDS_LEFT = 1;
    private static final long MILLIS_LEFT = 1_000;

    @Test
    public void stampMatchesWhatSettleCommits() {
        // 读数本身不提交，版本就是读数时已提交的那一版；随后结清到同一刻，身体时钟停在读数报的毫秒。
        HumanPlayerBody body = born();
        body.settle(SETTLED_MILLIS);
        long revision = body.snapshot().revision();
        TechniqueBodyStamp stamp = body.bodyStamp(LATER_MILLIS).orElseThrow();
        assertEquals(revision, stamp.revision());
        assertEquals(revision, body.snapshot().revision());
        body.settle(LATER_MILLIS);
        assertEquals(stamp.clockMillis(), body.snapshot().settledAt().elapsedMillis());
    }

    @Test
    public void stampIsWithheldOnceLifespanIsUsedUp() {
        // 寿元终点前一毫秒照常报数；终点当毫秒结清会停在终点并判死，读数答不出，死后同样答不出。
        HumanPlayerBody body = withLifespanLeft(SECONDS_LEFT);
        BodySnapshot shortened = body.snapshot();
        long end = BodyTimeAdvancement.nextLifespanBoundary(shortened, HumanPlayerBody.AGING_RATE)
                .orElseThrow()
                .elapsedMillis();
        assertEquals(MILLIS_LEFT, end - shortened.settledAt().elapsedMillis(), 1);
        assertTrue(body.bodyStamp(end - 1).isPresent());
        assertTrue(body.bodyStamp(end).isEmpty());
        body.settle(end);
        assertTrue(body.snapshot().vitality().dead());
        assertEquals(end, body.snapshot().settledAt().elapsedMillis());
        assertTrue(body.bodyStamp(end + MILLIS_LEFT).isEmpty());
    }

    @Test
    public void stampBeforeAccountedTimeIsWithheld() {
        // 早于已记账时间的结清会被拒绝，inspect 答不出身体，读数也不给。
        HumanPlayerBody body = born();
        body.settle(LATER_MILLIS);
        assertTrue(body.bodyStamp(SETTLED_MILLIS).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> body.settle(SETTLED_MILLIS));
    }

    private static HumanPlayerBody withLifespanLeft(double secondsLeft) {
        // 把寿命上限压到只剩这么多秒，按读档的方式换一具身体；年龄和已耗寿元不动。
        HumanPlayerBody born = born();
        BodyMessages.BodySnapshot saved = BodyProtobuf.toMessage(born.snapshot());
        var vitality = saved.getVitality();
        double limit = vitality.getAgeSeconds() + vitality.getSpentLifespanSeconds() + secondsLeft;
        var shortenedVitality = vitality.toBuilder()
                .setLifespanLimitSeconds(limit)
                .build();
        var shortened = saved.toBuilder()
                .setVitality(shortenedVitality)
                .build();
        return new HumanPlayerBody(BodyProtobuf.fromMessage(shortened), born.receipts(), born.releasedQi());
    }

    private static HumanPlayerBody born() {
        // 每个用例独立的玩家身份和时钟。
        return HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
    }
}
