# 区域环境服务

Human 为可信服务端宿主提供位置查询、真元释放、交接回收和世界时钟。调用方和玩家使用同一个 Framework Store 与区域库存。

## 发现与协议

从 Fabric entrypoint `meridian_environment_host` 获取对象，调用公开方法 `byte[] exchange(byte[])`。只允许服务器主线程调用，不提供客户端网络频道，调用方不依赖 Human 私有 Java 实现。

协议源为 `shared/src/main/proto/kizuna/meridian/human/environment_host.proto`，包名 `kizuna.meridian.human.v1`，Java 外部类 `EnvironmentHostMessages`。库存、时钟和失败码引用 Framework Protobuf。`protocol_version=1`，非空白的 `request_id` 用于关联响应。

| 操作 | 请求 | 结果 |
|---|---|---|
| `query`（10） | `RegionQuery`：规范维度 ID、方块 X/Z | `region`；没有区域时不设置 result |
| `release`（11） | `RegionRelease`：全局唯一 ID、原位置、库存明细 | `released`：可选来源 ID、`duplicate`、`persisted` |
| `clock`（12） | 必须为 true | `now`：时间域、世界 epoch、毫秒 |
| `forget_release`（新增 13） | 已持久删除原释放的 ID | `forgotten`（新增 15）：`ForgetReleaseResult.persisted` |

`ForgetReleaseResult.persisted` 的字段号为 1，值为 true 才确认遗忘。请求和响应的新增编号分别接原最大号 12、14 之后，不复用编号。`ReleaseResult.persisted` 为字段 3，`duplicate` 语义保持不变。两个必装模块一起更新，无旧接口接线或降级。

尚未绑定世界返回 `unbound=true`；线程错误返回 `ACCESS_DENIED`。非法维度返回 `INVALID_REQUEST`，不存在的维度返回 `ENVIRONMENT_UNAVAILABLE`。

## 查询、受理与入账

查询按方块坐标换算区域，负坐标向下整除。主世界区域按固定中心群系初始化一次，后续读取已保存库存。来源 ID 为 `human:world_<epoch>/minecraft/overworld/<区域X>/<区域Z>`。`RegionView` 包含来源 ID、体积、逐属性/相态库存、浓度和 revision，不限制属性命名空间。

释放库存必须非空，数量、密度、相态及重复明细由 Framework 校验。同 ID 同完整内容返回 `duplicate=true`，不重新排队；同 ID 改变位置或库存返回 `REQUEST_ID_CONFLICT`。重试始终携带原内容，释放 ID 不得复用。

首次受理只在内存登记 ID、完整请求及待入账事实，立即返回 `persisted=false`。Human 在 END_SERVER_TICK 统一保存所有未确认释放及待保存遗忘。普通世界保存与批量保存共用 tick 限制，**运行中每 tick 最多尝试一次整份保存，失败也不在同 tick 重试**。没有待保存交接时，重复请求只查最近一次成功保存的 ID 集合，不做文件 I/O。

只有该 ID 已包含在一次完整成功的保存中，重发 `release` 才返回 `persisted=true`；加载正式文件时也恢复该确认集合。不能用 `duplicate` 代替持久化确认。正常磁盘、每 tick 重试时，受理到取得确认最多两个服务器 tick，20 TPS 时约 100 毫秒；这包含模组回调顺序及当 tick 已保存的情况。保存耗时会拉长 tick；故障、暂停或低 TPS 下没有有限墙钟保证。

主世界外部释放到对应来源的下一个 30 秒供给窗才入账，非主世界到受理后的下一个 30 秒边界进入默认账户。入账失败保留队列，下次续窗重试；本窗内不改变玩家使用的来源 revision。身体、区域、默认账户、`release_ids` 与 `pending_credits` 同存 `data/meridian_human.dat`。

## 写盘确认

已核对 Minecraft 1.20.1 Yarn 实际字节码：`markDirty()` 只设标记；世界保存经 `ServerWorld.saveLevel` → `PersistentStateManager.save` 调用 `PersistentState.save(File)`。自动保存每 6000 tick（20 TPS 下约五分钟）执行，正常关服也保存；`savingDisabled` 跳过普通世界保存。原版直接覆盖文件，捕获异常后仍清 dirty，没有刷盘确认。

两端覆写保存，保留原版 `data` 与 `DataVersion` 封装，依次执行：

1. 写同目录 `.dat.tmp` 并关闭压缩流。
2. 对临时文件执行 `FileChannel.force(true)`，必须成功。
3. `ATOMIC_MOVE + REPLACE_EXISTING` 原子替换正式文件，必须成功，不回退为普通覆盖。
4. 支持目录 fsync 的平台同步目录和其上级目录；Windows 不打开目录通道，跳过目录同步，使用文件 force 与 NTFS 原子移动的元数据日志保证。

“写盘”指上述适用于该平台的步骤全部收到操作系统成功返回。支持目录同步的平台上，实际 I/O 错误仍使保存失败。失败保留 dirty 和未确认事实，记录日志，后续请求保持 `persisted=false`，下一 tick 重试；异步错误不会倒填此前已发出的受理响应。替换前失败保留旧文件；替换后同步失败可能已更新正式文件，仍不确认。仅从正式文件恢复，临时文件下次覆盖。

关服保存发生在停止 tick 后，仍提交最终脏状态，不受运行中 tick 限制阻止。批量保存不依赖五分钟自动保存或关服。操作系统、文件系统及硬件须兑现同步与原子替换语义；测试不模拟物理控制器故障。

## 调用方交接与遗忘

1. 持久保存全局唯一 ID 和完整待释放请求，使用原内容重发，直到 `released.persisted=true`。
2. 在自己的同一份文件中删除原请求并登记“待遗忘 ID”，持久提交该快照；失败时不得发送遗忘。
3. 之后只重试 `forget_release`。Human 尚有待入账事实时返回 `forgotten.persisted=false` 并保留原请求。
4. 入账后 Human 删除去重项，在 tick 末把余额与删除一起保存。删除未保存时仍返回 false，成功后或 ID 已不存在时返回 true；遗忘幂等，不保留永久墓碑。
5. 调用方收到 true 后删除待遗忘 ID 并保存。响应丢失或这次本地保存失败，重启只重试遗忘。

遗忘请求表示调用方已永久放弃重发原释放，不能在遗忘后重放旧备份中的 release。受理后至发送端删除落盘前崩溃，原请求仍可重放，Human 去重；发送端删除落盘后崩溃，只恢复待遗忘 ID，Human 仍拥有待入账事实或余额。Human 删除落盘前崩溃会恢复旧去重项并接受遗忘重试；删除落盘后丢响应，重复遗忘成功。每个余额和待入账转换均与去重变化同文件提交，不会产生第二次入账或丢失数量。

七个游戏日不再触发清理；**只保留当前未完成交接的历史**。去重空间为 O(未确认释放 + 等待入账/遗忘的释放)，不随已完成的累计死亡数增长。正常情况下入账等待至多 30 秒，之后再用少量 tick 完成遗忘；故障期间必须保留全部积压，无法给任意输入和无限故障下的固定字节上限。

## 玩家归位与时钟

玩家释放按执行时位置排队，默认账户归位按区域合并；续窗使用 Framework 的 `transferReleasedToSource` 从默认账户扣除并转入区域。外部区域释放使用 `creditEnvironmentSource`，无区域释放使用 `creditReleasedQi`。余额不足等失败保留队列。肺摄入回执已归还区域的炼化损耗不重复归位。

时钟为主世界累计 tick 乘 50，`/time set` 不改变它，暂停时停止；epoch 随世界保存，比较时间须同时检查 domain 与 epoch。

## 整体回归与命令

`DurableStateFileTest` 单元测试覆盖不支持目录同步仍可保存、替换，以及文件 force 失败必须拒绝保存。实际 `persisted` 路径另由真实服务端 GameTest 验证。

| HumanReleasePersistenceGameTest 测试 | 验证 |
|---|---|
| `memoryAcceptanceAndDuplicateDoNotConfirmDisk` | 首次及重复内存受理不确认 |
| `durableReleaseSurvivesRepeatedReloadAndCreditsOnce` | 目标先保存、四轮真实文件重载，余额只增加一次 |
| `failedWriteKeepsOldFileAndDuplicateMustRetryDisk` | 写失败保留旧文件和 dirty，恢复后确认 |
| `failedAtomicReplacementDoesNotConfirmDisk` | 临时写完、正式替换失败仍不确认 |
| `unresolvedReleaseSurvivesSevenDaysAndRejectsConflictingContent` | 超过原七日门槛仍去重，位置与库存冲突拒绝 |
| `batchAndWorldSaveShareOneAttemptPerTick` | 32 笔合并保存，同 tick 普通保存不能再次写盘 |
| `failedBatchOnlyRetriesNextTick` | 失败也限每 tick 一次，下 tick 恢复 |
| `unsupportedDirectorySyncStillConfirmsPersistence` | Windows 能力路径实际保存并返回持久化确认 |
| `forgetWaitsForCreditAndDurableDeletionThenReclaimsHistory` | 入账前保留，删除写失败恢复、丢响应重试及余额守恒 |

执行命令：

```bash
"./gradlew" --offline --console=plain test build demo :fabric:compileGametestJava
"./gradlew" --offline --console=plain :fabric:runGametest
python3 "scripts/check_docs.py"
git diff --check
```

最终结果与两轮真实强退恢复记录见 Humanoid 的 Persistence 页面；本仓日志位于 `fabric/build/persistence-logs/review-final-*.log`。GameTest 的故障写入异常为主动注入，mock 玩家缺 UI 通道属于现有夹具。未运行 Windows 实机、物理断电、网络文件系统及多人长期性能测试。

接入与构建总览见[依赖、构建与宿主接入](Integration.md)。
