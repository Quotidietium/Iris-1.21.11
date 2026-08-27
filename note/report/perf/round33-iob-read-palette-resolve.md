# Round 33 — .iob 读取路径：palette 预解析 + cursor vector 复用

日期：2026-08-27 ｜ 分支：`perf/optimization` ｜ 状态：完成

## TL;DR

`IrisObject.read()` 每块做两件昂贵的事：`new Vector3i(...)`（纯垃圾分配）和 `B.get(palette.get(id))`——
而 `B.get` 对 `minecraft:*` 字符串**没有任何缓存**：每次调用都走 `toLowerCase()` + **static synchronized**
`createBlockData` 全程重新解析字符串、新建 BlockData。改为 palette 一次预解析成 `BlockData[]` + 整个读取
循环复用一个可变 cursor vector。**300k 块对象读取：207ms→25ms（-88%）、668MB→34MB 分配（-95%）；
15/15 迭代双指标同向；读回内容 digest 逐位一致。**

## 优化点与机理（已证部分）

`.iob` 读取发生在对象加载缓存（`ObjectResourceLoader` 的 KCache）缺载时——预生成期的对象加载风暴正是
GC 压力来源。基线测量揭示了远超预期的每块成本（wide 场景每块 ~2.2KB 分配 / ~690ns）：

1. **`B.get` 无缓存链**（主因，代码证据）：`B.getOrNull` 只查 `custom` KMap——它仅由
   `registerCustomBlockData`（第三方模组方块 provider）填充，`minecraft:*` 字符串永远 miss →
   `parseBlockData` 每次执行 `ix.toLowerCase()`（新字符串）→ `createBlockData`（**static synchronized**）
   → `Bukkit.createBlockData(s)` 重新解析。成功路径**不回填任何缓存**。
2. **`new Vector3i` 每块一个**（~48B）：`VectorMap.put` 只读坐标构造内部 Key、不保留传入 vector，
   整个循环一个可变实例足够。
3. palette 预解析后每块的 `B.get(palette.get(id))`（字符串 contains/startsWith + 上述全解析链）
   变成 `resolved[id]` 数组下标加载。

## 改动（core 1 文件）

`IrisObject.read(InputStream)`：
- 读入 palette 后先 `BlockData[] resolved = ...` 每项 `B.get` 一次（共 p 次，而非 n 次）；
- `Vector3i cursor` 循环外创建，每块 `setX/setY/setZ` 复用（states 循环同样处理）。

`IrisObject.readLegacy`：无 palette（格式决定每块一个 UTF 字符串），只做 cursor 复用。

### 共享实例安全性论证

预解析使同一 palette 项的所有块共享一个 BlockData 实例（旧代码每块新鲜实例）。审计结论：安全——
- 放置/旋转路径先 `d.clone()` 再变换（`IrisObject.java` place 循环，R30 记录的"d.clone() 为正确性依赖"）；
- 写路径只读 `getAsString`；`clone()` 复制路径逐值 clone；
- MatterSlice/HashPalette 本来就按字符串去重共享实例（工程既有常态）。

## 量化（same-window A/B，各 5 迭代取中位，机器干净）

新增 3 场景 `object-ioread-small/large/wide`（与 R32 write 场景同一组 fixture，序列化一次后反复读回；
digest 折叠 cursor 遍历的全部读回内容——内容与顺序均由插入序列决定，改动前后必须一致）：

| 场景 | before ns/op | after ns/op | Δ耗时 | before B/op | after B/op | Δ分配 |
|---|---|---|---|---|---|---|
| small（10k 块） | 6,558,463 | 943,653 | **-85.6%** | 22,449,261 | 1,390,758 | **-93.8%** |
| large（100k 块） | 69,249,950 | 9,826,250 | **-85.8%** | 225,179,624 | 13,875,292 | **-93.8%** |
| wide（300k 块） | 206,561,300 | 25,393,400 | **-87.7%** | 668,323,632 | 33,652,232 | **-95.0%** |

- 15/15 迭代 ns/op 与 B/op 双向同向；三场景 digest 与改动前逐位一致。
- 每块成本：~2,228B/~690ns → ~112B/~85ns。
- **内存红线关联**：加载风暴期单对象瞬时分配 668MB→34MB（young-gen 压力大幅下降）；
  预期生产还有 bench 单线程测不出的额外收益——`createBlockData` 是 static synchronized，
  并行生成线程的加载风暴原本会在该锁上串行（此条为代码事实+推断，未在 bench 中量化）。

诚实边界：bench 的 `Bukkit.createBlockData` 是 R29 桩实现（Material 匹配+字符串切割），其单次成本与
生产 CraftBlockData（states map 构建）的绝对值不同；但结构性修复（每 palette 项解析一次）对任意
单次成本严格成立，生产单次成本只会更高。

## 门禁

| 门禁 | 结果 |
|---|---|
| `VerifyObjectIOB`（含 R32 跨 chunk 用例；读回往返经新 read()） | **PASS**（5,798 字节逐字节一致 + 110 块往返） |
| 老 52 场景 digest 子集比对（采纳新 golden 前） | ALL BIT-IDENTICAL |
| golden 55/55 全量（`r33-gate.csv`，1+1） | **ALL SCENARIOS BIT-IDENTICAL** |
| gradle `compileJava`（JDK 21） | **BUILD SUCCESSFUL** |

## 后续候选（本轮未做，已记 memory）

`B.get` 的无缓存问题影响所有调用方（不止 read()）。**修法是给 `B.getOrNull` 加 memoization**（字符串→
BlockData 缓存，键空间有限），但共享实例会扩散到全部 ~140 个调用点——必须先审计是否存在**不 clone 就
原地修改** B.get 结果的调用方，否则缓存会造成别名污染。作为 R34 候选，审计工作量是一轮的主体。

## 教训

1. **"缓存探测"注释会说谎**：Benchmark.java 里"B.get 的第一个动作是缓存探测，代理工厂立即应答"的
   注释基于 `custom` map 的存在——但它对 minecraft 字符串永远为空。读代码要读到最后一个 return。
2. **A/B 差值远超改动面时必须找齐机理**：cursor+数组化名义上只省 ~50B/块，实测省 2,116B/块——
   追到 `B.get` 无缓存链才敢写进报告（否则会归因错误）。
3. 顺带：`String.trim()` 无首尾空白时返回 this（零分配），`toLowerCase()` 永远新分配——热路径上
   前者无害后者致命。
