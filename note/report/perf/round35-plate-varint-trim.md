# Round 35 — JFR 重剖析驱动：板盘序列化 varint 写入 + trim 脏标志

日期：2026-08-27 ｜ 分支：`perf/optimization` ｜ 状态：完成

## TL;DR

对 R22-R34 之后的成本结构做 JFR 重剖析（cave-carve/plate-io/decorator-decorate/object-ioread 四场景），
plate-io 的执行样本 **53.6% 在 `Varint.writeUnsignedVarInt/Long` 的逐字节 `writeByte` 虚调用链**、
13.8% 在 `DataContainer.trim()` 的 O(n) 直方图扫描。三项行为零变化优化后：**plate-io 38.98→20.83 ms/op
（-46.6%，5/5 同向），B/op 中性；matter-roundtrip -6.6% 时间**；磁盘字节 digest 逐位一致。

## 剖析数据（prof-r35/，jfr 本地不入库）

plate-io（1924 exec 样本，本组最强信号）：
- `Varint.writeUnsignedVarInt` 39.0% + `writeUnsignedVarLong` 14.6% —— 逐字节 `out.writeByte` 虚调用
  （DataOutputStream→LZ4 块流），每 varint 每字节一跳；
- `DataContainer.trim` 13.8% —— 每次 writeDos 全量跑 O(length) 使用直方图，即使 palette 已稠密
  （早退也要先扫完全部 cell）；
- `DataBits.getOpaque` 16% —— dump 读取本身（R31 已优化到位）。

其余场景结论：cave-carve/decorator 样本被 setup 噪声稀释（`buildScenarios` 急切构造全部场景 fixture——
io 对象构建的 VectorMap.put 占 16-33% 首帧），真实信号（HashPalette.id、setNoiseMaskedFilledCompact 等）
均为个位数百分比——已收敛；object-ioread 62% 首帧在 VectorMap.put，是 map 结构固有驻留成本
（R33 已收割可避免部分）。

**剖析基建注记**：过滤单场景剖析时，`buildScenarios` 仍会构造全部场景的 fixture——轻场景
（cave-carve 30 样本）被 setup 淹没。后续单场景剖析应考虑 lazy fixture 或加大 iters。

## 改动（3 文件，编码逐位不变）

1. **`Varint.writeUnsignedVarInt/Long`**：单字节快速路径（value<128 直接一次 `writeByte`——palette id/
   小计数占绝对多数，空 cell 是 id 0）；多字节走 byte[5]/byte[10] scratch + 单次数组写。编码字节与
   原 shift 循环完全一致。
2. **`DataBits.write`**：scratch 提到循环外。关键认知：该方法按**打包 long**（非 cell）dump——每个 long
   都是多位 varlong，通用 Varint 的 per-call scratch 会变成每 long 一个 byte[10]（中间版本实测 B/op
   +60%，见教训）。本方法内联同编码循环 + 循环外复用缓冲。
3. **`DataContainer` 脏标志**：`set()` 置脏，`trim()` 两条路径（早退/重打包）清脏，`writeDos` 仅在脏时
   trim。**不变式证明**：使用直方图只能经 `set()` 改变（cell 只会被覆写为其他 id，palette 条目只在
   set 内新增），未变更容器的"已 trim 表示"序列化字节与再跑一次 trim 后完全一致（trim 幂等）——
   读入构造后未修改的 section（unload/reload 往返、重复 flush）从每次写盘的 O(n) 扫描中解放。

顺带发现（未动）：`DataContainer.TRIM`（`iris.trim-palette` 系统属性）声明后从未使用——死配置，
留待上游清理决策。

## 量化（same-window A/B，各 5 迭代）

| 场景 | before | after | Δ时间 | B/op |
|---|---|---|---|---|
| plate-io（写+读一盘 64 chunk×5 section） | 38.98 ms（中位） | 20.83 ms | **-46.6%** | 6.272→6.283 MB（+0.17%，中性） |
| matter-roundtrip | 400.2 μs | 373.8 μs | **-6.6%** | 75.0→76.0 KB（+1.2%，中性） |

- 两场景 5/5 迭代时间同向；digest（含 plate-io 的板盘字节格式证明）逐位一致。
- 生产影响：板盘写盘是 R26 硬上限 unload/reload 循环的持久化半边——预生成期间的 flush 停顿直接减半。

## 门禁

| 门禁 | 结果 |
|---|---|
| golden 56/56 全量（`r35-gate.csv`，1+1，场景集无变化直验） | **ALL SCENARIOS BIT-IDENTICAL** |
| `VerifyObjectIOB` | **PASS** |
| gradle `compileJava`（JDK 21） | **BUILD SUCCESSFUL** |

## 教训

1. **通用工具方法的最优形状由最大调用方决定**：Varint 加 per-call scratch 缓冲在"小值为主"的调用方
   （计数/id）是对的，但最大流量调用方（DataBits 按打包 long dump）全是多字节——通用路径的缓冲在
   那里变成每 long 一个分配（B/op +60%）。热路径调用方应自带循环外缓冲（本轮回补）。
2. **中间版本必须量化分配**：第一版 -46% 时间 + +60% B/op，若只看时间就提交会带入回归
   （R32 教训第二次应验）。
3. **JFR 场景剖析的 setup 污染**：buildScenarios 急切构建全部 fixture，轻场景剖析需辨析首帧归属
   （VectorMap.put/buildIoObject 出现在 cave-carve 剖析里是 fixture 而非被测路径）。
