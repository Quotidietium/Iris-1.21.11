# 性能优化 · 第 22 轮：carve 迭代链原生化 + CaveZone 复用

**日期**：2026-08-25 · **分支**：`perf/optimization`
**环境**：JDK 25.0.4 · 32 逻辑处理器 · **golden 49/49 位级一致**

## 本轮主旨

carve-modify（R18 无锁存储、R19 查找残留后）仍有 52.9 KB/op 分配与
装箱迭代链。JFR 剖析（876 执行样本 / 2833 分配样本）定位两类残留：
迭代链上的 Integer 装箱、positions 消费段的每 zone `CaveZone` 分配。

**前置证伪（上一会话遗留方向）**：VectorMap.forEach/forEachCoords 从
`Map.forEach` 改为 `entrySet().forEach` 的修改经 JDK 25 字节码证据
证伪后回退——`ConcurrentHashMap.forEach(BiConsumer)` 本身就是零分配
表遍历覆盖（Traverser 直接 accept node.key/node.val），调用点静态类型
为 `Map` 不影响运行时分派；而 `EntrySetView.forEach(Consumer)` 的
字节码在每个条目上 `new ConcurrentHashMap$MapEntry`（每 chunk 一个
包装分配）。该修改方向为纯分配恶化，未进入基准即回退。

## 1. 剖析发现（prof22/carve.jfr）

| 发现 | 占比 | 处置 |
|---|---|---|
| `MantleChunk.lambda$iterate$0` leaf | 27.4% 执行 | §2.1 原生迭代链（内联了整个 carve iterator body） |
| CHM Node + Node[] 分配 | 41% 分配样本 | **不动**（walls/positions 遍历序 = rng 消耗序，见 §4） |
| `int[]`（columnYs 初生/grow/精确化） | 26% 分配样本 | **不动**（同上：positions 依赖精确数组语义） |
| `IrisPosition`（walls key） | 15% 分配样本 | **不动**（key 类型决定 CHM 桶布局） |
| `CaveZone`（每 zone 一次） | 7.2% 分配样本 | §2.2 复用 |
| bench 桩伪影（`$Proxy24.getMaterial` 18.4%、`Benchmark$8` 20.0%、`Material.$SWITCH_TABLE` 5.0%） | — | 非生产成本（BlockData 动态代理 + Bukkit 枚举 switch） |

装箱链全貌：`MatterSlice.iterateSync(Consumer4<Integer,…>)` →
`MantleChunk.iterate` 的 `(a, b, c, f) -> iterator.accept(a, b + bs, c, f)`
（每 section 新 lambda，捕获 bs）→ carve iterator 的 `b + bs` 重装箱
（y>127 超出 Integer 缓存）→ iterator body 拆箱使用。分配样本中
`java.lang.Integer` 仅 13 例（y≤127 居多），装箱成本以**执行**为主。

## 2. 实施

### 2.1 原生坐标迭代链

- 新增 `com.volmit.iris.util.function.Consumer4I<T>`：`(int a, int b, int c, T d)`。
- `Hunk.iterateSyncInts(Consumer4I<T>)` default 方法：与
  `iterateSync(Consumer4)` 同循环序、同 null 传递语义（default 不过滤、
  PaletteHunk 过滤、MappedHunk/MappedSyncHunk 走 map 序）——**每个
  覆盖 iterateSync 的存储类对称覆盖 iterateSyncInts**（PaletteHunk、
  PaletteOrHunk、MappedHunk、MappedSyncHunk）。
- `MantleChunk.iterateInts(Class, Consumer4I<T>)`：与装箱版逐行同构
  （section 升序 → slice 序）。独立方法名（非重载）避免 7 处现有隐式
  lambda 调用点的重载歧义，旧调用方零改动。
- `IrisCarveModifier` iterator 切换至原生接口；body 内 xx/yy/zz 由
  Integer 拆箱变为 int。

遍历顺序逐路径不变 → digest 位级一致的构造性保证（PaletteHunk 覆盖
与 default 的 null 语义差异保持各自原有行为；cavern slice 16³=4096
走 palette 分支 = 过滤 null，与旧路径相同）。

### 2.2 CaveZone 复用

positions 消费段（zone 划分循环）从每 run `new CaveZone()` 改为
onModify 级单实例 + 字段重置（`setCeiling(-1)` + `setFloor(v[0])` /
`setFloor(i)`，与 new 后字段的 (-1, floor) 状态逐点相同）。zone 是
顺序处理、processZone 只读 zone，复用不可观测。

### 2.3 明确不动项（顺序锁死）

walls（`KMap<IrisPosition, MatterCavern>`）与 positions
（`KMap<Long, int[]>`）的 forEach 遍历序 = 墙体/zone 的 rng 抽取序。
CHM 遍历序由 key hashCode 的桶布局 + 插入序决定：换 key 类型、换初始
容量（表大小变化）、换数据结构均改变遍历序 → 改变地形输出。R11 的
firstTouch 重建已证明该约束的遵守方式（同 key 集 + 同插入序 = 同表）。
本轮 41%+26%+15% 的分配样本因此**结构性保留**。

## 3. 测量

carve-modify 单场景隔离跑（`-Dbench.filter`，3 预热 + 12 测量）：

| 侧 | 样本 | ns/op | B/op | digest |
|---|---|---|---|---|
| base（round22-a.csv + _prof.csv，同代码两时段） | 17 | 167-204（漂移带） | 52870.1-52875.8 | `103c0a75…` |
| new（iso22n-carve-modify.csv） | 12 | 229-259（漂移带） | **49111.6-49114.1** | `103c0a75…` |

- **B/op：中位数 52873 → 49113（-7.1%），29/29 样本方向一致**（漂移
  免疫指标）。降幅与剖析预测吻合：CaveZone 占分配样本 7.24% ×
  52873 ≈ 3828 B/op，实测差 3760 B/op——分配收益几乎全部来自 §2.2。
- 时间读数：base 代码自身两时段相差 47%（167 vs 204 µs/op），本机
  ~1.2× 双模态漂移期内（R20 方法论），时间净效应不做声明。装箱链
  消除属机制上纯减法（每块 2 次 Integer.valueOf/拆箱 + 一次重装箱），
  但量级低于漂移噪声底。
- **digest：base 17 次 + new 12 次全部 `103c0a75c96a9125`**，位级一致。

## 4. 未触碰项与原因

- `IrisCarveModifier.onModify` 的 columnYs `Arrays.copyOf` 精确化与
  positions `KMap<Long,int[]>`：value 数组的 0 填充尾部会破坏
  `Arrays.sort` + zone 扫描语义（0 是合法哨兵边界），且消除 Long 装箱
  需换 key 类型（顺序锁死）。
- Engine/IrisWorldManager/EngineMantle 的 5 处 `MantleChunk.iterate`
  装箱调用点：每块伴随 Bukkit `getBlock` 落块调用（微秒级），装箱占比
  可忽略；无基准场景覆盖，切换收益为零、徒增验证面。
- `Arrays.sort(v)`（列内 y 已天然升序：PaletteHunk i/j/k 循环序 ×
  section 升序）：删除属幂等优化但收益微小（小数组），且正确性依赖
  「所有 slice 实现遍历序一致」这一前提的逐实现审计，风险收益比不合算。

## 5. 结论

- carve-modify 第三轮（R11 列数组重建 → R18 无锁存储 → R19 备忘 →
  R22 迭代原生化）后分配 52.9→49.1 KB/op；剩余分配 82% 集中在
  walls/positions 两个 CHM（结构性保留，rng 顺序正确性依赖）。
- 装箱迭代链的通用设施（`Consumer4I` + `iterateSyncInts` 五实现 +
  `MantleChunk.iterateInts`）已就位，后续任何 mantle 遍历热路径可
  零成本切换。
- 漂移期方法论（R20 起）第三次适用：B/op 中位数 + digest 双指标，
  时间读数仅在跨时段比值 <±5% 且多场景同向时才做声明。
