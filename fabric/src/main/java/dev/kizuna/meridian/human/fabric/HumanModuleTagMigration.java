package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FillModuleTags;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ModuleTagAssignment;

import java.util.HashSet;
import java.util.List;

/** 人体模板只提供补齐意图，实际构型变更由 Framework 授权命令提交。 */
final class HumanModuleTagMigration {
    private static final BodyStructure TEMPLATE = HumanContent.bundle().getTemplates(0).getStructure();

    private HumanModuleTagMigration() {
        // 不保存身体状态，迁移始终以当前实际模块为准。
    }

    static FillModuleTags missingTags(BodySnapshot body) {
        // 仅匹配同种模块的实际 ID；已有非空差异也提交给框架明确拒绝。
        BodyStructure actual = BodyProtobuf.toStructure(body.structure());
        var request = FillModuleTags.newBuilder();
        addTissues(actual, request);
        addOrgans(actual, request);
        addMeridians(actual, request);
        return request.build();
    }

    private static void addTissues(BodyStructure actual, FillModuleTags.Builder request) {
        // 保留实际肌肉容量、连接和未知附属模块，只维护人体公开标签。
        for (var expected : TEMPLATE.getTissuesList()) {
            var current = actual.getTissuesList().stream()
                    .filter(module -> module.getId().equals(expected.getId())).findFirst();
            if (current.isPresent() && needsValidation(current.get().getTagsList(), expected.getTagsList())) {
                var assignment = ModuleTagAssignment.newBuilder()
                        .setTissueId(expected.getId())
                        .addAllTags(expected.getTagsList())
                        .build();
                request.addModules(assignment);
            }
        }
    }

    private static void addOrgans(BodyStructure actual, FillModuleTags.Builder request) {
        // 呼吸器官的现有改造状态与构型参数不参与迁移。
        for (var expected : TEMPLATE.getOrgansList()) {
            var current = actual.getOrgansList().stream()
                    .filter(module -> module.getId().equals(expected.getId())).findFirst();
            if (current.isPresent() && needsValidation(current.get().getTagsList(), expected.getTagsList())) {
                var assignment = ModuleTagAssignment.newBuilder()
                        .setOrganId(expected.getId())
                        .addAllTags(expected.getTagsList())
                        .build();
                request.addModules(assignment);
            }
        }
    }

    private static void addMeridians(BodyStructure actual, FillModuleTags.Builder request) {
        // 已开脉的体积等实际参数必须继续沿用，不能以模板重建身体。
        for (var expected : TEMPLATE.getMeridiansList()) {
            var current = actual.getMeridiansList().stream()
                    .filter(module -> module.getId().equals(expected.getId())).findFirst();
            if (current.isPresent() && needsValidation(current.get().getTagsList(), expected.getTagsList())) {
                var assignment = ModuleTagAssignment.newBuilder()
                        .setMeridianId(expected.getId())
                        .addAllTags(expected.getTagsList())
                        .build();
                request.addModules(assignment);
            }
        }
    }

    private static boolean needsValidation(List<String> actual, List<String> expected) {
        // 空模板标签没有补值含义；非空内容采用集合比较，不重排已有标签。
        return !expected.isEmpty() && !new HashSet<>(actual).equals(new HashSet<>(expected));
    }
}
