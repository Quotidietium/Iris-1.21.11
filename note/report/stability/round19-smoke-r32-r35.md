# Round 19 — R32-R35 分支 Paper 1.21.11 生产形态冒烟（预合并验证）

日期：2026-08-27 · 分支：`perf/optimization`（jar 含 R32-R35 全部改动，javap 抽验：`B.memo/MEMO_LIMIT/invalidateParseMemo`、
`IrisObject.buildPalette(int[],int[])`、`DataContainer.dirty` 均在产物内；jar 3,621,579 字节）

## 结论：冒烟全绿

复用 round17 环境（`build/smoke/`：Paper 1.21.11 build 132、预置 overworld 包、SlimJar 库缓存），
生产形态部署（插件 jar 丢 plugins/，无 dev agent 参数），`-Xmx8G`：

| # | 检查项 | 结果 |
|---|--------|------|
| 1 | Remapper 重映射 + `Enabling Iris v3.9.3-1.20.1-1.21.11` + SlimJar 缓存复用 + agent 挂载 | ✓ |
| 2 | Safeguard | Stable（无 Unstable 强停；8G 堆满足 ≥6G 硬阈值） |
| 3 | NMS `v1_21_R7` 绑定 + **BiomeSource 注入** iris_smoke | ✓ |
| 4 | 引擎初始化（-64..1024）+ 出生地生成（region r.-1.-1~r.0.0） | ✓ |
| 5 | **16 板块生成期间落盘**（R26 硬上限 + R35 新序列化器：Varint 快速路径/DataBits 缓冲外提/trim 脏标志全部在真机写盘路径上运行，LZ4 文件尺寸正常） | ✓ |
| 6 | **硬杀重启**：enable/绑定/引擎重注入/世界加载全零异常，Done 14.5s | ✓ |
| 7 | 全程零 Iris 异常（`Iris caught`/Exception 计数 = 0）；仅有的 ERROR 为已知噪音（vanilla `No key layers` codec 打印、动态 agent 提示） | ✓ |
| 8 | 堆：生成后空闲工作集 ~2.08GB（8G 上限，远低于一半；round17 生成期为 2.3GB 同量级） | ✓ |

## 与 round17 的差异记录

1. **极早期硬杀种子丢失复现**（round17 发现 #3，行为一致=非回归）：首启 ~90s 即杀、未到任何存盘点
   （level.dat 在整个测试中始终未产生——无玩家空闲服务器不触发存盘），重启引擎以新种子重建。
   含义：本轮重启验证的是"崩溃后世界目录（含新写入器产物）重载零异常"；**同种子板盘重读**未在真机覆盖，
   由离线 plate-io digest（100 次写读循环、字节逐位一致）补证——生产 IOWorker/TectonicPlate/Matter 读取链
   全部为真实类。
2. round17 的在线包下载项已由 round18 闭环（hosts 劫持，非 bug），本轮预置包直接复用。

## 对合并决策的意义

R32-R35 四轮（.iob 写/读单遍化、B.get memoization + stilt 腐蚀修复、板盘序列化 varint/trim）在真实
Paper 服务端完成端到端验证：写盘路径（板块落盘）在本轮是**真机直接执行**的新代码。与 golden 56/56
互补（输出逐位一致 + 真机能跑）。

**建议**：可合并。合并与 Release 的既定顺序不变——等用户按 `note/server-test-card.md` 执行服务器实测、
回传 heap 数据、确认内存红线（稳定 ≤50% -Xmx）后执行。
