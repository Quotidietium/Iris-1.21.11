# Iris 服务器实测操作卡片

> 用途：在**真实服务器**上回归验证 3.9.3（及后续 R32/R33 分支构建）的性能与内存表现。
> 全部步骤约 2-3 小时；可按优先级单独执行（P0 = 必做，P1 = 重要，P2 = 有余力做）。
> 每步的「记录」内容请收集好，反馈时一并提供。

## 红线判定（总标准）

1. **内存红线**：任何生成负载下，Iris 引起的堆占用稳定在 **JVM 最大堆（-Xmx）的 50% 以下**；
   生成范围扩大 10 倍，稳定期堆占用**不随之增长**（板块硬上限机制）。
2. **行为一致**：地形外观与历史版本一致；console 无异常刷屏（偶发单条 `Iris caught...` 上报属正常）；
   生成速度与 3.9.3 Release note 声明的量级一致。

## 环境准备（P0）

- Paper 1.21.11（fill API 可用的近期 build）。
- JVM：`-Xmx6G` 起步（Iris safeguard 要求 ≥6G，否则强制 Unstable 关闭引擎）；内存红线测试建议 `-Xmx8G`。
- 插件：`Iris-3.9.3-1.20.1-1.21.11.jar`（Release 附件）。
  若要一并验证 R32/R33（.iob 读写提速），用 `perf/optimization` 分支最新构建的 jar 代替——两者其余行为相同。
- （可选）spark：`/spark profiler`、`/spark tps` 用于采集。

## 步骤 1：常规生成 + 预生成（P0，≥30 分钟）

```
/iris studio download overworld        # 首次拉包（~15MB）
/iris create testworld overworld       # 创建 Iris 世界（名称/包名按服务器习惯）
/iris pregen 3000 testworld            # 半径 3000 方块 ≈ 375×375 区块，约 30-60 分钟
```

- 观察：console 生成速度稳定不衰减；无重复堆栈；`/iris` 状态命令可正常响应。
- **记录**：开始/中段/结束三份 console 片段；spark TPS 曲线（如有）。

## 步骤 2：内存红线专项（P0）

接步骤 1 或单独执行——**大范围**预生成：

```
/iris turbo 8000 testworld             # 半径 8000 方块 ≈ 1000×1000 区块（覆盖 ≥2000×2000 目标）
```

- 观察（任选其一）：spark 的 heap 曲线 / `/spark gc` / 主机监控的 JVM 进程内存。
- **判定**：
  - 生成 30 分钟后堆占用进入平台期（不再爬升），平台 ≤ `-Xmx` 的 50%；
  - 板块驻留数有上限（堆 MB ÷ 512，例如 8G 堆 ≈ 16 板块 × ~2MB 级）；
  - 停止预生成后堆可回落；再次启动不高于上一轮平台（无棘轮）。
- **记录**：heap 曲线截图或每 5 分钟一次的占用数值序列；异常时附 `logs/latest.log` 中 Iris 相关段落。

## 步骤 3：世界反复开/关（P1）

- 同一 Iris 世界卸载→加载 5 轮（`/mv unload`/`load` 或原生 `/forceload` 管理，按服务器习惯）；
  每轮后记录堆占用——**不应逐轮爬升**。
- 重启服务器一次：世界数据正常载入，无损坏报错。

## 步骤 4：Studio / GUI 循环（P1）

```
/iris studio open overworld            # 打开 Studio（VisionGUI 地图窗口）
```

- 地图窗口开-关 10 轮；NoiseExplorer（若使用）开-关 5 轮；jigsaw 编辑器进出 3 轮。
- 每轮后堆占用不累积；无 GUI 相关异常。
- Studio 内热重载一次（改一个 biome 参数 → `/iris studio reload`）：生效且无异常。

## 步骤5：.iob 对象往返 + 在线包（P1；R32/R33 构建时尤其相关）

- `/iris object` 系命令保存一个区域对象 → 重启 → 确认可加载、可放置，形状一致。
- （R32/R33 构建额外观察）大批量转换 `/iris object convert`（把 `plugins/Iris/convert` 内 .schem 批量转 .iob）：
  转换速度明显快于旧版、转换期间无长时间卡顿（对象读写均为单遍序列化）。
- `/iris studio download <其他包名>`：正常拉取；故意填错包名时，报错信息应指出**真实原因**
  （404/网络/超时），而不是笼统的"检查拼写"。

## 步骤 6：第三方 provider 在场（P2）

- 安装 CraftEngine 或 Nexo（其一）+ 对应 Iris 兼容包，生成含自定义方块的区域。
- 观察：无 provider 相关异常刷屏（单个 `Iris.reportError` 上报可接受）；自定义方块正常生成。

## 数据回收清单

| 项 | 格式 |
|---|---|
| heap 占用序列/曲线（步骤 1/2/3/4 各一份） | 截图或数值列表（时间点+MB） |
| console 片段（各步骤开始与结束） | 文本（含时间戳） |
| 异常完整堆栈（如有） | `logs/latest.log` 相关段 |
| **`Failed to read matter slice` 行数统计**（round20 观察项：本地 140k 区块预生成出现 280 次上游既有瞬态；>0 请附行数与前后日志段） | 一行计数 + 日志段 |
| 使用的 Iris jar 版本 + JVM 参数 | 一行文字 |

## 出问题时

- Iris 自身的错误会以 `Iris caught ...` 上浮并附追踪号，直接反馈该段日志即可；
- 若怀疑内存机制本身，可在对照轮加 `-Diris.mantle.hardcap=false`（关闭硬上限=复现旧行为），
  两轮 heap 曲线对比能直接定位问题是否出在 cap 逻辑；
- 所有 bug 已由 `Iris.reportError` 落盘到 Iris 数据目录的错误报告文件，可整目录打包反馈。
