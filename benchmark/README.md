# Iris 纯逻辑性能基准（benchmark/）

对 Iris 引擎中**不依赖 Bukkit 服务器运行时**的热点路径做可重复的性能测量，
用于量化每轮优化的收益，并通过**金样本摘要（digest）**保证行为（地形输出）逐位一致。

## 组成

| 路径 | 说明 |
|------|------|
| `build.sh` | 用 javac 将 **真实 core 源码**（除 14 个离线不可编译的第三方集成类）+ 少量 stub + 本目录 harness 一起编译。**兼作全量编译校验** |
| `run.sh <csv> [warmup] [iters]` | 运行基准（默认 3 次预热 + 5 次测量），输出 CSV |
| `verify.sh <a.csv> <b.csv>` | 比对两份 CSV 第 0 轮（固定种子 900000）的 digest，必须完全一致 |
| `stubs/` | 仅遮蔽无法独立编译的类：插件引导（Iris，`initialize` 为 classpath 扫描实现）、离线不可得的第三方 API（paralithic/MultiverseCore 等）、`org.bukkit.Bukkit`（`createBlockData` 返回 **BenchBlockData 字段读具体类**——R29 起替代 JDK 动态代理，代理的反射分发曾占 object-place 执行样本 ~40%，语义逐方法对等故 digest 不变——解锁 Matter/Mantle 存储层的离线测量）。第 14 轮起引擎的 ChunkContext/ChunkedDataCache/FlaggedChunk/MatterGenerator 已 Java 化，**真实实现直接参与编译与测量**（stub 已删）。stub 的默认值与真实代码一致 |
| `src/bench/Benchmark.java` | 43 个场景：CNG 噪声 2D/3D/断裂链/Perlin、fit 选择、IRare 生物群系挑选（现代/legacy）、implode 稀有度（重建/缓存）、2D 插值×3、3D 插值×2、WorldCache2D（全 miss/命中）、逐列 RNG、地形列填充（legacy/新路径，真实 IrisBiome/Region/Dimension）、并行×3（8 线程共享 CNG/共享缓存散点/raster）、Matter/Mantle 存储层×4（DataContainer set/get、MantleChunk 写链、16³ Matter 序列化往返）、HyperLock×2（单线程命中、8 线程同键争用）、对象放置×2（IrisObject.place 主循环/STILT 循环，真实 IrisObject+IrisObjectPlacement+记录型 placer）、装饰器×2、沉积、洞穴雕刻×2、后处理、群系高度、铺层、**区块上下文预填充×2（新行任务路径 vs 旧协程编排复刻 `OldContextFill`——真实 kotlinx-coroutines）**、标志位提升、TectonicPlate 写读盘往返 |
| `src/bench/Verify3D.java` | 3D 适配器 vs 原 lambda 链的 60 万采样 A/B 等价性证明 |
| `src/bench/VerifyMemoryBound.java` | R26 内存有界性证明：真实 Mantle+MantleWriter 的持续热预生成扫掠 + EngineSVC 节奏 trim/unload 驱动；断言硬上限（loaded≤limit+2）、settle 零钉住（引用泄漏端到端审计）、堆平台（无 ratchet）。`-Diris.mantle.hardcap=false` 臂复现 R26 前的线性驻留增长。用法：`java -cp <同run.sh> bench.VerifyMemoryBound [limit] [sweepWidthChunks] [sweepDepthChunks]` |
| `src/bench/VerifyBurstException.java` | R27 BurstExecutor.complete 异常语义回归证明：屏障 join-all、首失败传播（类+cause 链——FJP 反射重建丢身份）、幂等重试、单核内联身份保留。用法：`java -cp <同run.sh> bench.VerifyBurstException` |
| `results/` | 各轮原始 CSV（提交进库） |
| `golden/` | 金样本摘要快照（it=0 digest） |

## 方法论

- 每场景：3 轮预热（min(20 万次操作, 场景 ops)——重场景按其规模预热）+ 5 轮测量（按场景 ops，默认 100 万），取中位数。
- 指标：`ns_per_op`（耗时）、`bytes_per_op`（ThreadMXBean 线程分配字节）。
- JVM 固定参数：`-Xms3g -Xmx3g -XX:+AlwaysPreTouch`，JDK 25。
- 并行场景：`B/op` 仅统计主线程（ThreadMXBean 限制，无参考意义），耗时指标为墙钟；场景方差约 ±15%。
- **回归防护**：每轮测量的全部输出折叠进 64 位 FNV-1a digest；第 0 轮使用固定种子，
  任何代码改动后 digest 必须与基线一致，否则判定为改变了地形行为（红线）。
- 坐标/种子由 `java.util.Random` 生成（算法规范固定，跨 JDK 稳定）。
- **JDK 必须固定**（2026-08-25 事故）：`decorator-decorate`/`layers-gen` 两个噪声密集场景的
  digest 对 `Math` 三角函数实现的微差异敏感。`java` 若经 Oracle `javapath` 垫片解析，
  自动更新会静默切换运行时，导致 digest 漂移（当时误报为代码回归，跨 5 个提交重建复跑
  证伪后重建基线）。跑 digest 基线/验证前先 `java -version` 确认构建号未变，或用绝对路径
  指向固定 JDK。
- **双环境态 digest（2026-08-25 同日二次翻转）**：上述两场景的 digest 存在两个稳定态
  （`fc83d904…/f7eb17a7…` 与 `784ea6be…/ac12bdb4…`，samples 亦随之 ±2%）。8-25 上午
  重校准捕获的是后者（仅该时段的 5 次运行），同日 01:32 的 `audit-r1.csv` 与当日稍后
  的全部运行均回到前者——即上午基线本身取自短暂异常窗口。**golden.csv 对这两场景改用
  `|` 分隔的多值集合**：任一命中即通过（samples 跳过比较），其余 47 个场景仍 digest+samples
  双严格。真正的代码回归会产生集合之外的第三个值，仍会 MISMATCH，红线不放松。
- **全量运行时长与超时截断（2026-08-27，R31 记录）**：全套 49 场景（3 预热+5 测量）在
  本机需 9-11 分钟，逼近外部工具 10 分钟超时；**被截断的 run 落盘不完整且无 "written" 行**
  （R31 复现：17 场景即止）。全量跑用 3 预热+3 测量（`run.sh <csv> 3 3`，verify 只用 it=0）
  压进时限，或拆分两半；验证 CSV 前先确认末行是 "written" 且场景数=49。
- **被杀 run 的分离 JVM 尸体（R31 记录）**：杀掉 bash 包装只杀到壳——benchmark JVM 的
  MultiBurst/IOWorker 非守护线程池让它继续存活烧 CPU，后续 run 被拖到超时（症状：
  连续多次"莫名"截断且一次比一次早）。跑基准前确认 java 进程数为 0
  （`Get-Process java` / 循环 `Stop-Process -Name java -Force` 直到归零）。R29 那次
  "written 已打印但缺 plate-io 行"的异常与此族群相关，复跑即正常，成因未再复现。

## 离线依赖

`lib/` 下的 jar 不入库（见 `.gitignore`），用 `fetch-libs.sh` 重新下载；
`spigot-api` 与 `paper-api` 需从本地 Gradle 缓存或 SpigotMC 仓库获取（见 `lib.list`）。

## 典型流程

```bash
bash benchmark/build.sh                       # 编译（兼全量编译校验）
bash benchmark/run.sh benchmark/results/roundN.csv 3 5
bash benchmark/verify.sh benchmark/golden/golden.csv benchmark/results/roundN.csv
```
