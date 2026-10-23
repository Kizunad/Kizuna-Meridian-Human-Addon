# 依赖、构建与宿主接入

## 可信区域环境服务

服务端附属通过 Fabric entrypoint `meridian_environment_host` 获取服务对象，再反射调用公开的 `exchange(byte[])`，返回值为 `byte[]`。Human 内部绑定使用 `java.util.function.Function<byte[], byte[]>`；调用方不依赖 Human Java 接口。该服务不注册客户端网络频道，所有操作必须在服务器主线程调用。

线协议由 `shared/src/main/proto/kizuna/meridian/human/environment_host.proto` 生成，Protobuf 包为 `kizuna.meridian.human.v1`，Java 包为 `dev.kizuna.meridian.human.protocol.pb`，外部类为 `EnvironmentHostMessages`。请求版本固定为 1，`request_id` 不得为空白，必须选择一项 operation；响应回显请求 ID。操作编号固定为 `query=10`、`release=11`、`clock=12`。`clock` 必须为 true，通过 `now` 返回当前世界 epoch 和主世界累计 tick 对应的模拟时间。`RegionQuery` 使用 `dimension_id=1` 和方块坐标 `block_x=2`、`block_z=3`；维度 ID 必须为完整规范 ID，且维度实际存在。

`RegionRelease` 字段为 `release_id=1`、`position=2`（`RegionQuery`）、`released_qi=3`（框架 `QiInventory`）。`RegionView` 直接提供 `source_id=1`、`volume_blocks=2`、`inventory=3`、`concentration=4`、`source_revision=5`。`ReleaseResult` 提供可选 `source_id=1` 和 `duplicate=2`；无区域来源时不设置来源 ID。响应结果编号固定为 `unbound=10`、`region=11`、`released=12`、`now=13`、`failure=14`。

`release_id` 是释放事实的去重键，保留七个游戏日，仍在待入账队列中的记录不会过期；重试必须保留原 ID、位置与库存。调用方先持久化自己的待释放事实，成功返回 `released`（含 `duplicate=true`）后清除。受理只表示进入世界持久化队列，库存统一在 30 秒供给续窗时更新。接入顺序、存档边界与调用方重试约定见[区域环境服务](Environment-Host.md)。区域查询和释放通过同一 WorldState 与框架权威 Store 执行，不能另存一份环境账本。玩家进入使用 `join(ServerPlayerEntity, long)`，由 WorldState 在恢复身体前绑定该玩家的释放 sink。

失败使用框架 `RequestFailure` 的原始失败码：无法解析、超限、空请求 ID、缺操作、`clock=false`、非法库存或非法维度格式为 `INVALID_REQUEST`；版本不符为 `UNSUPPORTED_VERSION`；非主线程调用为 `ACCESS_DENIED`；维度不存在为 `ENVIRONMENT_UNAVAILABLE`；相同释放 ID 对应不同内容为 `REQUEST_ID_CONFLICT`；宿主内部异常为 `INTERNAL_ERROR`。存在的非主世界没有区域，查询返回未设置 result 的成功响应，释放返回未设置 `source_id` 的 `released`。加载阶段服务尚未绑定或没有运行世界时返回 `unbound=true`，与业务拒绝分开处理；这不提供缺失必装模块的替代路径。

开脉条件 `HumanOpeningConditions` 的唯一 protoc 源位于 `shared/src/main/proto/kizuna/meridian/human/v1/opening.proto`，由 Human 服务端裁定，Human-UI 读取已固定任务的维持阈值显示预警。协议字段与运行规则未改变；生成类从 server 移至 shared，客户端无需引用服务端实现。更新时同步构建 Human shared、client、server、fabric 及 Human-UI。

## 周天、外壁与相变接线

规则和调试命令见[中焦周天](Middle-Jiao-Cultivation.md)。新增 `shared/` 子项目通过 protoc 生成 `CultivationInput` / `CultivationState`，client、server 与 Human-UI 共用；`CultivationState.automatic_waiting` 表示自动周天保留意图但暂停做功，服务端恢复到启动线后续跑。Fabric 安装包内嵌 shared，开发全装配脚本显式加载。网络频道 `meridian_human:cultivation` 只接操作边沿和按键tick，绑定真实玩家及服务端时钟，每秒共用40包输入上限。未读残卷拒绝，重置/断线/关窗取消主动周天与动员及自动等待。

`HumanPlayerBody` 在250ms活动边界生成可信 Protobuf 计划，经 Framework `handleActivity` 原子执行；空闲且没有储备交换时不轮询。脾配方完成仍走 Framework 器官事件。所有模块在适用端必装，没有缺包分支。

## 残卷进度与主动修炼授权

Progression 是必装依赖。`SERVER_STARTING` 安装当前服务器的 `ProgressionStore`；读取和写入定位 UUID 绑定的同一 `HumanPlayerBody`。`meridian_progression:progress` 使用 `PlayerProgress` Any 保存在身体，初始版本 1、未读；写入通过 Framework 的可信附属事务，随 Human 世界存档标脏保存，重启和玩家实体替换保留。

吐纳和开脉在实际 `HumanPlayerBody` 请求入口独立检查已读状态，未读返回 `ACCESS_DENIED`。内观在 `meridian:body_view_request` 的 `BodyViewRequest` 订阅入口校验同一进度，未授权不发布开脉规则与操作卡。基础身体检视、出生 0.36 元、自然扩散、普通运动与 OP 真元调试保持各自业务流程。

重置在服务器主线程结束吐纳、暂停开脉、解除动作占用、停止打坐动画并撤销内观订阅，再发布权威进度与新快照。没有第二份解锁布尔值或独立存档。客户端隐藏入口和关窗由 UI 使用 Progression 投影完成。

## 玩家真元调试命令

Minecraft 入口由本包必装 Fabric 宿主注册，实际入账算子、协议、容量校验和事务在 Meridian Framework。必须同步更新两个产物。

| 指令 | 用途 |
|---|---|
| `/meridian zhenyuan` | 查看全身内腔、外壁、在途及身体总账 |
| `/meridian zhenyuan get <经脉ID>` | 查看指定脉的内腔、稳定容量和开拓状态 |
| `/meridian zhenyuan fill` | 补满全身经脉内腔 |
| `/meridian zhenyuan fill <经脉ID>` | 补满指定脉 |
| `/meridian zhenyuan fill <经脉ID> <目标总量>` | 补足至指定储量，已有更多时不扣除 |
| `/meridian zhenyuan diffuse <新增量> [经脉ID]` | 从单个入口注入，按真实通路逐步扩散；省略入口时取肺出口实际连接的经脉 |

写入需要权限等级 2（单机开启作弊或服务器管理员），ID 支持 Tab 补全。默认只注入密度 `0.01` 的游离态无属性真元，保留在途、外壁、其他属性、开拓与疲劳；扣除预约后的体积不足会整笔拒绝，不截断剂量。开脉运行中同样受稳定体积限制。

建议先用 `diffuse 0.2` 观察肺经到全身的流动，再用 `fill` 准备开脉。游炁时全身内腔稳定体积为 2000，启动线为 1600；普通目标脉初始体积为 50，启动线为 40。按游离态密度 `ρ = 0.01`，后者相当于 0.4 元；可用 `fill left_lung_meridian 0.45` 在目标仍有空位时测试局部门槛。`diffuse` 模拟炼化后入脉，不模拟环境扣账、肺加工或近期摄入证明；需要持续吐纳的规则仍须真实吐纳。

调试只操作当前玩家，通过框架唯一命令路径、去重回执与世界持久化；执行后刷新同一身体快照。调试创造的真元不计入吐纳「累计纳入」，可在其悬浮总账或本查询命令查看。

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

Human 清单声明 API 修订 `[1,1]`，要求九项 `meridian:` 框架能力：`body_templates_v1`、`organ_recipes_v1`、`organ_intake_v1`、`actions_v1`、`tissue_recovery_v2`、`passive_qi_flow_v1`、`opening_definitions_v1`、`opening_runtime_v1`、`organ_endurance_v1`。须同步使用支持这些契约的框架 checkout；不支持开脉运行的宿主不能只安装定义后宣称完整可用。框架保留命名空间 `meridian` 不是可以注册的普通包，因此不能伪造同名 `AddonDependency`。

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

`registry.recipes()`、宿主环境来源、`registry.actions()`、`registry.openings()` 分别传入框架 `BodyCommandHandler` 第六至第九个参数。宿主提供真实身份授权、私有策略、可信时间、区间位置来源与事务存储；Human 不替宿主绕过这些边界。基本聚气使用 [GatheringRequests 请求流程](Gathering.md)，已接入的图形选脉及命令操作见[修炼入口](Cultivation.md)，未来 3D 编辑器可走相同命令。

宿主根据真实行走/跑步状态提交 `start_action` / `stop_action`，每次切换前先结算至该事件时刻；玩家不能自己报告停止跑步以骗取恢复。吐纳会话已经管理 `human:breathing` 实例，关闭会话须在撤销权限前执行；清理失败交给可信宿主重试。动画播放请求与身体动作实例分别管理，播放成功不等于动作已登记或费用已支付。

## 可信伤害与恢复

Java 宿主将 `HumanDamage` 装配为框架 `BodyDamageHandler`；必装 Fabric 宿主已完成该装配。处理器接收 `DamageContext` 的 Protobuf 字节，返回 `DamageResolution`，按八类致伤机制计算局部分配与人体死亡结果，不写身体或存档。未提供命中点时复用框架的存活部位随机选择；精确组织/器官与 `CUSTOM` 分类只扣指定账户。框架固定该结果用于事务复核与幂等重试，统一保存局部 HP 和死亡释放。

宿主确认效果来源、身份和时间后，通过 `BodyCommandHandler.handleHealthEffect` 提交 `DamageBody`、`HealBody`、`RestoreFatigue`、`ConfirmBodyDeath` 或 `SetPartHealthLimits`。普通网络命令入口拒绝这些效果，客户端不能自行报告伤害、治疗或恢复成功。治疗和恢复剂量由实际物品或能力附属提供，Human 不新增第二套算子。经脉结构伤势修复与扣物品联合事务仍待实现。

Fabric 宿主通过 `meridian_health_host` entrypoint 提供 Framework `BodyHealthHost`：交换 `HealthHostRequest` / `HealthHostResponse` Protobuf 字节，只允许服务端主线程操作在线且实际绑定的玩家。查询先结算可信模拟时间，效果保留调用方请求 ID 和预期 revision；成功效果附带由同一请求回执读取的 `HealthResult`，供效果附属核验保命事实，查询不附带。幂等重试返回当前身体与原始固定事实；宿主暂停或拒绝时返回原失败码，不能转由另一套账本处理。身体、回执与释放账户仍保存于同一个世界状态。

Minecraft 原版事件由独立 Meridian Vanilla Damage Addon 接线：护甲阶段先固定命中部位，用 TOML 覆盖关系与单件护甲/韧性公式取代全局护甲，随后保留原版抗性、全身保护附魔与吸收生命，再将完整结算剂量映射到身体。身体因伤判死时同步实体死亡，原版死亡以 `ConfirmBodyDeath` 确认。Human 只提供人体处理器和上述宿主入口，不直接监听原版伤害或保存装备覆盖规则。死亡确认保留局部伤势；原版重生不自动治疗、补真元或创建新身体，复活规则另定。具体人体分配见[人体定义](Human-Definition.md)。旧存档不迁移；已有身体加载时保持原构型，不自动补入新增骨组。

必装 Vanilla Healing Addon 将原版治疗通过上述宿主均分到部位 HP，原版血条只显示身体比例；全局吸收改为各部位独立黄心。图腾通过可信 `DamageBody.death_protection` 在死亡清账前提供保命下限，不需要 Human 内置图腾逻辑，也不复活已经死亡的身体。普通治疗不清除独立组织、器官损伤；生命提升的可配置上限增量也由 Healing 附属声明并撤销。

## 整体验证

`HumanGatheringTest` 保留交付 JAR/依赖关系、五行及左右肺聚气与守恒、36 脉自然储元与连续聚气、全身满载及授权/容量失败、加工/在途保存恢复、幂等重试与手动吐纳整体回归。中焦周天回归仅保留游炁八脉之间的连接，逐边验证真实扩散与守恒，并核对保留脾→心主干后的 24 条正经大环。人中光迹坐标和客户端表现测试归独立 Human-UI 仓库。动作覆盖行走和吐纳并存、停气释放、指数恢复以及外部版本变化后的占用清理；框架内部算法测试不在人体仓库逐项复制。

`HumanHealthTest` 保护实际人体模板出生后的独立皮肤、骨骼、肌肉、器官和部位 HP，八类机制的局部反应与缺层处理，随机命中接入，以及头部归零、总体严格低于 20% 的人体判死边界。展示回归核对模型绑定的独立组织均存在于实际模板。

新增自定义宿主、改变模板或配方后，应重跑 `build` 与 `demo`。`HumanPlayerBodyTest` 保护运动切换结算、局部占用、指数恢复、并发动作保留、存档重载与耗尽。专服多人和 Bong 的独立部署验收仍需分别完成。

```bash
./gradlew build demo :fabric:runGametest --offline --console=plain --no-watch-fs
python3 "scripts/check_docs.py"
```

离线命令要求依赖已缓存，首次构建可去掉 `--offline`。`HumanMeridianGameTest` 在真实 Minecraft 服务端用测试玩家身份创建身体，通过正式玩家宿主从左肺经注入真元，确认跨侧扩散、Protobuf 保存恢复、真元守恒和被动流动不自动开脉。测试入口只位于 `gametest` 源集，不进入发布 JAR；运行目录为 `fabric/build/gametest`。该检查不包含 Windows 客户端画面或多人压力验收。

## Minecraft 行走、疾跑与数值查看

先构建完整装配，直接运行 `./gradlew :fabric:runClient`；专服使用 `:fabric:runServer`。`fabric/` 目标 Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1、Java 17。开发运行统一应用 Framework 的 `scripts/full-assembly.gradle`，固定读取 Meridian 九仓及 Inventory UI、BS3D 的安装产物；不存在安装开关。正式部署按适用端安装整套产物及其依赖，Items 作为服务端宿主库提供。

身体模型窗口使用必装的 Meridian UI、Human-UI、Inventory UI 和 owo。进世界按 `I` 打开工作台，进入“检视”，选择肌肉查看基础力量、爆发、耐力、疲劳、动作占用和恢复半衰期。已连接客户端缺少必需接收通道时明确报错并暂停身体，不静默丢弃推送。

“经脉”窗口通过 `meridian:opening_request` 提交框架 `MeridianOpeningRequest`，`meridian:opening_response` 返回 `ResponseEnvelope`。宿主绑定当前玩家、在服务器线程串行执行，与吐纳共享每秒 40 包额度；格式异常只拒绝该包，旧连接消息丢弃。首次 `meridian:body_view` 附带 Human 规则选项，后续按身体 revision 推送变化；客户端不上传时间或开脉结果。断线暂停开脉并清除连接回执缓存，已完成成长仍由原身体保存。

| 命令 | 用途 |
|---|---|
| `/meridian status` | 当前动作、下肢最小余力、身体 revision |
| `/meridian watch on` / `off` | 开关快捷栏上方的实时下肢余力 |
| `/meridian tissue left_rectus_femoris` | 左股直肌实际疲劳、当前耐力/力量/功率；ID 支持补全 |

识别基于服务端实际水平位移、地面状态和疾跑标志。静止按住疾跑不占用；乘坐、飞行、游泳、明显传送与受击区间过滤。它是基础可信适配，不是完整移动反作弊；宿主仍可提供自己的位置校验。

行走与疾跑占用人体已定义的八块下肢肌肉：每秒分别申请各自基础耐力的 `1/300` 和 `1/75` 作为做功量，由框架现有可用功率/耐力上限截断。健康低疲劳时，10 秒行走约增加 3.33/100 疲劳，10 秒疾跑约增加 13.33/100；接近耗尽后实际做功减小，不能理解为固定第 75 秒归零。这是首轮玩法初值，不是现实生理参数。未占用肌肉按原有 30 秒半衰期指数恢复；其他动作占用同一肌肉时不会恢复。

每 tick 只采样；动作切换和已有事件边界先结清旧区间，持续运动/有疲劳时最多每模拟秒常规结算一次，满恢复静止后停止该轮询。打开 UI 的渲染帧不扣资源。时间取主世界累计 tick，`/time set` 不改变它；单机暂停停止计时。

位置数据包可能隔 tick 到达，运动观察器保留两 tick 停步确认，第三个连续静止样本才切回静止，避免持续走跑逐 tick 重启。真实位移信号独立保留，吐纳期间移动仍在首次实际位移时正式结束会话。2026-10-21 Windows 完整装配已验证走路→疾跑→停止、正式吐纳与开脉附着、转头不结束吐纳以及移动结束后切行走。

`data/meridian_human.dat` 同时保存 Protobuf 身体、成功回执和释放账户。重连只推进离线世界模拟时间对应的寿命，疲劳冻结；停止残留移动实例，但保留其他附属动作。存档加载失败暂停人体功能并保留文件，不自动新建覆盖。当前复用框架内存参考 Store，回执没有 TTL，长期大服须另接具有保留策略的持久化 Store。

运动绑定实际肌肉消耗、恢复及只读观察；尚未用疲劳调整原版移速或饥饿条。原版生命映射通过必装 Vanilla Damage Addon 接入，死亡/重生不重建框架身体，复活规则另定。

## Minecraft 真实玩家吐纳

Fabric 宿主现在接收 `meridian:gathering_request`，返回 `meridian:gathering_response`，载荷分别为框架原有 `GatheringRequest` / `GatheringResponse`。身份只取连接玩家 UUID，时间取服务端模拟 tick；客户端只能上传公开的吸呼方向、瞄准和序号。网络入口限制消息大小及每模拟秒请求数；同一身体的摄入、行走、恢复与保存共用权威 Store。Human 源码不引用 UI 类，运行时 UI 仍是必装接线。

主世界按 `config/meridian-environment.toml` 的区域尺度与默认地形配置首次生成库存，读取区域中心群系元数据，不扫描方块。相同区域内玩家共享来源和供给预算；换区先结清旧区间，30 秒续窗保留剩余库存。其他维度暂不提供环境摄入。身体、区域、释放账户、待入账队列、释放 ID 和回执一起保存到 `meridian_human.dat`；玩家死亡、开脉中断和加工损耗按发生时位置排队，在区域续窗时从默认账户转回区域，非主世界留在默认账户。肺摄入回执的炼化损耗仍直接归还选定区域，不重复归位。当前接入未执行区域间扩散。

进世界按 `I` → 吐纳：原三维交互直接操作当前玩家身体。有效吸呼占用膈肌与注意组织，停止后按实际空闲区间恢复；自动吐纳低于80%时释放膈肌占用并保留自动意图，服务端在耐力恢复到启动线后续跑，等待期间不补算吸呼或摄入。连接重建不补满身体；断线清理本宿主会话占用和自动等待，异常保存留下的占用在重连时清理，其他附属动作保留。已摄入的原料和在途货物保存为现有事实。

Kizuna Players Animation 为客户端和服务端必装依赖，先构建同级动画仓库，再构建 Human。开发运行固定装配其 JAR，打坐还要求动画资源包含 `meditate` 和模型。

`HumanPlayerAnimation` 统一选择权威吐纳对应的 `meditate`、行走对应的 `walk` 和疾跑对应的 `run`，直接调用 `ServerPlayerAnimations.play/stop`。只在动作改变时停止旧动作、播放新动作，持续输入和数值推送不重新开始动画。只管理本宿主登记的动作，不停止其他附属动画。唯一同步契约为 Players-Animation 的 `player_animation.proto`（play/stop/result），请求 ID 返回值用于诊断；客户端回执不参与身体授权或开脉完成判定。

服务端观察到实际位置变化时，手动吐纳先通过正式 `END_BREATHING` 请求结清旧呼吸区间、释放膈肌和注意组织占用并发布停止回执；自动吐纳则继续由服务端推进，不依赖 UI 心跳。两种模式都按实际位置显示行走或疾跑步态；自动吐纳静止时显示打坐。只转头不改变吐纳状态。停止移动不会自动开始吐纳。换维度和重生重建会话，必须重新开始；明确结束、失能和断线清理本宿主动作用。缺必需通道在正常协商期限之后明确报错。

动画 JAR 内含双体型模型和 `walk/run/meditate` 等资源，完整装配不再启用外置资源补丁。吐纳预览按实际玩家体型取样同一骨架，器官和开脉光迹随骨矩阵附着。以下 2026-10-14 记录属于历史 checkout；本轮整合的验证记录单独列出。

2026-10-21 原仓 Java 17 构建、聚气 demo、33 项整体回归和 3 项 GameTest 通过。Windows 完整装配通过真实走路→疾跑→停止、持续动作请求不重启、正式吐纳与开脉附着、转头保持和实际移动正式结束会话。双客户端分别使用 default、slim 体型，完成本人/追踪者、远处排除、卸载重入、原始播放起点、重复补发、换维度、实体替换重生、断线及重连后新动作验收。服务端标记 `NETWORK_ANIMATION_ALL_PASS` 与双方客户端通过文件齐全，正常保存退出；逐帧美术校准仍独立待办。

2026-10-14 已完成隔离 Fabric 专服与真实客户端联调：通过 Human 的 Protobuf 开始、持续输入和结束请求，确认权威吐纳状态触发客户端实际 `meditate` 播放；重复数值推送不重播，结束停止，再次开始正常播放，换维度同时结束吐纳与动画。验证读取真实客户端播放器状态与播放序号，双方正常关闭。临时游戏驱动验证后删除；此次验收不包含多人压力测试。

整体回归包含 20 Hz 输入、移动正式结束吐纳及迟到输入拒绝、真实炼化入脉、同区域共享、身份拒绝、真元守恒以及保存重连后的恢复。

2026-10-14 真实集成服务器吐纳验收已通过：在正式玩家窗口点击开始并进行约 40 秒三维吸呼引导，累计摄入 0.75 元环境灵气，炼化入经脉 0.51 元无属性真元；膈肌耐力与精力同步消耗，NBT 保存重载保持身体与回执一致。验收同时确认 `meditate` 动画资源加载和盘坐预览；临时游戏驱动验证后删除。

2026-10-14 已在真实 Minecraft 1.20.1 集成服务器验收：通过客户端持续前进和疾跑输入，服务端识别到两种动作；停下约 31 秒后左股直肌疲劳从约 15.10 降至 7.38；NBT 保存/读取保持身体及回执；Protobuf 推送成功打开真实玩家身体窗口。临时游戏驱动验证后删除，保留以上必要整体回归。Windows 原生开发装配已启动，用户可在独立世界中手动测试。该验收不代表专服多人压力测试。
