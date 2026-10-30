package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageSpillover;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;
import dev.kizuna.meridian.server.BodyDamageHandler;
import dev.kizuna.meridian.server.DefaultBodyDamageHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 人体伤害：按致伤机制分配局部损伤，并根据部位总量与头部状态裁定死亡。 */
public final class HumanDamage implements BodyDamageHandler {
    private static final double DEATH_HP_RATIO = 0.2;
    private static HumanDamageRules configuredRules = HumanDamageRules.defaults();
    private final HumanDamageRules rules;
    private final String entityType;

    public HumanDamage() {
        this("minecraft:player");
        // 无 Fabric 的人体内容调用使用玩家类型，仍读取相同的模板配置。
    }

    public HumanDamage(String entityType) {
        this(configuredRules, entityType);
        // 宿主只传实体类型，不提供客户端可控的伤害策略开关。
    }

    public HumanDamage(HumanDamageRules rules, String entityType) {
        // 每个宿主固定启动时的规则；测试可注入独立配置而不影响其他身体。
        this.rules = rules;
        this.entityType = entityType;
    }

    public static void configure(Path file) {
        // 在身体宿主开始工作之前读取一次服务端配置。
        configuredRules = HumanDamageRules.load(file);
    }

    @Override
    public byte[] resolve(byte[] payload) {
        // 读取固定快照与可信伤害；未指定命中时复用框架选点，输出仍由框架逐目标校验。
        var context = HealthProtobuf.decodeContext(payload);
        var damage = context.getDamage();
        BodySnapshot body = context.getBody();
        HealthTarget hit = DefaultBodyDamageHandler.selectHit(context);

        // 部位记录总体损伤，内部账户记录不同机制的局部反应；总体 HP 不重复累加这些账户。
        var losses = new ArrayList<HpLoss>();
        DamageSpillover spillover = partLosses(body, hit, damage.getDamageHp(), losses);
        if (hit.hasPartId() && damage.getKind() != DamageKind.CUSTOM) {
            distributeLocalDamage(body, hit.getPartId(), damage.getKind(), damage.getDamageHp(), losses);
        }

        boolean lethal = lethal(body, losses);
        var result = DamageResolution.newBuilder()
                .setLethal(lethal)
                .addAllLosses(losses);
        if (spillover != null) {
            result.setSpillover(spillover);
        }
        return HealthProtobuf.encodeResolution(result.build());
    }

    private DamageSpillover partLosses(BodySnapshot body, HealthTarget hit, double hp, List<HpLoss> losses) {
        // 精确组织/器官命中不跨部位；开关关闭时沿用 Framework 的单目标过量裁剪。
        String templateId = body.getTemplate().getId();
        if (!hit.hasPartId() || !rules.enabled(entityType, templateId)) {
            losses.add(HpLoss.newBuilder().setTarget(hit).setHp(hp).build());
            return null;
        }
        double available = body.getPartStatesList().stream()
                .filter(part -> part.getId().equals(hit.getPartId()))
                .findFirst().orElseThrow().getHp();
        double direct = Math.min(hp, available);
        if (direct > 0) {
            losses.add(HumanDamageSpillover.loss(hit.getPartId(), direct));
        }
        double overflow = hp - direct;
        if (overflow == 0) {
            return null;
        }
        double multiplier = rules.multiplier(templateId, hit.getPartId());
        DamageSpillover spillover = HumanDamageSpillover.distribute(body, hit, overflow, multiplier);
        losses.addAll(spillover.getLossesList());
        return spillover;
    }

    private static boolean lethal(BodySnapshot body, List<HpLoss> losses) {
        // 将所有明确部位剂量一起投影后使用原有死亡规则，组织和器官不重复计入总 HP。
        Map<String, Double> partLosses = new HashMap<>();
        for (HpLoss loss : losses) {
            if (loss.getTarget().hasPartId()) {
                partLosses.put(loss.getTarget().getPartId(), loss.getHp());
            }
        }
        var projectedPartHp = new ArrayList<Double>();
        boolean headDestroyed = false;
        for (var part : body.getPartStatesList()) {
            double loss = partLosses.getOrDefault(part.getId(), 0.0);
            double remainingHp = Math.max(0, part.getHp() - loss);
            projectedPartHp.add(remainingHp);
            if (part.getId().equals("head") && remainingHp == 0) {
                headDestroyed = true;
            }
        }

        // 使用补偿求和，避免多个小数累加把恰好 20% 的边界误判为死亡。
        double totalPartHp = projectedPartHp.stream().mapToDouble(Double::doubleValue).sum();
        double maximumPartHp = body.getStructure().getPartsList().stream()
                .mapToDouble(part -> part.getMaxHp()).sum();

        // 总部位 HP 严格低于 20% 或头部归零才死亡，器官归零先表达为该器官失能。
        return headDestroyed || totalPartHp < maximumPartHp * DEATH_HP_RATIO;
    }

    private static void distributeLocalDamage(BodySnapshot body, String partId, DamageKind kind,
                                              double damageHp, List<HpLoss> losses) {
        // 先从实际构型取得同一部位的组织；替换或移除器官后不再按旧模板 ID 分配。
        DamageProfile profile = profile(kind);
        List<HealthTarget> skin = tissueTargets(body, partId, "human:skin");
        List<HealthTarget> muscle = tissueTargets(body, partId, "human:muscle");
        List<HealthTarget> bone = tissueTargets(body, partId, "human:bone");
        List<HealthTarget> organs = body.getStructure().getOrgansList().stream()
                .filter(organ -> organ.getPartId().equals(partId))
                .map(organ -> HealthTarget.newBuilder().setOrganId(organ.getId()).build())
                .toList();
        List<DamageGroup> groups = List.of(new DamageGroup(skin, profile.skin()),
                new DamageGroup(muscle, profile.muscle()), new DamageGroup(bone, profile.bone()),
                new DamageGroup(organs, profile.organ()));

        // 缺少某层时只在实际存在且有配置权重的层之间重新分配；不转移到身体别处。
        double availableWeight = groups.stream()
                .filter(group -> !group.targets().isEmpty())
                .mapToDouble(DamageGroup::weight)
                .sum();
        if (availableWeight == 0) {
            return;
        }

        // 同类内部均分；已经受损的模块仍承担该层分额，饱和损失由框架裁剪到零。
        for (DamageGroup group : groups) {
            if (group.targets().isEmpty() || group.weight() == 0) {
                continue;
            }
            double groupShare = group.weight() / availableWeight;
            double hpPerTarget = damageHp * groupShare / group.targets().size();
            if (hpPerTarget == 0) {
                // 极小浮点剂量可能下溢；保留部位主损失，不生成协议不允许的零剂量。
                continue;
            }
            for (HealthTarget target : group.targets()) {
                losses.add(HpLoss.newBuilder().setTarget(target).setHp(hpPerTarget).build());
            }
        }
    }

    private static List<HealthTarget> tissueTargets(BodySnapshot body, String partId, String tissueKind) {
        // 只按所属部位与组织类型查询，具体肌肉、骨组名称不参与伤害算法。
        return body.getStructure().getTissuesList().stream()
                .filter(tissue -> tissue.getPartId().equals(partId) && tissue.getKind().equals(tissueKind))
                .map(tissue -> HealthTarget.newBuilder().setTissueId(tissue.getId()).build())
                .toList();
    }

    private static DamageProfile profile(DamageKind kind) {
        // 顺序为皮肤、肌肉、骨骼、器官；这是人体首轮游戏数值，不是通用框架规则。
        return switch (kind) {
            case BLUNT -> new DamageProfile(.10, .35, .45, .10);
            case CUTTING -> new DamageProfile(.60, .35, 0, .05);
            case PIERCING -> new DamageProfile(.10, .20, .10, .60);
            case HEAT -> new DamageProfile(.70, .25, 0, .05);
            case COLD -> new DamageProfile(.40, .45, .05, .10);
            case ELECTRIC -> new DamageProfile(.10, .35, 0, .55);
            case CORROSIVE -> new DamageProfile(.80, .20, 0, 0);
            case TOXIC -> new DamageProfile(0, .20, 0, .80);
            default -> throw HumanErrors.invalid(HumanErrors.INVALID_DAMAGE_KIND);
        };
    }

    private record DamageProfile(double skin, double muscle, double bone, double organ) { }

    private record DamageGroup(List<HealthTarget> targets, double weight) { }
}
