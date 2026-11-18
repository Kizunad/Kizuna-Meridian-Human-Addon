package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonHostProfile;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonRegistration;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 服务端和真客户端共用独立测试守式，不依赖基础功法内容。 */
public final class HumanGuardTestCatalog implements TechniqueRegistryEntrypoint {
    public static final String GUARD = "human_guard_test:guard";
    public static final int WARMUP_TICKS = 4;
    public static final int RECOVERY_TICKS = 6;
    public static final double MOVEMENT = 0.4;
    private static final String RESOURCE = "human_guard_test/guard.txtpb";

    @Override
    public TechniqueRegistry registry() {
        // 目录照正式入口校验能力和资源；测试数据不放入安装 JAR。
        return TechniqueRegistry.load(addons(), List.of(catalog()));
    }

    private static AddonRegistry addons() {
        // 人体内容声明的能力与现有测试宿主保持一致。
        var host = AddonHostProfile.newBuilder()
                .setHostId("meridian:fabric")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE)
                .build();
        var registration = AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(HumanContent.bundle())
                .build();
        return AddonRegistry.load(registration);
    }

    private static TechniqueCatalog catalog() {
        // 资源或内容非法直接失败，不能静默跳过测试守式。
        var stream = HumanGuardTestCatalog.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("测试守式目录缺失");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var catalog = TechniqueCatalog.newBuilder();
            TextFormat.getParser().merge(reader, catalog);
            return catalog.build();
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取测试守式目录", failure);
        }
    }

    public static CastOutcome start(ServerPlayer player) {
        // 由真实服务分配阶段、锁和动作，测试不写施放状态。
        var reference = TechniqueReference.newBuilder()
                .setId(GUARD)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(player.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .build();
        return TechniqueFabric.service().cast(player.getUUID(), request);
    }

    public static void release(ServerPlayer player, String castId) {
        // 与按键适配器一样只结束服务端已知的身体和施放身份。
        var request = EndActiveRequest.newBuilder()
                .setBodyId(player.getStringUUID())
                .setCastId(castId)
                .build();
        TechniqueFabric.service().endActive(player.getUUID(), request);
    }
}
