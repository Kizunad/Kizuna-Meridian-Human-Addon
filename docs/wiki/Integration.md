# 依赖、构建与宿主接入

## 独立仓库与依赖

仓库名 `Kizuna-Meridian-Human-Addon`，项目名 `Kizuna's Meridian Human Addon`。首片验证的经脉框架基线是 `90d2205`。框架没有人体特例或反向依赖。

```text
Human client → 框架 client 的 protoc 消息（编译依赖）
Human server → 框架 server 公共编解码/内容校验 API
宿主 → 框架权威处理器 + Human 的 Protobuf 内容
其他功法附属 → human 内容依赖（显式声明版本范围）
```

`settings.gradle` 使用 Gradle composite build 引用独立框架仓库。`local.meridian:client/server` 只是本地项目替换键，不是已发布 Maven 坐标。默认相邻目录；可用以下命令指定：

```bash
./gradlew -PmeridianFrameworkDir="/path/to/Kizuna-Meridian-Mod-Framework" build
./gradlew demo
python3 "scripts/check_docs.py"
```

Java 编译、测试、内容导出与演示均使用 17。框架自己的构建负责 protoc 生成；Human 直接使用生成消息，不复制 `.proto`。人体模型所需的 `presentation.proto` 已在框架提交 `bc7cdfc` 中提供，旧基线 `90d2205` 不包含该文件。开发构建依赖指定 checkout 的实际源码，不会自动锁定或拉取远端提交，升级需重跑整体回归。

构建生成：

- `client/build/libs/kizuna-meridian-human-addon-client-0.1.0-SNAPSHOT.jar`：Protobuf 操作意图构造器与 `assets/meridian_human/meridian/adult.pb` 展示资源。
- `server/build/libs/kizuna-meridian-human-addon-server-0.1.0-SNAPSHOT.jar`：人体定义与出生候选，内含 `META-INF/kizuna_meridian/addons/human.pb`。
- `server/build/generated/human-content/META-INF/kizuna_meridian/addons/human.pb`：同一内容的独立二进制文件，其他语言宿主也可读取。

单独 `client/server` 内容 JAR 不内嵌框架或运行库。另有 `fabric/build/libs/kizuna-meridian-human-addon-fabric-0.1.0-SNAPSHOT.jar` 安装包，通过 Loom 嵌套原始内容、框架与运行依赖 JAR。它的 `meridian_human_host` 入口负责 Minecraft 适配，客户端资源仍由 `meridian_human` 容器提供；内容库没有 UI 反向依赖。

## 内容前置

Human 清单声明 API 修订 `[1,1]`，要求 `body_templates_v1`、`organ_recipes_v1`、`organ_intake_v1`、`actions_v1`、`tissue_recovery_v2` 五项 `meridian:` 框架能力。后两项对应动作占用和指数恢复，须同步使用支持这些契约的框架 checkout。框架保留命名空间 `meridian` 不是可以注册的普通包，因此不能伪造一条名为 `meridian` 的 `AddonDependency`。

本包没有其他生物内容包前置。其他附属使用 `human:lung` 等类型时，必须显式声明 `human` 及允许的包修订区间。例如以下是 `AddonManifest` 中的 Protobuf TextFormat 字段片段：

```text
dependencies {
  namespace: "human"
  versions { minimum: 1 maximum: 1 }
}
```

这是框架现有字段的说明，不是另一套 JSON 配置或 Java 回调协议。清单兼容宿主 ID 约定为 `meridian:java_reference`、`meridian:fabric`、`bong:server`。本仓已有 Java 参考宿主与 Fabric 玩家适配；Bong 尚需独立装配。

## 可信安装与首次创建

宿主从管理员安装来源读取 `human.pb`，调用 `AddonProtobuf.decodeBundle`，与其他包和自身能力组成 `AddonRegistration`，由 `AddonRegistry.load` 一次性校验。Java 宿主也可调用 `HumanContent.payload()` 取得同一字节。

模板 ID 为 `human:adult`，定义修订 1。Java 首次创建流程使用已有真实 API：

```java
// registration 来自宿主可信安装流程，bodyId 和 initialTime 也由宿主分配。
var registry = AddonRegistry.load(registration);
var template = registry.findTemplate(HumanContent.TEMPLATE_ID, 1).orElseThrow();
byte[] candidate = HumanBirth.candidate(template, bodyId, initialTime);
var initialBody = BodyProtobuf.decodeSnapshot(candidate);
```

`candidate` 只是可信初始化候选。宿主须保证身份唯一、只首次创建一次并禁止客户端快照覆盖。框架尚无通用创建身体命令，此 helper 不能注册为普通玩家请求。Fabric 玩家适配以 UUID 绑定身体，通过 Minecraft 世界存档保存；内存聚气演示仍使用自己的隔离状态。

跨语言宿主读取同一个 `AddonBundle` 得到完整构型和配方，可接收可信 Java 初始化方输出的 `BodySnapshot` 字节；若独立实现出生初始化，还须遵循[人体初值](Human-Definition.md)，当前包清单尚无通用出生参数字段。Java 函数不是跨语言通信要求。

`registry.recipes()`、宿主环境来源和 `registry.actions()` 分别传入框架 `BodyCommandHandler` 第六至第八个参数。宿主提供真实身份授权、私有策略、可信时间、区间位置来源与事务存储；Human 不替宿主绕过这些边界。基本聚气使用 [GatheringRequests 请求流程](Gathering.md)，未来界面和 3D 编辑器可走相同入口。

宿主根据真实行走/跑步状态提交 `start_action` / `stop_action`，每次切换前先结算至该事件时刻；玩家不能自己报告停止跑步以骗取恢复。吐纳会话已经管理 `human:breathing` 实例，关闭会话须在撤销权限前执行；清理失败交给可信宿主重试。动画播放请求与身体动作实例分别管理，播放成功不等于动作已登记或费用已支付。

## 整体验证

`HumanGatheringTest` 保留交付 JAR/依赖关系、五行及左右肺聚气与守恒、授权/容量失败、加工/在途保存恢复、幂等重试与手动吐纳整体回归。动作覆盖行走和吐纳并存、停气释放、指数恢复以及外部版本变化后的占用清理；框架内部算法测试不在人体仓库逐项复制。

新增自定义宿主、改变模板或配方后，应重跑 `build` 与 `demo`。`HumanPlayerBodyTest` 保护运动切换结算、局部占用、指数恢复、并发动作保留、存档重载与耗尽。专服多人和 Bong 的独立部署验收仍需分别完成。

## Minecraft 行走、疾跑与数值查看

先构建，直接运行 `./gradlew :fabric:runClient`；专服使用 `:fabric:runServer`。`fabric/` 目标 Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1、Java 17。安装时将 fabric 安装 JAR 与 Fabric API 放入隔离游戏目录的 `mods`。

可选身体模型窗口还需已构建的 Meridian UI、Inventory UI 和 owo。相邻仓库已构建时，`./gradlew -PwithBodyUi=true :fabric:runClient` 自动装载这些可选依赖。进世界按 `I` 打开工作台，进入“身体”，选择肌肉查看基础力量、爆发、耐力、疲劳、动作占用和恢复半衰期。

| 命令 | 用途 |
|---|---|
| `/meridian status` | 当前动作、下肢最小余力、身体 revision |
| `/meridian watch on` / `off` | 开关快捷栏上方的实时下肢余力 |
| `/meridian tissue left_rectus_femoris` | 左股直肌实际疲劳、当前耐力/力量/功率；ID 支持补全 |

识别基于服务端实际水平位移、地面状态和疾跑标志。静止按住疾跑不占用；乘坐、飞行、游泳、明显传送与受击区间过滤。它是基础可信适配，不是完整移动反作弊；宿主仍可提供自己的位置校验。

行走与疾跑占用人体已定义的八块下肢肌肉：每秒分别申请各自基础耐力的 `1/300` 和 `1/75` 作为做功量，由框架现有可用功率/耐力上限截断。健康低疲劳时，10 秒行走约增加 3.33/100 疲劳，10 秒疾跑约增加 13.33/100；接近耗尽后实际做功减小，不能理解为固定第 75 秒归零。这是首轮玩法初值，不是现实生理参数。未占用肌肉按原有 30 秒半衰期指数恢复；其他动作占用同一肌肉时不会恢复。

每 tick 只采样；动作切换和已有事件边界先结清旧区间，持续运动/有疲劳时最多每模拟秒常规结算一次，满恢复静止后停止该轮询。打开 UI 的渲染帧不扣资源。时间取主世界累计 tick，`/time set` 不改变它；单机暂停停止计时。

`data/meridian_human.dat` 同时保存 Protobuf 身体、成功回执和释放账户。重连只推进离线世界模拟时间对应的寿命，疲劳冻结；停止残留移动实例，但保留其他附属动作。存档加载失败暂停人体功能并保留文件，不自动新建覆盖。当前复用框架内存参考 Store，回执没有 TTL，长期大服须另接具有保留策略的持久化 Store。

本轮绑定实际肌肉消耗、恢复及只读观察；尚未用疲劳调整原版移速、饥饿条或 MC 生命值。原版死亡/重生不重建框架身体，后续生命映射需独立规则。

## Minecraft 真实玩家吐纳

Fabric 宿主现在接收 `meridian:gathering_request`，返回 `meridian:gathering_response`，载荷分别为框架原有 `GatheringRequest` / `GatheringResponse`。身份只取连接玩家 UUID，时间取服务端模拟 tick；客户端只能上传公开的吸呼方向、瞄准和序号。网络入口限制消息大小及每模拟秒请求数；同一身体的摄入、行走、恢复与保存共用权威 Store。Human 不依赖 UI，其他界面可按相同 Protobuf 接入。

主世界按 `config/meridian-environment.toml` 的区域尺度与默认地形配置首次生成库存，读取区域中心群系元数据，不扫描方块。相同区域内玩家共享来源和供给预算；换区先结清旧区间，30 秒续窗保留剩余库存。其他维度暂不提供环境摄入。身体、区域、释放账户和回执一起保存到 `meridian_human.dat`；当前接入未执行区域间扩散，释放仍保留在唯一释放账中。

进世界按 `I` → 吐纳：原三维交互直接操作当前玩家身体。有效吸呼占用膈肌与注意组织，停止后按实际空闲区间恢复。连接重建不补满身体；断线清理本宿主会话占用，异常保存留下的占用在重连时清理，其他附属动作保留。已摄入的原料和在途货物保存为现有事实。

打坐预览为可选动画资源，须另外安装包含 `meditate` 的 Kizuna Players Animation。开发启动可使用 `./gradlew -PwithBodyUi=true -PplayerAnimationDir="/path/to/animation-checkout" :fabric:runClient`，先构建该动画 checkout。动画目前只参与吐纳窗口内的模型预览，外世界玩家播放请求仍待动画侧提供处理器。

整体回归包含 20 Hz 输入、运动与吐纳共存、真实炼化入脉、同区域共享、身份拒绝、真元守恒以及保存重连后的恢复。

2026-10-14 真实集成服务器吐纳验收已通过：在正式玩家窗口点击开始并进行约 40 秒三维吸呼引导，累计摄入 0.75 元环境灵气，炼化入经脉 0.51 元无属性真元；膈肌耐力与精力同步消耗，NBT 保存重载保持身体与回执一致。验收同时确认 `meditate` 动画资源加载和盘坐预览；临时游戏驱动验证后删除。

2026-10-14 已在真实 Minecraft 1.20.1 集成服务器验收：通过客户端持续前进和疾跑输入，服务端识别到两种动作；停下约 31 秒后左股直肌疲劳从约 15.10 降至 7.38；NBT 保存/读取保持身体及回执；Protobuf 推送成功打开真实玩家身体窗口。临时游戏驱动验证后删除，保留以上必要整体回归。Windows 原生开发装配已启动，用户可在独立世界中手动测试。该验收不代表专服多人压力测试。
