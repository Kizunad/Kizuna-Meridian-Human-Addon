package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenExport;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 服务端出资宿主。lookup 读取本案内存状态，record 的成功回执保证同号实扣或退款已与余额一同落盘。 */
public interface InheritanceDonor {
    Optional<BodySnapshot> inspect();
    Optional<LumenExport> lookup(UUID operation);
    CompletableFuture<LumenExport> record(LumenExport allocation);
}
