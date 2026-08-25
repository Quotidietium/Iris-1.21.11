# 性能优化 · 第 25 轮：RotationPlan 计划化（负结果轮——旋转谓词链证伪）

**日期**：2026-08-25 · **分支**：`perf/optimization`
**环境**：JDK 25.0.4 · 32 逻辑处理器 · **代码已回退（负优化 9/9 一致）**

## 本轮主旨

object-place 重剖析（5470 执行样本 / 24961 分配样本，R21 变换就地化后
首次）发现 `IrisAxisRotationClamp.isLocked`（5.9%）+
`IrisObjectRotation.canRotateX`（4.9%）合计 10.8% 执行样本——place
每块重算 place 级常量谓词。尝试 RotationPlan（place 开头一次解析三轴
旋转模式 + 角度，循环内 switch 分派）。

## 1. 实施与验证（已回退）

- `RotationPlan` 嵌套类：三轴模式（SKIP/LOCK_180/LOCK_90/LOCK_270/
  FREE/UNLOCKED_AROUND_Y）+ 预计算弧度；模式解析逐字复刻 rotate() 的
  分支条件（含**上游 quirk**：Z 轴未锁时调 rotateAroundY——照抄保持
  逐位一致）。
- `bench.VerifyRotation`：60000 随机 clamp/spin 组合新旧 rotate
  **double 位级对比——全部一致**（含 interval<1 归一化写副作用路径）。
- place 两处每块调用点 + translate 切换 plan 版。digest 逐 seed 一致。

## 2. 测量与证伪

同时段对照（stash 切换、同窗口重跑 base）：

| 场景 | new vs base 时间 | 判定 |
|---|---|---|
| object-place | **9/9 更慢（+1.4%~+16.2%，中位 +8.6%）** | 负回归 |
| object-place-stilt | 4/9 快 5/9 慢（-9.5%~+30%） | 混合 |
| B/op | +88B（plan 对象本体，1/place） | 可解释 |

9/9 一致的负向时间信号——按 R19 纪律（palette id 备忘 +10% 回退的同族
判定）**当场回退全部代码**，负结果数据归档
（`iso25n/iso25b-object-rejected.csv`）。

## 3. 机理分析

原谓词链在 place 循环里**分支预测 100% 命中**（同一配置每块同结果），
实际成本是几次字段读 + 比较（远低于剖析样本占比暗示的"可省成本"——
JFR 样本计入的是包括预测失败开销与流水线效应的静态频率）。计划化用
switch 分派 + plan 字段读替代，理论上少几次比较，但：

1. rotate/translate 的新调用面（plan 字段 ×6 + 三 switch）改变 JIT 内联
   决策，place 巨型方法体的内联预算被重新分配（R19 教训：热路径方法
   体量与调用形状是性能资产）；
2. 谓词链的分支在预测完美时近乎零成本，而 switch 的间接跳转表无此
   待遇；
3. base 侧时间方差显著更小（23.9-25.5µs vs new 25.2-28.1µs 松散），
   与内联形状变化的行为特征吻合。

**结论**：剖析样本占比 ≠ 可回收成本。分支预测友好的谓词链不是热点，
是"被剖析器看见的便宜代码"。与 R6（派发扁平化 <1%）、R23（perfection
谓词合并 JIT CSE 已消除）构成同族教训第三次入账。

## 4. 遗留发现（后续轮次的输入）

- `IrisObject.place` 本体 29.4% leaf + String/Objects.equals 23.3%
  （大部分为 bench 桩 BlockData 代理伪影——`Bukkit.proxy(Material,
  String)` 的 Material 反查；生产 CraftBlockData 为字段读）。
- `VectorMap$CursorIterator.next` 6.4%——CHM 迭代本质，R16 已游标化。
- 1050 行 `data.getMaterial().equals(Material.AIR)` 可换枚举 `!=`
  （零风险微优化，未单独成轮——收益被桩伪影淹没无法量化）。

## 5. 结论

负结果轮。rotation 谓词链方向以 60000 位级验证 + 9/9 同时段对照证伪
并回退。cave 链（R23/R24）后 object-place 的下一可动面需服务器实测
或生产形状的 BlockData 桩（当前动态代理桩的时间占比失真 ~40%）。
