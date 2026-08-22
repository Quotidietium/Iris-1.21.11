# 性能报告索引（note/report/perf）

| 报告 | 内容 |
|------|------|
| [round0-baseline.md](round0-baseline.md) | 基线测量 + 代码级热点证据 + 方法论 |
| [round1-cpu-hotpath.md](round1-cpu-hotpath.md) | CPU 热点：CNG 定参/插值备忘/implode 缓存（11.4×）|
| [round2-memory.md](round2-memory.md) | 内存/分配：合计 -75.2%，3D 0 B/op |
| [round3-concurrency.md](round3-concurrency.md) | 并发/共享缓存：Caffeine、并行扩展 2.3×→3.6× |
| [round4-datastructures.md](round4-datastructures.md) | 数据结构/装箱：条带原始键缓存重写，命中 2.8×、8 线程 raster 62.8×、分配再 -53% |
| [round5-terrain-actuator.md](round5-terrain-actuator.md) | 地形执行器：列循环不变量提升 + 矿石预检，1.55×/列 |
| [round6-dispatch.md](round6-dispatch.md) | 派发扁平化：<1%（当时的结论——离线可测面收敛） |
| [round7-matter-mantle.md](round7-matter-mantle.md) | Matter/Mantle 存储层 + HyperLock：推翻"需服务器"判断（Bukkit 代理桩），锁 **26.8×**、容器写 **2.62×**、序列化往返 **1.26×/-64% 分配** |
| [round8-object-place.md](round8-object-place.md) | 对象放置写路径：IrisObject.place 五层防御克隆链塌缩——树放置 **2.44×**、stilt **2.11×**，分配 -33%/-41% |
| [round9-decorator.md](round9-decorator.md) | 装饰器路径：partOf 分区缓存 + gRNG/选择循环零分配化 + 白名单流消除——选择 **1.11×**、分配 -38%~-53%；JIT 画像伪回归鉴别方法 |
| [round10-deposit.md](round10-deposit.md) | 沉积放置写路径：toDeepSlateOre 急切 BlockData 表（消除每块 Material.values() ~8KB 克隆）+ VectorMap 单遍历 + hunk 双读提升——**8.08× / -99.4% 分配** |

- 原始数据:`benchmark/results/round*.csv`(每场景 5 次测量)
- 金样本:`benchmark/golden/golden.csv`(**34 场景**固定种子摘要,行为一致性的判定基准)
- 复现:`bash benchmark/build.sh && bash benchmark/run.sh <csv> 3 5 && bash benchmark/verify.sh benchmark/golden/golden.csv <csv>`
- 隔离跑(排除 JIT 画像污染):`java -Dbench.filter=<子串> ... bench.Benchmark <csv> 3 9`(见 round9 方法论节)
- 版本改动汇总：[../../release/3.9.3-1.20.1-1.21.11.md](../../release/3.9.3-1.20.1-1.21.11.md)
