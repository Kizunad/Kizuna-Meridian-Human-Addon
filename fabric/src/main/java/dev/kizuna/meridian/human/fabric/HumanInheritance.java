package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.Anatomy.MeridianId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.InheritanceBook;
import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.InheritanceReceipt;
import dev.kizuna.meridian.human.server.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyExports;
import net.minecraft.server.level.ServerPlayer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 服务端传功入口。聊天只传案号，实款从已绑定的供方宿主读取。 */
public final class HumanInheritance {
    static final String PROGRESS = "human:inheritance";
    private static final List<BodyMessages.MeridianOpeningRule> RULES = HumanContent.bundle().getOpeningRulesList();

    private record Claim(String donor, CompletableFuture<Optional<InheritanceReceipt>> result) { }
    private final Map<UUID, Claim> claims = new HashMap<>();

    public static Optional<LumenExport> preview(ServerPlayer player, UUID operation, InheritanceDonor donor) {
        // 报价先结清双方的时钟，任何一方尚未就绪就保留其他剧情选项。
        var body = ready(player);
        if (body == null || book(body.snapshot()).containsReceipts(operation.toString())) {
            return Optional.empty();
        }
        var funded = donor.lookup(operation);
        if (funded.isPresent()) {
            return funded.filter(BodyExports::pending)
                    .filter(record -> record.getRecipient().equals(body.snapshot().id().value()));
        }
        return donor.inspect().flatMap(source -> quote(body.snapshot(), source, operation));
    }

    static Optional<LumenExport> quote(BodySnapshot recipient, BodySnapshot donor, UUID operation) {
        // 按人体规则目录顺序固定第一条有容量的合格脉，同一版本重复查看得到同一份报价。
        for (var rule : RULES) {
            if (eligible(recipient, rule)) {
                String target = rule.getTargetMeridianId();
                double space = BodyExports.freeVolume(recipient, new MeridianId(target), rule.getLumenVolumeGain());
                var destination = LumenExport.newBuilder()
                        .setOperationId(operation.toString())
                        .setRecipient(recipient.id().value())
                        .setRecipientRevision(recipient.revision())
                        .setTarget(target)
                        .build();
                var offer = BodyExports.quote(donor, destination, space);
                if (offer.isPresent()) {
                    return offer;
                }
            }
        }
        return Optional.empty();
    }

    public static CompletableFuture<Optional<InheritanceReceipt>> receipt(ServerPlayer player, UUID operation) {
        // 查询返回实收事实前也要确认这版玩家状态已持久化。
        var body = ready(player);
        if (body == null) {
            return empty();
        }
        var existing = book(body.snapshot()).getReceiptsMap().get(operation.toString());
        if (existing == null) {
            return empty();
        }
        return persist(player).thenApplyAsync(ignored -> Optional.of(existing), player.level().getServer());
    }

    public static CompletableFuture<Optional<InheritanceReceipt>> accept(ServerPlayer player, UUID operation,
                                                                        InheritanceDonor donor) {
        // 内存可先看到正在写盘的导出；只有供方的持久回执才能授权开脉。
        var body = ready(player);
        if (body == null) {
            return empty();
        }
        var saved = donor.lookup(operation);
        if (saved.isEmpty() && book(body.snapshot()).containsReceipts(operation.toString())) {
            return empty();
        }
        var funding = saved.or(() -> donor.inspect().flatMap(source -> quote(body.snapshot(), source, operation)));
        if (funding.isEmpty() || !funding.get().getRecipient().equals(body.snapshot().id().value())) {
            return empty();
        }
        return body.inheritance.claim(player, operation, donor, funding.orElseThrow());
    }

    private CompletableFuture<Optional<InheritanceReceipt>> claim(ServerPlayer player, UUID operation,
                                                                  InheritanceDonor donor, LumenExport funding) {
        // 同案正在等落盘时共用原交接；其他供方不能借用同号，调用者取消等待也不撤销权威操作。
        var existing = claims.get(operation);
        if (existing != null) {
            if (existing.donor().equals(funding.getDonor())) {
                return existing.result().copy();
            }
            return empty();
        }
        var server = player.level().getServer();
        var result = donor.record(funding).thenComposeAsync(paid -> settle(player, donor, paid), server)
                .thenApplyAsync(receipt -> receipt, server);
        var claim = new Claim(funding.getDonor(), result);
        claims.put(operation, claim);
        result.whenCompleteAsync((ignored, failure) -> claims.remove(operation, claim), server);
        return result.copy();
    }

    private static CompletableFuture<Optional<InheritanceReceipt>> settle(ServerPlayer player, InheritanceDonor donor,
                                                                          LumenExport funding) {
        // 盘写完后回主线程重新核实在线身份与目标，断线时留给原案重试。
        var body = ready(player);
        if (body == null || !body.snapshot().id().value().equals(funding.getRecipient())) {
            return empty();
        }
        String operation = funding.getOperationId();
        var existing = book(body.snapshot()).getReceiptsMap().get(operation);
        if (!BodyExports.pending(funding)) {
            if (existing != null && !existing.getDonor().equals(funding.getDonor())) {
                throw invalid();
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(existing));
        }
        if (existing == null) {
            if (!open(body, funding)) {
                return donor.record(BodyExports.allocate(funding, 0)).thenApply(ignored -> Optional.empty());
            }
            receive(body);
            HumanTechniqueHost.bodyChanged(player.getUUID());
        }
        var paid = book(body.snapshot()).getReceiptsOrThrow(operation);
        if (!paid.getFundingDigest().equals(digest(funding))) {
            throw invalid();
        }
        var server = player.level().getServer();
        var allocation = BodyExports.allocate(funding, paid.getAcceptedVolumeLimit());
        return persist(player).thenComposeAsync(ignored -> donor.record(allocation), server)
                .thenApply(ignored -> Optional.of(paid));
    }

    private static CompletableFuture<Void> persist(ServerPlayer player) {
        return HumanTechniqueHost.state().persistInheritance(HumanFabric.stateFile(player.level().getServer()));
    }

    private static CompletableFuture<Optional<InheritanceReceipt>> empty() {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    static boolean open(HumanPlayerBody body, LumenExport funding) {
        // 开脉事实和在途凭据作为一次活动提交；恢复只能使用其中的目标，无权重选。
        var before = body.snapshot();
        var current = book(before);
        if (current.hasOpened()) {
            if (!current.getOpened().equals(funding)) {
                throw invalid();
            }
            return true;
        }
        if (current.containsReceipts(funding.getOperationId()) || !before.id().value().equals(funding.getRecipient())
                || !BodyExports.pending(funding)) {
            throw invalid();
        }
        var target = RULES.stream().filter(rule -> rule.getTargetMeridianId().equals(funding.getTarget())).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        var rule = target.orElseThrow();
        if (!eligible(before, rule)) {
            return false;
        }
        var id = new MeridianId(funding.getTarget());
        double free = BodyExports.freeVolume(before, id, rule.getLumenVolumeGain());
        QiInventory cargo = BodyExports.credited(BodyExports.allocate(funding, free));
        if (cargo.entries().isEmpty()) {
            return false;
        }
        var lumen = before.meridianStates().stream()
                .filter(state -> state.id().equals(id)).findFirst().orElseThrow().lumenQi();
        // 预演实际入账精度；若原余额无法表示此笔增量，开脉前即可原路退款。
        try {
            BodyExports.credit(lumen, cargo);
        } catch (IllegalArgumentException | dev.kizuna.meridian.error.MeridianErrors.TransportRejected unavailable) {
            return false;
        }
        var completion = CompleteMeridian.newBuilder()
                .setMeridianId(funding.getTarget())
                .setAddedVolume(rule.getLumenVolumeGain());
        var pending = current.toBuilder().setOpened(funding).build();
        var activity = BodyActivity.newBuilder()
                .addCompletions(completion)
                .putProgress(PROGRESS, Any.pack(pending))
                .build();
        body.applyInheritance(activity);
        return true;
    }

    static void receive(HumanPlayerBody body) {
        // 同次受理连续执行，重启则在恢复身体时先执行，其他活动还不能占用扩出的空间。
        var before = body.snapshot();
        var current = book(before);
        if (!current.hasOpened()) {
            return;
        }
        var funding = current.getOpened();
        var target = new MeridianId(funding.getTarget());
        var channel = before.meridianStates().stream()
                .filter(state -> state.id().equals(target)).findFirst().orElseThrow();
        if (channel.opening().stage() != MeridianOpening.Stage.OPENED
                || current.containsReceipts(funding.getOperationId())) {
            throw invalid();
        }
        double free = BodyExports.freeVolume(before, target, 0);
        QiInventory cargo = BodyExports.credited(BodyExports.allocate(funding, free));
        var balance = BodyExports.credit(channel.lumenQi(), cargo);
        var account = QiAccountChange.newBuilder()
                .setLumenId(funding.getTarget())
                .setExpected(BodyProtobuf.toInventory(channel.lumenQi()))
                .setReplacement(BodyProtobuf.toInventory(balance));
        var receipt = InheritanceReceipt.newBuilder()
                .setTarget(funding.getTarget())
                .setDonor(funding.getDonor())
                .setFundingDigest(digest(funding))
                .setAcceptedVolumeLimit(free)
                .setCreditedQi(cargo.totalQi())
                .build();
        var settled = current.toBuilder()
                .clearOpened()
                .putReceipts(funding.getOperationId(), receipt)
                .build();
        var activity = BodyActivity.newBuilder()
                .addAccounts(account)
                .setIngestedQi(BodyProtobuf.toInventory(cargo))
                .putProgress(PROGRESS, Any.pack(settled))
                .build();
        body.applyInheritance(activity);
    }

    private static boolean eligible(BodySnapshot body, BodyMessages.MeridianOpeningRule rule) {
        // 普通开脉与受功共用境界、强度和前置脉规则；受功还要求目标完好且没有任务占用。
        if (body.vitality().dead()) {
            return false;
        }
        var id = new MeridianId(rule.getTargetMeridianId());
        var task = body.openingTask();
        if (task != null && task.active()
                && task.participants().stream().anyMatch(part -> part.meridianId().equals(id))) {
            return false;
        }
        var state = body.meridianStates().stream().filter(item -> item.id().equals(id)).findFirst();
        if (state.isEmpty() || state.get().opening().stage() != MeridianOpening.Stage.UNOPENED
                || state.get().condition() != MeridianState.Condition.INTACT) {
            return false;
        }
        var conditions = HumanOpeningPolicy.conditions(rule);
        return HumanOpeningPolicy.meetsStructuralRequirements(BodyProtobuf.toMessage(body), rule, conditions);
    }

    private static HumanPlayerBody ready(ServerPlayer player) {
        // 只认该服务器当前在线的玩家实体，同号的旧连接不能领取。
        var server = player.level().getServer();
        if (!server.isSameThread() || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return null;
        }
        var body = HumanTechniqueHost.effectBody(player.getUUID());
        if (body != null) {
            long revision = body.snapshot().revision();
            body.settle(HumanTechniqueHost.nowMillis());
            if (body.snapshot().revision() != revision) {
                HumanTechniqueHost.bodyChanged(player.getUUID());
            }
        }
        return body;
    }

    private static InheritanceBook book(BodySnapshot body) {
        // 老存档没有受功记录，只有存在的载荷才需要解析并校验类型。
        var payload = body.addonProgress().get(PROGRESS);
        if (payload == null) {
            return InheritanceBook.getDefaultInstance();
        }
        try {
            return payload.unpack(InheritanceBook.class);
        } catch (InvalidProtocolBufferException corrupt) {
            throw invalid();
        }
    }

    private static ByteString digest(LumenExport funding) {
        // 已付凭据只需保留固定长度的原约指纹与分摊上限，原始逐脉实扣可从供方复核。
        try {
            return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(funding.toByteArray()));
        } catch (NoSuchAlgorithmException missing) {
            throw new AssertionError(missing);
        }
    }

    private static IllegalArgumentException invalid() {
        // 账目不符时停止本次受理，保留原案以便核对。
        return HumanErrors.invalid(HumanErrors.INVALID_INHERITANCE);
    }
}
