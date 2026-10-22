package dev.kizuna.meridian.human.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;

import java.util.List;

/** 第一版人体构型；表中数值是游戏初值，全部由附属内容声明。 */
final class HumanAnatomy {
    private HumanAnatomy() {
        // 构型构造不接触任何角色实例；修改模板不会重置已经保存的身体。
    }

    static BodyStructure structure() {
        // 先建立部位与独立组织，再声明器官、经脉及其真实连接。
        var body = BodyStructure.newBuilder();
        addPart(body, "head", "头部", 20);
        addPart(body, "neck", "颈部", 10);
        addPart(body, "chest", "胸部", 40);
        addPart(body, "abdomen", "腹部", 30);
        addPart(body, "pelvis", "骨盆", 30);
        for (String side : List.of("left", "right")) {
            String label = sideLabel(side);
            addPart(body, side + "_upper_arm", label + "上臂", 15);
            addPart(body, side + "_forearm", label + "前臂", 12);
            addPart(body, side + "_hand", label + "手", 8);
            addPart(body, side + "_thigh", label + "大腿", 25);
            addPart(body, side + "_lower_leg", label + "小腿", 18);
            addPart(body, side + "_foot", label + "足", 10);
            addMuscle(body, side + "_pectoralis_major", label + "胸大肌", "chest", 500, 400, 6000);
            addMuscle(body, side + "_biceps_brachii", label + "肱二头肌", side + "_upper_arm", 300, 250, 3000);
            addMuscle(body, side + "_triceps_brachii", label + "肱三头肌", side + "_upper_arm", 350, 280, 3500);
            addMuscle(body, side + "_brachioradialis", label + "肱桡肌", side + "_forearm", 180, 120, 2400);
            addMuscle(body, side + "_opponens_pollicis", label + "拇对掌肌", side + "_hand", 40, 20, 600);
            addMuscle(body, side + "_gluteus_maximus", label + "臀大肌", "pelvis", 800, 650, 12000);
            addMuscle(body, side + "_rectus_femoris", label + "股直肌", side + "_thigh", 600, 500, 9000);
            addMuscle(body, side + "_gastrocnemius", label + "腓肠肌", side + "_lower_leg", 500, 400, 8000);
            addMuscle(body, side + "_flexor_digitorum_brevis", label + "趾短屈肌", side + "_foot", 80, 40, 1200);
        }
        addMuscle(body, "diaphragm", "膈肌", "chest", 150, 80, 10000);
        // 精力以控制组织的可用做功预算表达，和膈肌共享疲劳规则但各自保存状态。
        body.addTissues(TissueDefinition.newBuilder().setId("attention_network").setDisplayName("注意控制组织")
                .setKind("human:neural").setPartId("head").setMaxHp(10).setFatigueCapacity(100)
                .setRecoveryHalfLifeSeconds(20)
                .setCapacity(TissueCapacity.newBuilder().setStrengthNewtons(0).setBurstWatts(1000).setEnduranceJoules(300)));

        // 皮肤单独记录局部 HP；当前不具备主动做功能力。
        for (PartDefinition part : body.getPartsList()) {
            body.addTissues(TissueDefinition.newBuilder().setId(part.getId() + "_skin")
                    .setDisplayName(part.getDisplayName() + "皮肤").setKind("human:skin")
                    .setPartId(part.getId()).setMaxHp(part.getMaxHp() * 0.25).setFatigueCapacity(100)
                    .setCapacity(TissueCapacity.newBuilder().setStrengthNewtons(0).setBurstWatts(0).setEnduranceJoules(0)));
        }

        // 骨架按当前模型的骨组独立记血；同部位的颅骨、下颌等不共用损伤账户。
        addBone(body, "skull", "颅骨", "head");
        addBone(body, "jaw", "下颌骨", "head");
        addBone(body, "cervical", "颈椎", "neck");
        addBone(body, "thoracic", "胸椎", "chest");
        addBone(body, "sternum", "胸骨", "chest");
        addBone(body, "lumbar", "腰椎", "abdomen");
        addBone(body, "pelvis", "骨盆", "pelvis");
        for (String side : List.of("left", "right")) {
            String label = sideLabel(side);
            addBone(body, side + "_ribs", label + "肋骨", "chest");
            addBone(body, side + "_shoulder", label + "锁骨与肩胛骨", "chest");
            addBone(body, side + "_humerus", label + "肱骨", side + "_upper_arm");
            addBone(body, side + "_forearm", label + "桡骨与尺骨", side + "_forearm");
            addBone(body, side + "_hand", label + "腕骨、掌骨与指骨", side + "_hand");
            addBone(body, side + "_femur", label + "股骨", side + "_thigh");
            addBone(body, side + "_lower_leg", label + "髌骨、胫骨与腓骨", side + "_lower_leg");
            addBone(body, side + "_foot", label + "跗骨、跖骨与趾骨", side + "_foot");
        }

        // 大脑有独立耐力，和注意组织的精力各自扣费、各自恢复。
        body.addOrgans(OrganDefinition.newBuilder().setId("brain").setDisplayName("脑").setKind("human:brain")
                .setPartId("head").setMaxHp(20).setVolumeCapacity(100).setThroughputPerSecond(1)
                .setEndurance(OrganEndurance.newBuilder().setEnduranceJoules(600).setMaximumWatts(20)
                        .setRecoveryHalfLifeSeconds(30))
                .addPorts(port("brain.in", PortDirection.INPUT)).addPorts(port("brain.out", PortDirection.OUTPUT)));
        // 双眼各自持有损伤与缓存状态，不能用头部 HP 冒充单眼健康程度。
        addOrgan(body, "left_eye", "左眼", "head", 4);
        addOrgan(body, "right_eye", "右眼", "head", 4);
        addLung(body, "left", "左肺");
        addLung(body, "right", "右肺");
        addOrgan(body, "heart", "心脏", "chest", 20);
        addOrgan(body, "liver", "肝脏", "abdomen", 20);
        addOrgan(body, "gallbladder", "胆囊", "abdomen", 8);
        addOrgan(body, "spleen", "脾脏", "abdomen", 12);
        // 脾的主动动员使用独立耐力，不借用经脉容量或脑疲劳。
        int spleenIndex = body.getOrgansCount() - 1;
        body.setOrgans(spleenIndex, body.getOrgans(spleenIndex).toBuilder()
                .setEndurance(OrganEndurance.newBuilder().setEnduranceJoules(600).setMaximumWatts(20)
                        .setRecoveryHalfLifeSeconds(30)));
        addOrgan(body, "stomach", "胃", "abdomen", 15);
        addOrgan(body, "small_intestine", "小肠", "abdomen", 15);
        addOrgan(body, "large_intestine", "大肠", "abdomen", 15);
        addOrgan(body, "left_kidney", "左肾", "abdomen", 12);
        addOrgan(body, "right_kidney", "右肾", "abdomen", 12);
        addOrgan(body, "bladder", "膀胱", "pelvis", 10);

        addMeridians(body);
        return body.build();
    }

    private static void addBone(BodyStructure.Builder body, String id, String name, String partId) {
        // 首轮沿用所属部位的 HP 上限作为骨组初值，但运行状态与部位完全分离。
        PartDefinition part = body.getPartsList().stream().filter(value -> value.getId().equals(partId))
                .findFirst().orElseThrow();
        body.addTissues(TissueDefinition.newBuilder().setId("bone_" + id).setDisplayName(name)
                .setKind("human:bone").setPartId(partId).setMaxHp(part.getMaxHp()).setFatigueCapacity(100)
                .setCapacity(TissueCapacity.newBuilder().setStrengthNewtons(0).setBurstWatts(0).setEnduranceJoules(0)));
    }

    private static void addPart(BodyStructure.Builder body, String id, String name, double hp) {
        // 部位 HP 是总体生命比例的基础，不与内部肌肉或器官 HP 相加。
        body.addParts(PartDefinition.newBuilder().setId(id).setDisplayName(name).setMaxHp(hp));
    }

    private static void addMuscle(BodyStructure.Builder body, String id, String name, String part,
                                  double strengthNewtons, double burstWatts, double enduranceJoules) {
        // 一块具名肌肉是一个模块；力量、功率与耐力独立于真元容量。
        body.addTissues(TissueDefinition.newBuilder().setId(id).setDisplayName(name).setKind("human:muscle")
                // 肌肉疲劳每休息 30 秒减半，具体动作占用期间由框架暂停恢复。
                .setRecoveryHalfLifeSeconds(30)
                .setPartId(part).setMaxHp(10).setFatigueCapacity(100)
                .setCapacity(TissueCapacity.newBuilder().setStrengthNewtons(strengthNewtons)
                        .setBurstWatts(burstWatts).setEnduranceJoules(enduranceJoules)));
    }

    private static void addOrgan(BodyStructure.Builder body, String id, String name, String part, double hp) {
        // 器官缓存以体积计；游离态基准密度下 100 体积对应 1 元。
        body.addOrgans(OrganDefinition.newBuilder().setId(id).setDisplayName(name).setKind("human:" + id)
                .setPartId(part).setMaxHp(hp).setVolumeCapacity(100).setThroughputPerSecond(1)
                .addPorts(port(id + ".in", PortDirection.INPUT))
                .addPorts(port(id + ".out", PortDirection.OUTPUT)));
    }

    private static void addLung(BodyStructure.Builder body, String side, String name) {
        // 两肺分别摄入、受伤和加工；每侧 200 体积沿用游离态下 2 元的原始容量。
        String id = side + "_lung";
        body.addOrgans(OrganDefinition.newBuilder().setId(id).setDisplayName(name).setKind("human:lung")
                .setPartId("chest").setMaxHp(15).setVolumeCapacity(200).setThroughputPerSecond(1)
                .setIntake(OrganIntakeCapability.newBuilder().setMaxQiPerSecond(1))
                .addPorts(port(id + ".in", PortDirection.INPUT))
                .addPorts(port(id + ".out", PortDirection.OUTPUT)));
    }

    private static void addMeridians(BodyStructure.Builder body) {
        // 十二正经各有左右实例；这里只采用中医布局灵感，连接距离是游戏近似值。
        List<String> regularIds = List.of("lung", "large_intestine", "stomach", "spleen", "heart", "small_intestine",
                "bladder", "kidney", "pericardium", "triple_burner", "gallbladder", "liver");
        List<String> regularNames = List.of("手太阴肺经", "手阳明大肠经", "足阳明胃经", "足太阴脾经", "手少阴心经", "手太阳小肠经",
                "足太阳膀胱经", "足少阴肾经", "手厥阴心包经", "手少阳三焦经", "足少阳胆经", "足厥阴肝经");
        for (String side : List.of("left", "right")) {
            for (int index = 0; index < regularIds.size(); index++) {
                String kind = regularIds.get(index);
                boolean armChannel = List.of("lung", "large_intestine", "heart", "small_intestine", "pericardium", "triple_burner").contains(kind);
                List<String> parts;
                if (armChannel) {
                    parts = List.of("chest", "neck", side + "_upper_arm", side + "_forearm", side + "_hand");
                } else {
                    parts = List.of("abdomen", "pelvis", side + "_thigh", side + "_lower_leg", side + "_foot");
                }
                addMeridian(body, side + "_" + kind + "_meridian", sideLabel(side) + regularNames.get(index), parts, 0.5);
            }
            for (int index = 0; index < regularIds.size(); index++) {
                String from = side + "_" + regularIds.get(index) + "_meridian";
                String to = side + "_" + regularIds.get((index + 1) % regularIds.size()) + "_meridian";
                if (regularIds.get(index).equals("large_intestine")) {
                    // 手阳明大肠经“交人中，左之右，右之左”，接到对侧胃经；距离仍取游戏近似 0.3 米。
                    String oppositeSide = side.equals("left") ? "right" : "left";
                    to = oppositeSide + "_stomach_meridian";
                }
                connectMeridians(body, from, to, 0.3);
            }
            // 手太阴肺经“起于中焦”：同侧脾经回肺，八条入门经脉即可组成中焦周天。
            connectMeridians(body, side + "_spleen_meridian", side + "_lung_meridian", 0.3);
            String lung = side + "_lung";
            String channel = lung + "_meridian";
            connect(body, lung + ".to.meridian", lung + ".out", channel + ".in", 0.1);
        }

        // 任、督、冲、带取中轴实例；阴阳跷、阴阳维左右分别实例化，共 36 条经脉。
        addMeridian(body, "ren", "任脉", List.of("head", "neck", "chest", "abdomen", "pelvis"), 1);
        addMeridian(body, "du", "督脉", List.of("head", "neck", "chest", "abdomen", "pelvis"), 1);
        addMeridian(body, "chong", "冲脉", List.of("chest", "abdomen", "pelvis"), 1);
        addMeridian(body, "dai", "带脉", List.of("abdomen", "pelvis"), 1);
        for (String side : List.of("left", "right")) {
            addMeridian(body, side + "_yin_qiao", sideLabel(side) + "阴跷脉",
                    List.of("head", "pelvis", side + "_thigh", side + "_lower_leg", side + "_foot"), 0.5);
            addMeridian(body, side + "_yang_qiao", sideLabel(side) + "阳跷脉",
                    List.of("head", "pelvis", side + "_thigh", side + "_lower_leg", side + "_foot"), 0.5);
            addMeridian(body, side + "_yin_wei", sideLabel(side) + "阴维脉",
                    List.of("neck", "chest", "abdomen", side + "_thigh"), 0.5);
            addMeridian(body, side + "_yang_wei", sideLabel(side) + "阳维脉",
                    List.of("head", "neck", "chest", side + "_thigh"), 0.5);
            link(body, side + "_lung_meridian", "ren");
            link(body, side + "_kidney_meridian", "chong");
            link(body, side + "_gallbladder_meridian", "dai");
            link(body, side + "_yin_qiao", side + "_kidney_meridian");
            link(body, side + "_yang_qiao", side + "_bladder_meridian");
            link(body, side + "_yin_wei", "ren");
            link(body, side + "_yang_wei", "du");
        }
        link(body, "ren", "du");
        link(body, "ren", "chong");
        link(body, "chong", "dai");
    }

    private static void addMeridian(BodyStructure.Builder body, String id, String name, List<String> parts, double volume) {
        // 普通经脉为 50 体积、中轴经脉为 100 体积；游离态容量初值合计 20 元。
        body.addMeridians(MeridianDefinition.newBuilder().setId(id).setDisplayName(name).addAllPartIds(parts)
                .setLumenVolume(volume * 100).setWallVolume(volume * 100)
                .setThroughputPerSecond(1).setResilience(1).setFatigueCapacity(100)
                .addPorts(port(id + ".in", PortDirection.INPUT))
                .addPorts(port(id + ".out", PortDirection.OUTPUT)));
    }

    private static Port port(String id, PortDirection direction) {
        // 端口 ID 含所属模块，保证整具身体内可以唯一寻址。
        return Port.newBuilder().setId(id).setDirection(direction).build();
    }

    private static void connect(BodyStructure.Builder body, String id, String from, String to, double distanceMeters) {
        // 距离与阻力由模板声明，真正耗时、预约及资源转移由框架结算。
        body.addConnections(Connection.newBuilder().setId(id).setFrom(from).setTo(to)
                .setResistance(0).setDistance(distanceMeters));
    }

    private static void link(BodyStructure.Builder body, String first, String second) {
        // 双向交汇显式使用两条有向连接，保留各方向独立的引用。
        connectMeridians(body, first, second, 0.2);
        connectMeridians(body, second, first, 0.2);
    }

    private static void connectMeridians(BodyStructure.Builder body, String from, String to, double distanceMeters) {
        // 自然扩散使用每路最多 0.5 元/秒，仍共享各经脉 1 元/秒的收发吞吐。
        // 小批阈值保留微小余差，平衡后停止事件调度；肺器官出口仍由摄入流程主动发送。
        body.addConnections(Connection.newBuilder().setId(from + ".to." + to)
                .setFrom(from + ".out").setTo(to + ".in").setResistance(0).setDistance(distanceMeters)
                .setPassiveQiFlow(PassiveQiFlow.newBuilder().setThroughputPerSecond(.5)
                        .setMaximumBatchQi(.25).setMinimumBatchQi(.0001)));
    }

    private static String sideLabel(String side) {
        // 英文局部身份保持稳定，中文只用于展示。
        if (side.equals("left")) {
            return "左";
        }
        return "右";
    }
}
