# 稳定性/安全性审计 第 5-8 轮：NMS 引导 / Decree 解析 / 监听器配对 / 预生成器生命周期

日期：2026-08-24
分支：`perf/optimization`
前置：R1（`round1-network-download-cluster.md`）、R2-R4（`round2-4-commands-lifecycle-config.md`）
验证：`benchmark/build.sh` 全量编译通过（1246 类）；引擎路径未触碰，R1 批次的 golden 49/49 验证仍然有效。

## R6：NMS 引导（commit 4228ac5）

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| D1 | `INMS.getTag` | 异常 bukkit 版本串（如 `1.21.x`）→ 未捕获 NumberFormatException → 静态初始化失败（ExceptionInInitializerError）→ 插件无法加载 | try/catch 回退默认 tag |

NMS 各版本绑定（11 模块）为反射垫片，错误处理模式（catch Throwable + fallback NMSBinding1X）健全，未深改。

## R7：Decree 参数解析（commit b5d3097）

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| D2 | `VirtualDecreeCommand.map` | `name=`（空值）→ split 去尾空 → `v[1]` AIOOBE 未捕获（玩家命令无响应）；`msg=a=b` → 无 limit 分割截断值 | limit-2 分割 + 空值提示 |
| D3 | `VirtualDecreeCommand` | `help=abc` NumberFormatException；命令方法抛异常时玩家端静默（仅控制台），TODO 占位异常 | 容错解析 + 发送者反馈 |
| — | 各类型 Handler（Integer/Long/...） | 已验证：catch Throwable → DecreeParsingException，模式健全 | 无需改 |

## R8：监听器配对与预生成器（commit 8a0afef）

| ID | 位置 | 缺陷 | 修复 |
|----|------|------|------|
| E1 | `LazyPregenerator` | implements Listener 但**从未注册**——WorldUnloadEvent 处理器永不触发，世界卸载后线程继续对已卸载世界 regenerateChunk/save；`shutdownInstance` 靠 NPE 崩溃线程（jobs.remove 后 tick 取 null）；lazygen.json 删除循环跑在**主线程**（文件被锁 = 全服卡死循环）；save 与删除竞争可使已停止任务复活；`lazyGeneratedChunks` 静态被多实例重置；单实例追踪与多世界不兼容；tick 异常跳过最终保存；执行器不关闭 | 注册/注销监听器、null 守卫自中断、世界消失自终止、tick try/catch、异步删除 + discardSave、实例字段化、按世界追踪 + 双 create 竞争替换、cleanup 关执行器 |
| E2 | `TurboPregenerator` | 同 E1 全部问题，**另加**：tick() 在标志竞争窗口内每次新建固定线程池（线程泄漏）；cache() 每次分配完全未使用的 N 线程执行器（纯泄漏）；cachinglock 在 cache() 抛异常时永不释放（生成器永久卡死）；run() 无休眠忙转（暂停时 100% 烧核） | 同 E1 + 删除两处执行器分配（改用 MultiBurst）+ finally 释放锁 + 50ms tick 上限 |
| — | `DeepSearchPregenerator` | 死代码（无任何调用方），同样模式未修 | 记录不修 |

## 累计统计（R1-R8）

- **42 项修复，17 个 commit**
- 编译：benchmark/build.sh 全量通过（1246 类）
- 回归：golden 49/49 位一致（引擎/存储行为零变化；R5+ 批次未触碰引擎路径）
- 报告：`round1`、`round2-4`、本文件

## 下一轮建议（未完待续）

- R9：`IrisConverter`（schematic 转换器）文件处理与异常路径
- R10：`ExternalDataSVC`/link 层（第三方插件集成）注册生命周期
- R11：热载入路径（`IrisData.hotloaded`）与并发读写的竞争
