package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 人体加载只补空标签，不以模板覆盖已改造身体或非空冲突。 */
public final class HumanModuleTagMigrationTest {
    private static final double EXPANDED_VOLUME_FACTOR = 2;

    @Test
    public void restoresTagsWithoutReplacingActualMeridianGeometryOrReceipts() {
        // 扩大的实际内腔应与资源、时间、进度和成功回执一起保留。
        var initial = HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        var message = BodyProtobuf.toMessage(initial.snapshot());
        var structure = message.getStructure().toBuilder();
        structure.getTissuesBuilderList().forEach(module -> module.clearTags());
        structure.getOrgansBuilderList().forEach(module -> module.clearTags());
        structure.getMeridiansBuilderList().forEach(module -> module.clearTags());
        var meridian = structure.getMeridians(0);
        var expanded = meridian.toBuilder()
                .setLumenVolume(meridian.getLumenVolume() * EXPANDED_VOLUME_FACTOR)
                .build();
        structure.setMeridians(0, expanded);
        var legacy = BodyProtobuf.fromMessage(message.toBuilder().setStructure(structure).build());
        var receipts = initial.receipts();
        var store = new InMemoryBodyStore(List.of(legacy), receipts, initial.releasedQi());
        var restored = new HumanPlayerBody(legacy, store, Set.of());
        var actual = BodyProtobuf.toMessage(restored.snapshot());
        assertEquals(0, HumanModuleTagMigration.missingTags(restored.snapshot()).getModulesCount());
        assertEquals(expanded.getLumenVolume(), actual.getStructure().getMeridians(0).getLumenVolume(), 0);
        assertEquals(BodyProtobuf.toMessage(legacy), actual.toBuilder()
                .setStructure(structure)
                .setRevision(legacy.revision())
                .build());
        assertEquals(receipts.size() + 1, restored.receipts().size());
    }

    @Test
    public void conflictingExistingTagsRejectLoadWithoutModifyingAuthoritativeBody() {
        // 非空差异必须由授权命令明确拒绝，不允许加载适配器直接覆盖。
        var initial = HumanPlayerBody.born(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        var message = BodyProtobuf.toMessage(initial.snapshot());
        var structure = message.getStructure().toBuilder();
        var conflicting = structure.getMeridians(0).toBuilder().clearTags().addTags("sample:conflict").build();
        structure.setMeridians(0, conflicting);
        var saved = BodyProtobuf.fromMessage(message.toBuilder().setStructure(structure).build());
        var receipts = initial.receipts();
        var store = new InMemoryBodyStore(List.of(saved), receipts, initial.releasedQi());
        var failure = assertThrows(HumanErrors.HostRejected.class,
                () -> new HumanPlayerBody(saved, store, Set.of()));
        assertEquals(FailureCode.INVALID_REQUEST, failure.code());
        assertEquals(saved, store.find(saved.id()).orElseThrow());
        assertEquals(receipts, store.receipts());
    }
}
