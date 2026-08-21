# 性能优化 · 第 1 轮：CPU 热点路径（分配削减）

**日期**：2026-08-22 · **分支**：`perf/optimization` · **提交**：`ca6f701e3`
**对照**：round0-baseline（中位数，5×1M ops，JDK 25）

## 改动清单

| # | 位置 | 改动 | 语义保证 |
|---|------|------|---------|
| 1 | `util/noise/CNG` | 新增 `noise(double[,double[,double]])`、`fit`、`fitDouble`、`fit(List)`、`fitRarityMapped` 定参重载；varargs 版本保留并委托同一数学体 | 定参与 varargs 展开后的表达式**完全相同**（2D 时 dim[1]→generator 第 2 参、第 3 参恒 0 的映射逐行复刻）；基准 digest 逐位一致 |
| 2 | `util/noise/CNG` | 删除每次采样的 `hits += oct` 公共静态计数器递增（无任何读者；跨核缓存行争用） | 统计字段保留（API 兼容），仅停止递增 |
| 3 | `IrisInterpolation.getNoise` | `HashMap<NoiseKey,Double>`+装箱 → 线程内栈式原始类型备忘表（开放寻址、stamp 代际失效、容量 64 溢出直通） | 同一去重语义（键=精确 double 偏移）；嵌套插值各层独立 memo；digest 一致 |
| 4 | `IrisInterpolation.getNoise3D`(Hunk 批量) | 删除键唯一的永不着缓存（纯装箱开销） | 直接调用，结果不变 |
| 5 | `IrisComplex.interpolateGenerators` | hi/lo 两遍采样合并为单遍（每次访问同时累计 max/min 和，lo 存入本次调用局部表，第二遍零重采样） | 两遍插值的采样点与权重本就相同；lo 之和由同样的 `getGenLinkMin` 序列累加；digest 层面等价（引擎路径，见下） |
| 6 | `IrisComplex.implode` | 每采样重建稀有度映射表 → 按父生物群系 `ConcurrentHashMap` 缓存（`CNG.buildRarityMap` 提取） | 映射只依赖加载后不可变的子列表与 rarity；hotload 重建整个 IrisComplex 即失效；基准实测新旧摘要相同 |
| 7 | `IRare.stream` | `RareTable` 预计算权重（1/rarity 与减法表，构建期一次除法，顺序与原逐次除法一致） | 同除法、同累加顺序 → 位级一致；`SELECTOR` 插值助手提为常量 |

## 数字（中位数）

| 场景 | ns/op 基线 → 新 | 提速 | B/op 基线 → 新 | 分配降幅 |
|------|----------------|------|----------------|---------|
| cng-noise2d | 217.7 → 207.9 | 1.05x | 32 → **0** | 100% |
| cng-noise3d | 225.6 → 212.0 | 1.06x | 40 → **0** | 100% |
| cng-fit-int2d | 241.1 → 231.9 | 1.04x | 32 → **0** | 100% |
| irare-pick | 273.2 → 245.9 | 1.11x | 32 → **0** | 100% |
| irare-pick-legacy | 246.8 → 246.9 | 1.00x | 80 → **0** | 100% |
| **implode-fitRarity-cached**（生产路径） | 2585 → **227** | **11.4x** | 2320 → **0** | 100% |
| interp-bilinear-starcast6 | 4319 → 4265 | 1.01x | 3492 → **40** | 98.9% |
| interp-bilinear | 879 → 885 | 0.99x | 976 → **16** | 98.4% |
| interp-hermite | 3743 → 3891† | 0.96x† | 2800 → **16** | 99.4% |
| worldcache2d | 13501 → 13646† | 0.99x† | 8894 → 6848 | 23.0% |

† 单线程 ±5% 抖动范围内；分配削减才是本轮主体收益——在多线程真实服务器上，热点路径 GB/s 级分配的消失直接转化为 GC CPU/停顿的下降。

## 验证

- `verify.sh golden.csv round1-cpu.csv`：**15/15 场景 digest 位级一致**（含新增 `implode-fitRarity-cached`，其 digest 与逐次重建路径相同，自证缓存选择不变）。
- `build.sh` 全量编译 1200 个类通过（等价于全 core 编译校验）。
- 引擎级路径（interpolateGenerators/implode）无法在无服务器环境运行，但其等价性由"同表达式同顺序 + 选择表缓存内容相同"的构造性论证 + 纯逻辑场景 digest 佐证。

## 遗留（进入第 2 轮）

- interp 场景残留 16-40 B/op（星射/双线性包装 lambda 捕获分配）→ 适配器对象复用。
- `interpolateGenerators` 每调用 `HashMap<NoiseKey,double[]>` → 原始类型对缓存。
- `ChunkCache2D` 构造器预分配 256 个 Entry/流/区块 → 惰性化（现有 CAS 路径已支持）。
- `WorldCache2D` 每次 get 的 Long 装箱 → 单槽 key 备忘。
