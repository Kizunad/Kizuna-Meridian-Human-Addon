package dev.kizuna.meridian.human.fabric;

import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 「测试拳」目录入口：一书一卷三式，只在 Human 游戏测试里加载。 */
public final class HumanFistTestCatalog implements TechniqueRegistryEntrypoint {
    static final String NAMESPACE = "human_fist_test";
    static final String VOLUME_ID = NAMESPACE + ":fists";
    private static final String RESOURCE = "human_fist_test/technique_catalog.txtpb";

    @Override
    public TechniqueRegistry registry() {
        // 与正式内容一样经目录入口校验书级拳掌规则、槽位和费用。
        return TechniqueRegistry.load(HumanTechniqueTestCatalog.addons(), List.of(load()));
    }

    private static TechniqueCatalog load() {
        // 测试资源缺失直接失败，不构造替代目录。
        var stream = HumanFistTestCatalog.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("测试拳目录缺失：" + RESOURCE);
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var catalog = TechniqueCatalog.newBuilder();
            TextFormat.getParser().merge(reader, catalog);
            return catalog.build();
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取测试拳目录", failure);
        }
    }
}
