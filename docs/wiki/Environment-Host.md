# 区域环境服务

Human 为可信服务端宿主提供位置查询、真元释放和世界时钟。Threatening 等调用方和玩家使用同一个 Framework Store、同一份区域库存。人体定义与身体计算继续由原有内容库和框架处理。

## 发现与协议

从 Fabric entrypoint `meridian_environment_host` 获取服务对象，反射调用其公开方法 `byte[] exchange(byte[])`。入口由 Human 的 `fabric.mod.json` 登记，调用方无需依赖 Human 的 Java 实现类。该接口只接受服务器主线程调用，不提供客户端网络频道。

协议源为 `shared/src/main/proto/kizuna/meridian/human/environment_host.proto`，包名 `kizuna.meridian.human.v1`；Java protoc 外部类为 `dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages`。库存、时钟和失败码直接引用框架 Protobuf 消息。`protocol_version` 固定为 1，`request_id` 必须非空白，响应回显它。每个请求必须选择一个操作。

| 操作 | 请求 | 成功结果 |
|---|---|---|
| `query` | `RegionQuery`：完整维度 ID、方块 X/Z 坐标 | `region`；该位置没有区域时不设置 result |
| `release` | `RegionRelease`：释放 ID、位置、库存明细 | `released`：可选来源 ID 和 `duplicate` |
| `clock` | 必须为 `true` | `now`：`human:minecraft` 时间域、世界 epoch、毫秒 |

加载阶段尚未绑定世界时返回 `unbound=true`。世界绑定后，服务端线程外调用返回 `ACCESS_DENIED`。维度 ID 必须规范且实际存在；不存在的维度返回 `ENVIRONMENT_UNAVAILABLE`，不能把拼写错误当作没有区域。

## 查询与释放

查询按方块坐标换算区域，负坐标向下整除。主世界未生成的区域按现有规则读取固定中心群系并初始化一次，后续查询始终读取已保存库存。来源 ID 沿用 `human:world_<epoch>/minecraft/overworld/<区域X>/<区域Z>`。

`RegionView` 包含来源 ID、区域体积（格）、逐属性/相态库存、浓度（元/格）和来源 revision。属性构成由调用方按库存占比计算；查询和释放都不限制属性命名空间，`qi_variants:wither` 等变体可直接入账。

释放库存必须非空，每条数量必须为正且有限；密度、相态和重复明细也按 Framework 库存规则校验。相同 `release_id` 和完整原内容重试返回 `duplicate=true`，不再次排队；相同 ID 的位置或库存改变返回 `REQUEST_ID_CONFLICT`。`request_id` 用于关联响应，释放去重只使用 `release_id`。

下界、末地等非主世界维持没有区域的规则：查询成功但不设置 result；释放成功但不设置 `source_id`，排队后进入默认释放账户。

## 受理、入账和持久化

服务受理时先登记释放 ID 和待入账事实，再 `markDirty`，立即返回 `released`。主世界区域库存到下一次该来源的 30 秒供给续窗才增加；非主世界释放到受理后的下一个 30 秒边界进入默认账户。这样本窗口内的释放不会改变玩家正在使用的来源 revision。入账失败会记录错误并保留队列，下次续窗重试。

身体、区域、默认释放账户、`release_ids` 和 `pending_credits` 均保存在同一个 `data/meridian_human.dat`。新字段是必需字段，缺失或损坏时拒绝加载，不迁移旧档。`pending_credits.kind` 区分外部释放 `external` 与玩家默认账户归位 `default`。

成功响应表示已登记进内存中的世界持久化状态，实际落盘随 Minecraft 世界保存；它不是独立的同步磁盘提交。调用方账本和 Human 世界存档不在同一事务内，必须考虑二者独立保存的顺序。下面的重试约定可避免保存后重试重复入账，但不能保证任意崩溃时两个存档原子提交。

`release_ids` 自首次受理起保留七个游戏日，即 `8,400,000` 世界模拟毫秒，在续窗时清理；仍有待入账项的 ID 始终保留。过期后不再提供去重保证，调用方不得将过期 ID 用作新的释放事实。

## 调用方约定

调用方身体在自己的账本中，应使用以下顺序：

1. 在自己的存档登记“待释放”，包含全局唯一 `release_id`、原位置和原库存明细。
2. 在服务器主线程发送 `release`。
3. 收到 `released` 后清除待释放；`duplicate=true` 同样表示此前已受理。
4. 中断后加载待释放记录，使用原 ID 和原内容重试。收到 `unbound` 或失败响应时保留记录。

建议 ID 格式为 `<宿主命名空间>:<身体 ID>:<释放序号>`。同一个释放事实即使调用方实体后来移动，重试仍须提交原位置。不要直接指定或猜测区域来源 ID，也不要在成功响应前自行改写 Human 库存。

## 玩家释放归位与时钟

Human 在健康、时间推进、可信活动及会话命令执行前后读取唯一默认释放账户，按增量记录本次释放。主世界按玩家当时位置排队，续窗调用 Framework `transferReleasedToSource` 从默认账户扣除并转入区域；有区域的外部释放调用 `creditEnvironmentSource`，没有区域的外部释放在下一窗口调用 `creditReleasedQi` 进入默认账户。余额不足等失败保留队列并记录错误。非主世界玩家释放留在默认账户。肺摄入回执已归还选定区域的炼化损耗不再次归位。

时钟使用主世界累计 tick 乘以 50，`/time set` 不影响它，暂停时停止；epoch 随世界保存。调用方比较时间前应同时检查 domain 和 epoch，不能直接比较不同世代的毫秒数。

接入与构建总览见[依赖、构建与宿主接入](Integration.md)。
