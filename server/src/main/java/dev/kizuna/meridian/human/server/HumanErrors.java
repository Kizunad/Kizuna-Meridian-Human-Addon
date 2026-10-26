package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

/** 人体内容工具的错误集中定义；身体业务拒绝仍由框架协议表达。 */
public final class HumanErrors {
    public static final String ENVIRONMENT_HOST_FAILED = "环境宿主请求处理失败";
    public static final String SAVE_FAILED = "人体世界存档写入失败，已保留待保存状态";
    public static final String ENVIRONMENT_CREDIT_FAILED = "环境待入账失败，已保留队列等待下次续窗";
    public static final String MIDDLE_JIAO_LOCKED = "请先右键使用《炁脉札记》和中焦残卷，再打开中焦，无须看完。";
    public static final String INVALID_CULTIVATION = "周天进度或人体模块配置无效";
    public static final String INVALID_PROGRESS = "身体内的修炼进度类型或版本无效";
    public static final String INVALID_DAMAGE_KIND = "人体伤害需要已知的致伤机制";
    public static final String DEBUG_PERMISSION = "真元调试需要管理员权限等级 2";
    public static final String DIFFUSION_ENTRY_MISSING = "当前身体不存在指定经脉或有效摄入出口";

    public static String debugRejected(FailureCode code) {
        // 调试不能跳过容量预约和身体状态，保留失败码方便定位。
        String reason = switch (code) {
            case CAPACITY_EXCEEDED -> "注入超过当前可用容量（已扣除在途预约），请减小剂量";
            case MODULE_NOT_FOUND -> DIFFUSION_ENTRY_MISSING;
            case MODULE_UNAVAILABLE -> "目标经脉受损，无法注入";
            case BODY_DEAD -> "身体已经死亡";
            case ACCESS_DENIED -> DEBUG_PERMISSION;
            default -> "真元调试未提交";
        };
        return reason + "（" + code + "）";
    }
    public static final String TEMPLATE_REQUIRED = "需要已注册的人体模板";
    public static final String INVALID_OPENING_POLICY = "人体开脉条件配置不完整或超出可计算范围";
    public static final String TEMPLATE_MISMATCH = "出生候选必须使用 human:adult 的第一版模板";
    public static final String EXPORT_DIRECTORY_REQUIRED = "需要唯一的内容导出目录参数";
    public static final String EXPORT_FAILED = "人体 Protobuf 内容导出失败";
    public static final String INVALID_SAVED_BODY = "人体存档损坏或版本不兼容，已停止加载，未重置身体";
    public static final String INVALID_MOVEMENT_TIME = "人体运动结算时间不能倒退或跨过未记录的运动切换";
    public static final String BODY_MISSING = "当前玩家尚未绑定人体";
    public static final String TISSUE_MISSING = "当前人体不存在此组织";
    public static final String HOST_FAILED = "人体宿主结算失败，已暂停该身体";
    public static final String INVALID_GATHERING_RESPONSE = "吐纳核心返回了无法解码的响应";
    public static final String UI_CHANNEL_MISSING = "客户端缺少必需的 Meridian UI 接收通道";

    public static IllegalStateException invalidGatheringResponse(Exception cause) {
        // 保留真正的协议损坏原因，不把它伪装为玩家能力不足。
        return new IllegalStateException(INVALID_GATHERING_RESPONSE, cause);
    }

    public static HostRejected hostRejected(FailureCode code) {
        // 保留协议失败码供宿主中止当前事件，避免失败后继续切换动作。
        return new HostRejected(code);
    }

    public static final class HostRejected extends IllegalStateException {
        private final FailureCode code;

        private HostRejected(FailureCode code) {
            // 协议拒绝保存为结构化值，不从运行时文案反解析失败码。
            super(HOST_FAILED + ": " + code);
            this.code = code;
        }

        public FailureCode code() {
            // 返回原始框架失败码，让宿主直接编码业务拒绝。
            return code;
        }
    }

    public static String openingRejected(FailureCode code) {
        // 公共失败码保留在消息末尾，玩家提示解释可调整的条件。
        String reason = switch (code) {
            case INSUFFICIENT_QI -> "全身内腔储量不足 80%，请继续吐纳";
            case OPENING_BUSY -> "已有开脉任务，请先暂停当前目标";
            case MERIDIAN_ALREADY_OPENED -> "这条经脉已经完成开拓";
            case OPENING_NOT_ACTIVE -> "当前没有正在运行的开脉任务";
            case OPENING_RULE_NOT_FOUND -> "没有对应经脉的开拓规则";
            case OPENING_REQUIREMENTS_UNMET -> "经脉承受度、脑耐力或精力未满足开脉要求";
            case MODULE_NOT_FOUND, MODULE_UNAVAILABLE, ROUTE_UNAVAILABLE -> "所需部位、器官或经脉不可用";
            default -> "开脉请求未执行";
        };
        return reason + "（" + code + "）";
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
