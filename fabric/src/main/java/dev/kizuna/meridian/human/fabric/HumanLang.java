package dev.kizuna.meridian.human.fabric;

/** 指令反馈的 lang 键；文案在 assets/meridian_human_host/lang，部位、组织、器官名沿用解剖数据中文名。 */
final class HumanLang {
    static final String GUARD_SHIELD_RAISED = "meridian_human_host.guard.shield_raised";
    static final String TARGET_NOT_PLAYER = "commands.meridian_human_host.target_not_player";
    static final String STATUS_NO_BODY = "commands.meridian_human_host.status.no_body";
    static final String WATCH_BODY_ON = "commands.meridian_human_host.watch.body.on";
    static final String WATCH_BODY_OFF = "commands.meridian_human_host.watch.body.off";
    static final String QI_SUBMITTED = "commands.meridian_human_host.qi.submitted";
    static final String QI_ALL_MERIDIANS = "commands.meridian_human_host.qi.all_meridians";
    static final String QI_MERIDIAN = "commands.meridian_human_host.qi.meridian";
    static final String QI_BODY = "commands.meridian_human_host.qi.body";
    static final String OPENING_COMPLETE = "commands.meridian_human_host.opening.complete";
    static final String REALM_SET = "commands.meridian_human_host.body.realm.set";
    static final String REALM_ONE_WAY = "commands.meridian_human_host.body.realm.one_way";
    static final String REALM_UNKNOWN = "commands.meridian_human_host.body.realm.unknown";
    static final String REALM_NAME = "commands.meridian_human_host.body.realm.name.";

    private HumanLang() {
        // 只集中键名，组装参数留在各指令的执行位置。
    }
}
