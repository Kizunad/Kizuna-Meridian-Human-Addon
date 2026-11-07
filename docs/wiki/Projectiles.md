# 射击宿主与原版箭

Human 执行功法库 `launch_projectile` 与 `target_lock`。目录负责式、时刻、倍率、持物条件和做功；
本仓负责真实世界中的目标、弹药、箭、烟花、附魔和耐久。身体只读系数来自 Damage，
不为射击复制肌力算法，也不另记一次近战做功。箭命中仍走原版伤害链和 Damage 的穿刺分类。

## 公开宿主接口

`dev.kizuna.meridian.human.fabric.HumanProjectileEffects` 是明确供玩家与类人生物共用的公开 API：

```java
new HumanProjectileEffects(LivingEntity actor, Predicate<LivingEntity> hostile)
void lockTarget(EffectIntent effect)
void launch(EffectIntent effect)
void finish(String castId)
```

调用方在服务器主线程按实体持有一个实例，在起手交付 `target_lock`，再交付射击效果。
调用方负责复用既有 `(cast_id, effect_id)` 回执去重；该 API 不重复建立效果回执。
正常收势、打断、取消调用 `finish`，实体卸载时丢弃实例。清理不会销毁已射出的箭。
Human 的 `HumanTechniqueEffects` 已接入上述流程；Threatening 可用自己的敌我谓词复用。
不能在没有锁点的情况下直接提交带 `lock_target` 的射击，此时明确拒绝。

`HumanProjectileItems` 提供只读 `category(ItemStack)`、`inspect(LivingEntity)` 和
`canShoot(LivingEntity, InteractionHand)`。`inspect` 在原有双手消息中显式追加两手弓弩类别与
`can_find_ammunition`，空手或非弓弩类别仍显式返回未指定。库存换兵预览可读取候选物品类别；
生物无须为了预览临时改写原版物品栏。

生物不扣弹药、不磨损弓弩，发射箭一律 `Pickup.DISALLOWED`。玩家创造模式不扣弹药与耐久。
生存玩家每次撒放重新找弹药；中途用光或选定手已没有相应武器时只播空弦声，效果照常确认，
不补发、不退款、不扣耐久。原版弩已有的 `DataComponents.CHARGED_PROJECTILES` 始终不读取消耗、不改写。

## 弹药与附魔

已按本机 26.2 原版 JAR 核对 `Player.getProjectile`、`ProjectileWeaponItem`、`CrossbowItem`、
`ArrowItem`、`Projectile.spawnProjectile` 与 `AbstractArrow`：

- 箭按副手、主手、背包顺序查找。烟花只允许作为弩另一只手里的弹药，背包烟花不被扫描。
- 无弹药的创造玩家生成普通箭；无限通过 `EnchantmentHelper.processAmmoUse` 的普通箭谓词判断，
  药箭与光灵箭不免耗。设计要求无限弓无普通箭也能起式，本宿主据此提供虚拟普通箭。
- `ArrowItem.createArrow` 保留药箭组件与光灵箭类型；箭保存发射武器副本。
  `Projectile.spawnProjectile` 应用火矢等生成附魔，命中时原版 `EnchantmentHelper.modifyDamage`
  加力量、`modifyKnockback` 加冲击。伤害基数先设为 `2 × 目录倍率`，力量在此后另加。
- 弩用 `processProjectileCount/processProjectileSpread` 读取多重射击；普通附魔产生三支、偏角 ±10°。
  只耗一支弹药，额外箭带原版 `INTANGIBLE_PROJECTILE`，不能被生存玩家捡回。
  `getPiercingCount` 得到的原版穿透与目录穿透相加，范围限制在原版 byte 上限。
- 弓每支箭磨损 1，弩每支普通箭磨损 1，多重射击三支合计 3；
  26.2 原版 `CrossbowItem.getDurabilityUse` 对每支烟花返回 3，因此三发烟花合计 9。
  耐久附魔、无法破坏和损坏广播交给 `ItemStack.hurtAndBreak`。
- 普通弓满弓初速 3.0 格/tick、弩箭 3.15、弩烟花 1.6。烟花保留原版爆炸伤害，不乘目录箭伤倍率。
  普通射击乘目录初速与撒放时的拉弦系数，并继承射手的水平运动；离地时也继承竖直运动。

附魔不根据名称复制数值计算。快速装填不改变功法调度时间。

## 目标与弹道

`look` 使用玩家撒放时视线。生物使用意图指定的同世界敌对目标及速度提前量。
`look_offset` 的正角度向右，`mirror_by_hand` 开启后副手反号；不再按惯用左右手重复镜像。
`target_snap_angle` 为中心两侧的半角，选扇区内最近、可见且通过宿主敌我谓词的生物。
散布在声明最大角度内均匀取锥面方向，不能用无界的原版高斯不确定度代替。

玩家 `target_lock` 冻结视线内最近目标的身体中心、速度与身体时刻；没有生物时冻结方块射线落点。
生物优先冻结宿主指定、同世界敌对且在最大距离内的已知目标，允许目标被掩体遮挡。
后续不会因目标死亡、移走或施放者转头而换目标，只按锁定瞬间速度外推。
`lob` 使用目录最大距离、随机圆盘半径、仰角与初速上限。锁定射击以冻结位置为圆心；
未锁定的生物抛射使用范围内的已知敌对目标，玩家与无有效目标的生物使用视线末端。
圆盘采样点向下投射到地面，
找不到地面时保留采样点；不会把普通平视下的空中末端当成已经落地。

纯计算在 `HumanProjectileBallistics`，单位为格和 tick：每 tick 先位移，再把速度乘 0.99，
最后减竖直重力 0.05。反解由同一离散积分求出，不使用真空抛物线公式；不足一个 tick 的到达时刻
按最后一段位移插值。发射惯性在反解时先扣除，再由原版发射加回。
整 tick 的位移与重力累计使用等比和直接计算，候选时间搜索无需从第零 tick 重复模拟；
仍保留相同的离散更新顺序与末段插值。时间输入拒绝非有限值或负数。

天地同至参考平射的身体时刻为
`cast_started_millis + reference_shot_millis - clip_start_millis`。
第一次仰射根据锁点距离和参考初速估算平射飞行时间，再加 `arrival_delay_millis`，
保存共同到达时刻。参考初速从 Damage 的 `inspectBody(entity)` 取得唯一宿主快照，再使用其
`VanillaDamageFabric.armCoefficientAt(snapshot, arm, elapsedMillis)` 预估未来时刻的肌力，不推进权威身体。
每支仰射按实际发出时刻反解剩余时间；仰角不足或初速超过上限时退为普通抛射，
不承诺同时到达。首支效果延迟到参考平射时刻之后才执行时，直接退为普通抛射，
不调用只允许当前或未来时刻的身体查询。运动目标按起手速度预测，之后变向或遇到障碍不属于跟踪制导。
若该平射参考点的曲线尚未登记，Human 只在当前 `cast_id` 与身体中的活动施放一致时，将其
`pending_modifiers` 加入内存候选并用同一肌力规则预估；其他施放不借用该曲线。实际撒放仍只读
已登记曲线，预测候选不会写回身体或提前改变箭的实际拉弦系数。

## 盾、输入与表现

`TechniqueArrowMixin` 只为原版箭提供追加穿透和盾冷却参数；冷却随箭保存，区块卸载后仍有效。
`TechniqueProjectileShieldMixin` 等 `LivingEntity.applyItemBlocking` 返回实际格挡量后，
才由原版 `BlocksAttacks.disable` 禁用盾。背面、未举起、免格挡类型或穿透绕盾不会误触发。
原版穿透箭绕过盾，因此设计中的“天地同至穿透 2”与“平射被盾挡后冷却 3 秒”不能同时发生；
本仓保留原版行为与目录数值，需内容审阅决定是否更改其一。

施放中仍由现有 LOCK/UNLOCK 与 Fabric 回调拦左键、右键，行矢目录使用 0.4，拉空弓使用 0。
禁用右键的式在起手与收势额外 `stopUsingItem`，取消先前原版拉弓／装填，不触发原版撒放；
显式放行使用的既有式不受影响，攻击冷却沿用既有收势清理。
主副手表现由 `HumanTechniqueAnimations` 将 `meridian_wayfarer:wayfarer_<式>` 映射到同名片段或 `_off`。
动画资源与物品外观属于 PA，不在本仓改写。

每次撒放由本执行器播放原版弓／弩发射事件，空弦使用较低音高。
装填的开始／中段／结束声应由目录按动作时刻声明既有 `TechniqueEffect.sound`，
经功法库声音监听者播放；`launch_projectile` 不带装填时段，宿主不能从撒放时刻倒推并补播。

26.2 的物品模型状态已核对原版 `assets/minecraft/items/bow.json` 与 `crossbow.json`：
弓用 `minecraft:using_item`、`minecraft:use_duration`（scale 0.05，阈值 0.65/0.9）；
弩用 `minecraft:charge_type`（`arrow`/`rocket`）、`minecraft:using_item`、
`minecraft:crossbow/pull`（阈值 0.58/1.0），不再是旧版本的模型谓词提供器。
主副手动画规格的射箭／弓梢命中时刻与设计稿 14 相同；收势脚步帧的修改不改变服务端射击时刻。

## 回归范围

`HumanProjectileBallisticsTest` 独立逐 tick 校验反解、固定仰角抛射、速度上限、汇聚退化、
平射飞行时间及移动射手的惯性补偿；并逐项比较搜索区间内的整 tick 和小数 tick 位移。
`HumanTechniqueAnimationsTest` 检查十式主副手片段。
`HumanProjectileGameTest` 经正式宿主字节入口覆盖双手事实、弹药顺序、重试、光灵箭、无限／火矢、
附魔武器保留与药箭真实命中、多重射击／穿透、空弦、弩原上弦、创造、生物公开入口、
原版 tick 与地面落点、输入拦截与盾冷却的三秒边界。
`mobLobReachesRememberedTargetBehindCover` 与 `lockedMobLobKeepsHiddenPointAfterTargetDisappears`
经过公开生物发射入口和真实箭碰撞，覆盖隔墙目标落点及锁定后目标消失、射手转头。
`HumanProjectileConvergenceGameTest` 使用原版箭在真实服务器 tick 中检查锁点、转头、承接偏移、
四箭到达差和迟到首箭的普通抛射；通过真实宿主活动保存待登记曲线，比较其与已登记曲线的
原版箭初速，并检查施放身份隔离、预测不改身体及当前拉弦系数不受待登记曲线影响。
这些用例不代替正式行矢目录的九式时刻、费用与全套学习／承接验收。

完整验证使用 `./gradlew --no-daemon test build demo :fabric:runGametest`。
`-PhumanGametestRunDir=build/<独立目录>` 为本轮测试新建运行目录，默认仍为 `build/gametest`。
结果见该运行目录下 `build/gametest-results.xml`；文档门禁是 `python3 "scripts/check_docs.py"`。

2026-11-03 本仓验证：`test build demo` 通过；JUnit 74 项（server 46、fabric 28），
GameTest 88 项必需用例通过（原有 Human 75、新增射击 12、Minecraft 内置 1），无失败或跳过。
当轮完整装配的外围运行 JAR 读取主工作区既有产物，Human、Technique、Damage 仍使用本工作区源码。
Threatening 新版依赖尚未装配的正式行矢目录，因此该轮使用其主工作区既有产物；
这证明 Human 宿主与公开生物执行入口，不表示新版掠夺者和正式行矢目录已经完成联合验收。
