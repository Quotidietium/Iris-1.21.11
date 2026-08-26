# 性能优化第 30 轮：VectorMap cursorIterator 原生化（负结果轮，代码已回退）

日期：2026-08-27 · 分支：`perf/optimization` · 主题：R29 遗留候选——消除 CursorIterator 内部的 CHM Entry 视图分配（JFR ~1046 EntryIterator + 369 MapEntry 采样，object-place 现第二大分配源）。

## 结论：按纪律当场回退

同窗口 9 样本 A/B（stash 基线 ↔ 候选，单 JVM 隔离跑 `-Dbench.filter=object-place`）：

| 场景 | 基线（entrySet 迭代） | 候选（keySet+get） | 判决 |
|---|---|---|---|
| object-place | 12020 ns / 4968 B | 12491 ns / 4976 B | **9/9 更慢（中位 +3.9%），B/op 1.002× 中性** |
| object-place-stilt | 22024 ns / 5448 B | 20396 ns / 5464 B | 3/9 更慢（方向混合，不采信） |

object-place 的 9/9 同向变慢是干净判决——**回退**（与 R25 RotationPlan 同族处置）。

## 尝试的方案

`CursorIterator` 从 `map.entrySet().iterator()`（外层）+ `chunk.getValue().entrySet().iterator()`（内层）改为 `keySet().iterator()` + 每 chunk 一次 `map.get(chunkKey)` + 每条目一次 `relativeMap.get(k)`。原理：CHM 的 Entry 视图对**每个 `next()` 分配一个 MapEntry**（内部节点可变，公共 Entry 必须装箱），而 Key 迭代器直接交付存储的键实例——两视图的 Traverser 走同一表结构，遍历序逐位一致（digest 证明：object-place/-stilt it=0 均 `a63e81aabd8fe8cd` 位级一致）。

## 为什么失败——机理与教训

1. **逃逸分析已经在消除装箱**：MapEntry 在 place 热循环里不逃逸（读 k/v 后即死），EA + 标量替换把大部分装箱分配优化掉了。JFR `ObjectAllocationSample` 抓到的是 EA 失败的尾部（剖析运行的内联决策与纯净运行不同），~1400 采样高估了真实可回收量。B/op 中性（±8B/op）是直接证据——装箱本就不在 B/op 里。
2. **每方块两次哈希探查比一次装箱贵**：keySet 方案为取 value 每条目多一次 `get()`（CHM 探查 = hash + 遍历桶），small-map 常数也不可忽略。
3. **与 R22 证据合并**：R22 当时记录"CHM.forEach(BiConsumer) 本就零分配表遍历"——推式 forEach 确实零分配（回调内联进 Traverser），但 CursorIterator 是拉式迭代器，无法套用；改推式会重排 place 主循环的 per-entry try/catch（CME 语义），风险不对称于收益（收益已被证伪为零）。

**入库教训（第四账）**：JFR 分配采样 ≠ 热循环真实分配——EA 标量消除的部分不会出现在 B/op 里，但会出现在剖析采样里；分配优化的方向决策必须以纯净运行的 B/op A/B 为准，采样占比只能当线索。

## 处置

- 代码回退至 HEAD（`git checkout -- VectorMap.java`），重建后隔离 digest 验证 object-place/-stilt 与 R29 基线逐位一致（`a63e81aabd8fe8cd`）。
- 证伪数据归档：`benchmark/results/_r30-base.csv`（基线 9 样本）、`_r30-cand.csv`（候选 9 样本）、`_r30iso.csv`（跨窗初测）、`_r30-revert.csv`（回退确认）。
- golden 49/49：回退后代码与 R29 验证态完全一致（同一提交），无需重跑全量；隔离 digest 双场景一致为据。

## VectorMap 后续状态

CursorIterator 维持 entrySet 实现装箱现状（EA 已兜底）；`forEachCoords`（推式零分配，R16）仍是有该需求时的正确工具。object-place 剩余 B/op ~5KB 中，d.clone()（正确性依赖）为大头——此路径的离线优化空间已实质收敛，剩余收益需服务器实测定位。
