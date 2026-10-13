package dev.kizuna.meridian.human.client;

import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyPresentation;
import java.io.IOException;
import java.io.UncheckedIOException;

/** 人体展示入口；Blockbench 编辑源在构建期导出为只读协议资源。 */
public final class HumanPresentation {
    public static final String RESOURCE = "assets/meridian_human/meridian/adult.pb";

    private HumanPresentation() {
        // 内容构造器只创建展示消息，不接触玩家或身体账本。
    }

    public static BodyPresentation create() {
        // 演示与宿主读取同一份交付资源，避免运行时几何与编辑源分叉。
        try (var input = HumanPresentation.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(HumanPresentationErrors.MISSING_RESOURCE + RESOURCE);
            }
            return BodyPresentation.parseFrom(input);
        } catch (IOException failure) {
            throw new UncheckedIOException(HumanPresentationErrors.UNREADABLE_RESOURCE + RESOURCE, failure);
        }
    }
}
