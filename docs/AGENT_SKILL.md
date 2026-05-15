# route-sql-scanner AI Agent Skill 说明

这份文档面向 IDEA 插件内的 AI agent。它把底层 `route-sql-scanner scan` CLI 收敛成一个小接口：agent 只负责准备“要扫哪个项目”和“用哪个分库分表规则 JSON”，其余扫描与报告选项由 skill 默认值托管。

## Skill 语义

当用户希望检查 Java 项目里的 MyBatis/JDBC SQL 是否满足分库分表路由字段完整性要求时，使用本 skill。

典型链路：

1. 用户提供分库分表中间件配置 id、目标项目等必要业务信息。
2. AI agent 通过 MCP 从远程系统拉取该配置 id 对应的分库分表规则 JSON。
3. AI agent 将 JSON 保存成本地临时配置文件。
4. AI agent 调用本 skill 扫描目标 Java 项目。
5. skill 返回一份固定为 `.xlsx` 的诊断报告路径。

本 skill 不负责向远程系统取配置，也不把配置 id 直接传给扫描器。配置 id 属于上游 MCP 取数流程；扫描器只接收已经落盘的规则 JSON 文件。

## Agent 暴露参数

只向 agent 暴露下面两个必填参数，一个可选参数：

```ts
type ScanRouteSqlSkillInput = {
  projectPath: string;
  configPath: string;
  outputPath?: string;
};
```

参数含义：

- `projectPath`：目标 Java 项目根目录，必须是本地绝对路径。
- `configPath`：MCP 拉取后落盘的分库分表规则 JSON 文件路径，必须是本地绝对路径。
- `outputPath`：可选的 `.xlsx` 报告输出路径；不传时默认写到 `${projectPath}/route-sql-diagnostics.xlsx`。

推荐调用：

```ts
import { scanRouteSqlSkill } from "./tools/opencode-sqlscan/src/index";

const result = await scanRouteSqlSkill({
  projectPath: "/path/to/user-java-project",
  configPath: "/tmp/route-sql-config.json"
});

// result.outputPath 即 xlsx 报告路径
```

## 隐藏默认值

下面这些是底层 CLI 能力，但不要作为 skill 参数暴露给 agent：

- 输出格式固定为 `.xlsx`。底层 CLI 使用 `--format excel`，文件扩展名使用 `.xlsx`。
- 默认只输出未通过校验的 SQL 诊断，相当于固定启用 `--failed-only`。
- 默认扫描 `.xml` 和 `.java`，不暴露 `--include`。
- 默认使用扫描器内置排除逻辑，不暴露 `--exclude`。
- 默认不让诊断影响 agent 调用成功失败，相当于固定 `--fail-on NEVER`。
- 默认使用插件内置的 scanner jar，不暴露 `jarPath`。
- 默认输出到 `${projectPath}/route-sql-diagnostics.xlsx`，只有用户明确要求报告位置时才传 `outputPath`。
- 默认超时使用适配层配置；只有产品侧需要治理长任务时才在内部调整，不作为用户意图参数。

底层 CLI 仍保留 `format`、`include`、`exclude`、`fail-on`、`failed-only`、`output` 等选项，供调试、回归测试或高级工具链使用。agent skill 的契约只暴露上面的最小接口。

## 配置 JSON 约定

agent 从 MCP 拉到的 JSON 应落盘为扫描器支持的轻量规则格式：

```json
{
  "dialect": "mysql",
  "defaultSeverity": "ERROR",
  "tables": [
    {
      "name": "orders",
      "requiredColumns": ["tenant_id", "order_id"],
      "operations": ["SELECT", "UPDATE", "DELETE"]
    }
  ]
}
```

字段语义：

- `name`：需要检查的表名，也兼容 `table`、`tableName`。
- `requiredColumns`：WHERE 中必须出现的路由列，也兼容 `columns`、`routeColumns`、`routeFields`。
- `operations`：可省略，默认检查 `SELECT/UPDATE/DELETE`。
- `requireAll`：可省略，默认 `true`，表示必要列必须全部出现。
- `dialect`：可省略，默认 `mysql`。
- `defaultSeverity`：可省略，默认由扫描器规则配置决定；推荐远程配置生成时填 `ERROR`。

## Agent 行为建议

调用前：

- 确认 `projectPath` 存在且是本地 Java 项目目录。
- 确认 MCP 返回的配置 JSON 已保存到 `configPath`。
- 如果用户没有指定报告文件名，不要询问，直接使用默认输出路径。

调用后：

- 向用户返回 `.xlsx` 报告路径。
- 简要说明报告只包含未通过分库分表字段完整性校验的 SQL。
- 如果扫描器异常退出，展示 stderr 中的核心错误，并保留 `configPath` 方便排查。

## 不应暴露给用户的选择

agent 不应询问用户要 `json`、`markdown` 还是 `xlsx`，默认就是 `xlsx`。也不应询问是否启用 `failed-only`、是否让命令按 ERROR 退出、是否设置 include/exclude glob。除非用户明确进入调试模式，否则这些属于底层扫描器选项，不属于 skill 语义。
