# GTOCutCorners

[GTOCore](https://github.com/GregTech-Odyssey/GTOCore) 附属模组，将 GTCEu 中所有非发电机配方的处理时长缩短至 **1 tick**。

---

## ⚠️ AI 生成代码声明

**本项目全部源代码由 AI（DeepSeek）生成，未经人工逐行审查。**

这意味着：
- 代码中可能存在未发现的 bug、逻辑漏洞或安全隐患
- 部分实现可能不符合最佳实践
- 在任意游戏阶段都可能出现预期之外的行为（坏档风险请自行评估）

**请在测试环境验证后再用于正式存档。使用本项目即表示你已理解并接受上述风险。**

---

## 功能

| 功能 | 说明 |
|------|------|
| 配方时长缩短 | 所有非发电机类 GTCEu 配方 duration → 1 tick |
| Mixin 注入 | 通过 Mixin + ClassFileLoadHook 在字节码层面打补丁 |
| Native 辅助 | 部分诊断逻辑使用 JNI native 代码 |

---

## 环境要求

- Minecraft **1.20.1**
- Forge **47.4+**
- [GTOCore](https://github.com/GregTech-Odyssey/GTOCore) **0.5.6+**
- GTCEu 1.20.1-1.8.0
- Java 21

---

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/`。

---

## 协议

[LGPL-3.0](LICENSE) —— 与上游 GTOCore 保持一致。
