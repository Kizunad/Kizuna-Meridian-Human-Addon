package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.human.server.HumanErrors;
import dev.kizuna.meridian.human.server.HumanOrganRefining;
import dev.kizuna.meridian.human.server.HumanRefineRecipes;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganRecipe;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefine;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefineIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.server.EffectHandling;
import dev.kizuna.meridian.technique.server.TechniqueAddonResources;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.technique.server.TechniqueEffectEvent;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 五脏炼化的玩家效果处理器：功法库排定的每一批交给玩家身体执行，施放结束时停止起批。 */
public final class HumanOrganRefineHost {
    private static final Logger LOG = LoggerFactory.getLogger(HumanOrganRefineHost.class);
    private static MinecraftServer server;
    private static HumanRefineRecipes recipes;

    private HumanOrganRefineHost() {
        // 只持有当前服务器与配方缓存，任务进度随玩家身体保存。
    }

    static void initialize() {
        // 服务就绪时按已加载功法预读配方族；下线和停服不需要额外清理，任务按身体窗口收尾。
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> server = starting);
        TechniqueFabric.SERVICE_STARTED.register(HumanOrganRefineHost::start);
        TechniqueFabric.EFFECT_HANDLER.register(HumanOrganRefineHost::handle);
        TechniqueFabric.CAST_ENDED.register(HumanOrganRefineHost::end);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            server = null;
            recipes = null;
        });
    }

    private static void start(TechniqueService service) {
        // 配方来自已安装模组导出的公共内容包；功法声明的每个配方族都必须能读出完整配方。
        var resources = new TechniqueAddonResources(HumanOrganRefineHost.class.getClassLoader());
        recipes = new HumanRefineRecipes(namespace -> recipes(resources, namespace));
        service.registry().definitions().stream()
                .map(TechniqueDefinition::getEffectsList)
                .flatMap(List::stream)
                .filter(TechniqueEffect::hasOrganRefine)
                .map(effect -> effect.getOrganRefine().getRecipeFamily())
                .distinct()
                .forEach(recipes::validate);
        service.addCastCheck((entityUuid, request) -> startRejection(service, entityUuid, request));
    }

    private static Optional<String> startRejection(TechniqueService service, UUID entityUuid, CastRequest request) {
        // 炼化功法扣费前先看本脏经里有没有可炼的真元；非玩家、非炼化功法和绑定不成交给功法库自己判定。
        Optional<TechniqueDefinition> definition = service.registry().find(request.getTechnique());
        Optional<OrganRefine> refine = definition.stream()
                .flatMap(found -> found.getEffectsList().stream())
                .filter(TechniqueEffect::hasOrganRefine)
                .map(TechniqueEffect::getOrganRefine)
                .findFirst();
        HumanPlayerBody body = HumanTechniqueHost.effectBody(entityUuid);
        if (refine.isEmpty() || body == null || server.getPlayerList().getPlayer(entityUuid) == null) {
            return Optional.empty();
        }
        var snapshot = body.snapshot();
        BindingOutcome binding = TechniqueBinder.bind(snapshot, definition.orElseThrow());
        if (!binding.hasBinding()) {
            return Optional.empty();
        }
        OrganRefineIntent planned = OrganRefineIntent.newBuilder()
                .addAllOrganIds(moduleIds(binding, refine.get().getOrganSlotId()))
                .addAllMeridianIds(moduleIds(binding, refine.get().getMeridianSlotId()))
                .setRecipeFamily(refine.get().getRecipeFamily())
                .build();
        return HumanOrganRefining.startRejection(snapshot, planned, recipes);
    }

    private static List<String> moduleIds(BindingOutcome binding, String slotId) {
        // 起手绑定按槽位记录的实际器官或经脉。
        return binding.getBinding().getSlotsList().stream()
                .filter(slot -> slot.getSlotId().equals(slotId))
                .flatMap(slot -> slot.getModuleIdsList().stream())
                .toList();
    }

    private static List<OrganRecipe> recipes(TechniqueAddonResources resources, String namespace) {
        // 依赖包随命名空间一起交给 Framework 校验，只取其中的器官配方。
        return resources.registry(Set.of(namespace)).recipes().definitions();
    }

    private static EffectHandling handle(TechniqueEffectEvent event) {
        // 只接管玩家的器官炼化；非玩家实体交给其他宿主，玩家身体未就绪时整次施放结束。
        EffectIntent intent = event.intent();
        if (!intent.hasOrganRefine() || server.getPlayerList().getPlayer(event.entityUuid()) == null) {
            return EffectHandling.notApplicable();
        }
        HumanPlayerBody body = HumanTechniqueHost.effectBody(event.entityUuid());
        if (body == null) {
            return EffectHandling.rejected(HumanErrors.REFINE_BODY_MISSING);
        }
        try {
            HumanOrganRefining.Batch batch = body.refine(HumanTechniqueHost.nowMillis(), intent, recipes);
            if (batch.rejected()) {
                return EffectHandling.rejected(batch.rejection());
            }
            return EffectHandling.handled();
        } catch (HumanErrors.HostRejected rejected) {
            return EffectHandling.rejected(rejected.getMessage());
        } finally {
            HumanTechniqueHost.bodyChanged(event.entityUuid());
        }
    }

    private static void end(TechniqueCastEnded event) {
        // 所有结束路径都停止起批；没有在线身体时任务按起批窗口自行收尾。
        HumanPlayerBody body = HumanTechniqueHost.effectBody(event.entityUuid());
        if (body == null) {
            return;
        }
        try {
            if (body.endRefine(HumanTechniqueHost.nowMillis(), event.castId())) {
                HumanTechniqueHost.bodyChanged(event.entityUuid());
            }
        } catch (HumanErrors.HostRejected rejected) {
            LOG.warn(HumanErrors.REFINE_END_REJECTED, rejected);
        }
    }
}
