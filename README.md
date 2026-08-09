# GTOCutCorners

[GTOCore](https://github.com/GregTech-Odyssey/GTOCore) 附属模组，将 GTCEu 中所有非发电机配方的处理时长缩短为 **1 tick**，并附带一套用于注册多方块机器、物品和 ME 工具的扩展框架。

> 代码由 AI 生成并持续维护，未经完整人工审核。请在任意游戏阶段自行评估坏档风险。

## 功能

| 功能 | 说明 |
|------|------|
| 配方时长缩短 | 所有非发电机类 GTCEu 配方 duration → 1 tick |
| 原版配方缩短 | 可选：原版烹饪类配方 duration → 1 tick |
| 服务器启动时补丁 | 在 `ServerStartingEvent` 一次性执行，不再每次玩家登录重复修补 |
| 多方块注册 | GTOHJS 同款：coremod 注入 `GTOMachines.<clinit>`，经 `MachineRegisterUtils` 注册到 `gtocore:` 命名空间 |
| 配方注册 | coremod 注入 `Data.commonInit()` 的 `RecipeFilter.init()` 之后，另有 CommonSetup 最佳努力兜底 |
| 已移植机器 | 简单之盒（蒸汽，13×7×13 原版结构）、太古石炉（零能耗原版熔炉配方，GTO 跨配方多线程：装线程仓可同时处理多种配方） |
| 物品/方块/创造标签 | 独立 `DeferredRegister`，新增 ME 网络扫描器、配方时长探针、整体青铜框架 |
| ME 工具 | `me_network_scanner`：右键 ME 网络方块查看物品/流体存储统计（依赖 AE2） |
| 超级ME样板总成 | 移植自 GTLAdditions：可配置容量（默认 9×6×255=13770 槽），支持神锻样板模式（输出倍率）与镜像部件 |

> v1.3.0 起已移除全部 ByPass 相关代码（BypassService、ModLauncher 修补、LaunchPluginsGuard 等），不再对抗 GTO 的启动校验。

## 环境要求

- Minecraft **1.20.1**
- Forge **47.4+**
- GTOCore **0.5.6-alpha-8**（编译依赖与运行时保持一致）
- GTCEu 1.20.1-1.8.3
- AE2 15.5.0（ME 工具与超级样板总成需要）
- GTMThings 1.3.5（GTOCore 的 ME 样板总成基类依赖，编译需要）
- Java 21

## 配置

配置文件位于 `config/gtocutcorners.json`：

```json
{
  "oneTickMode": true,
  "patchVanilla": true,
  "patchGT": true,
  "patchGTMass": true,
  "registerMachines": true,
  "dumpContent": false,
  "superPatternBuffer": {
    "patternsPerRow": 9,
    "rowsPerPage": 6,
    "maxPages": 255
  }
}
```

- `oneTickMode`：是否启用 1 tick 缩短
- `patchVanilla`：是否同时缩短原版烹饪类配方
- `patchGT`：是否处理 GT 配方
- `patchGTMass`：是否使用原生批量补丁（native DLL）
- `superPatternBuffer`：超级ME样板总成的每行样板数、每页行数、最大页数（槽位 = 三者乘积）

> v1.3.0 起已移除 `clearConditions`：配方条件不再被替换，机器仍须满足原有配方条件。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/`。native DLL 使用 CLion 自带 MinGW 重建：

```bat
cd src\main\native
build.bat
```

## GTO 版本适配（自动）

机器注册走 GTOHJS 式 coremod 注入，依赖 GTO 版本，但**适配已自动化**，GTO 更新后不需要人工/LLM 适配：

```bash
python tools\adapt_gto.py "G:\RESOURCES\GT私货\依赖\gtocore-forge-<新版本>.jar"
python tools\generate_gtohjs_coremod.py
```

1. `adapt_gto.py` 扫描新版 jar（自动提取内嵌 gtolib），按**结构特征**定位：
   - `GTOMachines.<clinit>`（机器窗口）
   - `GTORecipeTypes.<clinit>`（配方类型窗口）
   - `Data.commonInit()` 中 `RecipeFilter.init()` 调用点（配方窗口）
   - gtolib 混淆的 Mixin 守卫类、RecipeLogic 信任检查类
2. `generate_gtohjs_coremod.py` 根据 `build/adaptation_report.json` 重新生成
   `coremods/gtocutcorners_gto_window.js` 与 `META-INF/coremods.json`

## 扩展：注册内容

### 注册多方块机器

在 `GTOCMultiblocks.registerFromGtoWindow()` 中用 `MachineRegisterUtils.multiblock(...)` 添加，机器注册到 `gtocore:` 命名空间，由生成的 coremod 在 `GTOMachines.<clinit>` 末尾调用：

```java
MultiblockBuilder builder = MachineRegisterUtils.multiblock(
    "my_machine", "我的机器",
    (Function<MetaMachineBlockEntity, ? extends MultiblockControllerMachine>) factory);
builder.tier(4)
    .recipeType(GTORecipeTypes.BLAST_RECIPES)
    .overclock()
    .multiblockPreviewRenderer(true, true)
    .pattern(def -> FactoryBlockPattern.start()
        .aisle("AAA", "AAA", "AAA")
        .where('A', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get()))
        .where('B', Predicates.controller(def))
        .build())
    .register();
```

> 注意：结构请用程序化 `FactoryBlockPattern` 提供，不要用 `MultiBlockFileReader` 读极简 `.mbs`，
> 否则会产出不完整 pattern，导致 GTO 客户端 EMI 数据初始化崩溃。

### 注册物品 / ME 工具

在 `GTOItems.ITEMS` 中新增 `RegistryObject`，然后在 `GTOCreativeTabs.MAIN` 中把物品加入创造标签。ME 工具继承 `Item` 并实现 `onItemUseFirst`，可直接使用 AE2 API（见 `MeNetworkScannerItem`）。

## 协议

[LGPL-3.0](LICENSE)，与上游 GTOCore 保持一致。

开发避坑记录见 [DEVELOPMENT_NOTES.md](DEVELOPMENT_NOTES.md)，
GTOHJS 完整注册模板见 [docs/GTOHJS_REGISTRATION_TEMPLATES_ZH.md](docs/GTOHJS_REGISTRATION_TEMPLATES_ZH.md)。
