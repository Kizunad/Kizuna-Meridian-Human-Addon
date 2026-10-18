package dev.kizuna.meridian.human.tooling;

import dev.kizuna.meridian.human.client.HumanPresentation;
import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyTargetKind;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyPresentation;
import java.nio.file.Files;
import java.nio.file.Path;

/** 构建期导出展示资源，Human 客户端无需依赖 UI 或 Minecraft。 */
public final class ExportPresentation {
    public static void main(String[] arguments) throws Exception {
        // Blockbench 保存形状与解剖 UV；体表按玩家皮肤布局导出，身体引用仍使用同一 Protobuf。
        Path source = Path.of(arguments[0]);
        var definition = BodyPresentation.newBuilder();
        TextFormat.getParser().merge(Files.readString(source.resolve("human.textproto")), definition);
        // 展示资源声明每条经脉的最低境界，UI 只按通用枚举过滤，不硬编码人体数量。
        var presentation = BodyPresentation.newBuilder(definition.build()).clearVisuals();
        for (BodyVisual visual : definition.getVisualsList()) {
            if (visual.getTargetKind() == BodyTargetKind.MERIDIAN) {
                visual = visual.toBuilder().setMinimumRealm(HumanOpening.minimumRealm(visual.getTargetId())).build();
            }
            presentation.addVisuals(visual);
        }
        var model = new BlockbenchModel(source.resolve("human.bbmodel"));
        BodyPresentation scene = PlayerSkinSurface.bind(model.compile(presentation));
        Path target = Path.of(arguments[1]).resolve(HumanPresentation.RESOURCE);
        Files.createDirectories(target.getParent());
        Files.write(target, scene.toByteArray());
        Path texture = Path.of(arguments[1]).resolve("assets/meridian_human/textures/body/anatomy.png");
        Files.createDirectories(texture.getParent());
        model.writeTexture(texture);
    }

}
