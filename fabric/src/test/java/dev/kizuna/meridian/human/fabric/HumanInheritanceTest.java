package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class HumanInheritanceTest {
    private static final double TOLERANCE = 1e-8;

    @Test
    public void noFundsOrNoEligibleMeridianLeavesBothBodiesUntouched() {
        // 无资与本境全开的玩家分别拒绝；高一境尚未开的经脉不能拿来凑数。
        var student = born();
        var elder = born();
        var empty = BodyProtobuf.toMessage(elder.snapshot()).toBuilder();
        var emptyLumen = BodyMessages.QiInventory.getDefaultInstance();
        empty.getMeridianStatesBuilderList().forEach(state -> state.setLumenQi(emptyLumen));
        var operation = UUID.randomUUID();
        var initial = student.snapshot();
        assertTrue(HumanInheritance.quote(initial, BodyProtobuf.fromMessage(empty.build()), operation).isEmpty());
        var funded = fill(elder);
        var maximum = BodyProtobuf.toMessage(initial).toBuilder();
        for (var state : maximum.getMeridianStatesBuilderList()) {
            if (HumanOpening.minimumRealm(state.getId()) == BodyMessages.Realm.WANDERING_QI) {
                state.getOpeningBuilder().setStage(BodyMessages.MeridianOpeningStage.OPENED);
                state.getOpeningBuilder().setProgress(1);
            }
        }
        assertTrue(HumanInheritance.quote(BodyProtobuf.fromMessage(maximum.build()), funded, operation).isEmpty());
        assertEquals(initial, student.snapshot());
        assertEquals(funded, elder.snapshot());
    }

    @Test
    public void openedCheckpointCreditsExactlyOnceBeforeOtherBodyActivity() {
        // 停在扩脉已提交、真元未入账的位置，从身体字节重建，验证接收不依赖短期命令回执。
        var student = born();
        var before = student.snapshot();
        var elder = fill(born());
        var source = new InMemoryBodyStore(List.of(elder), List.of());
        var offer = HumanInheritance.quote(before, elder, UUID.randomUUID()).orElseThrow();
        var funded = source.recordExport(offer);
        assertTrue(HumanInheritance.open(student, funded));
        var opened = student.snapshot();
        assertEquals(before.totalQi(), opened.totalQi(), TOLERANCE);

        var recovered = restore(student);
        var after = recovered.snapshot();
        double loss = elder.totalQi() - source.find(elder.id()).orElseThrow().totalQi();
        assertTrue(loss > 0);
        assertEquals(before.totalQi() + loss, after.totalQi(), TOLERANCE);
        assertEquals(opened.structure(), after.structure());
        assertEquals(after, restore(recovered).snapshot());
    }

    private static HumanPlayerBody restore(HumanPlayerBody body) {
        // 新建 Store 和宿主，载入实际协议字节。
        var restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(body.snapshot()));
        return new HumanPlayerBody(restored, List.of(), QiInventory.empty());
    }

    private static dev.kizuna.meridian.core.BodySnapshot fill(HumanPlayerBody body) {
        // 用正式可信入口装填内腔，给报价提供真实且可扣的余额。
        body.completeOpening(0, "left_lung_meridian");
        var fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
        body.debugQi(0, fill);
        return body.snapshot();
    }

    private static HumanPlayerBody born() {
        // 每个供受方都使用不同身份，时钟固定在出生时刻。
        return HumanPlayerBody.born(UUID.randomUUID(), "inheritance-test", 0);
    }
}
