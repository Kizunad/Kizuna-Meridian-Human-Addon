package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanDamage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class HumanSpilloverRegistrationGameTest implements ModInitializer {
    private static final String TEMPLATE = "human_test:spillover";

    @Override
    public void onInitialize() {
        // 走附属使用的公开入口，覆盖模组初始化与世界加载之间的登记时机。
        HumanDamage.registerTemplateDefaults(TEMPLATE, HumanContent.TEMPLATE_ID);
    }

    @GameTest
    public void serverInstallsRegisteredTemplate(GameTestHelper context) {
        // 从真实服务端读回安装结果，避免只验证解析器内部的合并。
        var rules = HumanDamage.configuredRules();
        context.assertTrue(rules.enabled("minecraft:villager", TEMPLATE), "服务端遗漏已登记的溢伤模板");
        context.succeed();
    }
}
