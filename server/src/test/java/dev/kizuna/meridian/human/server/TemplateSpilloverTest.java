package dev.kizuna.meridian.human.server;

import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class TemplateSpilloverTest {
    private static final String INHERITED = "test:inherited";
    private static final String OVERRIDDEN = "test:override";
    private static final String SOURCE = HumanContent.TEMPLATE_ID;
    private static final String ARM = "left_upper_arm";
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void installsBundledDefaultsWithFileTakingPrecedence() throws Exception {
        // 使用独立登记表检验两种模板共存和安装边界，避免污染其他宿主测试。
        var registry = new HumanDamageRules.TemplateDefaults();
        registry.register(INHERITED, SOURCE);
        registry.register(OVERRIDDEN, SOURCE);
        assertThrows(IllegalArgumentException.class, () -> registry.register(INHERITED, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> registry.register("test:other", INHERITED));

        // 来源模板在玩家文件中已改成零。只有显式写入文件的目标模板受覆盖。
        var file = folder.newFile().toPath();
        String settings = """
                [entities]
                default_enabled = true
                [templates."human:adult"]
                default_multiplier = 0
                [templates."human:adult".parts]
                [templates."test:override"]
                default_multiplier = 0
                [templates."test:override".parts]
                """;
        Files.writeString(file, settings);
        byte[] original = Files.readAllBytes(file);
        var rules = registry.install(file);
        assertTrue(rules.enabled("test:mob", INHERITED));
        double packaged = HumanDamageRules.defaults().multiplier(SOURCE, ARM);
        assertEquals(packaged, rules.multiplier(INHERITED, ARM), 0);
        assertEquals(0, rules.multiplier(OVERRIDDEN, ARM), 0);
        assertArrayEquals(original, Files.readAllBytes(file));
        assertThrows(IllegalStateException.class, () -> registry.register("test:late", SOURCE));
    }
}
