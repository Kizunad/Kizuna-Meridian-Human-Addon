# 仓库协作约定

- 这是独立的 Kizuna's Meridian Human Addon，依赖 Kizuna's Meridian Mod Framework；人体定义只能放在本仓库，不向框架添加人体特例。
- 简体中文沟通、文档和函数内注释；公开标识符使用英文。可读性优先，按读取、校验、计算、请求、结果的业务顺序写代码，简单查找可使用 Stream。
- 新增或修改方法、构造器必须有内部中文意图注释；避免重复命名已表达的内容与无关的未实现说明。
- `client/` 只构造操作意图，不依赖权威计算；`server/` 提供人体内容与可信初始化候选，不保存或修改独立身体账本。
- 对外通信复用框架 `.proto` 的 protoc 生成消息，依赖通过 Gradle composite build 接入。不复制框架源码、协议或生成类，不另建 JSON 通信。
- 新通信语义确有需要时先设计 Protobuf 契约；通用算子归框架，人体数值、构型和固有配方归本仓库，功法学习与 3D 内景不混入人体附属。
- 运行时异常和文案集中在 `server/src/main/java/dev/kizuna/meridian/human/server/HumanErrors.java`；框架拒绝保持其原始 Protobuf 失败码。
- 只保留必要整体回归，覆盖独立内容注册、真实请求链、守恒、失败与重试、恢复和产物；临时验证通过后清理。
- 文档编辑源为 `docs/wiki/`，README 仅作为入口。行为或内容契约变化同步更新文档，交付前运行 `./gradlew build`、`./gradlew demo` 与文档检查。
- 用 patch/Edit 修改文件，命令路径使用双引号。保留用户修改，不强推、不改历史；提交、推送与远端建仓分别按用户授权执行。
- PolyForm Noncommercial 1.0.0；保留第三方许可。没有真实游戏宿主验证前，不称作已可直接放入 mods 的 Fabric 模组。
