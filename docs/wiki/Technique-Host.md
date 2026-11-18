# 玩家功法宿主

Human 实现功法库的 `meridian_technique_host` entrypoint（`HumanTechniqueHost`），只认领在线列表中、
已完成身体恢复的玩家。离线 UUID 与非玩家实体返回 `unbound`；已暂停的玩家身体返回失败。
功法库 `meridian_technique` 是两端硬依赖，协议与服务直接引用相邻 Technique Framework，
不复制 Protobuf，也不新增客户端频道。具体功法由独立内容目录提供；Human 正式产物不携带功法定义。

受控会话通过可信准入拒绝玩家自己的新式，并在有限调用作用域内代发，详见[玩家受控模式](Player-Control.md)。

## 持续守式

按住和松开的网络输入由 Combat UI 交给功法库；Human 消费施放与效果通知，不另设守状态或按键协议。
玩家正在使用盾的格挡动作时，准入拒绝守式，保留已举起的盾。提示键为
`meridian_human_host.guard.shield_raised`，两种语言暂留文案占位。

守式起手即使用目录声明的移动倍率，贯穿生效和完整收势。服务端从原有 Movement 锁携带的精确功法引用
识别守式，立即停止已有疾跑，拒绝后续疾跑请求；客户端从功法库 `CastStatus` 的持续施放身份禁止疾跑预测，
同一个身份在松开确定收势时刻后继续有效，结束才解除。当前功法库只允许守式使用持续模式。
攻击、挖掘、物品及方块/实体交互沿现有回调拦截，直接开始物品使用、延迟挖掘和直接破坏入口也检查同一占用。
起式停止已有物品使用；已有挖掘不能等解锁后自动做完。

数据表现 `guard_arms` 发起保持动作。进入 `RECOVERING` 时先停该次实际片段，再播收势；
Damage 的 `DamageInput.guarded` 提供本击冻结的 body/cast 身份，匹配仍占用的施放才播受击反馈。
最后前臂毁掉的顺序是命中反馈、停止保持与反馈、收势，硬控取消直接停止而不播放收势。
实际片段按施放身份跟踪，重复收势和旧施放通知不会重播或停止后一次守。

动作映射集中在 `HumanTechniqueAnimations`：保持 → `guard_arms`，收势 → `guard_arms_lower`，
受击 → `guard_arms_hit`，三段都是 Players-Animation 里为空手抱架做的片段。
宿主在玩法收势结束时主动停止落手片段。死亡沿原取消/恢复清理；断线、跨维度与实体替换在旧实体
仍可访问时先取消，再通过恢复入口完成遗留清理。停服清理排在功法服务关闭之前，不能遗留身体进度、属性和实际动作。

## 身体与活动

读档先经 Framework 授权命令给旧身体追加缺少的模块标签（含五脏诀用的五行标签），再恢复玩家与功法认领；
迁移保留实际构型与历史回执，成功后标脏保存。已有模板之外的标签时拒绝而不覆盖，细则见[人体定义](Human-Definition.md)。

所有操作在服务器主线程执行，其他线程返回 `ACCESS_DENIED`。`inspect` 先结算身体到主世界累计 tick
乘以 50 毫秒，与 Human 其他入口使用相同时钟；返回的 `BodySnapshot.settled_at` 含身体时刻。

功法库的可选读数 `bodyStamp` 不结清、不提交，只回两个数：玩家身体已提交的 revision，以及此刻 `inspect`
结清后 `settled_at` 会停的毫秒（同一个主世界时钟）。认领条件与 `exchange` 相同，非主线程、离线、未就绪或已暂停时不给；
身体已死、时刻早于已记账时间，或寿元会在这一毫秒或之前用完（结清会停在寿元终点并判死）时也不给，功法库退回 `inspect`。
寿元终点按已提交快照缓存，老化倍率与结清计划共用 `HumanPlayerBody.AGING_RATE`。
身体有在途真元时，Human 每 tick 到了到达边界就结清并提交，revision 跟着变，读数帮功法库跳过安静 tick、
帮战斗 HUD 跳过重算的机会就少：开过脉、真元充足的玩家十二正经成环，被动扩散一直在环上转，revision 几乎每 tick 都变；
在途真元走完以后 revision 才停下来。

身体回执同时采集服务端实体的 `held_items`：主副手物品 ID、排序后的注册表标签、显式
`damageable/contains_items` 与主手侧。空手仍提供无字段的 `HeldItem` 消息。
`damageable` 表达物品是否具有耐久结构（最大耐久大于 0），带 `Unbreakable` 的工具仍属于有耐久物品。
装物标志识别原版潜影盒的 `BlockEntityTag.Items` 与收纳袋的根 `Items` 非空库存列表，
空列表返回 false；不展开嵌套容器，也不推断第三方自定义库存格式。

腿法和拳掌的起手条件读 `inspect` 回传的服务端实体 `grounded/in_water/climbing/riding/gliding`；
腿法另读脚部装备的护甲值和剩余耐久。无靴时这些可选事实保持缺省，宿主不会把腿甲或实体总护甲计入腿击。

`activity` 使用库的固定调用身份 `meridian_technique:caster`，只授权当前玩家身体的 `ExecuteActivity`。
原始请求 ID、活动与 `expected_revision` 交给 Framework `handleActivity`，版本冲突等失败码原样返回。
成功活动及其回执保存在 Human 原有世界 Store，重复活动不会重复扣费；身体、回执和默认释放账户一起保存。
操作完成后标脏，并使既有身体视图按原来的 tick 推送规则刷新，不建立独立身体缓存。

起手释放先进入 Store 默认释放账户，再通过现有 `captureRelease` 回调按玩家当时位置排队。
30 秒供给窗边界用 `transferReleasedToSources` 批量扣默认账户、贷记原区域；重载与重复请求不重复归位。
非主世界没有来源时保留在默认账户。此流程与死亡、开脉和加工损耗共用
[区域环境服务](Environment-Host.md)及其存档规则。

## 拳脚打击

`EffectIntent.leg_strike` 和 `EffectIntent.limb_strike` 都由 `HumanLimbStrikes` 在服务器主线程执行。
腿击先经功法库 `TechniqueLimbStrikes.fromLeg` 换成同形的肢击意图，此后拳与腿走同一段代码。
执行器的施放者是 `LivingEntity`，玩家和生物的差别由 `HumanLimbStrikes.Host` 给出：敌我谓词、
单体打击的声明目标、靴子护甲与磨损、重击声的声音类别。玩家宿主 `HumanPlayerLimbHost` 与持物打击
共用 `HumanControl.allowsTarget`，单体按准星取目标、不认请求带的目标，靴子取脚部装备栏，声音归玩家类别。
其他附属给生物出拳脚时各建一个执行器、传入自己的 `Host`。

宿主先按生物资格、视线、距离、前后朝向和扇形筛选目标，再交给功法库的 `TechniqueStrikeTargets`
处理单击上限、整式目标上限和 `dedup_group` 去重。单体射线没有命中时使用后备锥，不会凭空命中实体。

腿的剂量是 `(base_damage + armor_damage × boot_armor) × 本击倍率`；拳的剂量是书级
`arm_rules.base_damage × 本击倍率`，`BOTH_ARMS` 再乘 1.2。手里的东西不参与出拳伤害，也不扣耐久。
`damage_type_id` 是目录提供的完整命名空间 ID，Human 直接解析该 ID 查 Damage 注册表，不附加固定命名空间。
整次打击包在 `VanillaStrikeContext.attack` 里，Damage 按声明的类型结算出力侧系数和疲劳。

每个选中的目标走两条路之一：

- **普通路**：调用原版 `hurtServer`，盾、无敌帧、局部护甲都由 Damage 现有路径处理。目标没绑身体时一律走这条，
  声明了透劲、麻的在这条路上有退路（`HumanDirectStrikes.hurtBodiless`）：
  - 透劲只剩护甲打折：这一下里目标的原版护甲值和护甲韧性各乘 `armor_multiplier`
    （打之前挂一条临时属性修正 `meridian_human:strike_armor_discount`，打完不论成没成都摘掉）。
  - 麻退成原版状态：打中以后给缓慢和虚弱，不管目录里麻写的是几秒、哪条手臂。多久、各几级读
    `meridian-human-damage.toml` 的 `[bodiless_numb]`（随包初值 60 tick、各一级；等级写 0 是那一样不给）。
    这一节是后加的，老配置文件里没有时按随包初值算。
- **直接路**：目标绑了身体，而且这一下指定了部位、带透劲或带命中附带（`TechniqueLimbStrikes.direct`）。
  由 `HumanDirectStrikes` 走 Damage 的 `damageDirect`，不吃无敌帧。它不经原版受伤入口，所以盾由执行器先查：
  正面被挡住时整下不算（回报 `BLOCKED`），伤害类型带 `#minecraft:bypasses_shield` 的照打。
  打中以后执行器调 `VanillaDamageFabric.presentHurt` 补原版的受伤表现（红闪、受伤动作、受伤声），不给无敌帧。
  - 部位：`fixed_part_id` 点名的部位；`armed_forearm` 取对方拿着东西那一侧的前臂（主手有东西或两手都空取主手侧，
    只有副手有东西取另一侧）；都没写、或点名的部位这具身体上没有时，用 `VanillaDamageFabric.aimedPart` 照伤害类型的部位组选。
  - 透劲：`organ_share` 交给伤害分层，`armor_multiplier` 只对这一下的局部护甲生效。
  - 麻：打中部位上的每块肌肉加一条能力修正（力量、功率乘倍数，ID 为 `meridian_human:numb_<肌肉>`，同一块再麻一次是续期）；
    `whole_arm` 时换成这一侧带 `human:arm_muscle` 标签的全部肌肉。修正和伤害同一笔提交。
- 两条路打中以后击退、命中声音、回报都一样。`disarm` 在打中以后生效：玩家是攻击冷却清零、正在用的物品被打断、东西不掉；
  别的活物是主手的东西掉在脚下。
出力侧由伤害类型标签决定，执行器不另选手臂：前手拳的类型进 `#meridian:off_hand`，双手拳进
`#meridian:two_handed`，后手拳两者都不进；左撇子由 Damage 按主手侧整体镜像。
前手拳挥副手，后手拳、双手拳和腿挥主手。

击退支持推开、横推、绊倒、踩落和掀起；正面盾挡在 `knockback_when_blocked` 开启时仍执行击退，
Damage 类型自身的 `#minecraft:bypasses_shield` 标签决定是否绕过盾。
绊倒清空目标水平速度，并将竖直速度设为向上 2 米/秒（原版每 tick 0.1 格），覆盖此前的下落或跳跃速度。
掀起先按 `knockback` 推开（可为 0），再把竖直速度设为 `lift_speed ÷ 20 × (1 − 击退抗性)`，保留推开后的水平速度。
腿法最后一击只磨脚部装备一次，创造模式不磨损；出拳不磨任何物品。每次实际出手播放一次原版重击声，
收势时释放本次施放的去重记录，并重置原版攻击冷却。

每个选中的目标结算完，执行器向功法服务 `reportStrike` 回报一次：真正受伤报 `HIT`，没受伤但被正面盾挡下报
`BLOCKED`，其余（无敌、挥空）不报。回报的施放身份和精确式取自意图的 `cast_id` 与 `technique`；
旧存档承接来的意图可能没有 `technique`，这时照常结算、不回报。玩家和生物施放者走同一处回报。

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
受击僵直由 Damage 写入功法库的 `HIT`，只打断可打断起手、拒绝新式并减半移动；不锁攻击或物品。
Brewing 的魅惑判定写 `CHARM`，袖手拳的擒写 `HOLD`、摔倒写 `DOWN`。Human 按种类记这三种：
都拦攻击、挥手、开始使用物品和挖掘完成，清理已开始的使用（举着的盾放下）与挖掘，跳跃力度乘 0；
`CHARM`、`HOLD` 另把移动速度乘 0。`DOWN` 的 ×0.2 减速只由功法库加，Human 用 `PlayerDownPoseMixin`
在倒地期间把玩家摆成爬行姿势（两端都按同步过来的倒地减速修饰判断）。
不拦移动包，下落、击退与转头保持原版行为。各种期限独立，到期分别解除，全部解除才放开门禁；都拒绝新起式。

`LOCK` 到 `UNLOCK` 之间玩家处于施放中，`HumanFabric` 在同一处拦原版输入：左键攻击实体、攻击方块一律拦，
挥手包由 `TechniqueHandSwingMixin` 拦；右键（用物品、放方块或与方块交互、与实体交互）在 LOCK 的
`allow_use_while_casting=false` 时拦，目录声明 true 的式（如服药）放行。
若只声明 `allow_off_hand_use_while_casting=true`，仅副手对实际命中且符合声明标签的方块放行；
主手、空气用物品、实体交互与不符合标签的方块仍拒绝。Brewing 釜和火共用此门禁。
右键门禁只在服务端判，客户端回调一律放行：单人游戏的客户端线程与内置服务端共享施放表，
若在客户端把主手拦成 FAIL，原版就不再试副手，副手放行的方块收不到交互；多人客户端本来就读不到施放表。
只有倍率小于 1、会下发 LOCK 的式进入施放中，
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

## 五脏炼化宿主

`EffectIntent.organ_refine` 由 `HumanOrganRefineHost` 接管。它注册在功法库的
`TechniqueFabric.EFFECT_HANDLER`，只处理在线、已就绪且未暂停的玩家；非玩家实体返回 `notApplicable`，
交给其他宿主。玩家身体未就绪时返回 `REFINE_BODY_MISSING`，功法库据此结束整次施放。
`SERVICE_STARTED` 时按已加载功法声明的 `recipe_family` 预读内容包的器官配方，经 Framework 校验，
形状错误或重复配方在首次施放前暴露；`CAST_ENDED` 在收势、打断、取消、死亡和断线等所有结束路径停止起批。

起手前先预检：`SERVICE_STARTED` 同时给功法库登记一条 `addCastCheck`，只看带 `organ_refine` 效果的功法和在线玩家。
它按当前身体绑定槽位，用与第一批相同的器官、通路和配方规则（下列 2、3 条）走一遍，再看选中的本脏经内腔里有没有
当前相态、至少最小转移量的无属性真元；没有就在扣费前拒绝，原因写出经名与相态，例如
「左手少阴心经、右手少阴心经里没有可炼的气态真元」，不再空站整段窗口。器官、通路、配方缺失时返回与第一批相同的原因；
绑定不成或不是玩家时交回功法库自己判定。真炁以后怎样攒气态真元见[吐纳](Gathering.md#真炁吐纳)。

功法库按目录排定每一批（五脏诀上卷为 1.0 s 起、每 2 s 一批、30 s 窗口），每批交给
`HumanOrganRefining.batch`，规则在 `server`，结果是一项 Framework 活动：

1. 同一施放同一批只取一次料，重复交付视为已处理。
2. 器官或所属部位 HP 为零的不能炼；成对的肺、肾一侧受损时另一侧照常炼，全部不可用返回
   `REFINE_ORGAN_UNAVAILABLE`。只用与本脏双向相通且未断裂的本脏经取料和送回，表里腑经只付强身费用；
   缺少通路返回 `REFINE_PATH_MISSING`（「这具身体没有本脏与本脏经之间的通路，炼化无从取料」）；旧存档的身体没有这些通路，
   见[人体定义](Human-Definition.md)。
3. 配方按器官种类和身体境界的相态选取（游炁 `FREE`、真炁 `GAS`、化元 `LIQUID`）；没有对应相态返回
   `REFINE_RECIPE_MISSING`。
4. 器官仍在加工时跳过这一批。否则从本脏经只取当前相态的无属性真元，投料不超过器官剩余缓存与本批吞吐；
   加工时长等于批次间隔，慢速施放时一起拉长。
5. 产物按配方的炼化率换成本行属性、保持投料密度；余量由 Framework 在加工完成时释放到环境，总账守恒。
6. 加工完成后才送回：先进本脏经内腔（扣除在途预约），内腔满时存进同一经脉外壁，仍放不下的留在本脏缓存，
   下次推进再送，不丢失。

施放结束时不再起批，已完成的产物立即送回，仍在加工的那一批由 `HumanCultivation` 的周期推进收尾；
没有结束事件（例如离线）时任务在起批窗口过后、器官空闲时自行移除。任务进度保存在
`meridian_human:organ_refine`（`organ_refine.proto`，schema 1）的身体进度里，随玩家身体存档。

互斥：肺炼起批时若正在手动或自动吐纳，先按结束按钮的同一意图停下吐纳；肺炼任务未收尾前，
开始吐纳与设置自动吐纳的请求都返回 `ORGAN_BUSY`，结束与查看照常。脾炼期间中焦储备暂停入库，
已完成的储备产物照常扫入外壁，外壁取用和主动动员不受影响，见[中焦周天](Middle-Jiao-Cultivation.md#脾与外壁)。

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

`HumanModuleTagsTest` 校验正式模板的六个公开标签与五行标签、精确成员与左右对称，以及五脏耐力和 18 条本脏通路。
`HumanOrganRefiningTest` 经真实 Framework 事务执行炼化：吞吐上限、缓存上限、炼化率、打断后不再起批、窗口收尾、脾炼暂停储备、器官损毁/缺通路/无配方拒绝及真炁用气态配方；`HumanPlayerBodyRefineTest` 覆盖肺炼停吐纳并拒绝重开。玩家学卷、经 `TechniqueService` 施放的整链 GameTest 在五脏诀仓（`Kizuna-Meridian-Evoker-Elements-Addon` 的 `gametest`）。
`HumanTechniqueGameTest` 使用经过真实登录、频道注册和断线事件的服务端测试玩家，
覆盖在线认领、身体时钟、版本冲突、持久活动重放、冲量方向与去重、速度包、未知表现、
学习命令到收势、释放归位、断线不退费、重新认领前清理残留施放及吐纳共享组织。
`HumanTechnique6aGameTest` 补充双空手与副手事实、潜影盒/收纳袋装物、有耐久的无法破坏工具、
移动限速及本人属性包、限速期间冲量、向后默认方向、收势/打断/断线移除限速、表现起播时刻及幂等，
以及未认领和死体恢复中的 STOP/UNLOCK、清理失败重试及清理先于进度提交。
`startCallbackCancelsAfterDeliveryWithoutRecursion` 覆盖 START 回调取消后的唯一 STOP 和限速清理；
`synchronousResubmissionDistinguishesExecutingFromSucceeded` 覆盖同步重提交、两种身份内容冲突及失败后的重试；
`disappearedTargetCancelsAndUnlocksRealPlayer` 覆盖目标销毁后取消与实体约束清理。
`HumanPlayerBodyStampTest` 校验身体戳与随后结清的版本、时钟一致，寿元终点前一毫秒照常报数、终点当毫秒和死后答不出，
早于已记账时间也答不出；`HumanTechniqueStampGameTest` 用真实登录的玩家核对读数时钟与 `inspect` 回执的
`settled_at` 相同，非主线程、身体暂停（撤销身体视图通道）和断线后都不给读数。
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
`HumanFistGameTest` 用只在 GameTest 源集注册的「测试拳」目录（`human_fist_test/technique_catalog.txtpb`，
一书一卷三式：前手、后手、双手）走正式学习、施放与宿主协议；入水和攀爬由测试玩家子类直接给出，
因为原版这两项只在实体 tick 里刷新，骑乘、滑翔和离地走原版入口。

| 玩家契约 | GameTest |
| --- | --- |
| 双空手、装物容器拒绝，副手与类别/耐久/主手优先 | `handsRejectEmptyAndContainersAndSelectByCategoryWearThenMain` |
| 铁斧/铁剑/泥土/火把剂量与叩门钝击 | `damageCategoriesDoseAndKnockBluntUseRealDamageChain` |
| 数据包标签变更类别 | `changedCategoryTagsChangeDamageWithoutItemSpecialCases` |
| 耐久倍率、耐久附魔、损坏、无法破坏、创造 | `durabilityMultipliersBreakageUnbreakingAndCreativeMatchVanilla` |
| 起手打断不磨损 | `warmupInterruptionDoesNotWearWeapon` |
| 破门起手：轻击低于僵直门槛不打断，重击打断、减速 0.5 并拒绝新式 | `cleaveWarmupInterruptedOnlyByStaggeringHit` |
| 未施放时重击减半移动，身体时钟到期恢复原速 | `staggerSlowsMovementUntilExpiry` |
| 一次性末击挥空、最后一个物品及重试幂等 | `disposableLastStrikeConsumesOnceEvenOnMissAndRetry` |
| 双手剂量与两臂做功、副手只用对应手臂 | `twoHandDoseAndOffhandDamageChargeCorrectArms` |
| 腿击读声明伤害类型、只在生存模式磨靴；绊倒速度 | `legStrikeReadsDeclaredDamageAndWearsBootOnlyInSurvival` / `legTripClearsHorizontalMotionAndLiftsFallingOrJumpingTargets` |
| 空手、剑、盾、火把都能出拳，物品不动 | `fistsCastWithAnyHeldItemAndLeaveItUntouched` |
| 出拳剂量 1.5 × 倍率、双手 × 1.2，前手副手侧、后手主手侧、左撇子镜像 | `fistDoseAndMuscleSideFollowArmAndMainHand` |
| 掀起竖直速度乘 (1 − 击退抗性) | `liftVerticalSpeedScalesWithKnockbackResistance` |
| 水中、空中、攀爬、骑乘、滑翔出拳回 NOT_GROUNDED，身体不变 | `offGroundFistIsRefusedBeforePayment` |
| 真实施放打中，回报一次 HIT，施放身份与精确式对得上 | `realFistCastReportsHitWithCastAndTechnique` |
| 正面举盾挡下回报 BLOCKED；不带所属式的意图不回报 | `shieldBlockedFistReportsBlockedButUnnamedIntentStaysSilent` |
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
