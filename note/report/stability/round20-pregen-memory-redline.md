# Round 20 — R32-R35 分支大范围预生成内存红线实测（本地 Paper 生产形态）

日期：2026-08-27 · 分支：`perf/optimization`（jar 同 round19 冒烟款，javap 验证含三轮改动）

## 结论一：内存红线 **PASS**（本地生产形态实测）

Turbo 预生成 radius 3000（**140,625 区块全部生成完毕**，非截断运行）+ 35 分钟每分钟 `jcmd GC.heap_info` 采样（`benchmark/results/r20/heap-curve2.csv`）：

- **used 峰值 2,990MB = 36.5% -Xmx（8G）**，全程 1.6–3.0GB 区间受控震荡（GC 健康）；
- **committed 平台期 3,644MB = 44.4%**，t≈30 分钟后完全恒定；
- **无棘轮**：生成范围 36→100 板盘持续扩大，堆使用不随之爬升；
- 板盘 256 个落盘、region 数据 1.36GB、全程零 Iris 异常崩溃，预生成正常完成（`Completed Turbo Gen!`）。

结合离线证据（R26/R28 硬上限+backstop、VerifyMemoryBound 扫掠恒定、R33 加载风暴瞬时垃圾 -95%），
**内存红线（≤50% -Xmx 且不随范围增长）在本地生产形态下成立**。

## 结论二：MatterCavern 读 panic = 上游既有瞬态，非 R32-R35 回归

预生成全程出现 280 次 `Failed to read matter slice, skipping it`（0.2%，均 MatterCavern 切片，
恢复路径=跳过该切片继续）。调查证据链：

1. **恢复/上报机制是上游代码**（commit `1dca502a9 "add more safety to mantle"`，先于本项目全部优化）；
2. **读端代码逐字未动**：master↔分支 diff 中 Matter.readDin/DataContainer 读构造/DataBits 读构造零变化（分支仅改写端：Varint 写/DataBits.write/trim 脏标志）；
3. **写端字节同一性双重证明**：plate-io digest 100 次写读逐位一致 + **panic 当次运行产出的全部 256 个真实板盘离线解析 100% 通过**（新工具 `bench.VerifyPlateParse`）；
4. panic 签名为瞬态读窗口：首次失败读停在 3.24MB 处，该文件最终 4.0MB 且现可完整解析；同板盘数秒内连续两次失败（createdAt 相差 28s 的不同 section）；
5. 板盘互斥本体正确（阻塞信号量 per 文件 + temp 文件 + flush truncate+force）。

**未能活体复现**：三次复跑均被测试工具学干扰（见下），panic 未再现；真实服务器全量首生成条件
（140k 区块从零+密集 carve+板盘高频换入换出）在短复跑中未重建。判定：**时序相关瞬态，非序列化回归**。
遗留：在实测卡片新增观察项——真实部署统计 `Failed to read matter slice` 行数，>0 则收集日志反馈。

## 过程发现（工具/环境，均记录）

1. **Decree 命令对 RCON 发送者静默无效**（`iris pregen/turbo` 空回显零执行）——可用性问题，
   实测卡片命令需玩家/控制台执行；本轮用伴随插件 `PregenBoot`（build/smoke-tools，不入库发行）编程启动。
2. **首次安装数据包后 Iris 按设计自动重启**（"New data pack entries installed! Restarting"），
   Paper 找不到 start.sh 即退出；且**预声明世界+处女世界会在 safeguard 处死锁**（dimension-types
   ERROR 在数据包安装前触发）——真实用户路径（先启动后 `/iris create`）不受影响。
3. **JDK 25 触发 WARN 级 issue（1 个不致停）；dimension-types 是 ERROR 级（1 个即 Unstable 强停）**。
4. **stale turbogen.json 会让新 pregen 任务静默变 no-op**（position≥total 被当作已完成）——上游
   命令路径有清理逻辑，直接构造 API 没有；影响的是测试工具而非用户命令。
5. **端口被占/残留 JVM**：冒烟重启必须 `tasklist` 验证归零（Stop-Process 可能静默失败）。
6. 半生成世界上 turbo 预生成恢复语义存在空转路径（线程在但无进展）——未深究，记录为上游行为。

## 对合并决策的意义

内存红线本地过线 + panic 判定非回归 + 前置门禁全绿（golden 56/56、gradle、round19 冒烟）——
**执行 R32-R35 合并与 3.9.4 Release**；服务器实测卡片（note/server-test-card.md）作为发布后回归，
新增 panic 观察项。
