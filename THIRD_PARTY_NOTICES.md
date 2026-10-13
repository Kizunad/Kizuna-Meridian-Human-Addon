# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

| 组件 | 版本 / 来源 | 许可与使用 |
|---|---|---|
| Kizuna's Meridian Mod Framework | 同级独立仓库，首片验证基线 `90d2205` | PolyForm Noncommercial 1.0.0；作为外部依赖使用，不内嵌源码或 JAR |
| Gradle Wrapper | 8.8，[来源](https://github.com/gradle/gradle/tree/v8.8.0) | Apache License 2.0；启动脚本及 Wrapper JAR 从经脉框架复制，保留版权头及 JAR 内 LICENSE；分发校验和沿用框架配置 |
| Protocol Buffers | 4.36.2，由框架解析 | BSD 3-Clause；复用框架 protoc 生成消息与外部运行库，不复制生成代码或单独打包运行库 |
| tomlj / ANTLR / Checker Qual | 1.1.1 / 4.11.1 / 3.21.2，由框架解析 | Apache License 2.0 / BSD 3-Clause / MIT；用于演示及测试环境配置，不内嵌到人体 JAR |
| JUnit / Hamcrest Core | 4.13.2 / 1.3 | Eclipse Public License 1.0 / BSD 3-Clause；仅整体测试依赖，不随人体 JAR 分发 |

原始依赖保留自身许可。当前不包含 Minecraft、Fabric、模型、贴图、字体或声音。
