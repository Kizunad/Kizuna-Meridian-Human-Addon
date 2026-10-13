package dev.kizuna.meridian.human.tooling;

import com.google.gson.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 将原生 Blockbench 分组、方块、网格与 UV 烘焙成客户端通用三角网格。 */
final class BlockbenchModel {
    private static final Map<String, int[]> CUBE_FACES = Map.of(
            "north", new int[]{0, 3, 2, 1}, "south", new int[]{4, 5, 6, 7},
            "west", new int[]{0, 4, 7, 3}, "east", new int[]{1, 2, 6, 5},
            "down", new int[]{0, 1, 5, 4}, "up", new int[]{3, 7, 6, 2});
    private final JsonObject model;
    private final Map<String, JsonObject> elements = new HashMap<>();
    private final Map<String, JsonObject> groups = new HashMap<>();
    private final Map<String, BodyVisual.Builder> visuals = new LinkedHashMap<>();
    private final Map<String, Map<Vertex, Integer>> vertexIndices = new HashMap<>();
    private final Set<String> visited = new HashSet<>();
    private final double textureWidth;
    private final double textureHeight;

    BlockbenchModel(Path path) throws IOException {
        // 只在构建期解析原生编辑文件；JAR 不携带 Gson 或 Blockbench 解析器。
        model = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        textureWidth = model.getAsJsonObject("resolution").get("width").getAsDouble();
        textureHeight = model.getAsJsonObject("resolution").get("height").getAsDouble();
        if (textureWidth <= 0 || textureHeight <= 0) {
            throw new IllegalArgumentException("Blockbench 图集尺寸无效");
        }
        for (JsonElement value : model.getAsJsonArray("elements")) {
            JsonObject element = value.getAsJsonObject();
            String id = element.get("uuid").getAsString();
            if (elements.put(id, element) != null) {
                throw new IllegalArgumentException("Blockbench 元素 UUID 重复：" + id);
            }
        }
        // Blockbench 5 把组定义独立保存，层级中只留下 UUID；旧文件仍使用内联组。
        if (model.has("groups")) {
            for (JsonElement value : model.getAsJsonArray("groups")) {
                JsonObject group = value.getAsJsonObject();
                groups.put(group.get("uuid").getAsString(), group);
            }
        }
    }

    BodyPresentation compile(BodyPresentation.Builder definition) {
        // 身体引用先于几何建立；空模型直接让构建失败，防止静默漏器官。
        for (BodyVisual visual : definition.getVisualsList()) {
            visuals.put(visual.getId(), visual.toBuilder().clearVertices().clearTriangles().clearTextureUv());
            vertexIndices.put(visual.getId(), new LinkedHashMap<>());
        }
        walk(model.getAsJsonArray("outliner"), null, List.of());
        definition.clearVisuals();
        for (BodyVisual.Builder visual : visuals.values()) {
            if (visual.getVerticesCount() == 0) {
                throw new IllegalArgumentException("Blockbench 缺少绑定分组：" + visual.getId());
            }
            definition.addVisuals(visual);
        }
        return definition.build();
    }

    void writeTexture(Path target) throws IOException {
        // Blockbench 内嵌图像为唯一贴图源，直接在编辑器绘制并保存后即可参与下次构建。
        JsonArray textures = model.getAsJsonArray("textures");
        if (textures.size() != 1) {
            throw new IllegalArgumentException("人体装配需要一张共用图集");
        }
        String source = textures.get(0).getAsJsonObject().get("source").getAsString();
        String prefix = "data:image/png;base64,";
        if (!source.startsWith(prefix)) {
            throw new IllegalArgumentException("Blockbench 贴图必须内嵌为 PNG");
        }
        byte[] pixels = Base64.getDecoder().decode(source.substring(prefix.length()));
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pixels));
        if (image == null || image.getWidth() != textureWidth || image.getHeight() != textureHeight) {
            throw new IllegalArgumentException("图集尺寸与 Blockbench UV 分辨率不符");
        }
        Files.write(target, pixels);
    }

    private void walk(JsonArray children, BodyVisual.Builder owner, List<JsonObject> parents) {
        // 原生父子层级决定装配关系；按从内到外的枢轴旋转烘焙每个子零件。
        for (JsonElement child : children) {
            if (child.isJsonObject()) {
                JsonObject hierarchy = child.getAsJsonObject();
                JsonObject group = hierarchy;
                if (!hierarchy.has("name")) {
                    group = groups.get(hierarchy.get("uuid").getAsString());
                }
                if (group == null) {
                    throw new IllegalArgumentException("Blockbench 层级引用了缺失的装配组");
                }
                BodyVisual.Builder binding = visuals.getOrDefault(group.get("name").getAsString(), owner);
                var transforms = new ArrayList<JsonObject>();
                transforms.add(group);
                transforms.addAll(parents);
                walk(hierarchy.getAsJsonArray("children"), binding, transforms);
                continue;
            }
            String id = child.getAsString();
            JsonObject element = elements.get(id);
            if (owner == null || element == null || !visited.add(id)) {
                throw new IllegalArgumentException("Blockbench 元素无绑定、缺失或重复：" + id);
            }
            var transforms = new ArrayList<JsonObject>();
            transforms.add(element);
            transforms.addAll(parents);
            String type = element.get("type").getAsString();
            if (type.equals("cube")) {
                cube(element, owner, transforms);
            } else if (type.equals("mesh")) {
                mesh(element, owner, transforms);
            } else {
                throw new IllegalArgumentException("不支持的 Blockbench 元素类型：" + type);
            }
        }
    }

    private void cube(JsonObject element, BodyVisual.Builder owner, List<JsonObject> transforms) {
        // 每个面独立 UV；相同位置但不同 UV 的顶点不能在贴图接缝处合并。
        double[] low = vector(element.getAsJsonArray("from"));
        double[] high = vector(element.getAsJsonArray("to"));
        double[][] points = {{low[0],low[1],low[2]}, {high[0],low[1],low[2]},
                {high[0],high[1],low[2]}, {low[0],high[1],low[2]},
                {low[0],low[1],high[2]}, {high[0],low[1],high[2]},
                {high[0],high[1],high[2]}, {low[0],high[1],high[2]}};
        for (String direction : List.of("north", "south", "west", "east", "down", "up")) {
            JsonObject face = element.getAsJsonObject("faces").getAsJsonObject(direction);
            if (face == null || face.get("texture").isJsonNull()) {
                continue;
            }
            JsonArray uv = face.getAsJsonArray("uv");
            double u1 = uv.get(0).getAsDouble(), v1 = uv.get(1).getAsDouble();
            double u2 = uv.get(2).getAsDouble(), v2 = uv.get(3).getAsDouble();
            double[][] coordinates = {{u1,v2}, {u2,v2}, {u2,v1}, {u1,v1}};
            int rotation = face.has("rotation") ? face.get("rotation").getAsInt() / 90 : 0;
            int[] indices = new int[4];
            for (int index = 0; index < 4; index++) {
                double[] texture = coordinates[Math.floorMod(index - rotation, 4)];
                indices[index] = vertex(owner, points[CUBE_FACES.get(direction)[index]], texture, transforms);
            }
            owner.addTriangles(indices[0]).addTriangles(indices[1]).addTriangles(indices[2]);
            owner.addTriangles(indices[0]).addTriangles(indices[2]).addTriangles(indices[3]);
        }
    }

    private void mesh(JsonObject element, BodyVisual.Builder owner, List<JsonObject> transforms) {
        // 网格坐标相对于自身原点；面保持原始绕序，不重新推测经脉连接。
        JsonObject points = element.getAsJsonObject("vertices");
        double[] origin = vector(element.getAsJsonArray("origin"));
        for (JsonElement value : element.getAsJsonObject("faces").asMap().values()) {
            JsonObject face = value.getAsJsonObject();
            JsonArray corners = face.getAsJsonArray("vertices");
            if (corners.size() < 3 || corners.size() > 4) {
                throw new IllegalArgumentException("网格面必须为三角面或凸四边面");
            }
            int[] indices = new int[corners.size()];
            for (int index = 0; index < corners.size(); index++) {
                String key = corners.get(index).getAsString();
                double[] point = vector(points.getAsJsonArray(key));
                for (int axis = 0; axis < 3; axis++) {
                    point[axis] += origin[axis];
                }
                JsonArray uv = face.getAsJsonObject("uv").getAsJsonArray(key);
                indices[index] = vertex(owner, point, new double[]{uv.get(0).getAsDouble(), uv.get(1).getAsDouble()}, transforms);
            }
            for (int index = 1; index < indices.length - 1; index++) {
                owner.addTriangles(indices[0]).addTriangles(indices[index]).addTriangles(indices[index + 1]);
            }
        }
    }

    private int vertex(BodyVisual.Builder owner, double[] source, double[] uv, List<JsonObject> transforms) {
        // 子零件共享身体坐标，逐级枢轴旋转后统一从模型像素转换成米。
        double[] point = source.clone();
        for (JsonObject transform : transforms) {
            rotate(point, transform);
        }
        float u = 0, v = 0;
        if (!owner.getTextureResource().isEmpty()) {
            u = (float) (uv[0] / textureWidth);
            v = (float) (uv[1] / textureHeight);
        }
        var vertex = new Vertex((float) (point[0] / 16), (float) (point[1] / 16), (float) (point[2] / 16), u, v);
        Map<Vertex, Integer> indices = vertexIndices.get(owner.getId());
        Integer existing = indices.get(vertex);
        if (existing != null) {
            return existing;
        }
        int index = owner.getVerticesCount();
        indices.put(vertex, index);
        owner.addVertices(ModelPoint.newBuilder().setX(vertex.x()).setY(vertex.y()).setZ(vertex.z()));
        if (!owner.getTextureResource().isEmpty()) {
            owner.addTextureUv(ModelUv.newBuilder().setU(u).setV(v));
        }
        return index;
    }

    private static void rotate(double[] point, JsonObject transform) {
        // Blockbench 方块与组采用 ZYX，网格采用 XYZ；向量应用顺序与欧拉声明相反。
        if (!transform.has("rotation")) {
            return;
        }
        double[] angles = vector(transform.getAsJsonArray("rotation"));
        double[] pivot = vector(transform.getAsJsonArray("origin"));
        boolean meshOrder = transform.has("type") && transform.get("type").getAsString().equals("mesh");
        for (int step = 0; step < 3; step++) {
            int axis = meshOrder ? 2 - step : step;
            if (angles[axis] == 0) {
                continue;
            }
            int first = (axis + 1) % 3, second = (axis + 2) % 3;
            double cosine = Math.cos(Math.toRadians(angles[axis]));
            double sine = Math.sin(Math.toRadians(angles[axis]));
            double a = point[first] - pivot[first], b = point[second] - pivot[second];
            point[first] = pivot[first] + a * cosine - b * sine;
            point[second] = pivot[second] + a * sine + b * cosine;
        }
    }

    private static double[] vector(JsonArray coordinates) {
        // 缺省原点视为零；文件中的位置单位仍是模型像素。
        if (coordinates == null) {
            return new double[3];
        }
        return new double[]{coordinates.get(0).getAsDouble(), coordinates.get(1).getAsDouble(), coordinates.get(2).getAsDouble()};
    }

    private record Vertex(float x, float y, float z, float u, float v) {}
}
