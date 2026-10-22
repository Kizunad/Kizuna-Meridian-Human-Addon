package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.ActionDefinition;

import java.util.ArrayList;
import java.util.List;

/** 人体基础动作的占用声明，公共框架只执行通用启停和恢复规则。 */
final class HumanActions {
    private HumanActions() {
        // 内容不跟踪玩家当前动作，运行实例保存在 Framework 的身体快照。
    }

    static List<ActionDefinition> definitions() {
        // 行走与跑步使用当前已建模的下肢肌肉；二者身份不同，可供宿主切换和功法引用。
        var locomotionTissues = new ArrayList<String>();
        for (String side : List.of("left", "right")) {
            locomotionTissues.add(side + "_gluteus_maximus");
            locomotionTissues.add(side + "_rectus_femoris");
            locomotionTissues.add(side + "_gastrocnemius");
            locomotionTissues.add(side + "_flexor_digitorum_brevis");
        }
        ActionDefinition walk = ActionDefinition.newBuilder().setId("human:walk").setVersion(1).setDisplayName("行走")
                .addAllTissueIds(locomotionTissues).build();
        ActionDefinition run = walk.toBuilder().setId("human:run").setDisplayName("跑步").build();
        ActionDefinition breathing = ActionDefinition.newBuilder().setId("human:breathing").setVersion(1).setDisplayName("吐纳")
                .addTissueIds("diaphragm").addTissueIds("attention_network")
                .addOrganIds("left_lung").addOrganIds("right_lung").build();
        ActionDefinition opening = ActionDefinition.newBuilder().setId(HumanOpening.ACTION_ID).setVersion(1)
                .setDisplayName("开脉引导").addTissueIds("attention_network")
                .addOrganIds("brain").addOrganIds("left_lung").addOrganIds("right_lung").build();
        // 自动吐纳仍占用双肺和膈肌，释放注意组织以沿用正常精力恢复。
        ActionDefinition automaticBreathing = breathing.toBuilder().setId("human:automatic_breathing")
                .setDisplayName("自动吐纳").clearTissueIds().addTissueIds("diaphragm").build();
        return List.of(walk, run, breathing, automaticBreathing, opening);
    }
}
