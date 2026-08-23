# 性能优化 · 第 14 轮：每区块固定协程开销消除（引擎 Kotlin 热路径 Java 化）

**日期**：2026-08-23 · **分支**：`perf/optimization`
**环境**：JDK 25 · 32 逻辑处理器 · **39 → 42 场景**（ctx-fill、ctx-fill-cellwise、flag-raise，A/B 自证后并入 golden）

## 本轮主旨

上游 2025-10 的协程化重构（`be35e493 "use coroutines for mantle generation"`）把三类
**每区块固定成本**放进了生成关键路径，且全部藏在 benchmark 无法触及的 Kotlin 侧
（此前 benchmark 对这 4 个类只编译类型形状 stub）：

| 路径 | 旧实现的固定开销（每区块） |
|------|--------------------------|
| `ChunkContext` 构造（每个区块、S1 之前必经） | 1×`runBlocking` 事件循环 + 6×流级 `launch` + **1536×每格 `launch`**（6 流 × 256 格全部派发到全局 FJP） |
| `FlaggedChunk`（每个 MantleChunk） | **256 个 kotlinx `Mutex`**（`MantleFlag.MAX_ORDINAL=255`，每锁一整个无锁状态机对象）+ `copyFrom` 用协程并行抢锁 + 异常时锁泄漏（无 try/finally） |
| `MatterGenerator.generateMatter` | 每组件组 1×`runBlocking` + 每邻域区块×组件 1×`launch`；组件组内顺序依赖协程屏障隐式维持 |

## 改动清单（5 个生产文件 + benchmark）

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `util/context/ChunkedDataCache`（Kotlin→Java） | 每格一协程 → 行循环 `fillRow(j)`/单格 `fillCell(i,j)`（线程安全：行/格互斥不相交） | 每格恰一次 `stream.get((x+i)d,(z+j)d)`，格值是坐标纯函数 → 任意顺序/并行度产出相同网格；`get` 未缓存/空值回退语义逐字保留 |
| 2 | `util/context/ChunkContext`（Kotlin→Java） | `runBlocking`+1536 launch → 96 个行级 `MultiBurst` 任务（6 流 × 16 行），`FutureJoiner` 逐个 join 并**解包重抛任务异常**（对齐旧 runBlocking 的构造期失败） | 冷缓存并行度保持 ≥ 核数；新增 6 流构造器（生产 `IrisComplex` 构造器委托），null 流退化为不填充 |
| 3 | `util/mantle/FlaggedChunk`（Kotlin→Java） | 256 `Mutex` → **每块 1 个 `ReentrantLock`**；`raiseFlagSuspend` → `raiseFlag(flag, Runnable)`（双重检查契约不变）；`copyFrom` 改为单锁全持 + **try/finally 释放**（修复异常路径死锁泄漏） | 磁盘标志字节格式逐位不变；`raiseFlagUnchecked`/`flag`/`isFlagged` 原样；块内跨标志串行化仅影响本轮已改为块内串行的 MatterGenerator 路径（唯一 raiseFlagSuspend 调用方，grep 证明） |
| 4 | `engine/mantle/MatterGenerator`（Kotlin→Java） | 协程 launch 风暴 → 每邻域区块 1 个普通 executor 任务（组件块内按列表顺序执行）；组件组屏障 = 显式 join；单核路径 = 就地执行；异常经 `FutureJoiner` 解包重抛 | PLANNED 快路径、组件组间屏障顺序、realRadius 的 PLANNED 扫描全部逐字保留；BurstExecutor 会吞异常，故不采用其 complete() |
| 5 | `engine/IrisEngineMantle.getComponents` | `aquire` → `peek` 先行（每区块经 getRadius/getRealRadius 调 3 次；R13 模式） | 缓存值恒非 null；命中/未命中语义不变 |
| 6 | `util/parallel/FutureJoiner`（新增） | join 一批已提交任务并解包重抛首个失败 | 替代会吞 `ExecutionException` 的 `BurstExecutor.complete()` |

Benchmark 侧：删除 4 个 Kotlin stub（benchmark 从此编译**真实生产实现**）；
deposit/carve/perfection 场景改用"网格流"构造生产 `ChunkContext`（网格流对任意坐标返回
`grid[(z&15)*16+(x&15)]`，与旧 stub prefill 逐值等价）；新增 3 场景（下节）。

## 新场景与 A/B 方法论

| 场景 | 配置 | 证明 |
|------|------|------|
| `ctx-fill` | 生产 `ChunkContext`：3 个 CNG 双精度流（高度流 3 倍频程 ~0.5-1µs/采样）+ 3 个噪声选择对象流，每 op 一个全新原点构造 + 6×256 读回 | 行任务填充（新路径） |
| `ctx-fill-cellwise` | **旧编排的忠实复刻**：benchmark 侧 `OldContextFill` 从 Java 驱动**真实 kotlinx-coroutines**（runBlocking + 每流 launch + 每格 launch，真实 MultiBurst dispatcher），每格工作走生产 `fillCell` | 协程编排（旧路径）；**digest 与 ctx-fill 逐迭代一致** |
| `flag-raise` | 每 op 新建 `MantleChunk` + 8 个不同 flag 的计数任务 + 已置位跳过路径 | 恰好一次语义（每 op 计数=8）+ 标志位读回 |

`ctx-fill` vs `ctx-fill-cellwise` 是本轮最强的正确性+性能双证据：同一 JVM、同一流、
同一原点序列下，**9/9 个迭代的 digest 逐一相同**——旧协程编排与新行任务编排产出
位级相同的网格。

## 结果

**隔离跑（3 预热 + 9 迭代，后 5 次中位数；`round14-iso-*.csv`）：**

| 场景 | ns/op | 主线程 B/op |
|------|------:|------:|
| ctx-fill-cellwise（旧协程编排） | 405,574.9 | 6,963 |
| ctx-fill（新行任务） | **95,929.3（4.23×）** | 10,533 |
| flag-raise（新单锁路径） | 67.7 | 320 |

- **4.23×**：每区块上下文预填充的固定成本。注意主线程 B/op 反升是测量假象——旧路径
  的 1536 个协程对象分配在 **FJP 工作线程**上（ThreadMXBean 只统计主线程）；
  每协程约 300B（StandaloneCoroutine+context+dispatched task）≈ **450KB/区块的
  分配在 worker 侧消失**，新路径总分配 ≈ 96 任务 × ~100B ≈ 10KB。
- flag-raise 的 320 B/op 即新 `MantleChunk` 全部构造 footprint；旧生产为每块
  额外 256 个 Mutex（每个 ~50-100B，**+13~25KB/块**，仅能以算术陈述——旧实现
  无法离线运行，Kotlin stub 时代无此对象）。
- `MatterGenerator` 的 launch→任务消除与 ctx-fill 同机制（每邻域区块 1 任务代替
  每区块×组件 1 launch + 每组 1 runBlocking）；其行为正确性由 39 个金样本
  （含 cave-carve/object-place 等经 Mantle 的场景）位级一致背书。
- perfection-modify 隔离跑 383.8µs ≈ round13 的 382.5µs（持平）；全套跑中该场景
  曾显示 -20%，与 terrain-col-fill/biome-height 等**本轮未触碰路径**的同步变慢一起，
  再次验证为 R9 记录的全套 JIT 画像污染（新增两个重场景改变了编译画像），
  非真实回归。

## 验证

- 全套 3+5 两轮（改动前 build 与最终 build）：**39 个旧金样本 digest 全部位级一致**。
- `ctx-fill` 与 `ctx-fill-cellwise` digest 逐迭代 18/18 一致。
- golden 更新为 **42 场景**；`verify.py` 全 OK。

## 结论与后续

- 每区块生成管线现在的固定成本构成：ChunkContext 预填充（~96µs→可再压缩空间小）、
  96 个行任务、Mantle 邻域扫描。协程已从生成热路径完全移除（仅存于
  PregenCacheImpl/脚本环境等非每区块路径）。
- Kotlin→Java 化同时解锁了这些类的离线可测性（stub 全删），后续轮次可直接覆盖
  Mantle IO/驱逐与 Engine 在线路径中的此类结构。
- 建议下一动作：服务器端预生成吞吐实测（本轮应显著：每区块省 ~310µs 编排 +
  ~450KB 分配，8 并发预生成下 FJP 争用缓解会放大收益）。
