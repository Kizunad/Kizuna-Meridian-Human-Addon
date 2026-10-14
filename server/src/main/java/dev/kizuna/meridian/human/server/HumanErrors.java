package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

/** 人体内容工具的错误集中定义；身体业务拒绝仍由框架协议表达。 */
public final class HumanErrors {
    public static final String TEMPLATE_REQUIRED = "需要已注册的人体模板";
    public static final String TEMPLATE_MISMATCH = "出生候选必须使用 human:adult 的第一版模板";
    public static final String EXPORT_DIRECTORY_REQUIRED = "需要唯一的内容导出目录参数";
    public static final String EXPORT_FAILED = "人体 Protobuf 内容导出失败";
    public static final String INVALID_SAVED_BODY = "人体存档损坏或版本不兼容，已停止加载，未重置身体";
    public static final String INVALID_MOVEMENT_TIME = "人体运动结算时间不能倒退或跨过未记录的运动切换";
    public static final String BODY_MISSING = "当前玩家尚未绑定人体";
    public static final String TISSUE_MISSING = "当前人体不存在此组织";
    public static final String HOST_FAILED = "人体宿主结算失败，已暂停该身体";
    public static final String INVALID_GATHERING_RESPONSE = "吐纳核心返回了无法解码的响应";

    public static IllegalStateException invalidGatheringResponse(Exception cause) {
        // 保留真正的协议损坏原因，不把它伪装为玩家能力不足。
        return new IllegalStateException(INVALID_GATHERING_RESPONSE, cause);
    }

    public static IllegalStateException hostRejected(FailureCode code) {
        // 保留协议失败码供宿主中止当前事件，避免失败后继续切换动作。
        return new IllegalStateException(HOST_FAILED + ": " + code);
    }

    public static IllegalStateException invalidSave(Exception cause) {
        // 加载失败必须保留原文件，不能用出生模板覆盖已有玩家。
        return new IllegalStateException(INVALID_SAVED_BODY, cause);
    }

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
