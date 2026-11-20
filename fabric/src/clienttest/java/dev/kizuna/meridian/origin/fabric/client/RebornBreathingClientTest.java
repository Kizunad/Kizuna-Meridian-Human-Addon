package dev.kizuna.meridian.origin.fabric.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec2;
import org.lwjgl.glfw.GLFW;

import static org.apache.commons.lang3.Validate.isTrue;

/** 新生从实际候选卡定下出身，打开吐纳窗口并取得服务端的真实吸入回执。 */
public final class RebornBreathingClientTest implements FabricClientGameTest {
    private static final String ORIGIN = "meridian_humanoid:reborn";
    private static final String FORM = "meridian_basic_techniques:later_breathing";
    private static final String BREATHING = "meridian_ui:cultivation";
    private static final int OFFER_LIMIT = 32;
    private static final int SETTLE_TICKS = 12;
    private static final float HALF = 0.5f;
    private static final double INTAKE = 1.5;

    @Override
    public void runTest(ClientGameTestContext context) {
        // 在测试自己的新存档里验出身、书籍事实、真实菜单、控件和网络回执。
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> ClientProgression.synchronizedWithServer() && module().snapshot() != null);
            context.runOnClient(client -> isTrue(!canOpen(BREATHING), "未选出身已经能打开吐纳"));
            choose(context, world);
            context.waitTicks(SETTLE_TICKS);
            context.runOnClient(client -> {
                var reading = ClientProgression.requireProgress();
                isTrue(!reading.getQiNotesRead() && !reading.getGatheringScrollRead(), "出身代读了残卷");
                isTrue(!ClientProgression.canInspect() && !ClientProgression.canUseMiddleJiao(),
                        "其他残卷权限被放开");
                isTrue(!canOpen("meridian_ui:aura") && !canOpen("meridian_ui:meridians"),
                        "环境或开脉入口已开放");
                isTrue(canOpen(BREATHING), "未读重生无法打开吐纳入口");
                isTrue(!ClientProgression.canGatherDetails() && !canOpen("meridian_ui:aura"),
                        "出身授权开放了环境详情");
            });
            startPractice(world);
            breathe(context);
            context.takeScreenshot(TestScreenshotOptions.of("reborn-unread-breathing").disableCounterPrefix());
        }
    }

    private static void choose(ClientGameTestContext context, TestSingleplayerContext world) {
        // 如果首次三张里没有重生，只重抽未定的候选；确定出身始终经玩家卡片发送选择包。
        for (int attempt = 0; attempt < OFFER_LIMIT; attempt++) {
            context.waitFor(client -> client.gui.screen() instanceof OriginScreen);
            boolean picked = context.computeOnClient(client -> {
                var screen = (OriginScreen) client.gui.screen();
                var card = screen.offer().getCardsList().stream().filter(c -> c.getId().equals(ORIGIN)).findFirst();
                if (card.isEmpty()) {
                    return false;
                }
                screen.pick(card.get());
                screen.settle();
                return true;
            });
            if (picked) {
                return;
            }
            context.setScreen(() -> null);
            world.getServer().runCommand("meridian debug origin offer @a[limit=1]");
        }
        throw new AssertionError("候选中一直没有重生出身");
    }

    private static void startPractice(TestSingleplayerContext world) {
        // 只有已学且能从当前经脉付款的身体能由正式功法入口发动。
        var ref = TechniqueReference.newBuilder().setId(FORM).setVersion(1).build();
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var request = CastRequest.newBuilder()
                    .setBodyId(player.getStringUUID())
                    .setCastId(UUID.randomUUID().toString())
                    .setTechnique(ref)
                    .build();
            var outcome = TechniqueFabric.service().cast(player.getUUID(), request);
            isTrue(outcome.getReason().isEmpty(), "选择出身后未学会正式的式 " + outcome);
        });
    }

    private static boolean canOpen(String feature) {
        // 菜单与直接打开都经过工作区的权限判断。
        return InventoryUiClient.runtime().workspace().canOpen(feature);
    }

    private static BodyUiModule module() {
        // 读取实际连接使用的界面模块。
        return FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
    }

    private static void breathe(ClientGameTestContext context) {
        // 首次点开始可能显示教学提示，只有在权威状态仍未开始时才再点一次。
        context.runOnClient(client -> {
            var runtime = InventoryUiClient.runtime();
            runtime.openWorkspace();
            runtime.open(BREATHING, null);
        });
        context.waitFor(client -> module().cultivationPanel() != null && module().gatheringView() != null);
        context.waitTicks(SETTLE_TICKS);
        click(context, "breathing-automatic");
        click(context, "breathing-start");
        if (!context.computeOnClient(client -> module().gatheringView().getBreathing().getActive())) {
            click(context, "breathing-start");
        }
        context.waitFor(client -> {
            var breath = module().gatheringView().getBreathing();
            return breath.getActive() && breath.getAutomatic()
                    && breath.getIntakeMultiplier() == INTAKE && breath.getAbsorbedQi() > 0;
        });
    }

    private static void click(ClientGameTestContext context, String id) {
        // GUI 坐标换算成窗口像素后发送真实鼠标按键。
        var cursor = context.computeOnClient(client -> {
            var component = module().cultivationPanel().root().childById(UIComponent.class, id);
            var window = client.getWindow();
            float scale = (float) window.getScreenWidth() / window.getGuiScaledWidth();
            return new Vec2((component.x() + component.width() * HALF) * scale,
                    (component.y() + component.height() * HALF) * scale);
        });
        context.getInput().setCursorPos(cursor.x, cursor.y);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTicks(SETTLE_TICKS);
    }
}
