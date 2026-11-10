package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.server.HumanOrganRefining;
import dev.kizuna.meridian.human.server.HumanRefineRecipes;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefineIntent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 肺炼与吐纳互斥：起批先经正式结束意图停下吐纳，炼化收尾前不能重新开始。 */
public class HumanPlayerBodyRefineTest {
    private static final String FAMILY = "sample:refine";
    private static final long START = 1_000;
    private static final long BATCH = 2_000;
    private static final long WINDOW_END = 31_000;
    private static final double FREE_DENSITY = .01;
    private static final double LUNG_RATE = .03;
    private static final List<String> LUNGS = List.of("left_lung", "right_lung");
    private static final List<String> LUNG_MERIDIANS = List.of("left_lung_meridian", "right_lung_meridian");

    @Test
    public void lungRefiningEndsBreathingAndBlocksRestartUntilTaskRetires() {
        // 自动吐纳中起肺炼：先停下吐纳再取料；任务在时开始吐纳被拒，结束与收尾后恢复可用。
        HumanPlayerBody body = HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        unlock(body);
        var responses = new ArrayList<GatheringResponse>();
        body.connectGathering(0, responses::add);
        assertTrue(breath(body, 0, 1, BreathAction.BEGIN_BREATHING, true).getView().getBreathing().getActive());
        DebugQi fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
        assertEquals(FailureCode.FAILURE_CODE_UNSPECIFIED, body.debugQi(0, fill));

        HumanOrganRefining.Batch batch = body.refine(START, intent(), recipes());
        assertFalse(batch.rejection(), batch.rejected());
        assertFalse(body.breathingActive());
        assertFalse(body.breathingAutomatic());
        assertEquals(BreathAction.END_BREATHING, responses.getLast().getView().getBreathing().getAction());
        assertTrue(HumanOrganRefining.refining(body.snapshot(), "left_lung"));

        assertEquals(FailureCode.ORGAN_BUSY,
                breath(body, START, 3, BreathAction.BEGIN_BREATHING, false).getFailure().getCode());
        assertEquals(FailureCode.ORGAN_BUSY,
                breath(body, START, 4, BreathAction.SET_AUTOMATIC_BREATHING, true).getFailure().getCode());
        assertFalse(breath(body, START, 5, BreathAction.END_BREATHING, false).hasFailure());

        assertTrue(body.endRefine(START, "cast"));
        assertFalse(body.endRefine(START, "other"));
        body.settle(START + BATCH);
        assertFalse(HumanOrganRefining.refining(body.snapshot(), "left_lung"));
        assertTrue(breath(body, START + BATCH, 6, BreathAction.BEGIN_BREATHING, false)
                .getView().getBreathing().getActive());
    }

    private static GatheringResponse breath(HumanPlayerBody body, long millis, long sequence, BreathAction action,
                                            boolean automatic) {
        // 只上传公开控制意图，身份与时间由测试宿主绑定。
        BreathControl control = BreathControl.newBuilder()
                .setSequence(sequence)
                .setAction(action)
                .setAutomatic(automatic)
                .build();
        GatheringRequest request = GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("breath-" + sequence)
                .setBodyId(body.snapshot().id().value())
                .setBreath(control)
                .build();
        return body.gather(millis, request);
    }

    private static void unlock(HumanPlayerBody body) {
        // 显式建立已读条件，不靠测试宿主绕过正式授权。
        body.writeProgress(body.progress().toBuilder()
                .setQiNotesRead(true /* value */)
                .setGatheringScrollRead(true /* value */)
                .setMiddleJiaoScrollRead(true /* value */)
                .build());
    }

    private static EffectIntent intent() {
        // 与功法库交付的第一批形状一致：两肺、两条本脏经。
        OrganRefineIntent refine = OrganRefineIntent.newBuilder()
                .addAllOrganIds(LUNGS)
                .addAllMeridianIds(LUNG_MERIDIANS)
                .setRecipeFamily(FAMILY)
                .setBatchMillis(BATCH)
                .setWindowEndMillis(WINDOW_END)
                .build();
        return EffectIntent.newBuilder()
                .setCastId("cast")
                .setEffectId("organ_refine_0_0")
                .setScheduledMillis(START)
                .setOrganRefine(refine)
                .build();
    }

    private static HumanRefineRecipes recipes() {
        // 只需游炁肺配方：单位投料 1 元，产物 3% 金行。
        var input = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 1, FREE_DENSITY)));
        var output = new QiInventory(List.of(new Qi(Qi.QiAttribute.METAL, LUNG_RATE, FREE_DENSITY)));
        BodyMessages.OrganRecipe lung = BodyMessages.OrganRecipe.newBuilder()
                .setId(FAMILY + "_lung_free")
                .setVersion(1)
                .setOrganKind("human:lung")
                .setInput(BodyProtobuf.toInventory(input))
                .setOutput(BodyProtobuf.toInventory(output))
                .setDurationMillis(BATCH)
                .setMinimumRealm(BodyMessages.Realm.WANDERING_QI)
                .build();
        return new HumanRefineRecipes(namespace -> List.of(lung));
    }
}
