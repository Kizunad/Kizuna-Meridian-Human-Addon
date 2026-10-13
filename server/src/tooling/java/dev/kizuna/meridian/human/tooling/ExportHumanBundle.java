package dev.kizuna.meridian.human.tooling;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanErrors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 构建期将校验通过的内容写入 JAR 资源；不是玩家运行时安装入口。 */
public final class ExportHumanBundle {
    private ExportHumanBundle() {
        // 导出工具不进入最终附属 JAR，避免把构建职责带到宿主进程。
    }

    public static void main(String[] arguments) {
        // 固定资源路径供任意语言宿主读取，只接受构建传入的输出根目录。
        if (arguments.length != 1) {
            throw HumanErrors.invalid(HumanErrors.EXPORT_DIRECTORY_REQUIRED);
        }
        byte[] payload = HumanContent.payload();
        Path target = Path.of(arguments[0]).resolve(HumanContent.BUNDLE_RESOURCE);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, payload);
        } catch (IOException exception) {
            // 构建失败时阻止 JAR 发布，不能回退到过期或空白内容。
            throw HumanErrors.exportFailed(exception);
        }
    }
}
