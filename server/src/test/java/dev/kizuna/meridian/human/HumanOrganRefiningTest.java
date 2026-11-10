package dev.kizuna.meridian.human;

import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import dev.kizuna.meridian.human.pb.OrganRefineMessages.OrganRefineState;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.human.server.HumanCultivationNumbers;
import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.human.server.HumanOrganRefining;
import dev.kizuna.meridian.human.server.HumanRefineRecipes;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.CompleteMeridian;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.QiAccountChange;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.AdvanceTime;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.ActionCatalog;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.MeridianOpeningCatalog;
import dev.kizuna.meridian.server.OrganRecipeCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefineIntent;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.*;

/** 五脏炼化经真实 Framework 事务执行：按缓存与吞吐取料、按境界炼化率送回、余量释放且总账守恒。 */
public class HumanOrganRefiningTest {
    private static final String FAMILY = "sample:refine";
    private static final long START = 1_000;
    private static final long BATCH = 2_000;
    private static final long WINDOW_END = 31_000;
    private static final double FREE_DENSITY = .01;
    private static final double GAS_DENSITY = .1;
    private static final double LUNG_RATE = .03;
    private static final double SPLEEN_RATE = .03;
    private static final double HEART_RATE = .075;
    private static final double OPENED_VOLUME = 1625;
    private static final double SOURCE_QI = 10;
    private static final double BATCH_QI = 2;
    private static final double FREE_BUFFER_QI = 1;
    private static final double TOLERANCE = 1e-9;
    private static final int PROTOCOL_VERSION = 1;
    private static final double REAL_TIME = 1;
    private static final List<String> LUNGS = List.of("left_lung", "right_lung");
    private static final List<String> METAL_MERIDIANS = List.of("left_lung_meridian", "right_lung_meridian",
            "left_large_intestine_meridian", "right_large_intestine_meridian");
    private static final List<String> EARTH_MERIDIANS = List.of("left_spleen_meridian", "right_spleen_meridian",
            "left_stomach_meridian", "right_stomach_meridian");

    @Test
    public void batchIntakeIsBoundedByThroughputAndProductReturnsAtRealmRate() {
        // 每侧肺按 1 元/秒 × 2 秒取料，只从同侧本脏经取；加工完成后 3% 金行产物送回，其余释放到环境。
        Host host = new Host(body(Realm.WANDERING_QI));
        host.open(METAL_MERIDIANS);
        host.set("left_lung_meridian", SOURCE_QI);
        host.set("right_lung_meridian", SOURCE_QI);
        double before = host.total();
        host.advance(START);
        double source = lumen(host.body(), "left_lung_meridian");
        double fu = lumen(host.body(), "left_large_intestine_meridian");
        HumanOrganRefining.Batch first = host.batch(intent(LUNGS, METAL_MERIDIANS, 0));
        assertFalse(first.rejection(), first.rejected());
        for (String lung : LUNGS) {
            assertEquals(BATCH_QI, processInput(host.body(), lung), TOLERANCE);
        }
        assertEquals(source - BATCH_QI, lumen(host.body(), "left_lung_meridian"), TOLERANCE);
        assertEquals(fu, lumen(host.body(), "left_large_intestine_meridian"), 0);
        assertTrue(HumanOrganRefining.refining(host.body(), "left_lung"));

        // 同一批重复交付不再取料，也不提交空活动。
        assertFalse(host.batch(intent(LUNGS, METAL_MERIDIANS, 0)).changesBody());

        double releasedBefore = host.store.environmentQi().totalQi();
        host.advance(START + BATCH);
        host.cultivate();
        double product = BATCH_QI * LUNG_RATE;
        for (String meridian : List.of("left_lung_meridian", "right_lung_meridian")) {
            assertEquals(product, element(host.body(), meridian, Qi.QiAttribute.METAL), TOLERANCE);
        }
        double released = LUNGS.size() * BATCH_QI * (1 - LUNG_RATE);
        assertEquals(released, host.store.environmentQi().totalQi() - releasedBefore, TOLERANCE);
        assertEquals(before, host.total() + host.store.environmentQi().totalQi(), TOLERANCE);
    }

    @Test
    public void intakeStopsAtFreeBufferVolume() {
        // 本脏缓存只剩一半空间时，投料按体积截断，不能借吞吐上限塞满缓存。
        Host host = new Host(body(Realm.WANDERING_QI));
        host.open(METAL_MERIDIANS);
        host.set("left_lung_meridian", SOURCE_QI + FREE_BUFFER_QI);
        host.moveToOrgan("left_lung_meridian", "left_lung", FREE_BUFFER_QI);
        host.advance(START);
        double source = lumen(host.body(), "left_lung_meridian");
        host.batch(intent(List.of("left_lung"), METAL_MERIDIANS, 0));
        assertEquals(FREE_BUFFER_QI, processInput(host.body(), "left_lung"), TOLERANCE);
        assertEquals(FREE_BUFFER_QI, buffer(host.body(), "left_lung"), TOLERANCE);
        assertEquals(source - FREE_BUFFER_QI, lumen(host.body(), "left_lung_meridian"), TOLERANCE);
    }

    @Test
    public void interruptedBatchFinishesButNoNewBatchStarts() {
        // 打断后已在加工的一批照常完成并送回，之后的批次不再取料，器官随即回到空闲。
        Host host = new Host(body(Realm.WANDERING_QI));
        host.open(METAL_MERIDIANS);
        host.set("left_lung_meridian", SOURCE_QI);
        double before = host.total();
        host.advance(START);
        host.batch(intent(List.of("left_lung"), METAL_MERIDIANS, 0));
        Optional<BodyActivity> ended = HumanOrganRefining.end(host.body(), "cast");
        assertTrue(ended.isPresent());
        host.apply(ended.get());
        assertTrue(HumanOrganRefining.refining(host.body(), "left_lung"));
        assertTrue(HumanOrganRefining.state(host.body()).getTasks(0).getCastEnded());

        host.advance(START + BATCH);
        assertTrue(HumanCultivation.needsAdvance(host.body()));
        host.cultivate();
        assertFalse(HumanOrganRefining.refining(host.body(), "left_lung"));
        assertNull(organ(host.body(), "left_lung").process());
        assertEquals(BATCH_QI * LUNG_RATE,
                element(host.body(), "left_lung_meridian", Qi.QiAttribute.METAL), TOLERANCE);
        assertEquals(before, host.total() + host.store.environmentQi().totalQi(), TOLERANCE);
        assertFalse(HumanCultivation.needsAdvance(host.body()));
    }

    @Test
    public void windowEndRetiresTaskWithoutCastEnd() {
        // 结束通知丢失时，起批窗口终点之后的空闲任务也会自行移除，不长期占用器官。
        Host host = new Host(body(Realm.WANDERING_QI));
        host.open(METAL_MERIDIANS);
        host.set("left_lung_meridian", SOURCE_QI);
        host.advance(WINDOW_END - BATCH);
        host.batch(intent(List.of("left_lung"), METAL_MERIDIANS, 0));
        host.advance(WINDOW_END);
        host.cultivate();
        assertEquals(0, HumanOrganRefining.state(host.body()).getTasksCount());
    }

    @Test
    public void spleenRefiningPausesReserveDeposit() {
        // 脾炼期间经脉再满也不起中焦储备批次；任务收尾后储备照常恢复。
        Host host = new Host(body(Realm.WANDERING_QI));
        host.open(EARTH_MERIDIANS);
        host.fillAll();
        host.advance(START);
        host.batch(intent(List.of("spleen"), EARTH_MERIDIANS, 0));
        assertEquals(FREE_BUFFER_QI, processInput(host.body(), "spleen"), TOLERANCE);
        host.advance(START + BATCH);
        host.cultivate();
        assertNull(organ(host.body(), "spleen").process());
        assertFalse(reserveProcessing(host.body()));
        assertEquals(FREE_BUFFER_QI * SPLEEN_RATE, element(host.body(), "left_spleen_meridian", Qi.QiAttribute.EARTH)
                + element(host.body(), "right_spleen_meridian", Qi.QiAttribute.EARTH), TOLERANCE);

        host.apply(HumanOrganRefining.end(host.body(), "cast").orElseThrow());
        assertFalse(HumanOrganRefining.refining(host.body(), "spleen"));
        host.advance(START + BATCH + HumanCultivationNumbers.STEP_MILLIS);
        host.cultivate();
        assertTrue(reserveProcessing(host.body()));
    }

    @Test
    public void unavailableOrganMissingPathAndRealmRecipeAreRejected() {
        // 本脏伤、本脏经没绑、境界没有对应配方都给出各自原因，不改动身体。
        Host host = new Host(body(Realm.WANDERING_QI));
        host.open(METAL_MERIDIANS);
        host.advance(START);
        long revision = host.body().revision();
        List<String> fuOnly = List.of("left_large_intestine_meridian", "right_large_intestine_meridian");
        assertEquals(HumanErrors.REFINE_PATH_MISSING, host.batch(intent(LUNGS, fuOnly, 0)).rejection());
        assertEquals(HumanErrors.REFINE_RECIPE_MISSING,
                host.batch(intent(List.of("heart"), List.of("left_heart_meridian"), 0)).rejection());
        Host injured = new Host(withOrganHp(body(Realm.WANDERING_QI), "left_lung", 0));
        injured.advance(START);
        assertEquals(HumanErrors.REFINE_ORGAN_UNAVAILABLE,
                injured.batch(intent(List.of("left_lung"), METAL_MERIDIANS, 0)).rejection());
        assertFalse(injured.batch(intent(LUNGS, METAL_MERIDIANS, 0)).rejected());
        assertEquals(revision, host.body().revision());
    }

    @Test
    public void trueQiBodyUsesGasRecipe() {
        // 真炁身体按气态配方炼心，投料只取气态无属性真元。
        Host host = new Host(body(Realm.TRUE_QI));
        host.open(List.of("left_heart_meridian", "right_heart_meridian"));
        host.set("left_heart_meridian", SOURCE_QI, GAS_DENSITY);
        host.advance(START);
        HumanOrganRefining.Batch batch = host.batch(intent(List.of("heart"),
                List.of("left_heart_meridian", "right_heart_meridian"), 0));
        assertFalse(batch.rejection(), batch.rejected());
        assertEquals(BATCH_QI, processInput(host.body(), "heart"), TOLERANCE);
        host.advance(START + BATCH);
        host.cultivate();
        assertEquals(BATCH_QI * HEART_RATE,
                element(host.body(), "left_heart_meridian", Qi.QiAttribute.FIRE)
                        + element(host.body(), "right_heart_meridian", Qi.QiAttribute.FIRE), TOLERANCE);
    }

    private static HumanRefineRecipes recipes() {
        // 测试配方族按器官种类与相态各一条，产物率取设计表的游炁肺、脾与真炁心。
        return new HumanRefineRecipes(namespace -> List.of(
                recipe("lung_free", "human:lung", FREE_DENSITY, Qi.QiAttribute.METAL, LUNG_RATE),
                recipe("spleen_free", "human:spleen", FREE_DENSITY, Qi.QiAttribute.EARTH, SPLEEN_RATE),
                recipe("heart_gas", "human:heart", GAS_DENSITY, Qi.QiAttribute.FIRE, HEART_RATE)));
    }

    private static BodyMessages.OrganRecipe recipe(String suffix, String kind, double density,
                                                   Qi.QiAttribute attribute, double rate) {
        // 配方只声明单位投料与产物；实际批量由宿主按缓存与吞吐换算。
        var input = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 1, density)));
        var output = new QiInventory(List.of(new Qi(attribute, rate, density)));
        BodyMessages.Realm minimum = BodyMessages.Realm.WANDERING_QI;
        if (density >= GAS_DENSITY) {
            minimum = BodyMessages.Realm.TRUE_QI;
        }
        return BodyMessages.OrganRecipe.newBuilder()
                .setId(FAMILY + "_" + suffix)
                .setVersion(1)
                .setOrganKind(kind)
                .setInput(BodyProtobuf.toInventory(input))
                .setOutput(BodyProtobuf.toInventory(output))
                .setDurationMillis(BATCH)
                .setMinimumRealm(minimum)
                .build();
    }

    private static EffectIntent intent(List<String> organs, List<String> meridians, int index) {
        // 与功法库交付的形状一致：起手绑定的器官与经脉、配方族、批次间隔和窗口终点。
        OrganRefineIntent refine = OrganRefineIntent.newBuilder()
                .addAllOrganIds(organs)
                .addAllMeridianIds(meridians)
                .setRecipeFamily(FAMILY)
                .setBatchMillis(BATCH)
                .setWindowEndMillis(WINDOW_END)
                .build();
        return EffectIntent.newBuilder()
                .setCastId("cast")
                .setEffectId("organ_refine_0_" + index)
                .setScheduledMillis(START + BATCH * index)
                .setOrganRefine(refine)
                .build();
    }

    private static BodySnapshot body(Realm realm) {
        // 出生身体换成目标境界，其余结构与状态保持人体模板原样。
        BodyMessages.TimeCursor start = BodyMessages.TimeCursor.newBuilder()
                .setDomain("test:world")
                .setEpoch("epoch")
                .setElapsedMillis(0)
                .build();
        BodySnapshot born = BodyProtobuf.decodeSnapshot(HumanBirth.candidate(HumanContent.bundle().getTemplates(0),
                "refine-test", start));
        return new BodySnapshot(born.schemaVersion(), born.id(), born.template(), born.structure(), realm,
                born.partStates(), born.tissueStates(), born.organStates(), born.meridianStates(), born.transits(),
                born.vitality(), born.settledAt(), born.revision(), born.actions(), born.openingTask(),
                born.addonProgress(), born.modifiers());
    }

    private static BodySnapshot withOrganHp(BodySnapshot body, String organId, double hp) {
        // 只改一个器官的 HP，模拟成对的脏一侧重伤。
        List<AnatomyState.Organ> organs = body.organStates().stream()
                .map(organ -> organ.id().value().equals(organId)
                        ? new AnatomyState.Organ(organ.id(), hp, organ.buffer(), organ.process(), organ.fatigueJoules())
                        : organ)
                .toList();
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), organs, body.meridianStates(), body.transits(),
                body.vitality(), body.settledAt(), body.revision(), body.actions(), body.openingTask(),
                body.addonProgress(), body.modifiers());
    }

    private static AnatomyState.Organ organ(BodySnapshot body, String organId) {
        // 器官状态与构型一一对应。
        return body.organStates().stream()
                .filter(organ -> organ.id().value().equals(organId))
                .findFirst()
                .orElseThrow();
    }

    private static double processInput(BodySnapshot body, String organId) {
        // 加工中的真实原料保存在 Framework 的加工记录里。
        return organ(body, organId).process().input().totalQi();
    }

    private static double buffer(BodySnapshot body, String organId) {
        // 器官缓存余额。
        return organ(body, organId).buffer().totalQi();
    }

    private static MeridianState meridian(BodySnapshot body, String meridianId) {
        // 经脉状态与构型一一对应。
        return body.meridianStates().stream()
                .filter(item -> item.id().value().equals(meridianId))
                .findFirst()
                .orElseThrow();
    }

    private static double lumen(BodySnapshot body, String meridianId) {
        // 经脉内腔余额。
        return meridian(body, meridianId).lumenQi().totalQi();
    }

    private static double element(BodySnapshot body, String meridianId, Qi.QiAttribute attribute) {
        // 送回的本行产物先进内腔、满了进外壁，两处合计；无属性真元不计入。
        MeridianState meridian = meridian(body, meridianId);
        return QiInventory.combine(List.of(meridian.lumenQi(), meridian.wallQi())).entries().stream()
                .filter(qi -> qi.attribute().equals(attribute))
                .mapToDouble(Qi::amount)
                .sum();
    }

    private static boolean reserveProcessing(BodySnapshot body) {
        // 中焦储备是否有正在进行的入库批次。
        return HumanCultivation.state(body).getReserveProcessing();
    }

    private static final class Host {
        final InMemoryBodyStore store;
        final BodyCommandHandler<String> handler;
        final BodyId id;
        final HumanRefineRecipes recipes = recipes();
        long now;

        Host(BodySnapshot body) {
            // 从合法快照起步，所有取料与送回都经过真实 handler 验证守恒。
            id = body.id();
            store = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty());
            handler = new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                    (caller, command, snapshot) -> true, (caller, snapshot) -> advancePlan(snapshot),
                    store, (caller, receipt, before, after) -> {}, new OrganRecipeCatalog(List.of()),
                    (caller, snapshot, plan) -> Optional.empty(), new ActionCatalog(List.of()),
                    new MeridianOpeningCatalog(List.of()), caller -> true);
        }

        private TimeAdvancePlan advancePlan(BodySnapshot snapshot) {
            // 推进到测试设定的当前时刻，生理时间与模拟时间同速。
            var target = BodyProtobuf.toTime(snapshot.settledAt()).toBuilder()
                    .setElapsedMillis(now)
                    .build();
            return TimeAdvancePlan.newBuilder()
                    .setTarget(target)
                    .setBiologicalSecondsPerSimulationSecond(REAL_TIME)
                    .setMode(TimeAdvanceMode.ACTIVE)
                    .build();
        }

        BodySnapshot body() {
            // 每次读取最新事务结果。
            return store.find(id).orElseThrow();
        }

        RequestEnvelope request(BodyCommand.Builder command) {
            // 每个请求独立身份，按当前版本提交。
            var addressed = command.setBodyId(id.value())
                    .setExpectedRevision(body().revision());
            return RequestEnvelope.newBuilder()
                    .setProtocolVersion(PROTOCOL_VERSION)
                    .setRequestId(java.util.UUID.randomUUID().toString())
                    .setBodyCommand(addressed)
                    .build();
        }

        void apply(BodyActivity plan) {
            // 活动必须被 Framework 接受，失败时打印回执。
            var command = BodyCommand.newBuilder()
                    .setExecuteActivity(plan);
            var response = MeridianProtocol.decodeResponse(handler.handleActivity("test",
                    request(command).toByteArray()));
            assertTrue(response.toString(), response.hasCommandCommitted());
        }

        void commit(BodyCommand.Builder command) {
            // 通用命令同样经过授权和唯一 Store。
            var response = MeridianProtocol.decodeResponse(handler.handle("test", request(command).toByteArray()));
            assertTrue(response.toString(), response.hasCommandCommitted());
        }

        HumanOrganRefining.Batch batch(EffectIntent effect) {
            // 与宿主一样：接受且改变身体时提交活动。
            HumanOrganRefining.Batch batch = HumanOrganRefining.batch(body(), effect, recipes);
            if (batch.changesBody()) {
                apply(batch.activity());
            }
            return batch;
        }

        void advance(long millis) {
            // 推进身体时钟，让 Framework 结算到期加工与被动流动。
            now = millis;
            var command = BodyCommand.newBuilder()
                    .setAdvanceTime(AdvanceTime.getDefaultInstance());
            commit(command);
        }

        void cultivate() {
            // 生理边界的周期推进：送回炼化产物并结算储备。
            apply(HumanCultivation.update(body(), null));
        }

        void open(List<String> meridians) {
            // 只跳过开脉等待，扩容仍走正常完成算子。
            var plan = BodyActivity.newBuilder();
            for (String meridian : meridians) {
                var completion = CompleteMeridian.newBuilder()
                        .setMeridianId(meridian)
                        .setAddedVolume(OPENED_VOLUME)
                        .build();
                plan.addCompletions(completion);
            }
            apply(plan.build());
        }

        void set(String meridianId, double amount) {
            // 游离态基准密度下精确设置一条经脉内腔。
            set(meridianId, amount, FREE_DENSITY);
        }

        void set(String meridianId, double amount, double density) {
            // 管理员精确设置一条经脉内腔的无属性真元。
            var debug = DebugQi.newBuilder()
                    .setMode(DebugQiMode.SET_INVENTORY)
                    .setMeridianId(meridianId)
                    .setAmount(amount)
                    .setDensity(density)
                    .build();
            commit(BodyCommand.newBuilder().setDebugQi(debug));
        }

        void fillAll() {
            // 全身内腔注满，使中焦储备满足入库阈值。
            var debug = DebugQi.newBuilder()
                    .setMode(DebugQiMode.FILL_LUMENS)
                    .build();
            commit(BodyCommand.newBuilder().setDebugQi(debug));
        }

        void moveToOrgan(String meridianId, String organId, double amount) {
            // 从经脉内腔搬一份无属性真元进器官缓存，预先占用缓存体积。
            BodySnapshot body = body();
            QiInventory lumen = meridian(body, meridianId).lumenQi();
            QiInventory cargo = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, amount, FREE_DENSITY)));
            QiInventory buffer = organ(body, organId).buffer();
            var source = QiAccountChange.newBuilder()
                    .setLumenId(meridianId)
                    .setExpected(BodyProtobuf.toInventory(lumen))
                    .setReplacement(BodyProtobuf.toInventory(QiAccounting.debit(lumen, cargo)));
            var target = QiAccountChange.newBuilder()
                    .setOrganId(organId)
                    .setExpected(BodyProtobuf.toInventory(buffer))
                    .setReplacement(BodyProtobuf.toInventory(QiAccounting.credit(buffer, cargo)));
            var transfer = BodyActivity.newBuilder()
                    .addAccounts(source)
                    .addAccounts(target)
                    .build();
            apply(transfer);
        }

        double total() {
            // 身体内全部真元：经脉内腔与外壁、器官缓存与加工原料、在途货物。
            BodySnapshot body = body();
            double meridians = body.meridianStates().stream()
                    .mapToDouble(item -> item.lumenQi().totalQi() + item.wallQi().totalQi())
                    .sum();
            double buffers = body.organStates().stream()
                    .mapToDouble(item -> item.buffer().totalQi())
                    .sum();
            double processes = body.organStates().stream()
                    .filter(item -> item.process() != null)
                    .mapToDouble(item -> item.process().input().totalQi())
                    .sum();
            double transits = body.transits().stream()
                    .mapToDouble(item -> item.cargo().totalQi())
                    .sum();
            return meridians + buffers + processes + transits;
        }
    }
}
