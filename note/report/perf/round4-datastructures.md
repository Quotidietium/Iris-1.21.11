# 性能优化 · 第 4 轮：数据结构 / 装箱消除（cache2D 层重写）

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`5ba49090a`
**环境**：JDK 25 · 32 逻辑处理器 · 基准新增 1 个热路径场景（`worldcache2d-hit`，共 21 场景）

## 优化点

`cache2D` 流（CachedStream2D，群系/高度等所有区块级缓存流的底座）在
第 3 轮 Caffeine 替换后仍有三层数据结构税：

1. **Long 装箱**：每次区块切换 `Long.valueOf`（16 B），此前用单槽 volatile 备忘缓解，但共享单槽本身在多线程间弹跳缓存行；
2. **Caffeine 读路径记账**：每次 `get` 都要向共享 read buffer 记录（LRU 频率统计），单线程无感（18.8 ns/get），但 8 线程高吞吐下 buffer 争用 + 同步 drain 使生产 raster 模式聚合吞吐**不升反降**（实测 8 线程墙钟 36.2 µs/op，比单线程热路径慢 ~30×/get）；
3. **每格 Entry 对象 + 坐标装箱**：ChunkCache2D 每格惰性分配 Entry（16 B），且 `Function2<Integer,Integer,T>` 每次未命中把两个世界坐标装箱（32 B + 拆箱）。

## 改动清单

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `util/cache/WorldCache2D` | Caffeine<Long,ChunkCache2D> → **32 条带开地址表**：原始 long 键（splitmix64 分散），槽存**不可变 Node(key, chunk)**，读路径单次 acquire-load/步（无锁无记账），插入按条带 synchronized，负载 75% 时整条带清空 | 纯记忆化层：任何驱逐策略产生相同值。A/B 两套实现的 21 场景 digest **位级一致**自证（见下）。getSize 保持近似值（仅指标展示，Caffeine estimatedSize 原本也是近似） |
| 2 | `util/cache/ChunkCache2D` | 每格 Entry 对象（volatile t + synchronized DCL）→ **扁平 Object[256] 槽**：CAS `null→COMPUTING` 哨兵，赢家计算后 release 写回，输者自旋等待（同「每格恰好一次计算」语义） | null 结果不记忆化（同旧）；异常清槽重抛（同旧：下次重算）；`iris.cache.fast` 保留（输者本地重算，同 FastEntry） |
| 3 | `util/function/IntFunction2`（新增） | resolver 从 `Function2<Integer,Integer,T>` 改为原生 `(int,int)->T`，坐标零装箱；`CachedStream2D` 的 `stream::get` 方法引用直接适配（int→double 加宽，数值逐位一致） | 仅消除装箱；计算路径不变 |

**并发正确性要点**：曾考虑分离 `long[] keys` + `Object[] vals` 双数组（更省一次 Node 分配），但在「驱逐清空 + 重插」下读者可能读到**旧值配新键**（跨区块污染 memo，会改变地形值）——两槽配对无法无锁原子更新。不可变 Node 把 (key, chunk) 收进单变量，release/acquire 发布即安全；这是选择 24 B Node 的原因。

## 结果（中位数，5 次测量；A=Caffeine 构建，B=新实现，背靠背运行）

| 场景 | A (Caffeine) | B (striped) | 提升 |
|------|-------------|-------------|------|
| worldcache2d-hit（100% 命中，64 get/op） | 1206 ns（18.8 ns/get） | **428 ns（6.7 ns/get）** | **2.8×** |
| worldcache2d（每 op 换随机区块，全 miss + churn） | 14060 ns / 2320 B/op | **10333 ns / 1079 B/op** | 1.36× / 分配 **-53%** |
| par-worldcache2d（8 线程散点全 miss） | 56113 ns | **10994 ns** | **5.1×** |
| par-worldcache2d-raster（8 线程生产 raster 模式） | 36231 ns | **577 ns** | **62.8×** |

- **并行扩展**（聚合吞吐 vs 单线程）：散点全 miss 从 ~1.5× → **7.5×/8 线程**（近线性）；raster 模式从**负扩展**（8 线程聚合仅 ~0.27×，read-buffer 争用所致）→ **5.9×**。第 3 轮 3.6× 的天花板被彻底移除。
- 全 miss 场景剩余 1079 B/op 即数据结构本体（Object[256] 区块槽 1048 B + 区块对象 + Node 24 B），坐标与返回值装箱均已消除（Integer -100..100 走 JVM 缓存）。
- 内存：每活跃区块 24 B Node（vs Caffeine ~48 B node + 16 B Long），无 eviction 线程、无 read buffer；1024 区块容量 ≈ 16 KB 槽数组 + ≤36 KB Node。
- 注：par-worldcache2d 的 it=1 在两次运行中系统性偏高（19-25 µs，疑似上轮迭代 ~1.3 GB 分配后的 GC 时机），中位数不受影响；raster 场景无此现象。

## 验证

- `verify.sh`：**21/21 场景 digest 位级一致**（A=Caffeine 构建 vs B=striped 构建，含 3 个并行场景 × 8 线程；`round4-caffeine-ab.csv` vs `round4-datastructures.csv`）。
- golden 更新：新增 `worldcache2d-hit`（digest `ecae5689c7f8f625`），其余 20 场景摘要与第 3 轮完全相同。
- 全量编译校验通过（`build.sh`，1209 类）。

## 四轮累计（vs round0 基线）

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配（单线程合计） | 18818 → **3471 B/op（-81.6%）**；CNG/IRare/3D 插值/缓存命中路径 **0 B/op** |
| implode 子群系选择 | **11.4×**（round1 缓存） |
| cache2D 命中路径 | 18.8 → **6.7 ns/get**（round3+4 复合） |
| cache2D 8 线程生产模式 | 负扩展 → **5.9× 聚合**（round3 CLHM→Caffeine 3.6× 被 round4 解除瓶颈） |
| 单线程噪声内核 | 1.0-1.2×（主体收益在分配与多线程扩展） |
