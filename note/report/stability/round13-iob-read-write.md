# 稳定性/安全性审计 第 13 轮：IrisObject / .iob 读写路径

日期：2026-08-27
分支：`perf/optimization`
前置：R1-R12（`round1`~`round12` 报告）
范围：`engine/object/IrisObject`（read/readLegacy/write×4 变体/shrinkwrap）、`core/loader/ObjectResourceLoader`（.iob 定位与加载）、并发 paste 面（VectorMap 弱一致迭代复核）。
验证：`benchmark/build.sh` 全量编译通过（1255 类）；golden 49/49 位级一致（`results/audit-r13.csv`，3+3 迭代）；**新增 `bench.VerifyObjectIOB`：重构后 write 与旧算法字节级一致（5758 字节逐位相同）+ 写读往返一致（109 块全对）**——.iob 格式红线机器证明。

## 修复清单（4 项）

| ID | 文件 | 缺陷 | 修复 | 严重性 |
|----|------|------|------|--------|
| H1 | `IrisObject.write(File)` ×2 变体 | **非原子写 + 流泄漏**：直接 `new FileOutputStream(file)` 截断目标文件（调用方含 Studio 的 `write(getLoadFile())` 直接覆写源 .iob），序列化中途失败（磁盘满/IO 错）→ **原文件毁成半截**且 close 不在 finally（异常时流泄漏）。与 R26-A1（下载缓存部分写毁坏）同族 | `writeAtomically`：同目录 `.tmp` + try-with-resources 写 + 成功后 rename（失败回退 `Files.move(REPLACE_EXISTING)`）；任何失败原文件原封不动，tmp 清理 | 高（数据完整性） |
| H2 | `IrisObject.write(OutputStream)` ×2 | **O(blocks × palette)**：每块 `palette.indexOf(getAsString())` 线性扫描 + 每块两次 `getAsString()` 字符串构造——大对象（数万块）写盘 O(n²) | 提取共享 `writePaletteAndBlocks`（HashMap 索引一次构建 O(1) 查找）；**输出字节逐位不变**（palette 构建序不变、首现索引同值——VerifyObjectIOB A 证明） | 高（性能） |
| H3 | `IrisObject.write` palette 计数 | palette >65535 时 `writeShort` 静默截断 → 写出**结构性损坏**的 .iob（读方循环错位） | 写前守卫抛 IOException（fail loudly 而非毁档） | 中（防御） |
| H4 | `ObjectResourceLoader` ×3 处 | `File.listFiles()` 可返回 null（目录不存在/被锁/权限）→ NPE 中断加载/键枚举 | null 守卫（getFiles 返回空、findFile/loadRaw 跳过该目录） | 中 |

## 审查为健全（未改）

- **并发 paste/读写**：blocks/states 为 VectorMap（CHM 底层），place 的 cursorIterator 弱一致迭代不抛 CME；`read(File)` 失败回退 legacy 的双开流设计（HeaderException 静默、其他异常上报后重试 legacy）语义正确。
- **w/h/d/center 多字段竞态**：读写在同对象并发时存在字段间不一致窗口，但 .iob 生命周期（Studio 编辑线程）不产生该并发；属理论项，改动需对象级同步（超出本轮安全改动面），记录不动。
- `readLegacy` 的 `available()` 短路：BufferedInputStream 近似值 quirk，历史行为保持。
- `readShort()` 坐标有符号：格式设计（short 坐标域），保持。

## 影响面

- .iob 读写为 Studio/转换器/对象保存路径（`CommandObject.save`、`CommandDeveloper` ×2、`IrisConverter`、`ConversionSVC`、`JigsawEditor`），全部受益于原子写与 O(n²) 消除。
- **磁盘格式零变化**：VerifyObjectIOB 字节级证明；golden 49/49（引擎路径未触碰）。
- H2 顺带统一两个 write 变体的重复序列化体（带 sender 变体仅保留进度计数差异）。

## 累计统计（R1-R13）

- **61 项修复，~29 个 commit**
- 编译：benchmark/build.sh 全量通过（1255 类）
- 回归：golden 49/49 位级一致 + VerifyObjectIOB（.iob 格式专项）
- 报告：`round1`、`round2-4`、`round5-8`、`round9-11`、`round12`、本文件

## 下一轮建议

- R14：`core/link/data/*DataProvider`（CraftEngine/Nexo/HMCLeaves 等 6 个离线不可编译实现，benchmark/build.sh 排除路径——需 stub 或 gradle 环境验证）
- 债务：全库 `e.printStackTrace()` 统一 `Iris.reportError`（R9-11 记录，~227 处/94 文件）
