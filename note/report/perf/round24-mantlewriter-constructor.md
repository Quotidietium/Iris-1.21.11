# 性能优化 · 第 24 轮：MantleWriter 构造器装箱中转消除（cave-carve -34~40% 分配）

**日期**：2026-08-25 · **分支**：`perf/optimization`
**环境**：JDK 25.0.4 · 32 逻辑处理器 · **golden 49/49 位级一致**

## 本轮主旨

R23 后 cave-carve 剩余 27-31 KB/op 的构成审计。代码级审计（JFR 样本
不足，见 R23）定位到 `MantleWriter` 构造器的**装箱中转**：单线程路径
先把 13×13=169 个 chunk 装进临时 `KMap<Long, MantleChunk>`（每 chunk
一个 Long 装箱 + CHM Node），再 `putAll` 拷贝进本就存在的
`Long2ObjectOpenHashMap`——临时 map 纯属装箱绕路，R23 剖析中
`lambda$new$0` 占分配样本 21% 的谜底。

## 1. 实施

`MantleWriter` 构造器：删除临时 KMap 与 putAll，按 multicore 分支把
`getChunks` 回调直接写入 cachedChunks（多线程分支 KMap 保持并发安全；
单线程分支 Long2ObjectOpenHashMap 原生 `put(long, ...)`，零装箱）。

同 key 集、同 use() 调用序列、close() 释放语义不变（release 顺序
无状态）；acquireChunk 查找语义不变。**digest 构造性一致**。

附带的顺手重构：`Positions`（21+21+21 位打包工具类）从 MantleWriter
私有实现提取到 `util.math` 共享（R23 的位域编解码单一事实来源）。

## 2. 过程否决（worm 重构，记录为容量调参教训）

`IrisWorm.generate` 循环重构（next 临时 IrisPosition 消除 + 访问集
packed 化）digest 一致但 B/op **方向混合**（短 worm seed +0.2~0.3%，
长 worm seed -1.5~-5.1%，均值 -1.5%）：首版 `LongOpenHashSet(64)`
初始数组（128 槽 1KB）比原 `KSet()`（CHM 32 槽 256B）大 4 倍，每
generate +768B 直接显形；改 16 对齐后剩余混合方向源于 fastutil
增长策略与 CHM 的对象布局差。**不满足 9/9 同向标准，按 R19/R20 纪律
整体回退**——机制减法不等于可测收益，当目标结构占比 <5% 时结构替换
的常数差可吞掉全部收益。

## 3. 测量

cave-carve 隔离跑（3 预热 + 9 测量，vs R23 提交态）：

| seed | B/op R23 | B/op R24 | 降幅 |
|---|---|---|---|
| 0-8 | 27229-30927 | 16712-19506 | **-34%~-40%（9/9 一致方向）** |

时间 33-78 µs/op（R23 同时段 54-84）——漂移带内多数下降，不做声明。
digest 9/9 逐 seed 相同；全量 golden 49/49 位级一致。

降幅（~11 KB/op）高于装箱理论值（169×48B≈8KB）：putAll 的装箱视图
迭代与再插入开销一并消失。

## 4. 结论

- cave-carve 分配累计：R11 态 30-35 KB → R23 27-31 KB → **R24 17-20
  KB/op（两轮累计约 -45%）**。剩余主体为 worm points 的必要 IrisPosition
  载体与 cleanup 插值。
- 三轮 cave 链（R23 集合代数 → R24 构造器）证明：分配剖析的"场景
  级 B/op"必须落到**构造路径**才能定位大头——集合代数（R23 -8.4%）
  只是次级目标。
- worm 重构否决与 R23 位域事故共同沉淀方法论：结构替换的初始容量/
  对象布局差是可测的（±0.3% 级），A/B 的 9/9 同向标准是防噪声门槛。
