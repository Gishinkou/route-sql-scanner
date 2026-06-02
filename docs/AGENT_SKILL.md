# route-sql-scanner AI Agent Skill 说明

这份文档面向 IDEA 插件内的 AI agent。它把底层 `route-sql-scanner scan` CLI 收敛成一个小接口：agent 只负责准备"要扫哪个项目"，其余扫描选项由 skill 默认值托管。

> 重要：当前版本扫描器只产出 **SQL 清单**（`compact-json`，schema v=2），**不再做任何规则诊断、不再生成 xlsx 报告**。诊断逻辑全部由服务端完成。

## Skill 语义

当用户希望检查 Java 项目里的 MyBatis/JDBC SQL 是否满足分库分表路由字段完整性要求时，使用本 skill。

典型链路：

1. 用户提供目标项目路径等必要业务信息。
2. AI agent 调用本 skill 扫描目标 Java 项目，得到本地 inventory JSON。
3. AI agent 将 inventory JSON 上传到服务端 SQL Analyze 接口，由服务端做规范化、表名提取、路由字段校验、兼容性检查。
4. AI agent 将服务端返回的诊断结果展示给用户。

本 skill 只负责第 2 步：产出 inventory JSON。配置拉取、上传、诊断展示由其它环节负责。

扫描器会枚举 MyBatis XML/注解里的静态可见动态 SQL 分支；例如 `<if>` 会生成包含和省略两种 SQL 变体，`<choose>` 会枚举 `when/otherwise` 分支。因此同一个 mapper statement 可能在 inventory 里出现多行，共享同一个 `at`，仅 `sql` 不同。

## Agent 暴露参数

只向 agent 暴露下面一个必填参数，两个可选参数：

```ts
type ScanRouteSqlSkillInput = {
  projectPath: string;
  outputPath?: string;
  configPath?: string;
};
```

参数含义：

- `projectPath`：目标 Java 项目根目录，必须是本地绝对路径。
- `outputPath`：可选的 inventory JSON 输出路径；不传时默认写到 `${projectPath}/route-sql-inventory.json`。
- `configPath`：可选；只用于在 inventory 里填 `project` 名称，不携带任何规则。

推荐调用：

```ts
import { scanRouteSqlSkill } from "./tools/opencode-sqlscan/src/index";

const result = await scanRouteSqlSkill({
  projectPath: "/path/to/user-java-project"
});

// result.outputPath 即 route-sql-inventory.json 路径
```

## 隐藏默认值

下面这些是底层 CLI 能力，但不要作为 skill 参数暴露给 agent：

- 输出格式固定为 `compact-json`（schema v=2），扩展名 `.json`。CLI 不再支持其它格式。
- 默认扫描 `.xml` 和 `.java`，不暴露 `--include`。
- 默认使用扫描器内置排除逻辑，不暴露 `--exclude`。
- 默认使用插件内置的 scanner jar，不暴露 `jarPath`。
- 默认输出到 `${projectPath}/route-sql-inventory.json`，只有用户明确要求输出位置时才传 `outputPath`。

底层 CLI 仍保留 `--include`、`--exclude`、`--output` 等选项，供调试或高级工具链使用。skill 契约只暴露上面的最小接口。

> 旧版本曾支持 `--format`、`--failed-only`、`--fail-on` 等选项；它们已经从 CLI 中移除。

## 配置 JSON 约定

如果 skill 需要传 `configPath`，落盘的 JSON/YAML 仅支持一个字段：

```json
{ "project": "my-service" }
```

```yaml
project: my-service
```

字段语义：

- `project`：项目名称，仅用于在 inventory 输出里携带，供下游展示。

历史版本曾接收 `tables[].name/requiredColumns` 等路由规则字段；这些字段不再被扫描器解析，应改为传给服务端 SQL Analyze 接口。

## Inventory 输出契约

调用成功后会得到一份 JSON 文件，顶层结构：

```json
{
  "v": 2,
  "project": "my-service",
  "scannedAt": "2026-06-01T12:34:56",
  "sqls": [
    {
      "at": "com.example.OrderMapper.selectById(OrderMapper.xml:42)",
      "sql": "select * from order_main where order_id = #{orderId}"
    }
  ]
}
```

`at` 使用 IDEA "Copy Reference" 风格，可直接粘进 IDEA "Navigate → File" 跳转。

## Agent 行为建议

调用前：

- 确认 `projectPath` 存在且是本地 Java 项目目录。
- 如果用户没有指定输出文件名，不要询问，直接使用默认输出路径。

调用后：

- 向用户返回 inventory JSON 路径。
- 如果要做诊断，把 inventory JSON 上传给服务端，再把服务端返回展示给用户。
- 如果同一个 mapper statement 出现多条 SQL，说明动态 SQL 被枚举成了多个可能变体，不应解释成重复扫描。
- 如果扫描器异常退出（exit code 3），展示 stderr 中的核心错误。

## 不应暴露给用户的选择

agent 不应询问用户要 `json`、`markdown` 还是 `xlsx`，输出永远是 compact-json。也不应询问是否启用 failed-only、是否设置 include/exclude glob、退出码策略。除非用户明确进入调试模式，否则这些属于底层扫描器选项，不属于 skill 语义。

## 与服务端的关系

- 扫描器：本地静态提取 SQL → inventory JSON。
- 服务端：收到 inventory JSON → 规范化、表名提取、路由字段校验、兼容性检查 → 诊断报告。

诊断报告（含 xlsx 等可读形式）由服务端产出。skill 不再在本地生成 xlsx。
