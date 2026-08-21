# Skill Blueprint: pack-doc-generator（数据包文档与 Schema 生成器）

> 自动生成自 codebase-analyzer · 分析时间：2026-08-22
> 源模块：`core/src/main/java/com/volmit/iris/engine/object/`（140 个模型类）+ `core/src/main/java/com/volmit/iris/core/loader/IrisData.java`

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| 推荐 Skill 名称 | `iris-pack-doc-generator` |
| 用途 | 从 Iris 数据模型类源码自动生成字段文档（Markdown）与 JSON Schema，供数据包作者与编辑器补全使用 |
| AI 替代等级 | 🤖 完全 AI 化（28/30） |
| 实施优先级 | 🥇 Quick Win |
| 源文件数 | ~140 |
| 源代码行数 | ~15,000（engine/object 全目录） |

## 2. 触发场景与关键词

- "给 Iris 数据包生成文档 / 生成 Schema"
- "我想知道 IrisBiome / IrisDimension 支持哪些字段"
- "Studio 的 vscode schema 过期了，重新生成一份"
- "新增了 IrisXxx 模型类，补充它的文档"

**推荐 description 触发词：**

```yaml
description: >-
  Generate field-level documentation and JSON Schemas for Iris dimension pack
  models from Java sources. Triggered by: "数据包文档", "pack schema",
  "generate Iris docs", "字段表", "vscode schema".
```

## 3. 输入输出契约

### 主要函数接口（AI 需要模仿的解析对象）

| 函数/结构 | 输入 | 输出 | 语义 | 代码位置 |
|-----------|------|------|------|---------|
| `IrisRegistrant` | 抽象基类 | `getLoadKey()/getLoadFile()` | 所有可序列化模型的根 | `core/loader/IrisRegistrant.java` |
| `ResourceLoader<T>` | `IrisData.get*Loader()` | 缓存加载器 | 定义目录名（如 `biomes/`）与文件寻址 | `IrisData.java:62-82` |
| Gson 序列化约定 | `@Data` POJO | JSON | 字段名即 JSON 键；`IrisData implements ExclusionStrategy` 有例外 | `IrisData.java:56` |

### 数据模型（Schema 目标形状，以 IrisBiome 为例）

```typescript
// 从 Java POJO 投影的 Schema 片段
interface IrisBiomeSchema {
  name: string;              // 显示名
  derivative: "WET"|"DRY"|"COLD"|...;  // 原版衍生群系（枚举）
  children: { [key: string]: number }; // 子群系权重表
  layers: IrisBiomePaletteLayer[];     // 地表调色板
  objects: IrisObjectPlacement[];      // 装饰物
  decorators: IrisDecorator[];         // 表面装饰
  // ...以源码字段为准
}
```

### 输出物

| 产物 | 路径建议 | 用途 |
|------|---------|------|
| 字段文档 | `docs/models/<ClassName>.md` | 人工查阅 |
| JSON Schema | `schema/<folder>.json`（folder = ResourceLoader 目录名） | VSCode 校验（对接 `StudioSVC.openVSCode`，StudioSVC.java:359） |
| 索引 | `docs/models/index.md` | 140 类导航 |

## 4. 依赖清单

| 类型 | 内容 | 来源 | AI 所需 Context |
|------|------|------|----------------|
| 模型类源码 | `engine/object/*.java` | 仓库 | 全部（Lombok 注解展开心智模型） |
| 加载器目录映射 | biomeLoader→"biomes" 等 | `IrisData.java:62-82` | 完整清单（20 个 loader） |
| 序列化例外 | `ExclusionStrategy` 实现 | `IrisData.java` 内部类 | 排除字段规则 |
| 枚举类型 | `InferredType`/`CarvingMode` 等 | engine/object | 枚举值列表 |
| 嵌套引用 | 如 IrisBiome 引用 IrisObjectPlacement | 跨文件 import | 递归解析到 Schema $ref |

### 配置项

| 键 | 类型 | 默认 | 说明 |
|-----|------|------|------|
| `includeDeprecated` | bool | false | 是否包含 `@Deprecated` 字段 |
| `schemaDraft` | string | "2020-12" | JSON Schema 版本 |

## 5. Skill 工作流设计

```markdown
### Step 1: 扫描模型目录
Glob engine/object/*.java，按 ResourceLoader 目录名分组（对照 IrisData.java:62-82）。

### Step 2: 解析类字段
每类提取：字段名/类型/默认值/Lombok @Data/@Builder 注解/注释/枚举类型展开/
嵌套类引用（形成 $ref 图，禁止循环引用——用 $recursiveRef）。

### Step 3: 标注文档元数据
字段说明 = 注释 > 字段名语义推断 > 同名字段既有文档；
标注单位（blocks/chance/ratio）与取值范围（从代码中 clamp/default 推断）。

### Step 4: 生成 Schema
每目录一个 schema JSON；顶层 additionalProperties: false（Iris 对未知键宽容，
但 schema 严格以发现拼写错误——在文档中说明此差异）。

### Step 5: 生成索引与 diff
与上次生成产物 diff，输出变更摘要（新增/删除/改名类）。

### Step 6: 错误处理
解析失败的类输出到 skipped.md 并附原因（缺源码/语法异常），不中断整体。
```

### Constraints

- Always 以源码为准，禁止凭记忆编造字段（幻觉是本 Skill 唯一致命风险）
- Always 引用源码 `文件:行号` 于每个字段表尾部
- Never 修改 Java 源文件
- Never 把 `core/safeguard` 相关类写入公开文档

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|------|------|--------|
| Read/Glob/Grep | 读取模型类与 loader 映射 | 必需 |
| Write | 输出 docs/ 与 schema/ | 必需 |
| Bash(git diff) | 增量变更摘要 | 可选 |

**建议 allowed-tools：** `Read Glob Grep Write`

## 7. 使用示例

### ✅ Do This

```
用户: "更新数据包文档，上次加了 IrisFrostbite 相关类"
AI: Glob 新类 → 解析字段 → 生成 docs/models/IrisFrostbite.md +
    更新 schema/dimension.json 的 $defs + 输出变更摘要
```

### ❌ Not This

```
用户: "IrisBiome 有没有 xxx 字段？"
❌ AI 直接凭训练记忆回答（Iris 迭代极快，记忆必过期）
✅ AI 必须 Grep 源码后回答并附行号
```

## 8. 参考材料

- 模型目录：`core/src/main/java/com/volmit/iris/engine/object/`
- 加载器注册：`core/src/main/java/com/volmit/iris/core/loader/IrisData.java:62-82`
- VSCode 集成点：`core/src/main/java/com/volmit/iris/core/service/StudioSVC.java:359`
- 官方文档（对照风格，勿抄内容）：https://docs.volmit.com/iris/
