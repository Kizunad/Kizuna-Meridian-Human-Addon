package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueRecoveryHost;
import net.minecraft.server.network.ServerPlayerEntity;

/** 恢复直接使用已认证实体和权威身体，允许在目录认领前及死体状态清理效果。 */
record HumanTechniqueRecovery(ServerPlayerEntity player, HumanPlayerBody body,
                              HumanTechniqueEffects effects) implements TechniqueRecoveryHost {
    @Override
    public BodySnapshot snapshot() {
        // 恢复调用方已在主线程结清时间，不再经 inspect 触发递归恢复。
        return body.snapshot();
    }

    @Override
    public long submitRecovery(String requestId, BodyCommand command) {
        // 身体提交保留库生成的请求、版本和原始业务失败码。
        return body.submitRecovery(requestId, command);
    }

    @Override
    public TechniqueHost.EffectCommit submitEffect(TechniqueHost.EffectSubmission submission) {
        // 与正常施放共用回执，但只允许幂等停止表现与解锁。
        HumanTechniqueEffects.validateRecovery(submission);
        return effects.submit(player, submission);
    }
}
