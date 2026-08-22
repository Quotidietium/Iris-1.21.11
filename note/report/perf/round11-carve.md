# 性能优化 · 第 11 轮:洞穴雕刻双向路径(写:MantleWriter 形状栅格 / 读:IrisCarveModifier)+ Matter 切片热路径

**日期**:2026-08-23 · **分支**:`perf/optimization`
**环境**:JDK 25 · 32 逻辑处理器 · **34 → 36 场景**(cave-carve、carve-modify,A/B 自证后并入 golden)

## 本轮主旨

洞穴是地下生成的另一半:写入侧每个洞穴由 worm 游走生成路径点,每个点膨胀
(girth³ 量级的格点栅格)、噪声遮罩后逐格写入 MatterCavern;读取侧
`IrisCarveModifier.onModify` 每区块遍历全部洞穴格,做 4 邻接检查、按列装配
CaveZone、逐列铺墙/顶底装饰。本轮沿"carve 流水线双向"审计,并用 JFR 分配
采样定位到一处跨切面浪费(**IrisMatter.slice 每次调用分配捕获 lambda**),
其影响面覆盖所有 Matter 读写(沉积、对象、洞穴……)。

### JFR 分配画像(carve-modify 场景,A 侧,按权重)

| 分配源 | 采样权重 | 归属 |
|--------|---------:|------|
| `IrisMatter$$Lambda`(slice 的 computeIfAbsent 捕获 lambda) | **2453 MB(57%)** | 每次切片访问分配,即使命中 |
| `Long.valueOf` ← PaletteHunk.iterateSync(内联) | 622 MB | A 侧 positions 逐块装箱(本轮消除) |
| `ConcurrentHashMap$Node` ← CHM.put | 384 MB | walls/positions 的 KMap 节点 |
| `IrisPosition` ← PaletteHunk.iterateSync(内联) | 378 MB | walls 墙格分配(顺序敏感,保留) |
| `ConcurrentHashMap` resize/treeify 系列 | ~400 MB | 每次调用新建 KMap 的增长抖动(顺序敏感,保留) |
| `CaveZone` | 129 MB | 区域装配(结构性,保留) |

## 新场景

| 场景 | 配置 | digest 证明 |
|------|------|------------|
| `cave-carve` | 真实 `IrisCave.generate`(真实 Mantle + MantleWriter,Engine/EngineMantle 为 JDK 代理;worm maxDistance 96 / maxIterations 128,girth 3-5;writer 半径 3 → 13×13 区块)。每 op 后把 writer 界内全部区块的 MatterCavern 切片迭代折叠进 digest 再删除(终态 digest,恒定规模) | 每次雕刻的全部 MatterCavern 格(坐标 + cavern 标志 + customBiome 哈希 + 液体类型) |
| `carve-modify` | 真实 `IrisCarveModifier.onModify`(真实 Mantle 预填水球 + 干隧道洞穴格;16×80×16 hunk 四带雕塑:空气早退/水体填充/流体跳过/洞穴空气写入;SHORT_GRASS 装饰块触发 processZone 清除写) | hunk.listen 全部写序(坐标+材质);M.r() 门控的 mantle marker 不可定种子,刻意不进 digest |

- 离线解锁:完整 `Mantle`(kotlin-stdlib 进入基准类路径,MultiBunt/CoroutineDispatcher 类链接需要);`ChunkContext` stub 增加 fluid 预填钩子。

## 改动清单(6 个生产文件)

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `IrisMatter.slice` | `computeIfAbsent(c, 捕获lambda)`(命中也分配 lambda + CHM 函数式读)→ 先 `get`,未命中才构造 + `putIfAbsent`(败者弃用) | 同一 Matter 实例内每类型仍是单一规范切片实例;竞态下胜者实例被后续读取复用,与 computeIfAbsent 的 create-once 语义一致 |
| 2 | `MantleChunk.get(x,y,z,type)` | 读路径 `getOrCreate(y>>4)` → `get(y>>4)` 为 null 直接返回 | 缺失段读作全 null,与"先物化空段再读空切片"恒等;消除读路径物化空 Matter 段 |
| 3 | `MantleWriter.getBallooned` | 每格 3 次 `Math.pow` + `hypot(double...)` varargs 数组 → 每偏移一次的 `sq[]` 备忘表;部分和次序保持 `((dx²+dy²)+dz²)` | 相同 Math.pow 调用、相同入参,仅备忘;加法次序逐位一致 |
| 4 | `MantleWriter.getMasked` | 同上备忘表结构 | 同上 |
| 5 | `IrisCave.generate` / `IrisRavine.generate` | `engine.getHeight(x,z,true)`、`getDimension().getFluidHeight()`、ravine 的 `rsurface`(高度流查询)提出循环——对固定 (x,z) 全部不变 | 纯 getter/流查询幂等,单次求值与 N 次求值同值 |
| 6 | `IrisCarveModifier.onModify` | 逐块 `positions.computeIfAbsent(Cache.key(rx,rz), …)`(装箱 Long + KList + 装箱 Integer)→ 256 槽 `int[][]` 列累加器;迭代后按**首触顺序**重建同一 `KMap<Long,int[]>`;worldTop/height 提升出循环 | 同键集 + 同插入序 → CHM 同表结构 → 迭代序(区域序、M.r 消费序)逐位一致;`Arrays.sort(int[])` 对互异值与 `sort(Integer::compare)` 同序 |

另修复 `MantleWriter.setCuboid` 上游 bug:内层两个循环原先都在 x1..x2 上迭代
(y/z 界被无视)。仓内零调用方,不影响任何已生成地形,仅修正外部插件潜在调用。

## 结果

### 隔离跑(3 预热 + 9 迭代,后 5 次稳态中位数;`-Dbench.filter`)

| 场景 | A ns/op | B ns/op | 提升 | A B/op | B B/op |
|------|--------:|--------:|-----:|-------:|-------:|
| carve-modify | 348 475 | **324 744** | **1.07×** | 148 561 | **52 849(-64.4%)** |
| cave-carve | 49 878 | 51 343 | 0.97×(噪声内) | 32 992 | 31 824(-3.6%) |

- cave-carve 写侧的 pow/varargs 消除在离线画像下无净收益:HotSpot 对
  `hypot` 内联后 varargs 数组被标量替换、`Math.pow(d,2)` 走 intrinsic 快路径。
  改动保留(同值备忘,服务端画像无回退风险,分配 -3.6%)。
- carve-modify 的 -64.4% 分配几乎全部来自 IrisMatter.slice(2453MB 采样权重
  → 0)与 positions 装箱链(622MB → 0)。

### 全套跑(36 场景同 JVM,污染 JIT 画像,3 预热 + 5 迭代中位)

| 场景 | A → B ns/op | 提升 | 分配 |
|------|-------------|-----:|-----:|
| carve-modify | 312 201 → 300 543 | 1.04× | **-64.4%** |
| cave-carve | 72 722 → 63 140 | 1.15× | -13.5% |
| deposit-place(外溢收益) | 5 374 → 4 726 | **1.14×** | **-56.6%** |
| mantlechunk-set(外溢) | — | ~1.0× | **40 → 16 B/op(-60%)** |
| matter-roundtrip(外溢) | 757.6 → 713.4 µs | 1.06× | ~0 |

- **digest 36/36 位级一致**(红线;全套 5 迭代 + 隔离 9 迭代全量校验)。
- object-place 全套 0.883× 复跑排除:同二进制两次隔离跑自身相差 6%
  (27.1 ↔ 28.8 µs),A 隔离 27.2 µs、B 隔离 27.1/28.8 µs —— 判定为 JIT 画像
  伪回归(R9 方法论),分配逐位相同。
- 生产收益预计高于离线:IrisMatter.slice 修复作用于**每个** Mantle 读写
  (沉积放置 -56.6% 分配即为外溢证据);服务器上 Matter 访问次数远高于基准。

## 十一轮累计(vs round0 基线)

| 指标 | 累计效果 |
|------|---------|
| 热点路径分配(R1-R8 口径,单线程合计) | 18818 → ~3400 B/op(-82%),主路径 0 B/op |
| implode 子群系选择 | **11.4×** |
| cache2D 命中路径 | 18.8 → **6.7 ns/get**;8 线程 raster **5.9× 聚合** |
| 地形列填充(典型配置) | **1.55×** |
| HyperLock 加解锁 | **26.8×**,0 B/op |
| 调色板容器写 / Mantle 写链 | **2.62× / 1.67×** |
| Matter 段序列化往返 | **1.26×**,-64% 分配 |
| 对象放置(树,CENTER_HEIGHT) | **2.44×**,-33% 分配 |
| 装饰器选择 / 表面装饰 | **1.11× / 0.99-1.14×**,-19~-53% 分配 |
| 沉积放置(矿床团块) | **8.08×**,**-99.4%** 分配(本轮再 -56.6%) |
| 洞穴修改读取(carve-modify) | 1.07×,**-64.4%** 分配 |

## 结论与后续

- `computeIfAbsent` 的捕获 lambda 在"每次调用都要过一遍"的访问器上是隐性的
  每调用分配——JFR 采样 57% 的权重印证其量级;get-then-putIfAbsent 是零语义
  代价的修法,建议后续审计同类模式。
- carve 读侧剩余成本结构:walls 的 IrisPosition/CHM(迭代序 = RNG 消费序,
  动不得)、4 邻接 mc.get(语义必需)、CaveZone 装配——已接近顺序保持约束下的下限。
- carve 写侧剩余:worm 每步 3 次 CNG fitDouble + 噪声遮罩每格 CNG——语义必需。
- R12 候选:KMap 每 onModify 重建的 resize 抖动(需可复现的顺序等价预容量方案);
  `Mantle.use()` 的 lastUse CHM(37MB 采样);Engine.java 在线更新路径
  (chunk.iterate + grid,需服务器实测);VectorMap 原始化(IrisObject 加载期,
  风险收益比待估)。
