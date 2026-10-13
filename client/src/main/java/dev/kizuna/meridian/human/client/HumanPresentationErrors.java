package dev.kizuna.meridian.human.client;

/** 展示资源错误；客户端不依赖服务端运行时。 */
final class HumanPresentationErrors {
    static final String MISSING_RESOURCE = "缺少人体展示资源：";
    static final String UNREADABLE_RESOURCE = "无法读取人体展示资源：";

    private HumanPresentationErrors() {
        // 资源读取失败时保留明确的内容路径，方便宿主报告缺失资源包。
    }
}
