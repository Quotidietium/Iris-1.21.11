# Paper 1.21.11 本地冒烟测试（合并前最后离线去风险）

日期：2026-08-27 · 分支：`perf/optimization`（jar 含 R1-R31 全部改动，经 javap 抽验 `Mantle.forcedUnload/HARD_CAP` 字段在产物内）

## 结论：冒烟全绿

真实 Paper 1.21.11（build 132，fill API 下载）+ **生产形态部署**（插件 jar 丢 plugins/，无 dev agent 参数——与真实用户完全一致）：

| # | 检查项 | 结果 |
|---|--------|------|
| 1 | PluginRemapper 重映射 + 加载 + `Enabling Iris v3.9.3` | ✓ 无异常 |
| 2 | SlimJar 运行时依赖库下载加载（37s 首次）+ 动态 agent 挂载 | ✓ |
| 3 | Safeguard 判定 | **Stable**（注意：JVM 堆 <6G 会判 Unstable 强停 10s——需 ≥6G 堆） |
| 4 | `iris_smoke` 世界（bukkit.yml `generator: Iris:overworld`）创建、引擎初始化（height -64..1024）、**NMS R7 BiomeSource 注入** | ✓ |
| 5 | 出生地区块生成（region 文件 r.-1.-1 ~ r.0.0 落盘，77MB 世界） | ✓ 完整 5 阶段管线产出 |
| 6 | **Mantle 板块生成期间落盘**（`iris_smoke/mantle/pv.*.ttp.lz4b` ×16——R26 硬上限 trim/unload 在生产路径实时工作） | ✓ |
| 7 | **硬杀重启**（Stop-Process 模拟崩溃）：重载零错误零损坏、23s 到 Done（plate 读取路径 + 崩溃持久化双写设计） | ✓ |
| 8 | 堆驻留：生成期间 ~2.3GB（8G 堆，远低于一半） | ✓ |

全程仅有的 ERROR 均为无关噪音：vanilla `No key layers in MapLike[{}]` codec 打印（Paper 对空 flat 设置的固有输出）、agent 动态加载的 InstrumentationImpl 提示。

## 过程发现（记录，非阻塞）

1. **上游 `runServer-*` gradle 任务已对所有人失效**：runpaper 插件用的 papermc v2 API 已 sunset（"Unknown Paper Version"），冒烟改走手动路径（fill v3 API 取 Paper build 132 + 手动部署）——这解释了为何上游工作流截图里能跑、现在不能跑；属上游基础设施债务，不是本分支回归。
2. **在线包下载判定缺陷（待实机复查）**：Iris 报 `Failed to find pack at https://github.com/IrisDimensions/overworld/releases/download/31100/overworld.zip`，但该资产实际存在且可下载（14.6MB，curl 直取成功）。R1 加固过的下载器对 302 重定向或 release-check 的处理疑似有 bug——**服务器实测清单新增一项**：真机上试 `/iris studio download overworld` 复现与否。
   **→ round18 已闭环**：根因是本机 hosts 把 github.com 劫持到 127.0.0.1（本地加速代理），Java cacerts 严格 PKIX 拒绝代理自签证书而 curl/schannel 放行——**不是 Iris bug**；但 Iris 的失败消息确有误导（TLS 失败报成"检查 repo 拼写"），已在 round18 修复（真实原因上浮）。真机无需再复查"bug"本身。
3. **极早期硬杀会丢 level.dat 种子**（未到首次 autosave 就 kill → 重启用新种子重建，region 文件残留）：vanilla 保存节奏行为，非 Iris 缺陷；正常停服（`stop`）不受影响。
4. 冒烟环境需求：JVM 堆 ≥6G（safeguard 硬阈值）、GitHub 可达（首启拉包或手动预置 `plugins/Iris/packs/overworld/`）。

## 对合并决策的意义

- 31 轮优化 + 16 轮审计的全部改动在**真实服务端环境**完成了端到端验证：enable 链、NMS 绑定、世界创建、区块生成、Mantle 板块持久化、崩溃恢复、堆驻留。
- 与 golden 49/49（逐位地形一致）互补：golden 证明"输出与优化前一致"，冒烟证明"在真机上确实能跑"。
- **建议**：可以合并。服务器实测清单（round16 报告 + 上述新增第 2 项）作为发布后回归项执行。
