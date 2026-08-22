# 性能优化 · 第 9 轮:装饰器选择与放置路径(IrisEngineDecorator / IrisDecorator)

**日期**:2026-08-23 · **分支**:`perf/optimization` · **提交**:`953336cb5`(场景+桩)+ 本轮优化提交
**环境**:JDK 25 · 32 逻辑处理器 · **31 → 33 场景**(decorator-select / decorator-decorate,A/B 自证后并入 golden)

## 本轮主旨

装饰是地形与对象放置之外的第三大每区块工作源:每区块 256 列,每列都要走
`getDecorator` 选择循环(遍历 biome 全部装饰器的 partOf 匹配 + 命中噪声评估),
5 个装饰器子类(Surface/Ceiling/SeaSurface/SeaFloor/ShoreLine)共享该循环。
本轮把整条选择与表面放置链纳入基准并优化。

## 设施扩展(先于优化)

1. **JDK 代理 Engine 桩**:`SeedManager`/`IrisData`(临时目录)/`IrisDimension` 全部用真实类,
   只有 `Engine` 外壳是按方法名分派的代理(getCacheID/getSeedManager/getData/getDimension)。
   `IrisSurfaceDecorator` 构造与 `decorate()` 全链真实执行。
2. **桩 Iris.service 解锁**:返回真实 `PreservationSVC`(registerCache 仅追加 WeakReference,
   离线安全),`IrisData` 的 23 个 ResourceLoader 构造链全部打通。
3. **Hunk.listen 摘要**:装饰写入通过真实 `ListeningHunk` 包装折入 digest(坐标+材质序)。
4. **bench.filter 系统属性**:`-Dbench.filter=decorator` 只跑匹配场景,用于隔离 JIT 画像研究。

## 新场景

| 场景 | 配置 | digest 证明 |
|------|------|------------|
| `decorator-select` | 真实 `getRNG`+`getDecorator`,biome 带 6 个装饰器覆盖全部 5 个 partOf(仅 2 个匹配 Surface) | 选中装饰器在列表中的下标 |
| `decorator-decorate` | 完整 `decorate()`(调色板选择、堆叠循环、白名单门、hunk 写入),高度扫过海平面上下 | 每次写入的坐标+材质序 |

## 改动清单(4 个生产文件)

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `IrisBiome`(新) | `getDecorators(IrisDecorationPart)`:partOf 分区缓存(普通 volatile 字段 + 良性竞争惰性初始化,无锁无 lambda 分配) | partOf 测试与坐标无关;分区由 decorators 列表确定(加载后不可变,无任何运行期修改点);竞态各方发布内容相同的数组;partOf 为 null 的非法装饰器照旧跳过并记同文错误(原为每列刷一条,现仅在分区构建时一次) |
| 2 | `IrisEngineDecorator.getDecorator` | ① `new RNG(seed)` 每列分配 → 构造器持有的 `pickerSeedRNG` 字段;② KList 收集 → 局部数组;③ 空分区提前返回 | RNG 仅被 `nextParallelRNG` 消费,该派生从不可变 `sx` 出发且不改 Random 状态,共享逐位一致;`rng.nextInt` 消费点与次数不变(仅命中数>0 时一次);候选顺序=原列表内序,均匀选取同下标 |
| 3 | `IrisDecorator` 三取块方法 | 重复的 palette/tops `AtomicCache.aquire` 读提升为局部变量(3→1 次) | 缓存一旦填充值永不变;调用序列与短路顺序不变 |
| 4 | `IrisSurfaceDecorator.decorate` | ① 白名单/黑名单 `.stream()`(每列分配 spliterator+lambda,且每个元素过一遍 `IrisBlockData.getBlockData` 缓存读)→ 预解析缓存的 `getWhitelistBlockData/getBlacklistBlockData` + 普通循环;② `getDimension().getFluidHeight()` 4 次调用 → 顶部局部变量 | 预解析列表元素与原逐列解析逐元素相等(同一 AtomicCache 语义);equals 接收端方向不变;fluidHeight 在单次 decorate 内是常量 |

## 方法论说明:全套 vs 隔离

本轮 A/B 出现过一个假回归:全套跑(33 场景单 JVM)中 decorator-decorate 曾慢 10-30%,
而隔离跑(`-Dbench.filter`)同一二进制稳定快 14%。定位:全套中 `AtomicCache.aquire`
等共享方法的内联缓存被几十个调用点污染(megamorphic),JIT 编译预算也被摊薄,装饰场景
排位靠后、到达稳态更慢;同时未触碰场景在全套跑内本身就有 ±20-34% 的逐场景波动
(matter-roundtrip 1.34×、par-raster 1.24× 均为同二进制波动)。因此最终报告以
**隔离跑(干净 JIT 画像)为主指标、全套长跑稳态段(后 5 次迭代中位数)为辅**。

## 结果

### 隔离跑(3 预热 + 9 迭代,中位数;A=旧代码 vs B=新代码,同条件同 JVM 参数)

| 场景 | A ns/op | B ns/op | 提升 | A B/op | B B/op |
|------|--------:|--------:|-----:|-------:|-------:|
| decorator-select | 95.6 | **84.7** | **1.13×** | 101.8 | **48.0(-53%)** |
| decorator-decorate | 206.6 | **180.8** | **1.14×** | 418.4 | **340.1(-19%)** |

### 全套长跑稳态(3 预热 + 12 迭代,后 5 次中位数,单 JVM 33 场景)

| 场景 | A ns/op | B ns/op | 提升 | A B/op | B B/op |
|------|--------:|--------:|-----:|-------:|-------:|
| decorator-select | 121.4 | **109.7** | **1.11×** | 311.0 | **192.4(-38%)** |
| decorator-decorate | 263.2 | 265.1 | 0.99× | 613.7 | **444.2(-28%)** |

- **digest 33/33 位级一致**(红线;含 12 迭代长跑的全量校验)。
- 全套稳态中 decorate 时间持平:污染画像下节省的调用/分配被内联恶化抵消,但分配 -28% 直接
  降低装饰阶段 GC 压力;选择循环(5 个装饰器共享、每列必经)在两种画像下均 ~1.1×。
- 生产环境收益结构:场景中 6 个装饰器仅 2 个 partOf 匹配;真实 pack 装饰器更多且多数不匹配
  当前列的 partOf,预过滤节省随列表变长线性放大。装饰选择对每列最多执行 5 次
  (surface 必经,sea/shore 条件经)。
- 未触碰场景全套 med-ratio:median 0.99(min 0.90 / max 1.40)——该波动带即本轮方法论基线。

## 九轮累计(vs round0 基线)

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配(R1-R8 口径,单线程合计) | 18818 → ~3575 B/op(-81%),主路径 0 B/op |
| implode 子群系选择 | **11.4×** |
| cache2D 命中路径 | 18.8 → **6.7 ns/get**;8 线程 raster **5.9× 聚合** |
| 地形列填充(典型配置) | **1.55×** |
| HyperLock 加解锁 | 422 → **15.8 ns(26.8×)**,0 B/op |
| 调色板容器写 / Mantle 写链 | **2.62× / 1.67×**,分配 -85%/-70% |
| Matter 段序列化往返 | **1.26×**,分配 -64% |
| 对象放置(树,CENTER_HEIGHT) | **2.44×**,分配 -33% |
| 装饰器选择 / 表面装饰 | **1.11-1.13× / 0.99-1.14×**,分配 -38~-53% / -19~-28% |

## 结论与后续

- 装饰路径的每列固定开销(选择循环的 KList/RNG 分配与全列表 partOf 扫描)已收敛:选择循环
  两种画像均 ~1.1×、分配减半;放置主体在干净画像下 1.14×,污染画像持平、分配 -28%。
- 噪声评估(chance 命中判定)是剩余的主要语义成本,不可消除——它是放置位置的确定性来源。
- **方法论资产**:`-Dbench.filter` 隔离跑 + 全套长跑稳态段 + 同二进制波动带校准,可鉴别
  JIT 画像伪回归(R9 全套 decorate 假回归 -10~-30% 即由此定位并排除)。
- R10 候选(按预估收益排序):IrisDepositGenerator 团块几何(palette 反射预灌)、
  `IrisObject.getSigned/VectorMap` 键原始化(BlockVector 32B → int 键)、
  装饰写入链的 Hunk.set 批处理(需评估与 mantle 写的交互)。**用户服务器端到端实测
  仍是验证这九轮离线收益的最大缺口。**
