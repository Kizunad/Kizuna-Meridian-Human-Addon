# Kizuna's Meridian Human Addon

经脉框架的独立人体附属，提供人体构型、初始状态及基础聚气内容。

文档入口：[项目说明](docs/wiki/Home.md) · [人体定义](docs/wiki/Human-Definition.md) · [聚气流程](docs/wiki/Gathering.md) · [依赖与接入](docs/wiki/Integration.md)。

```bash
./gradlew build
./gradlew demo
python3 "scripts/check_docs.py"
```

本地构建默认依赖同级 `Kizuna-Meridian-Mod-Framework` 仓库；其他路径使用 `-PmeridianFrameworkDir="/path/to/framework"`。目前交付 Java 17 附属库与 Protobuf 内容，尚无 Fabric 入口或游戏内操作界面。

许可：PolyForm Noncommercial 1.0.0，见 [LICENSE](LICENSE)。
