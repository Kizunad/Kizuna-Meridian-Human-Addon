# 玩家受控模式

Human 定义并实现 `meridian_player_control_host`，定义决策入口 `meridian_player_control_driver` 由 Threatening 实现。
两者都是可信服务端主线程的 `exchange(caller, byte[])`，没有客户端写入入口；Human 不依赖 Brewing 或 Threatening。
协议唯一来源为 `shared/src/main/proto/kizuna/meridian/human/v1/control.proto`。

宿主接收 caller `meridian_humanoid:control` 的 `ControlCommand`，上限 4096 字节。
会话包含 UUID、玩家/施药者/当前目标 UUID、主世界 gameTime 起止 tick；空目标表示跟随。
START 建立会话并关闭已有容器、停止用物品；同一 START 重试只确认身份，UPDATE 只能换目标或缩短期限。
END、死亡、断线和到期清会话；同进程结束不保存到玩家存档。

每个 END_SERVER_TICK，Human 从唯一身体读取已结算快照、已学功法、双手物品和服务端附近实体，
以 caller `meridian_human:control` 向决策入口发送 `ControlRequest`。请求包含严格递增序号与当前 tick。
`ControlIntent` 必须回显会话/序号/tick，期限为当前 tick 后 1–4 tick，移动分量在 [-1, 1]，视角为有限值，
俯仰在 [-90, 90]；攻击目标只能是本次会话目标。返回载荷上限 4096 字节。

Human 用 Framework `ProtobufChannel` 的 `meridian_human:control` 只向这个玩家下发意图。
攻击与功法由服务端代发，沿原版攻击冷却、距离、视线及 Technique 的已学、绑定、费用、冷却与僵直检查。
受控玩家的主动施放全部返回 `ACCESS_DENIED`；只有这一次同步代发的请求对象可通过准入。
原版攻击、物品、容器、切栏、丢弃、放置、挖掘及创造背包包在服务端拒绝，已有挖掘也不能完成。
聊天与暂停菜单保留原版行为。

客户端受控模式独立于战斗 UI。键盘移动、视角、跳和疾跑由意图映射；攻击不在客户端重复发送。
原始移动/攻击/快捷键、游戏内滚轮换栏和容器页面被拦；聊天、命令键与菜单鼠标保留。
按本地 tick 计算有效期，断流停步并保留最后视角；收到结束或断线清按键、移动向量、跳和疾跑。
乱序意图、已结束会话重放以及其他会话的迟到 END 不得接管当前输入。

`HumanLimbTempo` 是玩家与类人共用的只读内容 API：一次遍历实际构型建立肌肉索引，主手臂取最慢值，
两腿各含同侧臀大肌，分别取最慢值后平均。组织曲线由 Framework 计算，接口不保存身体副本。

纯客户端状态机回归覆盖输入映射、断流停步、结束复位、乱序与旧结束包。
Brewing 的完整装配 GameTest 验证真实玩家会话、通道、操作拒绝、代发和断线重登。
真实客户端按键、转头、聊天/暂停、延迟及移动观感仍未测；不含 HUD、紫色表现或动画。
