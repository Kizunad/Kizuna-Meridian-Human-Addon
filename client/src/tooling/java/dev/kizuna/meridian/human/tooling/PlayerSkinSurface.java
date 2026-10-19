package dev.kizuna.meridian.human.tooling;

import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import java.util.List;

/** 把 Blockbench 体表部位映射到原版 64×64 玩家皮肤，保留独立部位身份。 */
final class PlayerSkinSurface {
    private static final String FALLBACK = "minecraft:textures/entity/player/wide/steve.png";
    private static final int[][] FACES = {
            {1, 0, 3, 2}, {4, 5, 6, 7}, {0, 4, 7, 3},
            {5, 1, 2, 6}, {5, 4, 0, 1}, {2, 3, 7, 6}};

    static BodyPresentation bind(BodyPresentation presentation) {
        // 几何仍以人体模型为源；只有体表换用游戏皮肤，其余层继续使用解剖图集。
        BodyPresentation.Builder result = presentation.toBuilder().clearVisuals();
        for (BodyVisual visual : presentation.getVisualsList()) {
            if (!visual.getLayerId().equals("surface")) {
                result.addVisuals(visual);
                continue;
            }
            SkinBox reference = reference(visual.getTargetId());
            BodyVisual wide = mesh(visual, reference, false);
            BodyVisual slim = mesh(visual, reference, true);
            // 皮肤布局按部位生成后，选择目标切换到独立皮肤组织，避免显示成所属部位的 HP。
            result.addVisuals(wide.toBuilder().setTargetKind(BodyTargetKind.TISSUE)
                    .setTargetId(visual.getTargetId() + "_skin")
                    .setDescription("身体最外侧的皮肤与选择区域；健康读取此处皮肤的独立 HP。")
                    .setPlayerSkin(PlayerSkinAppearance.newBuilder()
                    .addAllSlimVertices(slim.getVerticesList()).addAllSlimTextureUv(slim.getTextureUvList())));
        }
        return result.build();
    }

    private static SkinBox reference(String partId) {
        // 分段共享完整身体块的 UV，胸腹与上下肢的贴图在部位边界处连续。
        return switch (partId) {
            case "head" -> new SkinBox(-4, 4, 24, 32, -4, 4, 0, 0, false);
            case "neck", "chest", "abdomen", "pelvis" -> new SkinBox(-4, 4, 12, 24, -2, 2, 16, 16, false);
            case "left_upper_arm", "left_forearm", "left_hand" -> new SkinBox(4, 8, 12, 24, -2, 2, 32, 48, true);
            case "right_upper_arm", "right_forearm", "right_hand" -> new SkinBox(-8, -4, 12, 24, -2, 2, 40, 16, true);
            case "left_thigh", "left_lower_leg", "left_foot" -> new SkinBox(-.1f, 3.9f, 0, 12, -2, 2, 16, 48, false);
            case "right_thigh", "right_lower_leg", "right_foot" -> new SkinBox(-3.9f, .1f, 0, 12, -2, 2, 0, 16, false);
            default -> throw new IllegalArgumentException("体表没有玩家皮肤绑定：" + partId);
        };
    }

    private static BodyVisual mesh(BodyVisual source, SkinBox reference, boolean slim) {
        // 从实际模型取分段边界，避免在另一张表中重复维护十七个部位的形状。
        List<ModelPoint> vertices = source.getVerticesList();
        float minX = (float) vertices.stream().mapToDouble(ModelPoint::getX).min().orElseThrow() * 16;
        float maxX = (float) vertices.stream().mapToDouble(ModelPoint::getX).max().orElseThrow() * 16;
        float minY = (float) vertices.stream().mapToDouble(ModelPoint::getY).min().orElseThrow() * 16;
        float maxY = (float) vertices.stream().mapToDouble(ModelPoint::getY).max().orElseThrow() * 16;
        float minZ = (float) vertices.stream().mapToDouble(ModelPoint::getZ).min().orElseThrow() * 16;
        float maxZ = (float) vertices.stream().mapToDouble(ModelPoint::getZ).max().orElseThrow() * 16;
        if (slim && reference.arm()) {
            // 原版窄臂宽三像素，肩轴向下半像素；朝躯干的一侧保持原位。
            float slimMinX = Math.max(reference.minX(), -7);
            minX = slimMinX + (minX - reference.minX()) * .75f;
            maxX = slimMinX + (maxX - reference.minX()) * .75f;
            minY -= .5f;
            maxY -= .5f;
            reference = new SkinBox(slimMinX, slimMinX + 3, 11.5f, 23.5f,
                    reference.minZ(), reference.maxZ(), reference.u(), reference.v(), true);
        }
        float[][] corners = {{minX, maxY, maxZ}, {maxX, maxY, maxZ},
                {maxX, minY, maxZ}, {minX, minY, maxZ},
                {minX, maxY, minZ}, {maxX, maxY, minZ},
                {maxX, minY, minZ}, {minX, minY, minZ}};
        BodyVisual.Builder result = source.toBuilder().clearVertices().clearTriangles().clearTextureUv()
                .setTextureResource(FALLBACK).setColorRgb(0xFFFFFF);
        for (int face = 0; face < FACES.length; face++) {
            int first = result.getVerticesCount();
            for (int corner : FACES[face]) {
                float[] point = corners[corner];
                result.addVertices(ModelPoint.newBuilder().setX(point[0] / 16).setY(point[1] / 16).setZ(point[2] / 16));
                result.addTextureUv(uv(reference, face, point));
            }
            result.addTriangles(first).addTriangles(first + 1).addTriangles(first + 2)
                    .addTriangles(first).addTriangles(first + 2).addTriangles(first + 3);
        }
        return result.build();
    }

    private static ModelUv uv(SkinBox box, int face, float[] point) {
        // 原版正面朝 -Z、Y 向下；本模型已转为正面 +Z、Y 向上，UV 同步翻转方向。
        float width = box.maxX() - box.minX();
        float depth = box.maxZ() - box.minZ();
        float alongX = point[0] - box.minX();
        float alongZ = point[2] - box.minZ();
        float u;
        float v = box.v() + depth + box.maxY() - point[1];
        switch (face) {
            case 0 -> u = box.u() + depth + alongX;
            case 1 -> u = box.u() + depth * 2 + width + box.maxX() - point[0];
            case 2 -> u = box.u() + alongZ;
            case 3 -> u = box.u() + depth + width + depth - alongZ;
            case 4 -> {
                u = box.u() + depth + alongX;
                v = box.v() + alongZ;
            }
            case 5 -> {
                u = box.u() + depth + width + alongX;
                v = box.v() + alongZ;
            }
            default -> throw new IllegalArgumentException("未知皮肤面：" + face);
        }
        return ModelUv.newBuilder().setU(u / 64).setV(v / 64).build();
    }

    private record SkinBox(float minX, float maxX, float minY, float maxY,
                           float minZ, float maxZ, int u, int v, boolean arm) {}
}
