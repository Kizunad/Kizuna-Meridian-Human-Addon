package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

/** 人体内容工具的错误集中定义；身体业务拒绝仍由框架协议表达。 */
public final class HumanErrors {
    public static final String TEMPLATE_REQUIRED = "需要已注册的人体模板";
    public static final String TEMPLATE_MISMATCH = "出生候选必须使用 human:adult 的第一版模板";
    public static final String EXPORT_DIRECTORY_REQUIRED = "需要唯一的内容导出目录参数";
    public static final String EXPORT_FAILED = "人体 Protobuf 内容导出失败";

    private HumanErrors() {
        // 内容工具使用同一组可追踪文案，避免把运行时错误分散在定义构造中。
    }

    public static IllegalArgumentException invalid(String message) {
        // 只描述内容接入错误，不替换框架已有的固定失败码。
        return new IllegalArgumentException(message);
    }

    public static IllegalStateException exportFailed(Exception cause) {
        // 保留文件系统原因供构建日志诊断，不吞掉不完整导出。
        return new IllegalStateException(EXPORT_FAILED, cause);
    }

    public static IllegalStateException demoRejected(FailureCode code) {
        // 演示请求失败时令构建返回非零状态，避免把半条聚气链误报成运行成功。
        return new IllegalStateException("聚气演示请求失败：" + code);
    }
}
