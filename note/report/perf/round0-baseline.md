# 性能优化 · 第 0 轮：基线报告

**日期**：2026-08-22
**分支**：`perf/optimization`（基线提交 `9f7532020`，基于 master `901c7d659`）
**环境**：Windows 10 26200 · JDK 25.0.4 (HotSpot G1) · `-Xms3g -Xmx3g -XX:+AlwaysPreTouch`
**方法**：`benchmark/` 独立 javac 基准（真实 core 源码 + 最小 stub），每场景 3×200k 预热 + 5×1M 测量，报文中取中位数；分配量来自 ThreadMXBean。行为一致性由固定种子（900000）下 64 位 FNV 摘要（golden）保证。

## 基线数据（中位数）

| 场景 | ns/op | B/op | 说明 |
|------|------:|------:|------|
| cng-noise2d | 217.7 | 32.0 | CNG.signature 2D 采样（地形噪声内核） |
| cng-noise3d | 222.9 | 40.0 | 3D 采样（洞穴/矿脉） |
| cng-fractured2d | 284.5 | 32.0 | signatureDouble 断裂链（大陆噪声样式） |
| cng-perlin2d | 80.3 | 32.0 | Perlin 签名链 |
| cng-fit-int2d | 241.1 | 32.0 | fit(min,max,x,z)（层/矿床选择） |
| cng-fitdouble2d | 232.0 | 32.0 | fitDouble（海拔映射） |
| irare-pick | 273.2 | 32.0 | 生物群系稀有度挑选（现代算法） |
| irare-pick-legacy | 246.8 | 80.0 | 稀有度挑选（legacy 算法） |
| implode-fitRarity | 2585.0 | 2320.0 | IrisComplex.implode 的子群系稀有度表重建（每采样） |
| interp-bilinear-starcast6 | 4571.4 | 3492.3 | 高度流默认插值 BILINEAR_STARCAST_6（hs=7） |
| interp-bilinear | 938.3 | 976.0 | 纯双线性 |
| interp-hermite | 3743.1 | 2800.0 | 厄米插值 |
| worldcache2d | 13500.8† | 8894.2† | cache2D 底层（†每外层操作含 64 次内层 get，约 211 ns/get） |
| rng-column | 21.7 | 24.0 | 每列 RNG（装饰器模式 new RNG(Cache.key)） |

## 热点证据（代码级）

1. **CNG `noise(double...)` varargs**：每次采样分配 `double[]`（32B/op 实测），且每次调用 `hits += oct` 递增公共静态计数器（跨核缓存行争用，仅统计用途、无消费者）。`CNG.java:488-508`。
2. **`IrisInterpolation.getNoise` 每调用分配**：`new HashMap<>(64)` + `NoiseKey` record + `Double` 装箱 + 多层捕获 lambda（`IrisInterpolation.java:1000-1002`），starcast6 实测 3492 B/op、4.3 µs/op。
3. **`IrisComplex.interpolateGenerators` 双通道重复采样**：hi/lo 两次 `interpolator.interpolate` 在完全相同的采样点各算一遍，另有每次调用新建 `HashMap<NoiseKey,IrisBiome>`（`IrisComplex.java:304-360`）。
4. **`IrisComplex.implode` 每采样重建稀有度映射表**：`getRealChildren().copy()` + `fitRarity` 交替头尾插入重建整个映射列表（`IrisComplex.java:392-407`、`CNG.java:346-386`），实测 2585 ns/op、2320 B/op。
5. **`getNoise3D`（Hunk 批量版）无效缓存**：键 `(k*w*h)+(j*w)+i` 每格唯一，缓存永不命中却全额付出装箱代价（`IrisInterpolation.java:977-988`）。

## 红线与验证

- 所有场景的输出已存入 `benchmark/golden/golden.csv`（固定种子摘要 + 样本数）。
- 每轮优化后必须：`verify.sh` 全部场景 **bit 级一致** 方可合入。
- `build.sh` 兼作全量编译校验（1197 个类，含全部真实 core 源码，仅 14 个离线不可得的第三方集成类被排除并由 stub 遮蔽）。

## 计划

- **第 1 轮（CPU 热点）**：上述 1-4 项 + IRare.pick 预计算权重。
- **第 2 轮（内存/分配）**：围绕 B/op 的系统性削减（装箱、流链中间对象、KList 拷贝）。
- **第 3 轮（并发/缓存）**：MultiBurst/锁/WorldCache2D 并发行为与命中率。
