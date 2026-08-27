# Round 36 — 四场景 JFR 剖析（负结果轮）：离线安全优化面宣告收敛

日期：2026-08-27 · 基线：master @ 3.9.4（0e85b5fda）· 状态：完成（无代码改动，判定性交付）

## TL;DR

对 R35 未覆盖的四个场景（carve-modify / matter-roundtrip / deposit-place / terrain-col-fill）做
JFR 剖析（`benchmark/results/prof-r36/`，分析文本 140 行已归档）。**结论：四个面全部触及红线约束或
不可量化，无可安全交付的优化点。离线"安全+可量化"优化面就此宣告收敛。**

## 各面判定

### 1. carve-modify（378 执行样本，信号最强）——被封死

- 分配：91% 样本在 `IrisCarveModifier.onModify` 迭代 lambda（`walls.put(new IrisPosition(...))` 每墙
  一分配 + KMap 节点）。
- **红线封条（R11 既往教训，仍然有效）**：walls/positions KMap 的 CHM 迭代序决定后续 zone 处理顺序与
  M.r 随机数序列——原语化/换容器/预置容量任何一项都会改变地形输出位。分配是该红线的既定代价。
- 执行：32.3% 在迭代 lambda 本体（语义工作）+ 15% Material.isAir（桩枚举 switch）。

### 2. matter-roundtrip（257 样本）——锁语义是资产不是开销

- 26.5% 执行在 `HashPalette.id`（DataContainer.set 的每次块写入咽喉）。
- 批量化 set（一次锁一批 cell）设想被算术否决：bench 单线程无争用，RRW 非争用锁 ~20ns × 4096 cell
  ≈ 80μs，对比场景 ~400μs/op 整体与生产多线程语义——**收益 <1%，却要动核心写路径的锁与 pin 结构**
  （R24 并发表损坏、R26 pin 泄漏的前车之鉴）。不做了。
- **生产真实但 bench 不可见的观察（留给未来）**：`HashPalette.id` 的键哈希在真机 CraftBlockData 上
  每次调用重算（states map），而 bench 桩缓存了 hash——若未来优化此点，需先造 FreshHash 形状桩
  （R32 字符串驻留教训的同族：桩的成本结构决定结论真伪）。

### 3. terrain-col-fill（300 样本）——归因伪影 + 已优化

- 31% "generateSeaLayers" 叶子帧与 fixture 事实矛盾（场景 biome 的 seaLayers 为空，方法近乎空转）——
  JFR 对内联体的叶子归因把 fill 循环拆到了错误的方法名下。fill/generateLayers 是 R4/R5 优化过的路径，
  在归因可信度不足时不动它。

### 4. deposit-place（30 样本）——无信号

- 样本被 `buildScenarios` 急切 fixture 构建污染（R35 已记录的基建局限），无可用信号。

## 剖析基建改进（顺带记录，未实施）

`buildScenarios` 急切构造全部场景 fixture，轻场景剖析被 setup 淹没（R35+R36 两次应验）。未来若再做
单场景剖析，应把重 fixture（io 300k×3 对象）改为 filter 命中时惰性构建。

## 循环状态

- 离线：36 轮（31 正结果 + 5 负结果），IO 三面（对象读写/方块串解析/板盘序列化）+ 存储层全部过过刀，
  剩余面被身份约束或风险收益比封住。
- 唯一有效推进器：**用户真实服务器实测数据**（`note/server-test-card.md`，重点 heap 曲线 +
  `Failed to read matter slice` 行数）——数据回来才有下一步（异常则根因分析，过线则 3.9.4 回归闭环）。
