# 性能优化 · 第 16 轮：全面重剖析 + 每块分配/查找开销清扫（复合轮）

**日期**：2026-08-24 · **分支**：`perf/optimization`
**环境**：JDK 25 · 32 逻辑处理器 · **43 场景 golden 全部位级一致**

## 本轮主旨

R14/R15 之后热点版图已位移。本轮先用 JFR 对 12 个重量级场景逐一重剖析建立**当前**热点地图，
再以"跨子系统的每块/每条目分配与查找开销"为主题做复合清扫。过程中发现并修复了
R15 遗留的一个 **golden 验证缺口**（plate-io digest 跨进程不稳定）。

## 1. 重剖析：方法与两次污染修正

新增 `benchmark/prof.sh`（单场景 JFR profile 记录）与 `benchmark/prof-analyze.py`
（ExecutionSample 叶帧/首 Iris 帧 + ObjectAllocationSample 聚合）。12 场景逐一隔离剖析。

两个剖析污染被当场抓住并修正：

- `ctx-fill` 过滤器子串同时匹配 `ctx-fill-cellwise`（旧协程 A/B 场景，375µs/op，
  样本占比 ~4/5）——初版"55-68% 调度开销"结论大部分来自 R14 已消除的旧编排；
- `object-place` 同理被 `object-place-stilt` 污染。

harness 新增 `-Dbench.filter==名字`（前导 `=` 表示全等匹配）。

## 2. 剖析发现（修正后的干净版图）

| 发现 | 定性 | 处置 |
|------|------|------|
| ctx-fill 真实噪声计算仅 ~38%，ForkJoinPool 调度 ~60%（隔离、空池 worst-case） | 生产预生成时池饱和、开销被摊销；bench 单区块放大 | **R17 候选**（CountedCompleter 化，需谨慎设计） |
| **所有"2D"CNG 噪声实际走 3D 内核**：`getNoise2` 调 `generator.noise(x, z, 0)`，layers-gen 的 GradCoord3D 占 43.7% | 换 2D 内核数值必变——**红线内不可动**，记录为永久禁区 | 文档封存 |
| layers-gen：`IrisBiomePaletteLayer.get` 占分配 68.7%（CNG varargs double[3]/块 + 每块 `new RNG`） | 真实生产成本（每列 × 256） | **本轮修复** |
| object-place：`EntryIterator` 每块分配 SimpleEntry+BlockVector；stilt 循环 keys()+get(g) 双查 | 真实生产成本（每棵树） | **本轮修复** |
| deposit：VectorMap.forEach 每块 resolve 分配 75.7% 样本权重 | bench 中逃逸分析已消除（B/op 持平佐证）；EA 失败的复杂消费者仍受益 | **本轮修复**（防御性） |
| decorator：getDecorator 17.6%、getBlockData 字符串哈希+Objects.equals | peek-first 缺失部分为真；字符串部分是 Bukkit 代理桩伪影（生产 CraftBlockData.getMaterial 为字段读） | peek-first 部分**本轮修复** |
| biome-height 69% HashMap.getNode | 纯代理伪影（`xg.getHeight()` 走 JDK 代理的 Map），生产为字段读 | 不动 |
| matter-roundtrip：DataBits VarHandle volatile get/set ~17% | 跨线程可见性语义=红线相邻 | 不动 |

## 3. 改动清单（全部位级等价）

| # | 位置 | 改动 |
|---|------|------|
| 1 | `VectorMap` | O(1) 维护式 `size()/isEmpty()`（原为每次 stream 全扫）；坐标 `get(int,int,int)` 重载；`forEachCoords`（零分配坐标消费）；`CursorIterator`（复用游标，遍历序与 EntryIterator 逐一相同） |
| 2 | `IrisObject.place` | 主循环与 stilt 循环改游标迭代（每块 SimpleEntry+BlockVector → 0）；复用一个可变 BlockVector 作 states/markers/rotate 的只读键；无 tile-state 对象整块跳过 states 查找 |
| 3 | `IrisDepositModifier.generate` | `forEach` → `forEachCoords`（每块 BlockVector → 0） |
| 4 | `IrisBiome` 三个 layer 生成（surface/sea/ceiling） | **每层单 RNG** 替代每块 `nextParallelRNG(i+j)`：`nextParallelRNG` 不消耗源 RNG，且 palette 层只在 j=0（种子恰为 `sx+i`，与提升后完全相同）或永不消费 rng——位级等价；layer/zoom 提升出块循环 |
| 5 | `CNG` | 固定元数 `fit(List, x, y, z)` 重载：varargs 形态每块分配 double[3]；同一噪声路径、无数组。3 个调用点（palette layer、decorator ×2）经重载解析自动切换 |
| 6 | `IrisDecorator` ×5、`IrisNoiseGenerator.getGenerator` | AtomicCache peek-first（aquire 调用点即使命中也分配捕获 lambda；R13 模式扩展到每采样/每列站点） |

## 4. plate-io golden 验证缺口（R15 遗留）修复

全套验证 42/43 一致、plate-io 不匹配。追查发现 **R15 的隔离跑 digest（`6df3c7b0...`）当时就与
golden（`be089257...`）不一致**——R15 只对比了 golden 与全套跑（同进程批次来源），缺口未被暴露。

根因（双重，均属格式设计而非缺陷）：
- `MatterHeader.createdAt = M.ms()`——**每个 Matter section 序列化时烙入墙钟时间戳**，
  整盘字节跨进程必然不同；
- `Matter.writeDos` 按 `getSliceTypes()`（`HashMap<Class<?>,...>`，Class 身份哈希）顺序写
  slice，多 slice section 的字节顺序随进程漂移。

修复（纯 bench 侧）：digest 改为**顺序无关的规范化内容证明**——重放坐标遍历读回 plate，
每 section 单独 `writeDos` 取 FNV（本场景每 section 恰好单 slice，section 内字节进程稳定；
createdAt在读回副本上固定为 0），排序后折叠。**双独立进程验证同一 digest
（`e58bba3d...`），全套上下文第三进程再次复现**；验证粒度从"整盘一次"强化为"每 section 一次"
（300 个/ op）。golden plate-io 行已更新。

## 5. 结果

### 5.1 正确性（红线）

- 全套（round16-a/b 两遍）：**43/43 场景 digest 位级一致**。
- 10 个受影响/参照场景隔离跑（3+9）：**9/9 迭代逐位一致**（两版本各一遍）。

### 5.2 隔离 A/B（git worktree 于 R15 基线 vs 本轮，中位数取后 5/9）

| 场景 | R15 ns/op | R16 ns/op | 提速 | R15 B/op | R16 B/op |
|------|----------:|----------:|-----:|---------:|---------:|
| layers-gen | 1124.4 | 1031.9 | **1.09×** | 690.9 | **211.0（-69%）** |
| object-place-stilt | 35265.6 | 32069.9 | **1.10×** | 87280.0 | **56512.0（-35%）** |
| object-place | 24982.4 | 24934.7 | 1.00× | 63568.0 | **53704.0（-16%）** |
| deposit-place | 3431.9 | 3389.2 | 1.01× | 1095.4 | 1095.4（EA 已覆盖） |
| decorator-decorate | 178.6 | 186.1 | 0.96×* | 251.1 | 219.7（-12%） |
| decorator-select | 86.1 | 87.7 | 0.98×* | 48.0 | 48.0 |
| biome-height / terrain-col-fill / ctx-fill / cave-carve（参照） | — | — | 0.97-1.00×（噪声带） | — | — |

\* decorator 两场景单进程波动 ±10%+（本会话内多次进程间 178→306ns 都出现过），判定持平；
分配减半是确定性的。

### 5.3 全套表（round15-b vs round16-b）

全套上下文给出了更大的方向性差（decorator-decorate 1.53×、deposit 1.47×、object-place
1.22×、matter-roundtrip 1.16×……），但**未触及场景同样出现 ±20% 漂移**（par-worldcache2d-raster
0.50×、flag-raise 1.22× 等）——全套数字只作方向参考，**计时结论以隔离表为准，分配结论
（ThreadMXBean 确定性计数）两表一致**。此为 R14 已确立的 JIT 画像方法论的重申。

### 5.4 生产收益框架

- 每块分配的确定性削减（layers −69%、stilt −35%、object −16%、decorator −12%）直接转化为
  服务器端 GC 压力下降（预生成吞吐的隐性税）；
- deposit 的 BlockVector 在 bench 中被逃逸分析消除，但 EA 覆盖随调用点复杂度/内联深度退化，
  零分配形状对内联退化免疫（R13 同理）；
- peek-first 7 站点消除命中路径 lambda 分配，同样对内联退化免疫。

## 6. 负结果与禁区（防未来误优化）

1. **2D 噪声换 2D 内核**：数值不等价（3D-simplex(z=0) ≠ 2D-simplex），红线内永久禁区。
   若未来要动，必须作为"地形外观变化"显式公告并重制 golden。
2. **DataBits volatile 降级**：Matter 跨线程读写依赖可见性，红线相邻，不动。
3. **ctx-fill 编排**：隔离 60% 调度开销主要是空池 worst-case；生产饱和池下摊销。任何
   CountedCompleter 化必须先建立"饱和池"基准形状再评估（R17 候选）。
4. **bench 代理伪影清单**（生产无此成本，勿据 bench 优化）：Bukkit 代理 getMaterial 的
   HashMap 查找（perfection 66%、deposit 44% 样本）、Engine 代理 getHeight 的 Map 查找
   （biome-height 69%）、代理 clone 的字符串往返（object-place ~30%）。

## 7. 工具沉淀

- `benchmark/prof.sh <filter> <iters> <out.jfr>`：单场景 JFR 记录（`=名字` 精确匹配）
- `benchmark/prof-analyze.py <dir>`：叶帧/首 Iris 帧/分配聚合
- `benchmark/compare-iso.py`：隔离 A/B 中位数 + digest 一致性
- `-Dbench.filter==名字`：harness 精确匹配（防名字互含污染）

## 结论

- 重剖析方法落地并自我纠错两次；R15 的 plate-io golden 缺口被封死（根因：createdAt 墙钟
  + Class 哈希 slice 序，格式设计使然）。
- 每块/每条目分配清扫完成：**43/43 位级一致**，layers-gen 1.09×/-69% B、stilt 1.10×/-35% B、
  object-place -16% B，7 个 peek-first 站点，deposit 防御性零分配化。
- 下一轮候选：ctx-fill 编排 CountedCompleter 化（先建饱和池形状基准）、服务器端到端实测
  （唯一大缺口）。
