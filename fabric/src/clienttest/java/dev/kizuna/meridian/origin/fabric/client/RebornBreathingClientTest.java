package dev.kizuna.meridian.origin.fabric.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.status.StatusArrangement.Single;
import dev.kizuna.meridian.human.client.HumanProgressionClient;
import dev.kizuna.meridian.technique.fabric.TechniqueCodex;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.ParentUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import org.lwjgl.glfw.GLFW;

import static org.apache.commons.lang3.Validate.isTrue;

/** 新生从实际候选卡定下出身，打开吐纳窗口并取得服务端的真实吸入回执。 */
public final class RebornBreathingClientTest implements FabricClientGameTest {
    private static final String ORIGIN = "meridian_humanoid:reborn";
    private static final String FORM = "meridian_basic_techniques:later_breathing";
    private static final String BOOK = "meridian_basic_techniques:later_breathing_book";
    private static final String BREATHING = "meridian_ui:cultivation";
    private static final String CODEX_SCREEN = "dev.kizuna.meridian.ui.client.CodexScreen";
    private static final int OFFER_LIMIT = 32;
    private static final int SETTLE_TICKS = 12;
    private static final float HALF = 0.5f;
    private static final double INTAKE = 1.5;
    private static final int EFFECT_GUI_SCALE = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        // 在测试自己的新存档里验出身、书籍事实、真实菜单、控件和网络回执。
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> progression().synchronizedWithServer() && module().snapshot() != null);
            context.runOnClient(client -> isTrue(!canOpen(BREATHING), "未选出身已经能打开吐纳"));
            choose(context, world);
            context.waitTicks(SETTLE_TICKS);
            context.runOnClient(client -> {
                var progression = progression();
                var reading = progression.requireProgress();
                isTrue(!reading.getQiNotesRead() && !reading.getGatheringScrollRead(), "出身代读了残卷");
                isTrue(!progression.canInspect() && !progression.canUseMiddleJiao(),
                        "其他残卷权限被放开");
                isTrue(!canOpen("meridian_ui:aura") && !canOpen("meridian_ui:meridians"),
                        "环境或开脉入口已开放");
                isTrue(canOpen(BREATHING), "未读重生无法打开吐纳入口");
                isTrue(!progression.canGatherDetails() && !canOpen("meridian_ui:aura"),
                        "出身授权开放了环境详情");
            });
            startPractice(world);
            breathe(context);
            context.takeScreenshot(TestScreenshotOptions.of("reborn-unread-breathing").disableCounterPrefix());
            showCodex(context, world);
            hoverPracticeEffect(context);
        }
    }

    private static HumanProgressionClient progression() {
        var entries = FabricLoader.getInstance().getEntrypointContainers(
                HumanProgressionClient.ENTRYPOINT, HumanProgressionClient.class);
        if (entries.size() != 1) {
            throw new IllegalStateException("人体客户端进阶入口数量错误：" + entries.size());
        }
        return entries.getFirst().getEntrypoint();
    }

    private static void choose(ClientGameTestContext context, TestSingleplayerContext world) {
        // 如果首次三张里没有重生，只重抽未定的候选；确定出身始终经玩家卡片
        // 发送选择包。
        for (int attempt = 0; attempt < OFFER_LIMIT; attempt++) {
            context.waitFor(client -> client.gui.screen() instanceof OriginScreen);
            boolean offered = context.computeOnClient(client -> {
                var screen = (OriginScreen) client.gui.screen();
                return screen.offer().getCardsList().stream().anyMatch(card -> card.getId().equals(ORIGIN));
            });
            if (offered) {
                context.waitTicks(SETTLE_TICKS);
                context.runOnClient(client -> {
                    var screen = (OriginScreen) client.gui.screen();
                    var card = screen.offer().getCardsList().stream()
                            .filter(candidate -> candidate.getId().equals(ORIGIN)).findFirst().orElseThrow();
                    screen.pick(card);
                    scrollPickedCardIntoView(screen, card.getId());
                });
                context.waitTicks(SETTLE_TICKS);
                context.takeScreenshot(TestScreenshotOptions.of("reborn-origin-card").disableCounterPrefix());
                context.runOnClient(client -> ((OriginScreen) client.gui.screen()).settle());
                return;
            }
            context.setScreen(() -> null);
            world.getServer().runCommand("meridian debug origin offer @a[limit=1]");
        }
        throw new AssertionError("候选中一直没有重生出身");
    }

    private static void scrollPickedCardIntoView(OriginScreen screen, String cardId) {
        // 选中会重建出身列表，滚动容器必须定位到重建后的目标卡片。
        try {
            var rootField = screen.getClass().getDeclaredField("root");
            rootField.setAccessible(true);
            var root = (io.wispforest.owo.ui.core.ParentUIComponent) rootField.get(screen);
            ScrollContainer<?> scroll = root.children().stream().filter(ScrollContainer.class::isInstance)
                    .map(ScrollContainer.class::cast).findFirst().orElseThrow();
            int cardIndex = IntStream.range(0, screen.offer().getCardsCount())
                    .filter(index -> screen.offer().getCards(index).getId().equals(cardId))
                    .findFirst().orElseThrow();
            ParentUIComponent cards = (ParentUIComponent) scroll.child();
            UIComponent card = cards.children().get(cardIndex);
            scroll.scrollTo(card);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法将重生出身卡滚入可见区域", failure);
        }
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

    private static void showCodex(ClientGameTestContext context, TestSingleplayerContext world) {
        // 手持真实功法谱打开本局目录，翻到重生出身学会的书。
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(TechniqueCodex.item()));
        });
        context.waitFor(client -> client.player.getMainHandItem().is(TechniqueCodex.item()));
        context.runOnClient(client -> client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND));
        context.waitFor(client -> {
            var screen = client.gui.screen();
            return screen != null && screen.getClass().getName().equals(CODEX_SCREEN);
        });
        context.runOnClient(client -> selectBook(client.gui.screen()));
        context.waitFor(client -> {
            var screen = client.gui.screen();
            return screen != null && screen.getClass().getName().equals(CODEX_SCREEN)
                    && BOOK.equals(selectedBook(screen));
        });
        context.runOnClient(client -> {
            Object screen = client.gui.screen();
            isTrue(isVolumeListed(screen, FORM + "_volume"), "《溯息经》书目没有已学的纳息篇");
            scrollCodexBookIntoView(screen, BOOK);
            client.gui.toastManager().clear();
        });
        context.waitTicks(SETTLE_TICKS);
        context.takeScreenshot(TestScreenshotOptions.of("reborn-codex-book-page-v5").disableCounterPrefix());
        context.runOnClient(client -> client.gui.setScreen(null));
    }

    private static void selectBook(Object screen) {
        // 功法谱只在自身包内暴露目录操作器，测试通过反射选择目标书，不触碰正式 API。
        try {
            Object panel = codexPanel(screen);
            Method select = panel.getClass().getDeclaredMethod("select", String.class);
            select.setAccessible(true);
            select.invoke(panel, BOOK);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法翻到《溯息经》", failure);
        }
    }

    private static String selectedBook(Object screen) {
        // 读取目录当前书籍，确认截图前右页已经完成目标书重排。
        try {
            Object panel = codexPanel(screen);
            Method selected = panel.getClass().getDeclaredMethod("selectedBook");
            selected.setAccessible(true);
            return (String) selected.invoke(panel);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法确认功法谱当前书籍", failure);
        }
    }

    private static boolean isVolumeListed(Object screen, String volumeId) {
        // 截图前确认目录中存在目标卷，不能只凭翻页字段判断书目可见。
        try {
            Object panel = codexPanel(screen);
            var catalogField = panel.getClass().getDeclaredField("catalog");
            catalogField.setAccessible(true);
            Object catalog = catalogField.get(panel);
            Method findVolume = catalog.getClass().getDeclaredMethod("volume", String.class);
            findVolume.setAccessible(true);
            return ((Optional<?>) findVolume.invoke(catalog, volumeId)).isPresent();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法确认功法谱目录中的纳息篇", failure);
        }
    }

    private static void scrollCodexBookIntoView(Object screen, String bookId) {
        // 左页目录按书籍行排列，截图前把目标书行滚入可见区域。
        try {
            Object panel = codexPanel(screen);
            var contentField = panel.getClass().getDeclaredField("content");
            contentField.setAccessible(true);
            ParentUIComponent content = (ParentUIComponent) contentField.get(panel);
            ScrollContainer<?> indexScroll = content.childById(ScrollContainer.class, "index-scroll");
            ParentUIComponent index = (ParentUIComponent) indexScroll.child();
            var catalogField = panel.getClass().getDeclaredField("catalog");
            catalogField.setAccessible(true);
            Object catalog = catalogField.get(panel);
            Method books = catalog.getClass().getDeclaredMethod("books");
            books.setAccessible(true);
            var bookList = (java.util.List<?>) books.invoke(catalog);
            int bookIndex = IntStream.range(0, bookList.size())
                    .filter(indexPosition -> {
                        Object book = bookList.get(indexPosition);
                        try {
                            Method id = book.getClass().getDeclaredMethod("id");
                            id.setAccessible(true);
                            return bookId.equals(id.invoke(book));
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError("无法读取功法谱书目", failure);
                        }
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("功法谱没有《溯息经》书目"));
            UIComponent row = index.children().get(bookIndex);
            indexScroll.scrollTo(row);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法将《溯息经》滚入功法谱目录", failure);
        }
    }

    private static Object codexPanel(Object screen) throws ReflectiveOperationException {
        Method panelAccessor = screen.getClass().getDeclaredMethod("panel");
        panelAccessor.setAccessible(true);
        return panelAccessor.invoke(screen);
    }

    private static void hoverPracticeEffect(ClientGameTestContext context) {
        // 运功保持有效时打开背包，并悬停框架接管后的真实状态图标。
        context.runOnClient(client -> {
            var effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(FORM)).orElseThrow();
            isTrue(client.player.getActiveEffects().stream().anyMatch(instance -> instance.getEffect().equals(effect)),
                    "运功效果没有保持激活");
            client.options.guiScale().set(EFFECT_GUI_SCALE);
            client.resizeGui();
            client.gui.setScreen(new InventoryScreen(client.player));
        });
        context.waitTicks(SETTLE_TICKS);
        var cursor = context.computeOnClient(RebornBreathingClientTest::effectCursor);
        context.getInput().setCursorPos(cursor.x, cursor.y);
        context.waitTicks(SETTLE_TICKS);
        context.takeScreenshot(TestScreenshotOptions.of("reborn-effect-hover").disableCounterPrefix());
    }

    private static Vec2 effectCursor(Minecraft client) {
        // 读取框架最近一帧的真实格位，悬停会触发统一状态提示而非原版背包列表。
        StatusIcons.Layout layout = StatusIcons.lastLayout();
        var slots = layout.slots();
        int index = IntStream.range(0, slots.size())
                .filter(position -> slots.get(position) instanceof Single single
                        && single.effect().id().equals(FORM))
                .findFirst()
                .orElseThrow(() -> new AssertionError("背包状态栏没有溯息运功图标"));
        StatusLayout.Area cell = layout.cells().get(index);
        int half = StatusLayout.FRAME_PIXELS / 2;
        float scale = (float) client.getWindow().getGuiScale();
        return new Vec2((cell.left() + half) * scale, (cell.top() + half) * scale);
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
