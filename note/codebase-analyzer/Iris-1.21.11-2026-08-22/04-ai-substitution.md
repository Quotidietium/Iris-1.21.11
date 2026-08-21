# Iris AI 工作流替代方案报告

> 分析时间：2026-08-22 · 评估维度（各 1-5 分，满分 30）：确定性 / 输入结构化 / 安全风险 / 领域复杂度 / 上下文需求 / 重复性
> 等级：🤖 完全 AI 化（24-30）· 🧑‍💻 AI 辅助（15-23）· 👤 人工主导（6-14）

## 1. 模块级评估总表

| # | 模块 | 确定性 | 结构化 | 安全 | 领域 | 上下文 | 重复性 | 总分 | 等级 |
|---|------|-------|--------|------|------|--------|--------|------|------|
| 1 | `engine/object` 数据模型文档+Schema 生成（140 类） | 5 | 5 | 5 | 4 | 4 | 5 | **28** | 🤖 完全 AI 化 |
| 2 | 崩溃/调试产物分析（debug/*、dump/*、Sentry 队列） | 4 | 4 | 5 | 4 | 3 | 5 | **25** | 🤖 完全 AI 化 |
| 3 | `util/` 纯逻辑类单元测试生成（noise/hunk/math/collection） | 4 | 5 | 5 | 3 | 4 | 4 | **25** | 🤖 完全 AI 化 |
| 4 | NMS 新版本移植（`nms/v1_21_R8` 之类的新绑定） | 4 | 4 | 3 | 2 | 2 | 5 | **20** | 🧑‍💻 AI 辅助 |
| 5 | 数据包 JSON 编写辅助（Studio 用户侧 pack 创作指导） | 3 | 4 | 5 | 4 | 3 | 4 | **23** | 🧑‍💻 AI 辅助 |
| 6 | Decree 命令/handler 扩展（新命令样板） | 4 | 4 | 4 | 3 | 3 | 3 | **21** | 🧑‍💻 AI 辅助 |
| 7 | 变更日志/版本整理（`v+` 提交 → release note） | 4 | 3 | 5 | 4 | 3 | 4 | **23** | 🧑‍💻 AI 辅助 |
| 8 | 引擎核心算法（IrisComplex 流图/noise/mode 编排） | 2 | 2 | 1 | 1 | 1 | 2 | **9** | 👤 人工主导 |
| 9 | Mantle 并发持久化（TectonicPlate/IOWorker/HyperLock） | 2 | 2 | 1 | 1 | 1 | 2 | **9** | 👤 人工主导 |
| 10 | Safeguard/授权（Kotlin 防护） | 3 | 3 | 1 | 3 | 2 | 1 | **13** | 👤 人工主导 |

## 2. 模块评估卡片

### 2.1 数据模型文档 + JSON Schema 生成 — 🤖 完全 AI 化（28/30）🥇

**当前状态**：140 个 `engine/object/Iris*.java` POJO 是数据包格式的唯一权威定义，但字段语义靠字段名与零散注释传递；官方文档在 docs.volmit.com（独立维护，易漂移）。
**AI 替代方式**：读取类源码（Lombok @Data + Gson 序列化痕迹 + `@DontObfuscate` 等注解）→ 生成每类 Markdown 字段表 + JSON Schema（可进 `openVSCode` 的 schema 目录，StudioSVC.java:359 已有 vscode 集成点）。
**实施难度**：低——一次性脚本 + 每次新增模型类时增量更新。
**预期收益**：文档从"漂移态"变"生成态"；Schema 直接提升包作者体验（编辑器自动补全/校验）。
**风险**：注解驱动的序列化例外（`IrisData implements ExclusionStrategy`）需人工确认纳入。
**对应 Blueprint**：[blueprints/01-pack-doc-generator.md](blueprints/01-pack-doc-generator.md)

### 2.2 调试产物分析 — 🤖 完全 AI 化（25/30）🥇

**当前状态**：`Iris.reportError` 把异常落盘到 `debug/caught-exceptions/*.txt`（Iris.java:371-394），`Iris.dump()` 产生全线程栈 `dump/td-*.txt`（:396-421），区块错误 `debug/chunk-errors/*`——全部是给"人"看的纯文本，无任何聚合分析。
**AI 替代方式**：读取这些产物 → 归类堆栈（同一异常签名聚类）→ 关联源码行 → 输出"最高频 N 个故障 + 根因假设 + 涉及模块"。
**实施难度**：低；**收益**：高（零测试项目里生产报错是唯一质量信号）。
**对应 Blueprint**：[blueprints/02-crash-log-analyzer.md](blueprints/02-crash-log-analyzer.md)

### 2.3 纯逻辑单元测试生成 — 🤖 完全 AI 化（25/30）🥇

**当前状态**：0 测试。`util/noise`、`util/math`、`util/collection`(KList/KMap)、`util/hunk`（视图/插值）、`util/stream`（ProceduralStream 算子）、`util/interpolation` 均无 Bukkit 依赖，是最可测的资产。
**AI 替代方式**：对每个纯函数类生成 JUnit5 参数化测试（噪声确定性：同种子同输出；流算子：zoom/cache2D 语义）。
**实施难度**：低-中（需要先建 gradle test 依赖，当前 build.gradle.kts 无 test 配置）。
**收益**：13 万行代码的回归底线；重构（如减少锁竞争的近期提交 `01a2999e0`）有安全网。
**注意**：AI 生成后必须人工抽检断言语义，防止"生成即通过"的假阳性测试——标 🧑‍💻 审查点。
**对应 Blueprint**：[blueprints/04-test-scaffold-generator.md](blueprints/04-test-scaffold-generator.md)

### 2.4 NMS 新版本移植 — 🧑‍💻 AI 辅助（20/30）🥈

**当前状态**：每次 MC 新版本 = 复制上一版 `nms/v1_21_R7` → 逐个方法修映射（近期 `7befce108 initial 1.21.11 support` + 4 个后续修复提交即此流程）。33 个 Java 文件 × 11 版本目录，靠人工 diff Mojang 映射。
**AI 替代方式**：输入新旧版本 NMS 差异（spigot member mapping diff）+ 上一版 Binding 源码 → AI 生成新 Binding 草稿与修改清单 → 人工验证编译与运行时行为（`runServer-<new>` 一键验证）。
**实施难度**：中——需要喂给 AI 正确的映射差异，这是准备工作而非生成本身。
**收益**：版本支持周期从"数天修补"压缩到"数小时草稿"。
**人工介入点**：混淆映射正确性、ByteBuddy 注入逻辑、生物群系注册副作用。
**对应 Blueprint**：[blueprints/03-nms-porting-assistant.md](blueprints/03-nms-porting-assistant.md)

### 2.5 数据包创作指导 — 🧑‍💻 AI 辅助（23/30）🥈

**当前状态**：包作者学 JSON 格式靠 docs.volmit.com + 示例包；字段误用只能运行时发现（引擎 `Iris.warn`）。
**AI 替代方式**：基于 2.1 的 Schema/文档作为 context，让 AI 回答"怎么配置 XXX"并生成片段；配合 `StudioSVC.downloadSearch` 的社区包语料做 RAG。
**收益**：降低生态门槛 = 更多付费包 = 商业价值。

### 2.6 其余模块速评

- **Decree 命令扩展**：框架高度模式化（`CommandStudio.java` 等 14 个类同构），AI 生成新命令样板可靠；但参数 handler 需要理解 Decree 上下文注入，生成后人工过一遍注册链。
- **Release note**：`git log` + `v+` 标记是结构化的，AI 整理成用户可读 changelog 收益直接；注意本项目 changelog 面向 Spigot 买家，需人工润色商业措辞。

## 3. 明确不建议 AI 化的部分（及原因）

| 模块 | 原因 |
|------|------|
| `IrisComplex` 流图调整 / 噪声算法 | 输出即玩家世界本身，错误代价不可逆（坏档）；调参是审美+地质直觉的混合 |
| `Mantle`/`TectonicPlate` 并发与 IO | 数据竞争 bug 表现为随机世界损坏，极难复现；近期提交（`01a2999e0 decrease locking`）显示团队在此谨慎迭代 |
| Safeguard / 授权逻辑 | 涉及商业保护，AI 参与编写授权代码有利益冲突风险 |
| Studio 产品决策（GUI/交互） | 需求驱动，非结构化 |

## 4. ROI 优先级矩阵

```
                    高收益
                      │
   🥈 NMS移植辅助     │   🥇 数据包文档+Schema生成
   🥈 数据包创作指导  │   🥇 调试产物分析
   🥈 release note   │   🥇 纯逻辑测试生成
低难度 ───────────────┼───────────────── 高难度
                      │
        （暂无）      │   引擎算法/Mantle（人工主导，不做）
                    低收益
```

## 5. AI 改造路线图

### Phase 1 — Quick Wins（本月，全部可由 Blueprint 直接落地）
1. **pack-doc-generator**：140 类 → 字段文档 + JSON Schema（预期：文档成本 −90%，Schema 反哺 Studio 编辑体验）
2. **crash-log-analyzer**：debug/dump 产物 → 聚合诊断报告（预期：排障定位时间 −60%）
3. **test-scaffold-generator**：util 纯逻辑类 JUnit 脚手架（预期：首个回归安全网，覆盖率 0→~15%）

### Phase 2 — Strategic（本季度）
4. **nms-porting-assistant**：下一 MC 版本（1.21.12+）支持时试点 AI 草稿 + 人工验证流
5. 数据包创作指导 RAG（依赖 Phase 1 的 Schema 产物）

### Phase 3 — Transformative（本年度）
6. 引入最小 CI（编译矩阵 + Phase 1 测试）后，把 AI 代码审查接入 PR 流（dev→master）

## 6. 函数级下钻（Top 函数契约示例）

### `IrisData.get(File)` — 完全 AI 化候选（缓存门面）

```java
// IrisData.java:97-99
public static IrisData get(File dataFolder)
  前置：dataFolder 为数据包根目录（可不存在，懒创建 loader）
  后置：dataLoaders.computeIfAbsent 命中或新建；同一路径全局单例
  不变式：一个 File ↔ 一个 IrisData 实例
  错误场景：目录无权限 → loader load 时 IOException 由调用方报告
```

### `INMS.bind()` — AI 辅助候选（版本绑定）

```java
// INMS.java:80-103
private static INMSBinding bind()
  前置：Bukkit 已完成服务器初始化（Bukkit.getServer().getClass() 可用）
  后置：返回版本匹配 binding 或 NMSBinding1X 兜底；打印绑定日志
  错误场景：ClassNotFoundException（未来版本）→ 降级 1X + 两条 warn
  AI 用途：新版本 REVISION 表条目（INMS.java:33-49）与对应 nms 模块的映射生成
```

### `EngineMode.generate(...)` — 人工主导（管线编排核心）

```java
// EngineMode.java:71-78
default void generate(int x, int z, Hunk<BlockData> blocks, Hunk<Biome> biomes, boolean multicore)
  前置：引擎已 setupEngine；当前线程已绑定 IrisContext
  后置：blocks/biomes 全量填充；ChunkContext 进入本线程 ThreadLocal
  并发点：Stage 间串行（数据依赖），Stage 内 burst 并行（MultiBurst）
  修改此处错误代价：所有生成的世界 → 不可 AI 化的根因
```

> 各模块完整接口契约、依赖清单与 Skill 工作流见 `blueprints/`。
