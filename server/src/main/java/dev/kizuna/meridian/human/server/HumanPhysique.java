package dev.kizuna.meridian.human.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.TissueTraits;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.OriginPhysique;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

public final class HumanPhysique implements TissueTraits {
    public static final String KEY = "meridian_human:origin_physique";
    private static final double ENDURANCE = 1.20;
    private static final double BURST = 1.25;
    private static final double STRONG_BURST = 1.375;
    private static final double WEAK_BURST = 1.125;
    private static final int EXTRA_HOLD_DIVISOR = 4;

    @Override
    public String progressKey() {
        // 策略仅解释人体体质契约。
        return KEY;
    }

    public static OriginPhysique read(BodySnapshot body) {
        // 旧身体没有此字段，直接使用普通体质。
        var saved = body.getAddonProgressMap().get(KEY);
        if (saved == null) {
            return OriginPhysique.getDefaultInstance();
        }
        return unpack(saved);
    }

    private static OriginPhysique unpack(Any saved) {
        // 类型不符时保留原存档，由宿主报告损坏。
        try {
            return saved.unpack(OriginPhysique.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
        }
    }

    @Override
    public Factors factors(Any saved, Anatomy.TissueDefinition tissue) {
        // 臀肌属于步态用腿肌。膈肌随呼吸计费，不在四肢肌肉标签里。
        boolean arm = HumanLimbTempo.armMuscle(tissue);
        if (!arm && !HumanLimbTempo.legMuscle(tissue)) {
            return Factors.NORMAL;
        }
        var state = unpack(saved);
        double endurance = state.getEnduringLimbs() ? ENDURANCE : 1;
        double burst = 1;
        if (arm && state.getBurstUntilTick() > 0) {
            burst = switch (state.getArmBurst()) {
                case NORMAL -> BURST;
                case STRONG -> STRONG_BURST;
                case WEAK -> WEAK_BURST;
                case UNRECOGNIZED -> throw HumanErrors.invalid(HumanErrors.INVALID_PROGRESS);
            };
        }
        return new Factors(endurance, burst);
    }

    static long holdMillis(BodySnapshot body, long normalMillis) {
        // 已受理任务冻结该结果，后续读档不再按当前体质重算。
        if (read(body).getSlowOpening()) {
            return Math.addExact(normalMillis, Math.round((double) normalMillis / EXTRA_HOLD_DIVISOR));
        }
        return normalMillis;
    }
}
