# 性能优化第 26 轮：内存有界性（板块驻留硬上限 + 三处隐藏泄漏修复）

日期：2026-08-27 · 分支：`perf/optimization` · 主题：**"不管生成多大范围，内存占用保持在低位（≤ JVM 分配的一半）"**——来自实机部署反馈（内存泄漏/占用随生成范围异常增长）。

## 结论先行

| 指标（同一持续热预生成扫掠，limit=6 板块，2560×32 区块 ≈ 84 万列·块面积） | R26 前（`-Diris.mantle.hardcap=false`） | R26 后（默认） |
|---|---|---|
| 生成期间驻留板块峰值 | **126 个，随面积线性增长**（无上界） | **恒定 6（=limit），80 板块面积仍恒定** |
| 生成期间驻留堆（GC 后） | **243 MB，线性增长** | **恒定 13.7 MB** |
| 生成中途 trim 卸载尝试 | 10/10 个检查点 `queued=0`（4s 闲置底线在不停顿的生成下永不满足） | 每检查点强制排出 12 个超限最旧板块 |
| settle（≥4.5s 停顿后）驻留 | 0 | 0（零钉住 = 引用计数泄漏端到端审计通过） |
| 结束堆 vs 基线 | +1.8 MB（IOWorker 通道 LRU，正常） | +1.8 MB（相同） |

- **golden 49/49 位级一致**（`round26-base.csv` → `round26-post.csv`）：地形输出零变化（红线）。
- 热点场景 B/op 全部 1.000–1.001×（零分配回归）；时间读数在主机漂移带内不声明（守则）。

## 审计发现（5 项缺陷，3 处为隐藏生产 bug）

### 1. 板块驻留是软限，持续生成下无硬界（机理缺陷，本轮主修复）

`Mantle.trim(baseIdle, limit)` 超限时只是把闲置判定从 30s 压缩到最低 **4000ms**——这是**时间窗式软界**：每次板块访问都刷新 `lastUse`，持续快速生成（预生成/多引擎并行）中任何板块都不会"闲置 4 秒"，trim 永远标不出可卸载板块（实测 10/10 检查点 `queued=0`）。驻留量 = f(吞吐×4s)，无硬保证。扫掠暂停 ≥4.5s 后才能一次性排空——用户看到的"内存随生成范围一直涨"即此。

**修复**：trim 在 `loadedRegions.size() > tectonicLimit` 时，除闲置标记外，**把 lastUse 最旧的 `size-limit` 个板块强制标记进卸载队列（`forcedUnload` 集，绕过闲置年龄检查）**；`unloadTectonicPlate` 对 forced 条目只保留 `inUse()`（在途引用）一道闸——活跃工作集（writer 邻域、carve 钉住）不受影响，数据从盘重载逐位还原，仅驻留变化。`use()` 清除 forced 标记（活跃板块下轮 trim 重新评估）。逃生阀：`-Diris.mantle.hardcap=false`。

### 2. 引用计数泄漏会永久钉住整个板块（4 条泄漏路径全封）

`MantleChunk.use()` 的信号量 pin 若不释放，`inUse()` 永真 → `unloadTectonicPlate` 跳过并刷新 lastUse → **该板块整个引擎生命周期驻留**。一处泄漏 = 512×512 区块的 matter 全部滞留。修复 4 条路径：

| 路径 | 缺陷 | 修复 |
|---|---|---|
| `IrisCarveModifier.onModify` | `mc.use()`（:63）→ `mc.release()`（:213）跨 150 行**无 try/finally**，中途任何异常（热重载竞态/OOM）永久钉住 | try/finally 包裹（正文缩进保持原样，两行 diff） |
| `IrisCustomModifier.onModify` | 同上（burst 队列/完成抛出时泄漏） | try/finally |
| `MantleWriter` 构造器 | 邻域逐区块 `c.use()` 期间任何失败（引擎关闭竞态抛 IllegalStateException）→ 构造器异常 → try-with-resources 的 close() 永不执行 → 已获取的全部 pin 泄漏 | 构造器 catch-释放-重抛 |
| `MantleWriter` 单线程路径（**R24 引入的并发回归，实机泄漏的可能根因**） | `Mantle.getChunks` 的逐 region 回调**在 ioBurst 线程池异步执行**（即使单线程 writer，parallelism=4 是板块加载扇出而非 writer 模式）——跨 ≥2 板块的 writer 从多线程并发 `put` 进非线程安全的 `Long2ObjectOpenHashMap`：开地址表损坏 → `close()` 迭代器 `IndexOutOfBoundsException` 崩溃，或**静默丢条目——丢失条目里的 pin 永久泄漏**（本轮 harness 实测抓到：settle 后 1 板块因丢条目钉住）。生产 multicore=true 走 KMap(CHM) 安全，golden 场景均为单 region 或碰巧串行，故未暴露 | 手递同步锁：回调内 `synchronized(handoff){ map.put(key, c.use()) }`——原生 put 零装箱保留，单板块 writer 无争用 |

### 3. `CavernMatter.writeNode` 对 null customBiome NPE → 板块永不能写盘（隐藏生产 bug）

数据包 JSON 写 `"customBiome": null` 时（或任何第三方构造 null），序列化 `writeUTF(null)` NPE 使**整个 TectonicPlate 写盘失败**；卸载路径的异常被 BurstExecutor 吞掉（R14 已知它吞异常）→ 板块 close 了却仍驻留内存。修复：null 归一为 `""`（磁盘上"无自定义群系"的既有编码，读取方 `isEmpty()` 语义一致，磁盘格式零变化）。

### 4. 持续堆压 backstop（保证机制的最后一层）

`IrisEngineSVC` 卸载循环新增：连续 2 个 tick（2s 间隔，过滤 GC 噪声）堆使用率 > `performance.mantleMemoryBackstopPercent`（默认 45%，0 关闭）时，把 trim 目标压到 1——强制排空一切非钉住板块。层级保证算术：默认板块上限 = 堆MB/512（10G 堆→20 板块）× 现实板块体积（实测 ~2MB）≈ 40MB（0.4%）；即使病态 256MB 板块也只到堆的 50%，而 45% backstop 在此之前已介入。**目标"≤ JVM 一半"由"计数硬限 + 堆压 backstop"双层机制保证。**

### 5. 每线程 ChunkContext 滞留（小项）

`IrisContext.chunkContext` 生成结束不清空 → 每个池线程滞留最后一个区块的预填充数组（并经 complex 钉住引擎图）。`EngineMode.generate` finally 中清空；`ContextInjectingStream` 对 null 上下文本就回退源流（线程从未生成过区块时同语义），行为零变化。

## 验证设施：`bench.VerifyMemoryBound`（常驻验证器）

- 真实 `Mantle` + `MantleWriter`（radius 1、单线程模式——恰好覆盖 R24 回归路径），逐区块写 MatterCavern 球（生产 carve 形态，无 Bukkit 依赖），EngineSVC 节奏（每 128 列 trim+unload）驱动。
- 断言：(a) 硬上限——每个检查点 `loaded ≤ limit+2`；(b) **settle 零钉住**——停顿 4.5s 后 trim(0)+unload 必须清零（这同时是全部 pin 泄漏路径的端到端审计：任何 use()/release() 不配对都会表现为残留板块并指认具体 chunk 坐标）；(c) 堆平台——结束堆与基线差 ≤64MB。
- `-Diris.mantle.hardcap=false` 臂复现旧行为（本报告 A/B 数据即来自该臂）。
- 排障记录：4s 闲置底线使快速扫掠（<4s）内 trim 永不标记——审计初期误判为"泄漏"，加 checkpoint 队列诊断 + 板块年龄诊断后分离出"时间窗软界"与"真 pin 泄漏"两个独立问题。诊断输出保留在 harness 中。

## 兼容性说明

- 地形/存档格式零变化：板块卸载-重载往返数据逐位还原（golden 49/49 + 磁盘字节格式未动）。
- 卸载时机变化仅影响内存驻留与 IO 节奏：超限时最旧板块更早写盘，下次触碰从盘重载（数值不变）。持续生成期间的额外 IO 代价 = 每板块一次提前写盘，被"驻留 18× 下降"抵消。
- 新设置：`performance.mantleMemoryBackstopPercent`（默认 45，0 关闭）；系统属性 `-Diris.mantle.hardcap=false` 可整体关闭硬上限回到纯闲置策略。
- EngineSVC 卸载循环的行为变化仅在堆压持续超阈值时发生。

## 过程事故与教训

1. **harness 首跑即抓到两个隐藏生产 bug**（CavernMatter NPE、R24 并发回归）——都属"异常被吞/概率性损坏"类，单测式断言（settle→0）比 digest 更适合抓这类缺陷；digest 只证明输出一致，不证明无泄漏。
2. **时间窗软界的鉴别方法**：加"trim 后 toUnload 队列大小"与"板块 lastUse 年龄"两个诊断量后，"标记阶段从未触发"（queued=0）与"卸载阶段失败"（closed=true 但驻留）立刻可分。
3. fastutil 开地址表并发损坏的症状是迭代器 `IndexOutOfBoundsException` 或**静默丢条目**——后者更危险（本例中丢失的是持有 pin 的 chunk 引用）。任何"回调可能在别的线程执行"的 API（此处 `getChunks` 的 CompletableFuture 链）都值得复查消费方的容器线程安全性，即使调用方名字叫"单线程路径"。

## 改动清单（5 commits）

| commit | 内容 |
|---|---|
| `01e9d9890` | pin 泄漏三处 try/finally + writer 构造器安全释放 |
| `290c53a47` | Mantle 硬上限（forcedUnload）+ EngineSVC 堆压 backstop + ctx 清理 + 设置项 |
| `CavernMatter/MantleWriter handoff` | NPE 加固 + R24 并发回归修复（见 `git log`） |
| bench commit | VerifyMemoryBound + 双臂原始输出 |

原始数据：`benchmark/results/round26-base.csv`（修复前全量 49 场景）、`round26-post.csv`（修复后全量 49 场景）、VerifyMemoryBound 控制台记录见本文件表格。
