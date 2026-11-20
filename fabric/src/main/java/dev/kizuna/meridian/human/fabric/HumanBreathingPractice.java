package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueBreathingStatuses;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice;
import dev.kizuna.meridian.technique.server.TechniqueCastEndReason;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 起式、硬控与原版清效都在玩家所属服务器线程结清同一身体的运功。 */
final class HumanBreathingPractice {
    private HumanBreathingPractice() {
        // 连接和身体仍由原有宿主管理。
    }

    static void initialize(Supplier<MinecraftServer> server) {
        // 目录没有运功式时不为普通完式反复查询身体。
        TechniqueFabric.SERVICE_STARTED.register(service -> {
            if (service.registry().definitions().stream().noneMatch(TechniqueDefinition::hasMaintainedBreathing)) {
                return;
            }
            service.addCastCheck((id, request) -> rejection(service, id, request));
            service.addCastEndListener(event -> completed(server.get(), service, event));
            service.addStaggerListener(event -> {
                if (event.remainingMillis() > 0 && event.kind() != TechniqueService.StaggerKind.HIT) {
                    end(server.get().getPlayerList().getPlayer(event.entityUuid()));
                }
            });
        });
        TechniqueBreathingStatuses.REMOVED.register((player, effectId) -> {
            var body = HumanTechniqueHost.effectBody(player.getUUID());
            if (body != null && TechniqueBreathingPractice.active(body.snapshot())
                    .filter(active -> effectId.equals(active.getDefinition().getEffectId())).isPresent()) {
                end(player);
            }
        });
    }

    private static Optional<String> rejection(TechniqueService service, UUID id, CastRequest request) {
        // 只有运功式另查身体与未开内腔，普通式仍由原绑定、知识和起式费用校验。
        var definition = service.registry().find(request.getTechnique());
        if (definition.isEmpty() || !definition.get().hasMaintainedBreathing()) {
            return Optional.empty();
        }
        var body = HumanTechniqueHost.effectBody(id);
        if (body == null) {
            return Optional.of("此身无法运转这门吐纳法");
        }
        body.settle(HumanTechniqueHost.nowMillis());
        var snapshot = body.snapshot();
        if (TechniqueBreathingPractice.active(snapshot).isPresent()) {
            return Optional.of("已在运功");
        }
        if (!TechniqueBreathingPractice.canPay(snapshot)) {
            return Optional.of("经脉内没有真元");
        }
        return Optional.empty();
    }

    private static void completed(MinecraftServer server, TechniqueService service, TechniqueCastEnded event) {
        // 只有前台正常完式且身份吻合时，才进入后台并挂无限图标。
        // 还须确认玩家存活且仍有可付真元。
        if (event.reason() != TechniqueCastEndReason.COMPLETED) {
            return;
        }
        var player = server.getPlayerList().getPlayer(event.entityUuid());
        var body = HumanTechniqueHost.effectBody(event.entityUuid());
        if (player == null || body == null || !player.isAlive()) {
            return;
        }
        var last = TechniqueState.read(body.snapshot()).getLastCast();
        if (last.getEnding() != CastPhase.FINISHED || !last.getCastId().equals(event.castId())) {
            return;
        }
        service.registry().find(last.getTechnique()).filter(TechniqueDefinition::hasMaintainedBreathing)
                .ifPresent(definition -> {
                    if (body.beginPractice(HumanTechniqueHost.nowMillis(), last.getCastId(), definition)) {
                        TechniqueBreathingStatuses.show(player, definition.getMaintainedBreathing().getEffectId());
                        HumanTechniqueHost.bodyChanged(event.entityUuid());
                    }
                });
    }

    static void end(ServerPlayer player) {
        // 卸载或硬控可能与原版清效同刻发生，以身体上的结束事实去重。
        if (player == null) {
            return;
        }
        var body = HumanTechniqueHost.effectBody(player.getUUID());
        if (body != null && body.endPractice(HumanTechniqueHost.nowMillis())) {
            HumanTechniqueHost.bodyChanged(player.getUUID());
        }
    }
}
