# Round 34 — B.get 解析 memoization + stilt 原地旋转腐蚀 bug 修复

日期：2026-08-27 ｜ 分支：`perf/optimization` ｜ 状态：完成

## TL;DR

R33 破案的 `B.get` 无缓存链（每次调用 `toLowerCase` + **static synchronized** `createBlockData`
全量重解析）在本轮从读取路径推广到全局：`getOrNull` 增加成功结果 memo。**咽喉点量化：462→13 ns/op
（-97%，35×）、2110→0 B/op（命中零分配）；plate-io（板盘重载）B/op -22.7%**。前置别名审计发现并修复
一个**上游即存在、与 memoization 无关的生产腐蚀 bug**：stilt 路径对（可能共享的）BlockData 原地
rotate/merge。

## 别名污染审计（memoization 前置，全库）

BlockData 接口的原地修改方法仅 `rotate`/`mirror`/子接口 setter（setFacing/setAxis/setFace/...）与
`merge`（修改 this 并返回）。全库调用点逐一裁决：

| 调用点 | 裁决 |
|---|---|
| `IrisObject` 主放置循环（1059 clone → 1067 setPersistent / 1078 merge / 1092 rotate） | 安全：先 clone |
| `IrisObject` stilt 路径（1173 `d = entry.value` 无 palette 分支；1185 palette 分支） | **活 bug**：原地 rotate(1193)/merge(1205)——修复见下 |
| `IrisObject.rotate()`（1286 逐条目原地旋转） | 安全但脆弱：唯一入口是 `rotateCopy → copy()`（293 行逐值 clone，先消共享）；已加注释固化该耦合 |
| `IrisEngineDecorator.fixFaces`（92-119 setFace） | 安全：`b.clone()` 后修改 |
| `B.parseBlockData` 尾部 setPersistent | 安全（对新解析实例；缓存值即调用方历史所得）——但依赖 settings 标志，见失效设计 |
| `HMCLeavesDataProvider` | 安全：自建实例 |
| `CustomItemsDataProvider` setFace×6 | 注释掉的死代码 |
| `MatterSlice`/`HashPalette`/`placer.set` | 只存不改（palette 系统本就按字符串去重共享实例） |

### stilt 腐蚀 bug（本轮修复，独立于 memo 有价值）

无 stilt palette 时 `d = entry.value`（对象存储实例）直接进 `rotate(d,...)`（原地）与 `d.merge(...)`（原地）：
- **上游 bug**：缓存对象的存储方块实例被每次放置累计旋转（真机 Orientable/Directial 方块的 axis/facing
  逐次偏移 90°）——同一对象第二次带不同 spin 放置即出错；palette 分支同样腐蚀 `IrisBlockData.blockdata`
  的 aquire 缓存实例。
- R33（palette 预解析共享）会把腐蚀扩散到同对象的所有同 palette 位置；memoization 会进一步扩散到
  全局缓存。bench 桩 BlockData 无状态且 rotate no-op，故 digest 从未暴露此 bug。
- 修复：lowest 层过滤后 `d = d.clone()`（与主循环同模式；跳过层不付 clone）。

## memo 设计（B.getOrNull）

- 命中位置：`custom` 检查之后、cauldron 重写之前，以**原始输入串**为键（cauldron/grass_path 特例也入缓存）；
- **只缓存成功结果**（null 不缓存：第三方 provider 运行期注册后同串可能成功）；
- 容量防护：>65536 条整体清空（真实键空间=pack 内不同方块串，数千级；防御性上限对齐内存红线）；
- **失效钩子**：`IrisSettings.invalidate()`（settings 热重载）调用 `B.invalidateParseMemo()`——解析尾部按
  `preventLeafDecay` 标志烘焙 persistent 位，重载翻转后缓存必须失效；
- 并发：KMap(CHM) get/put 竞争良性（值幂等）；miss 路径的 `createBlockData` 原有 synchronized 保留；
- 语义保证：命中返回的实例=首次解析结果（含 persistent 烘焙），与历史每次新鲜解析行为一致；
  调用方按"不可变模板"使用（审计全过，stilt 修复补上最后一处）。

## 量化（same-window A/B，各 5 迭代）

新增 `bget-parse` 场景（16 个含状态串随机取用——io fixture 同源模式）：

| 场景 | before | after | Δ |
|---|---|---|---|
| bget-parse ns/op | 462.3（中位） | 13.0 | **-97.2%（35.5×）** |
| bget-parse B/op | 2,110.5 | 0.0 | **-100%（命中零分配）** |
| plate-io B/op | 8,112,468 | 6,271,823 | **-22.7%** |
| plate-io ns/op | 39,745,836 | 39,242,023 | -1.3%（5/5 同向，诚实标注为小幅稳定改善——板盘 IO 大头是 LZ4+磁盘） |

- bget 5/5、plate-io 5/5 迭代双指标同向；digest 逐位一致（含 plate-io 的板盘字节格式证明）。
- 生产影响面（代码路径证据，非 bench 声明）：`BlockMatter.readNode = B.get(每节点)`——hard-cap 换入换出
  下的板盘重载、`readLegacy`（无 palette 旧格式每块 UTF）、`IrisCompat` 兼容链 miss 路径，全部受益；
  `static synchronized createBlockData` 的跨生成线程锁争用在命中路径彻底消失（bench 单线程无法量化，机理陈述）。
- 内存红线角度：memo 驻留 = 不同方块串数 × 实例（真机 CraftBlockData ~百字节级，数千串 ≈ 数百 KB，
  上限 65536 条防御性清空）；板盘重载瞬时垃圾 -22.7%。

## 门禁

| 门禁 | 结果 |
|---|---|
| 老 55 场景 digest 子集比对（采纳新 golden 前） | ALL BIT-IDENTICAL |
| golden 56/56 全量（`r34-gate.csv`，1+1；含 object-place-stilt 不变=stilt 修复 digest 中性） | **ALL SCENARIOS BIT-IDENTICAL** |
| `VerifyObjectIOB`（经 memo 后的 B.get 链） | **PASS** |
| gradle `compileJava`（JDK 21） | **BUILD SUCCESSFUL** |

## 教训

1. **审计本身产出超出预期**：别名审计的副产物（stilt 腐蚀 bug）比优化主体更早暴露——"共享实例化"
   类改动（R33/memo）的正确性审计是挖掘上游潜伏 bug 的探测器。
2. **无状态桩测不出状态腐蚀**：rotate/merge 的原地修改在 BenchBlockData 上全是 no-op，digest 永远绿——
   状态依赖 bug 只能靠代码审计或生产形状桩（带状态的 BlockData 桩是潜在基建项）。
3. **缓存失效要跟着配置生命周期**：解析结果烘焙了 settings 标志（persistent 位），失效钩子必须挂在
   配置重载点（IrisSettings.invalidate）而不是指望调用方。
