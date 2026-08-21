# 性能优化 · 第 3 轮：并发 / 锁 / 共享缓存

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`94809ea86`
**环境**：JDK 25 · 32 逻辑处理器 · 基准新增 8 线程并行场景（每线程独立种子，摘要按线程序合并，确定性）

## 改动清单

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `util/parallel/MultiBurst` | `getService()` 每次调用的 `AtomicLong` 读改写（跨核缓存行争用，每 Stage burst 数百次）→ 普通 volatile 写；该时间戳无任何热路径读者 | 纯记账字段，读写语义不变 |
| 2 | `util/parallel/BurstExecutor`、`MultiBurst.sync` | 删除 `queue(List)`/`queue(Runnable[])`/`sync(List)` 中每次的 KList 拷贝 | 直接迭代同一列表，提交/执行顺序不变 |
| 3 | `engine/data/cache/AtomicCache` | `aquire()` 快路径从两次 volatile 读降为一次（局部变量缓存） | 同一双检锁语义 |
| 4 | `IrisComplex.implode` | `setInferredType` 仅在值变化时写（共享子群系被多线程同时 implode，同值普通写仍会弹跳缓存行） | 值相同→跳过写，最终状态与可见值完全一致 |
| 5 | `util/cache/WorldCache2D` | CLHM → **Caffeine**（项目既有依赖） | 缓存是纯记忆化层：任何驱逐策略产生相同值。CLHM/Caffeine 两次构建的 20 个场景 digest **全部一致**自证；`getSize()` 变为估计值（仅指标展示用） |

## 并行基准（新增场景，8 线程）

| 场景 | 含义 | 结果 |
|------|------|------|
| par-cng-noise2d | 共享 CNG 定参采样（8 线程） | ~0.23 µs/op 墙钟 ≈ 单线程 205 ns → **约 7× 聚合扩展**。证明第 1 轮移除静态计数器 + varargs 后 CNG 完全无共享可变状态 |
| par-worldcache2d（散点） | 每 op 随机区块窗口（最坏情况：全 miss + 驱逐churn） | CLHM 59.8 → Caffeine 53.5 µs/op（1.12x） |
| par-worldcache2d-raster（生产模式） | 每窗口连续光栅 1024 op（模拟并行区块生成共享 cache2D 流） | CLHM 45.7 → **Caffeine 29.7 µs/op（1.54x）**；聚合扩展从 2.3× 提升到 3.6×（8 线程） |

> 注：并行场景的 `B/op` 列仅统计主线程分配（ThreadMXBean 限制），无参考意义；耗时指标为墙钟。并行场景运行间方差约 ±15%（共享机器），A/B 为背靠背运行。

## 验证

- `verify.sh`：**20/20 场景 digest 位级一致**（含 3 个并行场景）。
- CLHM 与 Caffeine 两套构建产出相同 digest（缓存透明性的直接证据）。
- 全量编译校验通过。

## 三轮累计（vs round0 基线）

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配 | 单线程场景合计 B/op **18818 → ~4700（-75%）**；CNG/IRare/3D 插值等主路径 **0 B/op** |
| implode 子群系选择 | **11.4×**（2585 → 227 ns，0 分配） |
| 共享缓存并行扩展（raster, 8 线程） | 2.3× → 3.6×（聚合吞吐 +56%） |
| 单线程耗时 | 噪声内核 1.0-1.2×（主体收益在分配与多线程扩展） |
