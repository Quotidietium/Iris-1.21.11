# 性能优化第 31 轮：matter-roundtrip 序列化本体（机理减工作量轮）

日期：2026-08-27 · 分支：`perf/optimization` · 主题：memory 记录的最后大块离线候选——16³ Matter 段序列化往返的成本结构与行为中立优化。**磁盘字节格式零变化是红线**（matter-roundtrip digest 含重序列化字节折叠 + golden it=0 一致为证）。

## 真实成本结构（JFR `round31-mr.jfr`，553 exec 样本）

| 占比 | 帧 | 归属 |
|---|---|---|
| 27.3% | `HashPalette.id` → CHM.get | 场景构建期 `DataContainer.set` 每格调色板查表（R19 曾试 id 备忘回归，不动） |
| 12.7% | `Random.nextInt` | 场景自造数据（bench 专属，非生产成本） |
| 8.9%+3.4% | `VarHandleLongs$Array.get/setVolatile` | DataBits 批量通道的 volatile 语义 |
| 5.4% | `CountingDataInputStream$Counter.count` | 每次读取的字节计数（`Math.addExact` 溢出分支 + mark 分支） |
| 9.0% | `Varint.writeUnsignedVarInt/Long` | 格式本体（不动） |
| 4.5% | `HashPalette.ensureCapacity` | 反序列化 palette 的逐次 grow+拷贝（16→32→…） |
| 26.3% 分配 | `DataBits.<init>` | 读路径每段新位阵列（结构本体） |

## 三项行为中立优化（全部落地保留）

1. **DataBits 批量通道 volatile→opaque**：`write()`（整阵 dump，容器写锁内）、`longs()`（读构造，final-field 发布前）、`DataContainer.trim()` 直方图/重打包（写锁内或未发布；重打包阵经 structureVersion volatile 写发布，opaque 写先于 release 发布可见）。新增 `getOpaque/setOpaque` 访问器（逐元素一致性保持，仅去掉逐元素 volatile 排序——排序责任在调用方的锁/发布边）。**x86 上 volatile 读本无围栏，此改主要惠及弱序架构（ARM 服务器）；x86 收益为 VarHandle 分派形态差**。
2. **HashPalette.from 预分配**：两个 `from` 重载在循环前 `ensureCapacity(已知条数)` 一次到位，消除 16→32→…→size 的逐次 grow+拷贝链（反序列化每个 palette 省掉 log₂(N) 次数组分配+拷贝）。
3. **Counter.count 去 addExact**：`Math.addExact` → 普通加法（long 字节计数实际不可达 2⁶³，溢出分支纯属每次读取的额外开销）。

## 验证（同窗 stash A/B，9 样本）

| 指标 | 基线 | 候选 | 判决 |
|---|---|---|---|
| ns/op 中位 | 399809 | 406590 | 4/9 更快，方向混合——不声明（漂移带内） |
| B/op 中位 | 87736 | 87184 | **0.994×（-0.6%，方向一致）** |
| digest | — | — | **9/9 迭代全一致**；it=0 与 golden `59be4bc51896dadd` 逐位一致 |

按 R13 先例（机理严格减工作量 + 实测持平即保留）：三项改动每项都严格 ≤ 原工作量（opaque ≤ volatile 排序强度、1 次分配 ≤ k 次、无分支 ≤ 有分支），无任何变慢机理，digest 全一致——保留。诚实声明：本收益是小量级；大头（palette.id 查表 27.3%、Varint 格式、DataBits 结构本体）要么已被 R19 证伪（id 备忘的内联预算回归）、要么是格式/结构红线不可动。

## 全量回归与基础设施发现

- golden 49/49 位级一致（`round31-post.csv`，干净机器 3+3 迭代）。
- **R29/R31"run 莫名截断"谜底全解**：链条是 外部 10 分钟超时击杀 run → **被杀的 bash 只杀到壳，benchmark JVM 因 MultiBurst/IOWorker 非守护线程池继续存活并烧 CPU** → 残留 JVM 拖慢后续 run 使其接连超时（症状一次比一次早）。解法：跑前确认 java 进程归零（循环 `Stop-Process -Name java -Force`）；全量用 3+3 迭代（verify 只用 it=0）压进时限。两项均记入 benchmark README 方法论节。

## 改动清单

| commit | 内容 |
|---|---|
| perf(R31) | DataBits getOpaque/setOpaque + 三通道切换、HashPalette.from 预分配、Counter 去溢出分支 |
| bench/docs | `_r31-base/_r31-cand/_r31iso.csv` + `round31-mr.jfr` 归档、报告、README/release/memory 更新 |
