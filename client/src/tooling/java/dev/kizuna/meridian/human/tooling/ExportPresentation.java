package dev.kizuna.meridian.human.tooling;

import dev.kizuna.meridian.human.client.HumanPresentation;
import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyPresentation;
import java.nio.file.Files;
import java.nio.file.Path;

/** 构建期导出展示资源，Human 客户端无需依赖 UI 或 Minecraft。 */
public final class ExportPresentation {
    public static void main(String[] arguments) throws Exception {
        // Blockbench 保存形状与 UV；身体引用使用同一 Protobuf 的文本格式。
        Path source = Path.of(arguments[0]);
        var definition = BodyPresentation.newBuilder();
        TextFormat.getParser().merge(Files.readString(source.resolve("human.textproto")), definition);
        var model = new BlockbenchModel(source.resolve("human.bbmodel"));
        BodyPresentation scene = model.compile(definition);
        Path target = Path.of(arguments[1]).resolve(HumanPresentation.RESOURCE);
        Files.createDirectories(target.getParent());
        Files.write(target, scene.toByteArray());
        Path texture = Path.of(arguments[1]).resolve("assets/meridian_human/textures/body/anatomy.png");
        Files.createDirectories(texture.getParent());
        model.writeTexture(texture);
    }
}
