# Skill Blueprint: crash-log-analyzer（调试产物聚合分析器）

> 自动生成自 codebase-analyzer · 分析时间：2026-08-22
> 源模块：`Iris.java` 的错误落盘体系（371-421 行）+ `core/report/` + Sentry 绑定

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| 推荐 Skill 名称 | `iris-crash-analyzer` |
| 用途 | 聚类分析 Iris 服务器目录下的 debug/caught-exceptions、debug/chunk-errors、dump/ 线程转储，输出根因假设与源码定位 |
| AI 替代等级 | 🤖 完全 AI 化（25/30） |
| 实施优先级 | 🥇 Quick Win（零测试项目里这是最高价值质量回路） |
| 源文件数 | 输入格式 3 类（异常 txt / 区块错误 txt / 线程 dump txt） |
| 源代码行数 | 生成侧 ~100 行（Iris.java:353-421） |

## 2. 触发场景与关键词

- "服务器控制台刷异常，帮我分析"
- "plugins/Iris/debug 目录下的报错是什么问题"
- "Iris 生成到某坐标就崩，chunk.12.-5.txt 里的堆栈帮我看看"
- "分析一下 dump 线程转储，是不是死锁"

**推荐 description 触发词：**

```yaml
description: >-
  Analyze Iris plugin crash artifacts (caught-exceptions, chunk-errors,
  thread dumps) and cluster them into root-cause hypotheses with source
  locations. Triggered by: "分析报错", "crash log", "线程 dump", "异常聚类".
```

## 3. 输入输出契约

### 输入产物格式（Iris 侧生成逻辑）

| 产物 | 生成代码 | 文件名模式 | 内容结构 |
|------|---------|-----------|---------|
| 异常落盘 | `Iris.reportError`（Iris.java:371-394） | `<异常类>-<类>-<行号>[-<cause类>-<行号>].txt` | 首行 Thread/First + 完整 printStackTrace |
| 区块错误 | `Iris.reportErrorChunk`（:353-369） | `chunk.<x>.<z>.txt` | 同上，坐标即文件名 |
| 线程转储 | `Iris.dump()`（:396-421） | `dump/td-<日期>.txt` | 全线程栈 + 商店水印行 |
| 引擎 panic | `EnginePanic`（engine/EnginePanic.java） | 内存/日志 | 分阶段 panic 计数 |

### 输出报告结构

```markdown
# Iris 调试产物分析报告
## Top 故障聚类（按签名去重计数）
| # | 签名 | 次数 | 首次出现 | 根因假设 | 源码定位 | 建议动作 |
## 区块错误热力分布（x,z 坐标 → 是否集中于某区域/尺寸边界）
## 线程状态摘要（dump 输入时）：RUNNABLE/BLOCKED/WAITING 分布 + 疑似死锁对
## 静默吞异常清单（对照 catch(ignored) 源码点位提示盲区）
```

### 错误码/分类（AI 判定规则）

| 异常家族 | 典型类 | 指向 |
|---------|--------|------|
| 数据包解析 | JsonParseException/JsonSyntaxException | 用户 pack JSON 误写（对接 01-pack-doc-generator 的 Schema 校验） |
| NMS 反射 | NoSuchFieldException/NoSuchMethodException | 版本绑定缺方法（对接 03-nms-porting-assistant） |
| 并发 | ConcurrentModificationException/InterruptedException | Mantle/MultiBurst 区域（升级给人工） |
| IO | IOException(区域文件) | 磁盘/权限/损坏板块 |
| 生成期 NPE | NullPointerException | 引擎路径（附完整调用栈帧对应源码） |

## 4. 依赖清单

| 类型 | 内容 | 来源 | AI 所需 Context |
|------|------|------|----------------|
| 产物目录 | `plugins/Iris/debug/**`, `plugins/Iris/dump/**` | 运行时服务器 | 用户提供的路径 |
| 符号表 | 源码仓库 | 本仓库 | 堆栈帧 → 源码行映射（Read 验证） |
| 已知吞异常点 | `catch (Throwable ignored)` 全集 | Grep 仓库 | 提示"这类错误可能根本没落盘" |
| 上下文辅助 | settings.json 的 debug 开关 | Iris.java:373 | 解释为何某些用户目录为空 |

## 5. Skill 工作流设计

```markdown
### Step 1: 收集
Glob debug/**/*.txt 与 dump/*.txt；按文件名模式初步分类。

### Step 2: 聚类
解析每个堆栈，签名 = 顶层异常类 + 第一个 com.volmit 帧；
相同签名合并计数，保留最早/最晚时间戳。

### Step 3: 定位
对每聚类的前 3 帧在本仓库 Read 对应源码行（验证行号仍成立，
分支漂移时按方法名 Grep 重定位）。

### Step 4: 归因
按 §3 错误码表分类 + 源码上下文写根因假设；
区分"数据包用户错误"vs"引擎缺陷"vs"环境问题"。

### Step 5: 区块专项
chunk.*.txt 有坐标：输出分布特征（规则网格→预生成边界；
随机→并发热点）。

### Step 6: 输出与边界
只读分析；绝不修改服务器目录；建议动作中涉及并发/Mantle 的
一律标注"需人工复核"，不给出自动修复。
```

### Constraints

- Never 修改 debug/dump 产物或任何服务器文件
- Never 对 ConcurrentModification/死锁类给出"确定性结论"，只给假设
- Always 每个结论附源码 `文件:行号` 或产物文件路径
- Never 读取产物中可能包含的服务器密钥/玩家隐私之外传

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|------|------|--------|
| Read/Glob/Grep | 产物读取 + 源码定位 | 必需 |
| Write | 输出分析报告（note/report/ 下） | 必需 |

**建议 allowed-tools：** `Read Glob Grep Write`

## 7. 使用示例

### ✅ Do This

```
用户: "F:/server/plugins/Iris/debug 下一堆 txt，帮我看看什么毛病"
AI: 聚类 → 发现 47 个 JsonSyntaxException 同一签名
    → 定位用户 pack biomes/xxx.json 缺逗号
    → 报告："数据包错误，非插件缺陷"，附文件与行号
```

### ❌ Not This

```
❌ AI 不读文件直接按"常见 Iris 报错"输出通用建议
❌ AI 把 ConcurrentModificationException 归因为"重启即可解决"
```

## 8. 参考材料

- 落盘逻辑：`core/src/main/java/com/volmit/iris/Iris.java:353-421`
- panic 体系：`core/src/main/java/com/volmit/iris/engine/EnginePanic.java`
- Sentry 绑定：`util/misc/Bindings.java#setupSentry`
