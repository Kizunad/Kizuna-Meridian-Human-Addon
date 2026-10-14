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

宿主需提供对应框架及其运行依赖。Human JAR 不内嵌框架、Protobuf 运行库、测试或演示代码。客户端含 `meridian_human` Fabric 资源容器清单，使模型进入资源管理器；无运行入口、渲染依赖或 UI 反向依赖，服务端仍为 Java 内容库。

## 内容前置

Human 清单声明 API 修订 `[1,1]`，要求 `body_templates_v1`、`organ_recipes_v1`、`organ_intake_v1`、`actions_v1`、`tissue_recovery_v2` 五项 `meridian:` 框架能力。后两项对应动作占用和指数恢复，须同步使用支持这些契约的框架 checkout。框架保留命名空间 `meridian` 不是可以注册的普通包，因此不能伪造一条名为 `meridian` 的 `AddonDependency`。

本包没有其他生物内容包前置。其他附属使用 `human:lung` 等类型时，必须显式声明 `human` 及允许的包修订区间。例如以下是 `AddonManifest` 中的 Protobuf TextFormat 字段片段：

```text
dependencies {
  namespace: "human"
  versions { minimum: 1 maximum: 1 }
}
```

这是框架现有字段的说明，不是另一套 JSON 配置或 Java 回调协议。清单兼容宿主 ID 约定为 `meridian:java_reference`、`meridian:fabric`、`bong:server`；只有第一个已跑实际接入验证，其他两个表示内容使用的通用能力可供相应适配方实现，不宣称宿主已经上线。

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

`candidate` 只是可信初始化候选。真实宿主仍须保证身份唯一、只首次创建一次、原子保存，并禁止客户端提交快照覆盖已有身体。框架尚无通用创建身体命令，此 helper 不能注册为普通玩家请求。当前内存演示用 `InMemoryBodyStore` 首次装载；不代表已经有游戏实体生命周期或磁盘存档。

跨语言宿主读取同一个 `AddonBundle` 得到完整构型和配方，可接收可信 Java 初始化方输出的 `BodySnapshot` 字节；若独立实现出生初始化，还须遵循[人体初值](Human-Definition.md)，当前包清单尚无通用出生参数字段。Java 函数不是跨语言通信要求。

`registry.recipes()`、宿主环境来源和 `registry.actions()` 分别传入框架 `BodyCommandHandler` 第六至第八个参数。宿主提供真实身份授权、私有策略、可信时间、区间位置来源与事务存储；Human 不替宿主绕过这些边界。基本聚气使用 [GatheringRequests 请求流程](Gathering.md)，未来界面和 3D 编辑器可走相同入口。

宿主根据真实行走/跑步状态提交 `start_action` / `stop_action`，每次切换前先结算至该事件时刻；玩家不能自己报告停止跑步以骗取恢复。吐纳会话已经管理 `human:breathing` 实例，关闭会话须在撤销权限前执行；清理失败交给可信宿主重试。动画播放请求与身体动作实例分别管理，播放成功不等于动作已登记或费用已支付。

## 整体验证

`HumanGatheringTest` 保留交付 JAR/依赖关系、五行及左右肺聚气与守恒、授权/容量失败、加工/在途保存恢复、幂等重试与手动吐纳整体回归。动作覆盖行走和吐纳并存、停气释放、指数恢复以及外部版本变化后的占用清理；框架内部算法测试不在人体仓库逐项复制。

新增自定义宿主、支持另一版内容协议、改变模板或配方后，应重跑 `build` 与 `demo`。当前使用内存权威核心，真实单机、专服和 Bong 的网络与持久化验收另行完成。
