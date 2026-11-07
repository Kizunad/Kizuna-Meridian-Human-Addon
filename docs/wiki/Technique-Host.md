# 玩家功法宿主

Human 实现功法库的 `meridian_technique_host` entrypoint（`HumanTechniqueHost`），只认领在线列表中、
已完成身体恢复的玩家。离线 UUID 与非玩家实体返回 `unbound`；已暂停的玩家身体返回失败。
功法库 `meridian_technique` 是服务端硬依赖，协议与服务直接引用相邻 Technique Framework，
不复制 Protobuf，也不新增客户端频道。具体功法由独立内容目录提供；Human 正式产物不携带功法定义。

## 身体与活动

读档先经 Framework 授权命令补齐旧身体的空模块标签，再恢复玩家与功法认领；
迁移保留实际构型与历史回执，成功后标脏保存。非空冲突拒绝而不覆盖，细则见[人体定义](Human-Definition.md)。

所有操作在服务器主线程执行，其他线程返回 `ACCESS_DENIED`。`inspect` 先结算身体到主世界累计 tick
乘以 50 毫秒，与 Human 其他入口使用相同时钟；返回的 `BodySnapshot.settled_at` 含身体时刻。

身体回执同时采集服务端实体的 `held_items`：主副手物品 ID、排序后的注册表标签、显式
`damageable/contains_items` 与主手侧。空手仍提供无字段的 `HeldItem` 消息。
`damageable` 表达物品是否具有耐久结构（最大耐久大于 0），带 `Unbreakable` 的工具仍属于有耐久物品。
装物标志识别原版潜影盒的 `BlockEntityTag.Items` 与收纳袋的根 `Items` 非空库存列表，
空列表返回 false；不展开嵌套容器，也不推断第三方自定义库存格式。

腿法 `inspect` 还回传服务端实体的 `grounded/in_water/climbing/riding/gliding`，以及脚部装备的
护甲值和剩余耐久。无靴时这些可选事实保持缺省，宿主不会把腿甲或实体总护甲计入腿击。

`activity` 使用库的固定调用身份 `meridian_technique:caster`，只授权当前玩家身体的 `ExecuteActivity`。
原始请求 ID、活动与 `expected_revision` 交给 Framework `handleActivity`，版本冲突等失败码原样返回。
成功活动及其回执保存在 Human 原有世界 Store，重复活动不会重复扣费；身体、回执和默认释放账户一起保存。
操作完成后标脏，并使既有身体视图按原来的 tick 推送规则刷新，不建立独立身体缓存。

起手释放先进入 Store 默认释放账户，再通过现有 `captureRelease` 回调按玩家当时位置排队。
30 秒供给窗边界用 `transferReleasedToSources` 批量扣默认账户、贷记原区域；重载与重复请求不重复归位。
非主世界没有来源时保留在默认账户。此流程与死亡、开脉和加工损耗共用
[区域环境服务](Environment-Host.md)及其存档规则。

## 腿击

`EffectIntent.leg_strike` 由 `HumanLegStrikeEffects` 在服务器主线程执行。宿主先按生物资格、视线、
距离、前后腿朝向和扇形筛选目标，再交给功法库的 `TechniqueStrikeTargets` 处理单击上限、整式目标
上限和 `dedup_group` 去重。单体射线没有命中时使用声明角度的后备锥，不会凭空命中实体。

伤害剂量按起手保存的书级规则计算为 `base_damage + armor_damage × boot_armor`，再乘本击倍率。
`damage_type_id` 是目录提供的完整命名空间 ID，Human 直接解析该 ID 查 Damage 注册表，不附加固定
命名空间。击退支持推开、横推、绊倒和踩落；正面盾挡在 `knockback_when_blocked` 开启时仍执行
击退，Damage 类型自身的 `#minecraft:bypasses_shield` 标签决定是否绕过盾。最后一击只磨脚部装备一次，
创造模式不磨损；每次实际出手播放一次原版重击声，并在收势时重置原版攻击冷却。

绊倒清空目标水平速度，并将竖直速度设为向上 2 米/秒（原版每 tick 0.1 格），覆盖此前的下落或跳跃速度。

## 冲量与表现

`launch_projectile`、`target_lock` 和新增双手弓弩事实见[射击宿主与原版箭](Projectiles.md)。
公开执行器由玩家与类人生物共用；射击效果沿用本页的回执幂等与取消清理。

冲量设置实体速度，换算为 `速度（m/s）÷20 = 格/tick`，随后设置 `hurtMarked=true`。
已核对 26.2：`ServerEntity.sendChanges` 看到此标记后，经跟踪表的 `sendToTrackingPlayersAndSelf` 向观察者和玩家本人发送
`ClientboundSetEntityMotionPacket`，再清除标记。GameTest 实际经过此跟踪器并检查双方发包。

`FORWARD` 取玩家水平朝向；`BACKWARD` 取其反方向；`UP` 垂直向上；
`INPUT` 的 x/z 是世界坐标，归一化后使用。零输入按 `input_default_direction` 选前方或后方，缺省前方。
客户端或战斗 UI 负责先按视角把按键方向转换成世界方向，Human 不再旋转输入。
`TOWARD_TARGET` 要求 UUID 指向同世界仍存在且位置不同的实体，无法定位时拒绝。单位和剂量沿用功法库校验。

同一连接内，请求 ID 与 `(cast_id, effect_id)` 分别校验幂等；两种身份任一已成功都不重复执行，
不同内容返回 `REQUEST_ID_CONFLICT`。兼容没有 `effect_id` 的旧冲量时以请求 ID 补足效果身份。
失败不写成功回执，允许原 ID 重试。
执行中身份与成功回执分开保存。同步重提交同请求 ID 或同 `(cast_id, effect_id)` 时，
相同载荷返回暂时拒绝 `INTERNAL_ERROR`，不同载荷返回 `REQUEST_ID_CONFLICT`；不会递归执行或提前确认成功。
执行退出后清除执行中标记，只有副作用正常完成才保存成功回执。
效果回执在连接结束时清除；库不跨重启恢复效果游标，不补发离线效果。

`HumanTechniquePresentation.EVENT` 是公开的服务端表现钩子，回调为
`apply(ServerPlayer player, String requestId, EffectIntent effect)`。
正常施放和恢复共用此通道，完整传递施放/效果身份、选手、START/PLAY_ONCE/STOP 及原始 `start_millis`。
适配器应按请求身份幂等，只有实际接受意图后才返回；抛出 `TechniqueErrors.HostRejected` 可保留原失败码并重试。
多个监听器中有失败时，已执行的监听器也可能再次收到同一请求，因此不能仅依赖 Human 的成功回执去重。
回调中的功法取消由 Technique 按实体排队，当前交付退出后才执行；回调内取消返回空结果表示排队。
业务效果永久拒绝时，Technique 终止施放并分别重试 STOP/UNLOCK，某项失败不阻塞另一项。
对尚未开始或已经停止的表现也必须允许幂等 STOP；不能将拒绝或未认领当作已清理。
功法表现已接入 PA。公开内容映射 `HumanTechniqueAnimations` 由玩家与类人宿主共用：
轻步按输入方向选择四向片段，蓄力与调息使用对应现有片段。
叩门、破门、撞门使用 `door_breaker_knock/cleave/ram`，起手选定副手时使用对应 `_off`；
左右惯用手由 PA 运行时镜像，不由宿主重复换片段。其余五式暂用对应主副手 `attack_main/off`。
玩家通过 `ServerPlayerAnimations.playFrom` 保留起播时刻，类人通过 Mobs-Addon 同步相同映射。
片段不决定打击结算；三式目录打击与动画 `hit` 同为 1250、1600、1650 毫秒（25、32、33 tick）。
撞门冲量在 800、1050、1300 毫秒，位于动画 800–1500 毫秒的迈步窗口，后两次与落脚同刻。
承接或迟到时保留原始起点和剪辑偏移，不能从第零帧重播。

`LOCK/UNLOCK` 按施放 ID 派生固定 UUID，只修改原版 `MOVEMENT_SPEED` 的临时属性修正，
用 `MULTIPLY_TOTAL=(movement_speed_multiplier-1)` 施加目录倍率，缺省为 1，破门斩八式为 0.4。
本人立即收到 `ClientboundUpdateAttributesPacket`，旁观者由原版跟踪器同步。
不同施放的修正独立，承接移除前式后再应用新式；重复清理不影响其他修正。
限速仍允许主动移动，不缩放冲量、不拦跳跃或转向；收势、打断、死亡、下线和恢复清理临时属性。
施放锁定期间 Fabric 拦截原版实体攻击、方块攻击、物品使用、方块交互和实体交互；功法自己的效果
仍由正式宿主回执执行。
受击僵直不由本宿主判定：原版伤害附属在命中提交后写入功法库，功法库给实体另加一份固定 UUID 的
`MULTIPLY_TOTAL` 僵直减速（默认 ×0.5），与施放限速相乘，到期移除；僵直中新起式回执「僵直中」。

`LOCK` 到 `UNLOCK` 之间玩家处于施放中，`HumanFabric` 在同一处拦原版输入：左键攻击实体、攻击方块一律拦，
挥手包由 `TechniqueHandSwingMixin` 拦；右键（用物品、放方块或与方块交互、与实体交互）在 LOCK 的
`allow_use_while_casting=false` 时拦，目录声明 true 的式（如服药）放行。只有倍率小于 1、会下发 LOCK 的式进入施放中，
基础功法吐纳、轻身、蓄力倍率为 1，左右键都不拦。

`status_benefit` 交给功法库的共享原版状态执行器。轻身的每次跃步仍经本宿主提交 Framework 做功活动，
绑定六块腿肌、不设力量门槛；按当前有效功率与耐力检查预算，不另建体力或真元账本。
起跳与落地通过既有 PA 映射播放和停止，
实际位移、碰撞及额外跌落伤害由功法库共享宿主处理；飞行 tick 由功法库按直线长度与起跳速度求出，
落地间隔以服务端回执的可起跳时刻为准，本宿主不另计。

26.2 原版速度包在编码时按三轴最大绝对值取整数倍率，各轴相对倍率以 15 位量化（倍率 1 时步长约 1/16383 格/tick）；
服务端仍设置契约中的完整速度。分量悬殊时小分量的量化误差随大分量放大，普通冲量不保证精确同步；跃步另用功法库的 Protobuf 轨迹频道进行权威纠正。
Human 会标记已结算的功法冲量，下一次玩家位置采样只更新运动基线，不把冲量位移重复记作 WALK/SPRINT
做功；之后的正常位移恢复原有采样规则。
GameTest 使用 10 m/s，验证服务端为 0.5 格/tick，并检查同步标记与速度包。

## 取消与组织占用

Fabric 断线和停服事件显式排序：先由功法库默认阶段调用取消，再由 Human 结束自己的活动、撤销认领。
取消清 `active_cast` 和功法动作，不退费，按实际终止身体时刻重算冷却并保留取消历史。
异常退出留下的施放在再次认领玩家前，通过
`TechniqueService.recoverOnLoad` 取消，不经实体目录认领，不补发离线冲量或表现。
`HumanTechniqueRecovery` 持有已认证实体和权威身体，直接委托共用的 `HumanTechniqueEffects` 执行
幂等 STOP/UNLOCK，不经 `exchange` 或 `inspect` 重入。恢复前结清身体时间，
先完成实体清理，再提交库生成的身体命令，成功后才对外认领；清理拒绝保留施放进度供原身份重试。
恢复适配器拒绝 START/PLAY_ONCE 和冲量，死体仍可停止表现及移除移动限速。
存活身体通过 Framework 活动解除动作；死体通过既有可信 `SetAddonProgress` 结束残留施放。
两者均记录取消历史，保留知识、曲线、伤势和已付费用，冷却遵循相同的提前终止规则。
成功恢复会保存并标脏。损坏进度仍明确拒绝，不覆盖为空进度。

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
`HumanTechnique6aGameTest` 补充双空手与副手事实、潜影盒/收纳袋装物、有耐久的无法破坏工具、
移动限速及本人属性包、限速期间冲量、向后默认方向、收势/打断/断线移除限速、表现起播时刻及幂等，
以及未认领和死体恢复中的 STOP/UNLOCK、清理失败重试及清理先于进度提交。
`startCallbackCancelsAfterDeliveryWithoutRecursion` 覆盖 START 回调取消后的唯一 STOP 和限速清理；
`synchronousResubmissionDistinguishesExecutingFromSucceeded` 覆盖同步重提交、两种身份内容冲突及失败后的重试；
`disappearedTargetCancelsAndUnlocksRealPlayer` 覆盖目标销毁后取消与实体约束清理。
`castingBlocksUseInputUnlessTechniqueAllowsIt` 让两名玩家分别施放只差放行声明的两式，各自用盾、开活板门、剪羊：
未声明的三样都被拦，声明放行的三样都生效。
旧身体回归在服务器启动读档前，将专用玩家的无标签身体写入真实 `meridian_human.dat`，
由正式宿主加载后真实登录，核对标签及保存重载；再走正式学习命令与功法服务，
施放同时绑定手经和足经的测试功法。夹具保留世界中其他玩家与区域数据。
释放归位曾使 `commandLearnCastFinishAndReleasedQiReturnsOnce` 偶发失败：九笔 `0.001` 起手释放
按三个区域分组后，最后一区域意图为 `0.0010000000000000009`，逐区域扣转只剩 `0.001`，
被严格余额校验拒绝。相同真实施放输入已验证修复前失败、修复后归位清空；
捕获数值、跨区域尾差与跨窗重载分别由分配回归和持久化 GameTest 保护。
测试功法只在 GameTest 源集注册，不进入发布产物。此验证不代替真实客户端移动手感与多人压力验收。

`HumanCombatWorldGameTest` 使用发布目录的武器与劈门算子，通过正式 Human 宿主协议执行，
检查真实伤害、物品磨损、原版盾冷却、门掉落与玩家总开关。
敌我只由宿主谓词判断（功法框架不预筛）：玩家不打原版同伴，即 `isAlliedTo` 为真的同队玩家与自己驯服的动物；
同队时即使队伍开着友伤（原版默认）也不命中，`/team` 分开后照常命中。
`HumanCombatServiceGameTest` 通过正式学习和施放服务检查选手、费用、八式效果时刻、承接、移动限速及攻击包拦截。
`HumanCombatTestCatalog` 从运行时破门斩资源派生测试目录，只降低出生身体的境界、经脉数量和费用门槛；
效果时刻、曲线、物品规则、倍率、承接与冷却保持正式定义，费用与做功仍由唯一权威身体结算。
测试玩家仍经过真实登录和网络处理，观察子类仅记录原版速度入口与目标伤害入口。

| 玩家契约 | GameTest |
| --- | --- |
| 双空手、装物容器拒绝，副手与类别/耐久/主手优先 | `handsRejectEmptyAndContainersAndSelectByCategoryWearThenMain` |
| 铁斧/铁剑/泥土/火把剂量与叩门钝击 | `damageCategoriesDoseAndKnockBluntUseRealDamageChain` |
| 数据包标签变更类别 | `changedCategoryTagsChangeDamageWithoutItemSpecialCases` |
| 耐久倍率、耐久附魔、损坏、无法破坏、创造 | `durabilityMultipliersBreakageUnbreakingAndCreativeMatchVanilla` |
| 起手打断不磨损 | `warmupInterruptionDoesNotWearWeapon` |
| 破门起手：轻击低于僵直门槛不打断，重击打断、减速 0.5 并拒绝新式 | `cleaveWarmupInterruptedOnlyByStaggeringHit` |
| 未施放时重击僵直减速，身体时钟到期恢复原速 | `staggerHalvesMovementUntilExpiry` |
| 一次性末击挥空、最后一个物品及重试幂等 | `disposableLastStrikeConsumesOnceEvenOnMissAndRetry` |
| 双手剂量与两臂做功、副手只用对应手臂 | `twoHandDoseAndOffhandDamageChargeCorrectArms` |
| 起手后换物、选定手为空落空且不退费 | `switchingSelectedHandItemChangesHitAndEmptyHandMissStillPays` |
| 斧先破盾、剑先格挡再冷却、方块不破盾 | `axeBreaksShieldBeforeDamage` / `swordBlocksThenCoolsShield` / `blockItemDoesNotDisableShield` |
| 同队玩家不受功法伤害，移出队伍后同一式命中 | `sameTeamPlayerIsNotStruckUntilTeamsSplit` |
| 横扫两半圈、同组去重与五目标预算 | `sweepHitsBothHalvesOnceAndSharesFiveTargetBudget` |
| 木门/活板门/栅栏门掉落、铁门/非工具拒绝 | `woodenDoorsDropAndIronAndNonToolsRefuse` |
| 副手斧劈门、不交换物品、不额外磨损 | `offhandAxeBreaksDoorWithoutMovingItemsOrExtraWear` |
| 服主关闭玩家劈门 | `playerDoorRuleDisablesBreaking` |
| 限制移速、拦原版攻击与挥手包、收势重置冷却 | `castingBlocksRealAttackAndSwingPacketsThenResetsCooldown` |
| 七式打击及冲量 tick，连门独立组可重复命中 | `sevenStandaloneStrikesUseCatalogTicksAndChainCanRehit` |
| 承接窗口、剪辑起播、缩短起手与原式冷却边界 | `followUpShortensWarmupAndEndsPredecessorWithinWindow` |
| 终式承接命中与冲量时刻、峰顶倍率及做功 | `finaleHitsAtShortenedTickInsideArmPeak` |
| 终式不能单独起手，起跳前打断仍虚脱并恢复 | `finaleRequiresFollowUpAndEarlyInterruptionStillExhaustsThenRecovers` |
| 同刻 INPUT/UP 正交组合与重试幂等 | `orthogonalImpulsesCombineInEitherOrderAndRetryIsIdempotent` |
| 错层目标仍保留完整水平速度，纯竖直目标拒绝 | `elevatedTargetKeepsFullHorizontalImpulseAndVerticalSpeed` |

`bash "gradlew" :fabric:runSpawnProtection` 使用真实专用服务端验证出生点保护：
普通玩家劈门被拒，授予管理员权限后同一把工具可劈开同一扇门；结果写入 `fabric/build/spawn-protection/result.txt`。
GameTest 的 GameTestServer 不实现该权限，不能替代这项独立验证。
动画客户端的 `hit` 帧视觉对齐及打断淡出也需真实客户端验收；以上时序测试检查服务端目录时刻和实际世界效果。
朝目标冲量先将目标位移投影到水平面再归一化，目标高低差不会削减声明的水平速度；
UP 独立更新竖直分量，纯竖直目标没有有效水平推进方向，保持 `INVALID_REQUEST` 拒绝。
