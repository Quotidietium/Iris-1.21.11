# 稳定性/安全性审计 第 16 轮：nms 模块 printStackTrace 债务批次 + 合并前事务清单

日期：2026-08-27
分支：`perf/optimization`
前置：R15（core 222 处已清零；nms 99 处留待本批）
范围：11 个 nms 模块（v1_20_R1 ~ v1_21_R7）全部裸 `printStackTrace()`。
验证：**gradle 编译门禁**——`gradle <全部 :nms:*:compileJava> --offline` **BUILD SUCCESSFUL**（11/11 任务行确证；v1_21_R7 全量 1m6s 含 buildSrc+core 重编译，其余 10 模块热缓存 7s；`core:compileJava` 作为依赖同步编译，core 的 R15 批次就此获得 gradle 侧背书）；core golden 49/49 位级一致（`results/audit-r16.csv`，1+1）。

## 转换内容（33 文件，99 处）

| 类别 | 数量 | 处理 |
|---|---|---|
| 独立 `X.printStackTrace();` | 77 处 | → `Iris.reportError(X);` |
| 相邻重复（reportError 同变量在上一行） | 22 处 | 删除 |
| import 补充 | 0（nms 文件均已引用 Iris） | — |
| 同作用域间隔重复 | 0（折叠器跑过，无命中） | — |

与 R15 的差别：nms 的 catch 风格更统一（reportError+printStackTrace 总是紧邻），无间隔/颠倒对；无行内 lambda。

## 过程事故（假门禁第三次被逮）

find `-maxdepth 3` 没够到 gradle.bat 的真实深度（`dists/<hash>/gradle-9.6.1/bin/` 为 4 层）→ 前几次 "exit=0" 全是 `cmd //c ""` 的 no-op。判别方法：**门禁输出必须非空且含任务行**（`> Task :nms:...:compileJava` 与 `BUILD SUCCESSFUL`）——空日志的 exit=0 一律视为未运行。与前两次（R15 的 `rm && build` 跳链、R29/R31 的分离 JVM）合并为基建三教训。

## 合并前事务清单（本轮交付，供实机验证后执行）

1. **服务器实测**（合并的先决条件，release note 已注明）：
   - 常规生成 + 预生成（Turbo/Lazy）各 ≥30 分钟，观察：生成速度、console 无异常刷屏、`/iris` 状态命令正常；
   - **内存红线复测**：大范围预生成（≥2000×2000 区块）期间堆占用应稳定在低位（板块硬上限 = 堆MB/512 + 45% 堆压 backstop；对照 R26 双臂数据）；反复开/关世界无累积（R26-C1 dereferencer 修复 + R12 GUI 泄漏修复的实机确认）；
   - Studio 热重载、VisionGUI/NoiseExplorer 开-关循环、jigsaw 编辑器进出（R12/R13 修复面）；
   - `.iob` 对象保存-加载往返（R13 原子写）；第三方插件（CraftEngine/Nexo 至少其一）在场生成（R14 伞形防护）。
2. **版本号**：`build.gradle.kts` 已是 3.9.3；实测通过后无新增改动则维持。
3. **合并**：`git merge perf/optimization` → master。
4. **Release**：GitHub tag `3.9.3-1.20.1-1.21.11` + 附件 `build/Iris-3.9.3-1.20.1-1.21.11.jar`（3.4MB SlimJar，构建环境见 `note/report/build-env/`）；描述取 release note 的总览（一个点一句话）。

## 累计统计（R1-R16）

- 65 项审计修复 + R15 core 222 处 + R16 nms 99 处格式统一（**全库 printStackTrace 债务清零**）
- 门禁：nms 11/11 gradle 编译 + core 1274 类 javac + golden 49/49
- 报告：`round1` … `round15`、本文件
