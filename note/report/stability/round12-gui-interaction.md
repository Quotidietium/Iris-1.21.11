# 稳定性/安全性审计 第 12 轮：GUI / 编辑器交互层

日期：2026-08-27
分支：`perf/optimization`
前置：R1-R11（`round1`~`round9-11` 报告）；R3 已覆盖命令层并修复 NoiseExplorerGUI 重复注册（C5）与 JigsawEditor 掉线泄漏（C3）。
范围：`core/gui/`（NoiseExplorerGUI、VisionGUI、PregeneratorJob + components）、`core/edit/JigsawEditor`（R3 修复复核）。
验证：`benchmark/build.sh` 全量编译通过（1253 类）；golden 49/49 位级一致（`results/audit-r12.csv`，3+3 迭代）。

## 修复清单（5 项）

| ID | 文件 | 缺陷 | 修复 | 严重性 |
|----|------|------|------|--------|
| G1 | `NoiseExplorerGUI.createAndShowGUI(loader, genName)` | 带 loader 变体（`/iris studio noise <gen>`）**无 windowClosing 注销**（R3 只给无参变体加了）：窗口关闭后 Bukkit listener 仍持有实例，hotload 事件持续触发，隐藏的 JFrame + 1440×820 BufferedImage（~3.4MB）永久钉住——每开-关一次泄漏一个 | windowClosing 注销 + `frame.dispose()`（两个变体统一，无参变体补 dispose） | 高（内存泄漏） |
| G2 | `NoiseExplorerGUI.paint` | 首次 paint 可能先于布局完成（w=0）：`new BufferedImage(0, h/acc)` 抛 IllegalArgumentException，渲染循环崩溃；accuracy 极大时 `w/accuracy==0` 同理 | `img==null && w/accuracy>0 && h/accuracy>0` 守卫 + `img!=null` 包裹渲染块 | 中（功能性崩溃） |
| G3 | `VisionGUI.paint` | **`fastpositions` 从不清理**（只有 `positions` 有视图外逐出）：低清预览图随漫游无界累积，每张仍是完整 BufferedImage——Studio 用户开 VisionGUI 长时间漫游即泄漏 | 对称的视图外逐出循环（不在当前渲染集 `gg` 内的 fast 图块移除） | 高（内存无界增长） |
| G4 | `PregeneratorJob.shouldGc` | 预生成期间每 ~32 个 region **强制 `System.gc()`**——对板块驻留泄漏的创可贴（根因已被 R26 硬上限真正修复），现存唯一效果是预生成中途的 STW 全量停顿 | 删除 shouldGc + rgc + cl 专用字段（监听回调保留为空，行为面不变） | 高（性能/停顿） |
| G5 | `JigsawEditor`（复核） | R3 修复（quit 内联清理、ticker 取消、listener 注销、sfut null 防护）在位且完整 | 无需改 | — |

## 审查为健全（未改）

- **VisionGUI 线程池**：`e`/`eh` 在 windowClosing 双双 shutdown ✓；paint 的 `isVisible` 链在窗口隐藏后停止 repaint 自循环 ✓；engine 关闭时面板自隐藏 ✓。
- **positions/fastpositions/working 并发**：KMap/KSet 均 CHM 底层，EDT 写 + 渲染线程 put/remove 安全；HD 升级的 `mk==mscale && mkd==scale` 防陈旧写 ✓。
- **PregeneratorJob 生命周期**：`onClose` 路径 `service.shutdownNow()` + `instance.compareAndSet(this,null)` ✓；虚拟线程执行器按任务短命 ✓；`stop()` 经 pregenerator.close() 走到 onClose。
- **NoiseExplorerGUI 静态字段共享**（两窗口共享偏移/缩放）：quirk 而非稳定性缺陷，保持既有行为。
- **paint 内多线程 `img.setRGB`**：列分区互不重叠，INT_RGB 逐像素 int 写无共享可变状态（既有渲染行为保持）。

## 影响面

- GUI 层为 Studio/运维工具面，非生成热路径——引擎路径零触碰，golden 49/49 不变。
- G4 的 System.gc 删除影响预生成停顿（正收益），不影响任何生成结果。
- 与 R26 的协同：G4 是 R26 硬上限落地后可安全移除的"历史补丁"，两轮共同把预生成期间的内存管理交还给 JVM 与硬上限机制。

## 累计统计（R1-R12）

- **57 项修复，~26 个 commit**
- 编译：benchmark/build.sh 全量通过（1253 类）
- 回归：golden 49/49 位级一致（重校准后基线）
- 报告：`round1`、`round2-4`、`round5-8`、`round9-11`、本文件

## 下一轮建议

- R13：`IrisObjectRegistry` / .iob 读写路径（文件 I/O 完整性、并发 paste）
- R14：`core/link/data/*DataProvider`（需 gradle 环境验证的 6 个集成实现）
- 债务：全库 `e.printStackTrace()` 统一为 `Iris.reportError`（格式债，R9-11 记录）
