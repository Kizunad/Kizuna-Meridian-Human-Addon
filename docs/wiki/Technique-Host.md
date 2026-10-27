# 玩家功法宿主

Human 实现功法库的 `meridian_technique_host` entrypoint（`HumanTechniqueHost`），只认领在线列表中、
已完成身体恢复的玩家。离线 UUID 与非玩家实体返回 `unbound`；已暂停的玩家身体返回失败。
功法库 `meridian_technique` 是服务端硬依赖，协议与服务直接引用相邻 Technique Framework，
不复制 Protobuf，也不新增客户端频道。具体功法由独立内容目录提供；Human 正式产物不携带功法定义。

## 身体与活动

所有操作在服务器主线程执行，其他线程返回 `ACCESS_DENIED`。`inspect` 先结算身体到主世界累计 tick
乘以 50 毫秒，与 Human 其他入口使用相同时钟；返回的 `BodySnapshot.settled_at` 含身体时刻。

`activity` 使用库的固定调用身份 `meridian_technique:caster`，只授权当前玩家身体的 `ExecuteActivity`。
原始请求 ID、活动与 `expected_revision` 交给 Framework `handleActivity`，版本冲突等失败码原样返回。
成功活动及其回执保存在 Human 原有世界 Store，重复活动不会重复扣费；身体、回执和默认释放账户一起保存。
操作完成后标脏，并使既有身体视图按原来的 tick 推送规则刷新，不建立独立身体缓存。

起手释放先进入 Store 默认释放账户，再通过现有 `captureRelease` 回调按玩家当时位置排队。
30 秒供给窗边界用 `transferReleasedToSource` 扣默认账户、贷记原区域；重载与重复请求不重复归位。
非主世界没有来源时保留在默认账户。此流程与死亡、开脉和加工损耗共用
[区域环境服务](Environment-Host.md)及其存档规则。

## 冲量与表现

冲量设置实体速度，换算为 `速度（m/s）÷20 = 格/tick`，随后设置 `velocityModified=true`。
已核对 Yarn 1.20.1：`EntityTrackerEntry.tick` 看到此标记后，向观察者和玩家本人发送
`EntityVelocityUpdateS2CPacket`，再清除标记。GameTest 实际经过此跟踪器并检查双方发包。

`FORWARD` 取玩家水平朝向；`UP` 垂直向上；`INPUT` 的 x/z 是世界坐标，归一化后使用，零输入回到朝向。
客户端或战斗 UI 负责先按视角把按键方向转换成世界方向，Human 不再旋转输入。
`TOWARD_TARGET` 要求 UUID 指向同世界仍存在且位置不同的实体，无法定位时拒绝。单位和剂量沿用功法库校验。

同一连接内，相同效果请求 ID 与相同 `EffectIntent` 只施加一次；不同内容返回 `REQUEST_ID_CONFLICT`。
失败不写成功回执，允许原 ID 重试。未知表现 ID 确认接受并忽略，当前不播放功法动画。
效果回执在连接结束时清除；库不跨重启恢复效果游标，不补发离线效果。

原版速度包会把每轴限制到 ±3.9 格/tick（±78 m/s），并以 1/8000 格/tick 量化；服务端仍设置契约中的完整速度。
高于此逐轴范围的效果无法通过原版包精确同步。本次没有新增自定义速度频道；基础功法应使用原版可表示的速度。
GameTest 使用 10 m/s，验证服务端为 0.5 格/tick，并检查同步标记与速度包。

## 取消与组织占用

Fabric 断线和停服事件显式排序：先由功法库默认阶段调用取消，再由 Human 结束自己的活动、撤销认领。
取消清 `active_cast` 和功法动作，不退费，不清冷却。异常退出留下的施放在再次认领玩家前，通过
`TechniqueService.recoverOnLoad` 取消，不经实体目录认领，不补发离线冲量或表现。
存活身体通过 Framework 活动解除动作；死体通过既有可信 `SetAddonProgress` 仅清 `active_cast`，
不改知识、冷却、伤势或已付费用。成功恢复会保存并标脏。损坏进度仍明确拒绝，不覆盖为空进度。

Framework `BodyActions` 允许不同动作实例共享组织，占用取并集。因此吐纳占用膈肌时，功法仍可建立自己的膈肌动作，
但必须通过当下力量、功率和耐力校验。功法结束只停止本次实例，吐纳自己的占用保留，反向亦然。
共享组织并不取消做功费用或允许占用期间恢复。GameTest 覆盖真实吐纳与施放并存及收势后占用保留。

原版 `ALLOW_DEATH` 回调先尝试取消，再交给后续死亡确认。
健康命令已在 Framework 提交死亡、结时寿尽或加载死体时，使用同一个恢复入口清理残留。
取消不提前插入健康命令事务，因此伤害仍按其原始 `expected_revision` 检验；重试保留原健康回执。
此前残留会让登录处理暂停 Human 身体，网络连接本身不会被断开；有效死体残留现在可清理后正常认领。
Framework 尚无复活规则，原版重生不会创建新身体或恢复 HP；死体不能施放，但不会再被旧 `active_cast` 卡住。
健康宿主做功转发与区域边界协议不在本页范围内。

## 回归验证

`HumanModuleTagsTest` 校验正式模板的六个公开标签、精确成员与左右对称。
`HumanTechniqueGameTest` 使用经过真实登录、频道注册和断线事件的服务端测试玩家，
覆盖在线认领、身体时钟、版本冲突、持久活动重放、冲量方向与去重、速度包、未知表现、
学习命令到收势、释放归位、断线不退费、重新认领前清理残留施放及吐纳共享组织。
测试功法只在 GameTest 源集注册，不进入发布产物。此验证不代替真实客户端移动手感与多人压力验收。
