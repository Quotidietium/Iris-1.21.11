# 稳定性/安全性审计 第 1 轮：网络下载与包安装集群

日期：2026-08-24
分支：`perf/optimization`
范围：网络客户端代码（无内置 HTTP 服务器，网络面 = GitHub 下载器 + Bukkit 命令面）
验证：`benchmark/build.sh` 全量编译通过（1248 类）；真实 `Iris.java` 经 stub 扩展后离线编译验证通过。

## 修复清单（12 项，5 个 commit）

| ID | 文件 | 缺陷 | 修复 | 严重性 |
|----|------|------|------|--------|
| A1 | `Iris.java` `getCached` | 下载失败残留部分文件且 `f.exists()` 为真 → 缓存被**永久污染**（一次瞬时网络错误 = 缓存条目永远损坏） | 失败时删除部分文件 | 高（数据完整性） |
| A2 | `Iris.java` 三个下载方法 | `URL.openStream()` 无连接/读取超时 → 挂起的连接无限阻塞线程 | `URLConnection` + 10s 连接/60s 读取超时 | 高（稳定性） |
| A3 | `Iris.java` `getNonCachedFile` | 失败时返回部分/空文件，调用方 null 检查形同虚设 | 失败返回 null（调用方已有 null 分支） | 中 |
| A4 | `StudioSVC.onEnable` | 默认包下载经 `J.s` 在**主线程**执行，首次启动遇慢网络 = 整服冻结数分钟 | 改 `J.a` 异步 | 高（可用性） |
| A5 | `CommandIris.download` | `/iris download` 在主线程阻塞下载 | 命令体改 `J.a` 异步 | 高（可用性） |
| A6 | `StudioSVC.download` | 原本靠"只在主线程执行"获得串行性，A4/A5 异步化后会引入并发解压/拷贝竞争 | 新增 `DOWNLOAD_LOCK` 进程级串行 | 高（并发引入的回归预防） |
| A7 | `DL.finishDownload` | 状态守卫反转：要求 `NEW` 状态，但唯一调用点状态必为 `DOWNLOADING` → 正常完成必然抛异常 | 守卫改为 `DOWNLOADING` | 高（功能性） |
| A8 | `DL.downloadChunk` | EOF 判定 `d < 0` 永假（`IO.transfer` 返回值 ≥0）→ 下载线程 EOF 后**永久忙循环** | `d <= 0` 即完成 | 高（死循环） |
| A9 | `IrisPackRepository.install` | 下载目标写成最终目录 `pack`，解压任务却读从未写入的 `dl` → 安装必然失败；`work.listFiles()[0]` NPE/AIOOBE 无守卫 | 下载目标改 `dl` + 空守卫 + 失败抛出 | 高（功能性） |
| A10 | `IrisPackRepository.from` | `"a/"` 输入触发无限自递归 → StackOverflowError；`github.com/<user>` 触发 AIOOBE；`/tree/` 空分支 | 输入守卫，非法输入返回 null（调用方已处理） | 中 |
| A11 | `IrisPack.from` / `install` | 任务失败时 `CompletableFuture` 永不完成（调用方永久等待）；"Pack already exists" 分支不执行回调 | 异常传播 `completeExceptionally` + 已存在分支执行回调 | 中 |
| A12 | `IrisPack.blank` | 包名未校验直接拼 JSON（注入）与路径（穿越写任意目录） | `[a-zA-Z0-9_-]+` 白名单 + `JSONObject` 构建 | 中（安全） |
| A13 | `Job.execute(sender,...)` | 任务失败仍播报 "Completed" 且异常被静默吞噬；回调异常消失于被忽略的 future；空集合除零 | 失败播报 + `reportError` + 回调异常捕获 + 除零守卫 | 中（交互正确性） |

## 附带

- `DL.start()`：`openStream()` 抛异常时 `FileOutputStream` 泄漏 → try/finally 关闭并标记 FAILED。
- `DL.calculateSize()`：`HttpURLConnection` 未 disconnect → finally 断开。
- `DL.downloadChunk()`：耗时 0ms 时 bps 计算除零 → 守卫。
- `DownloadJob`：失败时不再把进度强行设为 100%。
- benchmark `Mode` stub 补 `tag()`/`trySplash()`（离线编译真实 Iris.java 所需）。

## 影响面评估

- `/iris download`、`/iris studio create`、首次启动默认包下载、世界创建时缺包自动下载（`installInto`）全部经过此集群。
- `IrisPack.from(sender, ...)` 当前无生产调用方（死代码路径），仍按逻辑错误修复。
- 世界生成热路径（R11-R19 优化区域）未被触碰；golden 49 场景不受影响（无引擎行为变更）。

## 下一轮

R2：命令权限/输入校验攻击面（Decree 权限模型、路径穿越全集、异常命令参数）。
