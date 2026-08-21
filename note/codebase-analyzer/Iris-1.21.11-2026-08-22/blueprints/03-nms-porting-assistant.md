# Skill Blueprint: nms-porting-assistant（NMS 版本移植助手）

> 自动生成自 codebase-analyzer · 分析时间：2026-08-22
> 源模块：`core/src/main/java/com/volmit/iris/core/nms/INMS.java` + `nms/v1_20_R1..v1_21_R7`（11 个版本目录，33 个 Java 文件）

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| 推荐 Skill 名称 | `iris-nms-porter` |
| 用途 | 新 Minecraft 版本发布时，基于上一版 NMSBinding 与新旧映射差异生成新版本绑定层的移植草稿与修改清单 |
| AI 替代等级 | 🧑‍💻 AI 辅助（20/30）——产物必须人工审核 |
| 实施优先级 | 🥈 Strategic |
| 源文件数 | 33（nms 全部）+ INMSBinding 接口 1 |
| 源代码行数 | ~4,000（11 目录合计） |

## 2. 触发场景与关键词

- "1.21.12 出了，移植 Iris 的 NMS 绑定"
- "新建 nms/v1_21_R8 模块"
- "这个版本的 getBiomeBase 映射变了，帮我改"
- "对比 v1_21_R7 和 v1_21_R6 的 NMSBinding 差异"

**推荐 description 触发词：**

```yaml
description: >-
  Assist porting Iris NMS bindings to new Minecraft versions by diffing
  previous bindings and spigot mappings, generating draft modules and a
  change checklist. Triggered by: "移植", "NMS binding", "新版本适配",
  "port to 1.21.x".
```

## 3. 输入输出契约

### 现有移植模式（AI 需复刻的流程）

| 步骤 | 现有人工作法 | 证据 |
|------|-------------|------|
| 1. 新建模块 | 复制 `nms/v1_21_R7` 目录 → 改包名 → settings.gradle.kts 加 include | settings.gradle.kts:33-44 |
| 2. 注册版本 | `nmsBindings` map 加条目（`build.gradle.kts:71-84`）+ `INMS.REVISION` 表加 `new Version(major, minor, "v1_21_R8")`（INMS.java:33-42）+ `CURRENT` 上限更新（INMS.java:29-31） | 
| 3. 逐方法修映射 | 按 spigot member mapping diff 修 `NMSBinding.java` 中每个反射/ByteBuddy 目标 | nms/*/NMSBinding.java |
| 4. 数据包版本 | `INMS.PACKS` 表加 `new Version(...)`（INMS.java:44-49） | |
| 5. 验证 | `gradlew runServer-v1_21_R8` 一键起服冒烟 | build.gradle.kts:86-117 |

### 核心接口契约（移植面）

```java
// core/nms/INMSBinding.java —— 新实现必须满足的全部能力（节选，全文见源文件）
public interface INMSBinding {
    void inject(long seed, Engine engine, World world)          // BiomeSource 注入，失败致命
        throws NoSuchFieldException, IllegalAccessException;
    boolean supportsCustomBiomes();                              // 决定 custom biome 功能开关
    Object getCustomBiomeBaseFor(String mckey);                  // 自定义群系注册
    void forceBiomeInto(int x, int y, int z, Object b, BiomeGrid g);
    boolean hasTile(Material material);                          // tile 序列化族
    KMap<String, Object> serializeTile(Location location);
    void deserializeTile(KMap<String, Object> s, Location newPosition);
    CompoundTag serializeEntity(Entity e); Entity deserializeEntity(...);
    void placeStructures(Chunk chunk);                           // 结构放置
    KMap<Identifier, StructurePlacement> collectStructures();
    MCAPaletteAccess createPalette();                            // Anvil 调色板
    // ...完整 60+ 方法签名见 INMSBinding.java
}
```

### 输出物

| 产物 | 形式 |
|------|------|
| 新模块骨架 | `nms/v1_21_R8/`（含 build.gradle.kts、NMSBinding.java 草稿） |
| 修改清单 | 逐方法：旧目标 → 新目标 → 置信度（高=映射直接命中/中=方法重载歧义/低=语义变更需人工） |
| 构建注册补丁 | settings.gradle.kts / build.gradle.kts / INMS.java 三处 diff |
| 验证脚本 | runServer 任务名 + 冒烟 checklist（enable 无异常、生成一个世界、custom biome 数量>0） |

## 4. 依赖清单

| 类型 | 内容 | 来源 | AI 所需 Context |
|------|------|------|----------------|
| 接口定义 | INMSBinding 全部方法 | core/nms/INMSBinding.java | 完整 |
| 参考实现 | 上一版 v1_21_R7 全部源码 | nms/v1_21_R7/ | 完整 |
| 映射差异 | 新旧 spigot member mapping（.csrg/.tsrg） | 用户提供或下载 | 完整 diff |
| 降级基线 | NMSBinding1X（nms/v1X/） | 仓库 | 接口默认行为 |
| 构建约定 | NMSTools 插件 DSL | build.gradle.kts:71-94 + buildSrc/NMSBinding.kt | nmsBinding{} 语义 |

### 配置项

| 键 | 类型 | 说明 |
|-----|------|------|
| `baseVersion` | string | 参考的旧版本 tag（默认最新现有） |
| `newMcVersion` | string | 如 `1.21.12-R0.1-SNAPSHOT` |
| `newTag` | string | 如 `v1_21_R8` |

## 5. Skill 工作流设计

```markdown
### Step 1: 收集差异
用户给出（或 AI 拉取）新旧两版 spigot mapping diff；
提取与本接口相关的：biome/registry/tile/entity/structure/palette 类的成员变更。

### Step 2: 生成骨架
复制上一版目录改包名；生成三处构建注册 diff。

### Step 3: 逐方法标注
对 NMSBinding 每个方法：
- 反射目标未变 → 不动（置信度高）
- 方法签名变/移动包 → 给出新目标（高）
- 重载歧义 → 列候选（中）
- 语义级变更（如 biome 持久化格式改版） → 标记 BLOCKED 需人工（低）

### Step 4: 自检
编译新模块（gradlew :nms:<newTag>:compileJava）；
失败信息回填修改清单循环修复（最多 3 轮，仍失败输出残局清单）。

### Step 5: 交付
输出修改清单 + runServer 冒烟步骤；明确声明：
"所有中/低置信度项必须人工验证后才可提交"。
```

### Constraints

- Never 直接 git commit 产出（人工审核后由用户提交）
- Never 修改 baseVersion 及更早版本的绑定文件
- Always 保持与上一版的 diff 最小化（只改映射必需处）
- Always 低置信度项显式 BLOCKED，禁止静默选择一个候选

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|------|------|--------|
| Read/Glob/Grep | 读上一版源码与接口 | 必需 |
| Write/Edit | 生成新模块文件 | 必需 |
| Bash(gradlew compileJava) | 编译自检 | 必需 |
| Bash(下载 mapping) | 获取差异 | 可选（可要求用户提供） |

**建议 allowed-tools：** `Read Glob Grep Write Edit Bash(gradlew:*)`

## 7. 使用示例

### ✅ Do This

```
用户: "1.21.12 的 spigot mapping 在这，基于 v1_21_R7 移植 v1_21_R8"
AI: 生成骨架 + 清单：92 方法中 84 未变 / 6 高置信自动改 /
    1 中置信（placeStructures 重载）/ 1 BLOCKED（biome codec 变更）
    → 编译通过 → 交付冒烟步骤
```

### ❌ Not This

```
❌ AI 不看 mapping diff，直接按"猜测的新类名"重写绑定
❌ AI 把 BLOCKED 项自行决定并声称完成
```

## 8. 参考材料

- 绑定门面：`core/src/main/java/com/volmit/iris/core/nms/INMS.java:29-49,80-103`
- 接口：`core/src/main/java/com/volmit/iris/core/nms/INMSBinding.java`
- 最新参考实现：`nms/v1_21_R7/`
- 历史移植提交范式：`7befce108`（initial 1.21.11 support）及其后 4 个修复提交
- NMSTools：`build.gradle.kts:28`、`buildSrc/src/main/kotlin/NMSBinding.kt`
