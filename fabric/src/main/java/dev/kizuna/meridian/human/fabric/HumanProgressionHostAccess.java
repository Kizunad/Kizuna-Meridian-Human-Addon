package dev.kizuna.meridian.human.fabric;

import net.fabricmc.loader.api.FabricLoader;

/** 解析唯一的进阶宿主入口，缺少必需接线时在服务器启动阶段明确失败。 */
public final class HumanProgressionHostAccess {
    public static HumanProgressionHost require() {
        var containers = FabricLoader.getInstance()
                .getEntrypointContainers(HumanProgressionHost.ENTRYPOINT, HumanProgressionHost.class);
        if (containers.size() != 1) {
            throw new IllegalStateException("人体进阶宿主入口数量错误：" + containers.size());
        }
        return containers.getFirst().getEntrypoint();
    }

    private HumanProgressionHostAccess() {
    }
}
