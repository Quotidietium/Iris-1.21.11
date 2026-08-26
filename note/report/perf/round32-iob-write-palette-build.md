# Round 32 — .iob 写入路径：palette 构建去二次遍历

日期：2026-08-27 ｜ 分支：`perf/optimization` ｜ 状态：完成

## TL;DR

`IrisObject.write()` 的 palette 构建仍是 `addIfMissing`（每块 O(palette) 的 `contains` 线性扫）加每块两次
`getAsString()`（真机 CraftBlockData 每次调用都新分配字符串）。改为单遍 cursor 遍历 + HashMap 去重 +
`int[]` 预记录每块 palette id，写入循环零查找零字符串调用。**转换级对象（300k 块、96 palette）单次保存
耗时 -53%（37.6→17.6 ms）、分配 -14%；15/15 迭代时间与分配双向同向。输出字节与历史算法逐字节一致
（含跨 chunk 用例）。**

## 优化点与调用面

`.iob` 写入发生在三条用户路径：Studio 保存/克隆对象（`CommandObject`）、世界→对象批量转换
（`ConversionSVC`）、schem→iob 转换（`IrisConverter`）。R13 已把写入循环的 `palette.indexOf()` 换成
HashMap，但遗留两处：

1. **palette 构建的 `addIfMissing`**：`KList.contains` 是 O(p) 线性扫，整体 O(n×p)。宽 palette 的转换级
   对象（一个大型建筑快照轻松 96+ 种方块状态、几十万块）在构建阶段就要做千万次字符串比较。
2. **每块两次 `getAsString()`**：一次在 palette 构建、一次在写入循环查 id。真机上每次调用都构造新字符串，
   n 块对象每次保存多分配 n 个字符串。

## 改动（core 1 文件）

`core/src/main/java/com/volmit/iris/engine/object/IrisObject.java`

- 新增 `buildPalette(int[] ids, int[] progress)`：单遍 `cursorIterator()`（零分配、与 `iterator()`
  同序——两者实现是同一段 chunk→relative 遍历代码）构建 first-seen palette，HashMap 去重，同时把每块
  的 palette id 写进 `ids[k]`；`progress` 供交互保存 Job 保持进度计数。
- `writePaletteAndBlocks(dos, palette, ids)`：写入循环直接消费 `ids[k++]`，无查找、无 `getAsString`。
- `write(OutputStream)` 与 `write(OutputStream, VolmitSender)`（Job 变体，进度语义 `c += built[0]`、
  `total -=` 校正保持不变）都走同一新路径。
- 字节格式零变化：palette first-seen 顺序与历史算法一致（同为该遍历序的首见序），id 映射同源。

### 中间版本（已否决）记录

第一版 buildPalette 用 `for (var entry : blocks)`（entrySet）——时间同样大胜（wide -49%）但 B/op **+31%**：
EntryIterator 每块分配 `Map.entry` 包装 + `BlockVector`（约 72B/块），palette 构建阶段只读 value 却付了
整套包装分配。换 `cursorIterator()`（复用同一游标对象）后该项归零，时间与分配双双转负。

## 量化（benchmark，same-window A/B，各 5 迭代取中位，机器干净）

新增 3 场景 `object-iowrite-small/large/wide`（10k/100k/300k 块，palette 12/32/96）。关键基建：
**FreshStringBlockData**——`getAsString()` 每次返回新字符串实例（模拟 CraftBlockData）。R29 的
BenchBlockData 缓存字符串实例，`String.equals` 走 `==` 引用快速路径，会把 palette 扫描的真实成本
（逐字符比较）整个藏掉，测出来的是假快。

| 场景 | before ns/op | after ns/op | Δ耗时 | before B/op | after B/op | Δ分配 |
|---|---|---|---|---|---|---|
| small（10k 块, p12） | 598,903 | 517,528 | **-13.6%** | 1,399,448 | 1,199,856 | **-14.3%** |
| large（100k 块, p32） | 7,286,300 | 5,390,988 | **-26.0%** | 14,036,288 | 12,036,568 | **-14.2%** |
| wide（300k 块, p96） | 37,635,900 | 17,598,250 | **-53.3%** | 44,334,016 | 38,334,056 | **-13.5%** |

- 15/15 迭代在 ns/op 与 B/op 两个维度全部同向（9/9 纪律满足且超额）。
- 三场景 digest 与改动前一致（输出字节未变）。
- 阶梯符合理论：palette 越宽，被消灭的 O(n×p) 扫描占比越大。
- 用户体验换算：一个 300k 块的建筑快照保存从 ~38ms 降到 ~18ms；转换服务批量处理数百对象时每对象
  少 ~20ms CPU 与 ~6MB 瞬时垃圾。

## 门禁

| 门禁 | 结果 |
|---|---|
| `VerifyObjectIOB`（新算法 vs 逐字复刻的 pre-R13 算法，全字节比对 + 往返） | **PASS**（5,798 字节逐字节一致；110 块往返） |
| 跨 chunk 强化（本轮新增用例：块坐标 0/700/1024/1500/-1300 跨 4 个 VectorMap chunk） | PASS——证明 palette 构建（cursor）、写入（entrySet）、legacy 参考（values()）三种遍历跨 chunk 同序 |
| 老 49 场景 digest 子集比对（采纳新 golden 前） | ALL BIT-IDENTICAL |
| golden 52/52 全量（`r32-gate.csv` vs 采纳后的 `golden.csv`，1+1） | **ALL SCENARIOS BIT-IDENTICAL** |
| gradle `compileJava`（JDK 21，部署工具链） | **BUILD SUCCESSFUL**（25 tasks） |

### VerifyObjectIOB 跨 chunk 用例的必要性

历史（pre-R13）算法 palette 用 `values()` 构建、写入用 `entrySet()`——两种视图的顺序等价性此前只有单
chunk 证据（基准与验证对象的坐标都 <1024，全部落在 chunk 0）。本轮用例把块铺到 4 个 chunk（含负坐标），
从此该等价性每个验证运行都有字节级证据，而不依赖对 CHM 视图迭代顺序的直觉。

## 风险与兼容

- 字节格式、读取路径、API 签名零变化；`.iob` 新旧文件互读写不变。
- `ids` 顺序对齐依赖「同一未修改 map 的两次迭代同序」与「cursorIterator 与 iterator 同序」（后者是
  VectorMap 自己的 javadoc 契约 + 实现同构），并有跨 chunk 字节门禁兜底。
- 内存红线无交集：`ids`（4B/块）与 HashMap 均为方法内瞬时对象，方法返回即可回收；不引入任何驻留结构。
- 内存占用注意点：无（与 R26/R28 的驻留 cap 无关，纯写入路径）。

## 教训

1. **桩的字符串驻留会伪造算法结论**：R29 桩把 `getAsString()` 做成 final 字段读，等于把所有 String
   `equals` 变成 `==`。任何以字符串比较为主的路径（palette 去重/查找）用该桩测量都会严重低估优化前
   成本。需要测量字符串路径时必须用 FreshString 形状。
2. **迭代器形状就是分配形状**：只读 value 的遍历用 entrySet 每块白付 72B 包装分配；cursorIterator 这类
   零分配游标应该成为 VectorMap 只读遍历的默认选择（对象放置路径 R25 时期已验证过同款结论）。
3. **中间版本也要量化**：entrySet 版时间 -49% 看似可交付，B/op +31% 是被时间数字掩盖的回归；若不
   中途复测分配，这个回归会带进 release。
