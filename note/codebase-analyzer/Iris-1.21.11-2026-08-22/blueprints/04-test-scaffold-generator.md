# Skill Blueprint: test-scaffold-generator（纯逻辑单元测试脚手架）

> 自动生成自 codebase-analyzer · 分析时间：2026-08-22
> 源模块：`core/src/main/java/com/volmit/iris/util/`（35 个包，重点 noise/hunk/stream/math/collection/interpolation）

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| 推荐 Skill 名称 | `iris-test-scaffold` |
| 用途 | 为 Iris 无 Bukkit 依赖的纯逻辑类生成 JUnit5 测试脚手架，把项目从 0 测试状态建立回归安全网 |
| AI 替代等级 | 🤖 完全 AI 化（生成）/ 🧑‍💻（断言语义人工抽检） |
| 实施优先级 | 🥇 Quick Win |
| 目标包 | util/noise, util/stream, util/hunk, util/math, util/collection, util/interpolation, util/cache |
| 前置缺口 | `core/build.gradle.kts` 当前无 test sourceSet/依赖配置，需先补 |

## 2. 触发场景与关键词

- "给 util/noise 写测试"
- "建立测试基线 / 测试脚手架"
- "重构 MultiBurst 前先补安全网"
- "这个类改了，跑一下相关测试"

**推荐 description 触发词：**

```yaml
description: >-
  Generate JUnit5 test scaffolds for Iris's Bukkit-free utility classes
  (noise, streams, hunks, math, collections). Triggered by: "写测试",
  "单元测试", "test scaffold", "回归安全网".
```

## 3. 输入输出契约

### 优先目标类（可测性排序）

| 优先级 | 类/包 | 可测性质 | 测试模板 | 证据 |
|--------|------|---------|---------|------|
| P0 | `util/noise/*`（CNG/Perlin/Simplex/WhiteNoise） | 种子确定性 | 同 seed 两次采样全等；不同 seed 差异；输出∈[-1,1] | util/noise/ |
| P0 | `util/stream/ProceduralStream` 算子 | 纯函数链 | zoom/cache2D/convert/selectRarity 语义各一例 | IrisComplex.java:120-225 的用法即集成样例 |
| P1 | `util/hunk/*`（视图/apply） | 数组变换 | view 切片读写映射正确 | util/hunk/ |
| P1 | `util/math/*`（M/RNG/Position2） | 纯函数 | RNG.nextParallelRNG 分裂独立性 | IrisEngine.java:113 种子分裂用法 |
| P2 | `util/collection`（KList/KMap/KSet） | 容器语义 | 与 JDK 等价行为对照 | util/collection/ |
| P2 | `util/interpolation/*` | 数值 | 端点精确、单调性 | util/interpolation/ |
| 禁测 | `util/mantle`、`util/parallel`（MultiBurst） | 并发+IO | 需集成环境，本 Skill 不碰 | — |

### 测试约定（生成物契约）

```java
// 命名：<ClassName>Test；包镜像 com.volmit.iris.util.noise
// 必备三类断言：
//  1. 确定性：new RNG(seed) 两次产生相同序列
//  2. 边界：输入域端点（0/负数/Integer.MAX_VALUE 坐标）
//  3. 不变式：如 ProceduralStream.cache2D 命中后不重算（用计数 lambda）
// 每个测试方法必须只测一个行为；@DisplayName 中文描述意图
```

### 输出物

| 产物 | 路径 |
|------|------|
| 测试文件 | `core/src/test/java/com/volmit/iris/util/**/*Test.java` |
| 构建补丁 | `core/build.gradle.kts` 增 `testImplementation("org.junit.jupiter:junit-jupiter:5.x")` + `useJUnitPlatform()` |
| 覆盖清单 | `note/report/test-scaffold-coverage.md`（已测/待测/禁测分类） |

## 4. 依赖清单

| 类型 | 内容 | 来源 | AI 所需 Context |
|------|------|------|----------------|
| 被测源码 | util 各包 | 仓库 | 每类完整源码 |
| 测试框架 | JUnit5 | 新增依赖 | 版本与 gradle 写法 |
| 反面约束 | Bukkit import 的类跳过 | Grep `import org.bukkit` | 排除规则 |
| 现有惯例 | 无（0 测试） | — | 本 Blueprint 即约定起点 |

## 5. Skill 工作流设计

```markdown
### Step 1: 选类
用户给包名或类名；否则按 P0→P2 顺序批量。Grep 排除含
org.bukkit/io.papermc 依赖的类，输出"跳过清单+原因"。

### Step 2: 静默依赖检查
被测类若引用 Iris.instance / Bukkit 静态（如 Iris.debug），
标记为"需 mock 或跳过"，不强行生成必红测试。

### Step 3: 生成测试
按 §3 契约生成；断言只写"从代码语义可推导"的期望，
不确定的期望值用"两次调用一致性"替代具体数值（防脆弱断言）。

### Step 4: 运行
gradlew :core:test --tests "<target>"；失败分诊：
- 编译失败 → 修 scaffold
- 断言失败 → 大概率是发现了真实 bug：单列报告，不改被测代码

### Step 5: 报告
更新覆盖清单；断言失败项输出到 note/report/ 待人工判定。
```

### Constraints

- Never 修改被测源码（即使测试揭示 bug——只报告）
- Never 为并发类（MultiBurst/Mantle/IOWorker）生成确定性断言测试
- Always 断言失败优先解释为"疑似真 bug"而非"改断言让它绿"
- Always 保持单文件 <300 行，超限拆分

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|------|------|--------|
| Read/Glob/Grep | 选类与依赖检查 | 必需 |
| Write/Edit | 生成测试与构建补丁 | 必需 |
| Bash(gradlew test) | 运行验证 | 必需 |

**建议 allowed-tools：** `Read Glob Grep Write Edit Bash(gradlew test:*)`

## 7. 使用示例

### ✅ Do This

```
用户: "给 util/noise 和 util/stream 建测试"
AI: 28 类中 22 无 Bukkit 依赖 → 生成 22 个 Test 文件 →
    运行：21 绿 1 红（WhiteNoise 边界疑似 off-by-one）→
    报告 red 项源码定位，未改被测代码
```

### ❌ Not This

```
❌ 为了全绿把可疑断言删掉或 @Disabled
❌ 给 Mantle 写"确定性的"并发测试（必然 flaky）
```

## 8. 参考材料

- 被测目录：`core/src/main/java/com/volmit/iris/util/`
- 流算子真实用法（测试场景灵感）：`core/src/main/java/com/volmit/iris/engine/IrisComplex.java:120-225`
- 种子分裂惯例：`core/src/main/java/com/volmit/iris/engine/IrisEngine.java:113`、`IrisTerrainNormalActuator.java:48`
- 构建脚本：`core/build.gradle.kts`、根 `build.gradle.kts`
