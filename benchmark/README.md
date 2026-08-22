# Iris 纯逻辑性能基准（benchmark/）

对 Iris 引擎中**不依赖 Bukkit 服务器运行时**的热点路径做可重复的性能测量，
用于量化每轮优化的收益，并通过**金样本摘要（digest）**保证行为（地形输出）逐位一致。

## 组成

| 路径 | 说明 |
|------|------|
| `build.sh` | 用 javac 将 **真实 core 源码**（除 14 个离线不可编译的第三方集成类）+ 少量 stub + 本目录 harness 一起编译。**兼作全量编译校验** |
| `run.sh <csv> [warmup] [iters]` | 运行基准（默认 3 次预热 + 5 次测量），输出 CSV |
| `verify.sh <a.csv> <b.csv>` | 比对两份 CSV 第 0 轮（固定种子 900000）的 digest，必须完全一致 |
| `stubs/` | 仅遮蔽无法独立编译的类：插件引导（Iris）、Kotlin 类（ChunkContext/FlaggedChunk/MatterGenerator/脚本环境等）、离线不可得的第三方 API（paralithic/MultiverseCore 等）。stub 的默认值与真实代码一致 |
| `src/bench/Benchmark.java` | 21 个场景：CNG 噪声 2D/3D/断裂链/Perlin、fit 选择、IRare 生物群系挑选（现代/legacy）、implode 稀有度（重建/缓存）、2D 插值×3、3D 插值×2、WorldCache2D（全 miss/命中）、逐列 RNG、并行×3（8 线程共享 CNG/共享缓存散点/raster） |
| `src/bench/Verify3D.java` | 3D 适配器 vs 原 lambda 链的 60 万采样 A/B 等价性证明 |
| `results/` | 各轮原始 CSV（提交进库） |
| `golden/` | 金样本摘要快照（it=0 digest） |

## 方法论

- 每场景：3 轮预热（20 万次操作）+ 5 轮测量（100 万次操作），取中位数。
- 指标：`ns_per_op`（耗时）、`bytes_per_op`（ThreadMXBean 线程分配字节）。
- JVM 固定参数：`-Xms3g -Xmx3g -XX:+AlwaysPreTouch`，JDK 25。
- 并行场景：`B/op` 仅统计主线程（ThreadMXBean 限制，无参考意义），耗时指标为墙钟；场景方差约 ±15%。
- **回归防护**：每轮测量的全部输出折叠进 64 位 FNV-1a digest；第 0 轮使用固定种子，
  任何代码改动后 digest 必须与基线一致，否则判定为改变了地形行为（红线）。
- 坐标/种子由 `java.util.Random` 生成（算法规范固定，跨 JDK 稳定）。

## 离线依赖

`lib/` 下的 jar 不入库（见 `.gitignore`），用 `fetch-libs.sh` 重新下载；
`spigot-api` 与 `paper-api` 需从本地 Gradle 缓存或 SpigotMC 仓库获取（见 `lib.list`）。

## 典型流程

```bash
bash benchmark/build.sh                       # 编译（兼全量编译校验）
bash benchmark/run.sh benchmark/results/roundN.csv 3 5
bash benchmark/verify.sh benchmark/golden/golden.csv benchmark/results/roundN.csv
```
