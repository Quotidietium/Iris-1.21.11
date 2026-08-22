# 性能优化 · 第 10 轮:沉积放置写路径(IrisDepositModifier.generate / B.toDeepSlateOre)

**日期**:2026-08-23 · **分支**:`perf/optimization`
**环境**:JDK 25 · 32 逻辑处理器 · **33 → 34 场景**(deposit-place,A/B 自证后并入 golden)

## 本轮主旨

沉积(矿石/矿床团块)是地下方块写的主要来源:每区块对 dimension/region/biome 三层沉积生成器
各执行一轮 `generate`,每个团块的每个方块都要走 VectorMap 查找 + 双 hunk 读 +
深板岩转换。本轮审计发现一处数量级级浪费:

**`B.toDeepSlateOre` 对每个转换方块调用 `Material.values()`(每次克隆 ~2000 槽引用数组,~8KB)
+ `Material.createBlockData()`(服务器上还需解析方块状态)**。深板岩层(y<0)恰是绝大多数
矿石沉积的位置——A 侧实测 **每 op 分配 392KB**(每团块 ~48 个转换块 × 8KB 数组克隆)。

## 新场景

| 场景 | 配置 | digest 证明 |
|------|------|------------|
| `deposit-place` | 真实 `IrisDepositModifier.generate`(Engine 代理 getHeight=256,真实 MantleChunk,16×256×16 hunk 预填:sub-64 全 DEEPSLATE,以上 STONE;高度网格 ~100-110;3 种矿石调色板,2-4 团块/op) | 每次写入的坐标+材质序(含深板岩转换后的材质,直接证明转换表逐位一致) |

- 每次迭代前经**非监听**基础 hunk 重置地形层,保证转换路径持续被触发(~半数放置块落在深板岩层)。
- 团块几何由 `IrisDepositGenerator` 的 AtomicCache 生成一次(lombok @Data 的内容哈希做种子,跨运行确定)。

## 改动清单(3 个生产文件)

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `B.toDeepSlateOre` | `Int2IntMap` 序数映射 + 每次调用 `Material.values()`+`createBlockData()` → 类初始化时急切构建的 `BlockData[]` 序数表(静态初始化安全发布,零每调用分配) | 映射内容与原 8 对材质逐一相同;containsKey ⟺ 表槽非 null;共享 BlockData 实例与既有调色板缓存共享实例的放置模式一致(Iris 路径不改放置后的 BlockData) |
| 2 | `IrisDepositModifier.generate` 团块写循环 | `keys()` 迭代 + 每块 `get(j)`(VectorMap.get 每次 2 个 Key 分配+双段查找)→ `forEach((j, ore))` 单遍历;hunk 双读(基岩检查+转换入参)→ 局部 `cur` 单读;`getEngine().getHeight()` 每块调用 → 循环外提升;`isReplaceBedrock()` 提升 | HashMap 的 keys()/forEach 遍历同一 table 序,写序不变(digest 证明);两次 hunk 读之间无修改,单读值恒等;getHeight/replaceBedrock 在单次 generate 内是常量 |
| 3 | `IrisDepositGenerator.nextBlock` | `getBlockData(rdata)` 双读 → 局部单读 | 缓存值不变;RNG 消费序列不变 |

## 结果(隔离与全套双重,12 迭代,后 5 次稳态中位数)

| 条件 | A ns/op | B ns/op | 提升 | A B/op | B B/op |
|------|--------:|--------:|-----:|-------:|-------:|
| 隔离跑(干净 JIT) | 35 474 | **4 409** | **8.04×** | 392 062 | **2 433(-99.4%)** |
| 全套跑(污染 JIT) | 42 234 | **5 230** | **8.08×** | 393 048 | **2 503(-99.4%)** |

- **digest 34/34 位级一致**(红线;隔离 9 迭代与全套 12 迭代全量校验)。
- 两种画像下提升一致(~8×)——与 R9 不同,本轮优化不依赖内联质量:`Material.values()` 的
  数组克隆与 `createBlockData` 是无条件成本。
- **生产收益预计高于离线测量**:桩的 `createBlockData` 只是 matchMaterial+代理;Paper 服务器的
  CraftBlockData 构造(状态解析)每块可达数百 ns~µs,全部被急切表消除。
- 该修复同时惠及 `IrisCarveModifier` 的两处 `toDeepSlateOre` 调用(洞穴雕刻顶/底板)。

## 十轮累计(vs round0 基线)

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
| 装饰器选择 / 表面装饰 | **1.11-1.13× / 0.99-1.14×**,分配 -19~-53% |
| 沉积放置(矿床团块) | **8.08×**,分配 **-99.4%** |

## 结论与后续

- `Material.values()` 在每方块热路径上曾是数量级级的隐藏分配源;类初始化急切表一次解决。
  剩余成本结构:VectorMap.forEach 每块的 BlockVector 解析(1 alloc)、双 hunk 读写、
  MatterCavern 查询——语义必需或已接近下限。
- R11 候选:`Matter.java` 的同款 keys()+get() 双查(对象→Matter 加载,加载期路径);
  `IrisCarveModifier` 洞穴写循环(本轮已间接受益于 toDeepSlateOre);
  VectorMap 键原始化(面广,风险高)。**用户服务器端到端实测仍是最大缺口**——
  沉积路径 -99.4% 分配应直接反映在生成期 GC 停顿的下降上。
