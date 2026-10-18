package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;

/** 可信宿主首次创建人体时使用；返回候选消息，不替宿主登记身份或保存身体。 */
public final class HumanBirth {
    public static final double SECONDS_PER_YEAR = 365.25 * 24 * 60 * 60;
    public static final double INITIAL_AGE_YEARS = 18;
    public static final double LIFESPAN_YEARS = 80;
    public static final double INITIAL_QI_PER_MERIDIAN = 0.01;

    private HumanBirth() {
        // 出生规则属于人体内容；已有身体只能恢复自己的快照，不能重套本规则。
    }

    public static byte[] candidate(BodyTemplate installedTemplate, String bodyId, TimeCursor initialTime) {
        // 宿主须先从完整注册表取得模板；身份与时钟由宿主提供，不接受客户端覆盖快照。
        if (installedTemplate == null) {
            throw HumanErrors.invalid(HumanErrors.TEMPLATE_REQUIRED);
        }
        TemplateReference reference = installedTemplate.getReference();
        if (!reference.getId().equals(HumanContent.TEMPLATE_ID) || reference.getVersion() != HumanContent.CONTENT_VERSION) {
            throw HumanErrors.invalid(HumanErrors.TEMPLATE_MISMATCH);
        }
        BodyProtobuf.fromMessage(installedTemplate);
        var body = BodySnapshot.newBuilder().setSchemaVersion(2).setId(bodyId).setTemplate(reference)
                .setStructure(installedTemplate.getStructure()).setRealm(Realm.WANDERING_QI).setRevision(0)
                .setSettledAt(initialTime)
                .setVitality(VitalityState.newBuilder().setAgeSeconds(INITIAL_AGE_YEARS * SECONDS_PER_YEAR)
                        .setLifespanLimitSeconds(LIFESPAN_YEARS * SECONDS_PER_YEAR)
                        .setSpentLifespanSeconds(0).setDead(false));

        // 各模块从健康初值创建，器官缓存与外壁为空；自然存元不等于可主动调用。
        for (PartDefinition part : installedTemplate.getStructure().getPartsList()) {
            body.addPartStates(PartState.newBuilder().setId(part.getId()).setHp(part.getMaxHp()));
        }
        for (TissueDefinition tissue : installedTemplate.getStructure().getTissuesList()) {
            body.addTissueStates(TissueState.newBuilder().setId(tissue.getId()).setHp(tissue.getMaxHp()).setFatigue(0));
        }
        for (OrganDefinition organ : installedTemplate.getStructure().getOrgansList()) {
            body.addOrganStates(OrganState.newBuilder().setId(organ.getId()).setHp(organ.getMaxHp())
                    .setBuffer(QiInventory.getDefaultInstance()));
        }
        QiInventory naturalQi = QiInventory.newBuilder().addEntries(Qi.newBuilder().setPhase(QiPhase.FREE)
                .setAttribute("meridian:none").setAmount(INITIAL_QI_PER_MERIDIAN)).build();
        for (MeridianDefinition meridian : installedTemplate.getStructure().getMeridiansList()) {
            body.addMeridianStates(MeridianState.newBuilder().setId(meridian.getId()).setLumenQi(naturalQi)
                    .setWallQi(QiInventory.getDefaultInstance()).setFatigue(0).setCondition(MeridianCondition.INTACT)
                    .setOpening(MeridianOpening.newBuilder().setStage(MeridianOpeningStage.UNOPENED).setProgress(0)));
        }

        // 复用框架完整校验再输出；候选只有由可信宿主首次保存后才成为权威身体。
        return BodyProtobuf.encodeSnapshot(BodyProtobuf.fromMessage(body.build()));
    }
}
