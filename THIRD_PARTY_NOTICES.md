# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

| 组件 | 版本 / 来源 | 许可与使用 |
|---|---|---|
| Kizuna's Meridian Mod Framework | 同级独立仓库，首片验证基线 `90d2205` | PolyForm Noncommercial 1.0.0；内容库使用外部依赖，可选 fabric 安装包嵌套原始服务端 JAR，保留许可 |
| Gradle Wrapper | 8.8，[来源](https://github.com/gradle/gradle/tree/v8.8.0) | Apache License 2.0；启动脚本及 Wrapper JAR 从经脉框架复制，保留版权头及 JAR 内 LICENSE；分发校验和沿用框架配置 |
| Protocol Buffers | 4.36.2，由框架解析 | BSD 3-Clause；复用框架 protoc 消息；fabric 安装包嵌套原始运行库 JAR |
| tomlj / ANTLR / Checker Qual | 1.1.1 / 4.11.1 / 3.21.2，由框架解析 | Apache License 2.0 / BSD 3-Clause / MIT；fabric 安装包携带 tomlj、ANTLR 原始 JAR，Checker Qual 为注解依赖 |
| Fabric Loader / Fabric API / Loom | 0.16.10 / 0.92.3+1.20.1 / 1.6 | Apache License 2.0；Loader 与 API 由游戏安装提供，Loom 为构建工具 |
| JUnit / Hamcrest Core | 4.13.2 / 1.3 | Eclipse Public License 1.0 / BSD 3-Clause；仅整体测试依赖，不随人体 JAR 分发 |
| Gson | 2.10.1 | Apache License 2.0；仅构建期读取 Blockbench 原生文件，不随人体 JAR 分发 |

原始依赖保留自身许可。人体几何由本项目设计并整理成 Blockbench 原生编辑文件，图集为本项目原创像素纹理；剪影遵循 Minecraft 原版宽臂玩家基础模型的尺寸与站立比例，没有打包原版皮肤、模型文件、字体或声音。Minecraft 属于 Mojang/Microsoft，其权利不受本项目许可覆盖；内容客户端是 Fabric 资源容器，独立 fabric 模块提供适配代码，两者均不打包 Minecraft 实现。
