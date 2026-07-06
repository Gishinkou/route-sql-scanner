# scanner enriched origin 字段实现总结

> **状态（2026-07-06）：已落地。** route-sql-scanner v0.3.0 起，compact-json v=2 的每条
> `sqls[]` 追加可选结构化 `origin`，供服务端 `datasourceBinding` 归属使用。
> 字段口径以 [`sql-remote-analysis-api.md`](./sql-remote-analysis-api.md)「enriched origin 字段」小节
> 与 [`datasource-binding-schema.md`](./datasource-binding-schema.md)「sourceType → 可匹配字段」为准。

## 目标

服务端按 SQL 出处归属数据源时，仅靠展示用的 `at` 字符串反解 namespace/path 不可靠。因此 scanner 为
每条 SQL 追加一个结构化 `origin`，让服务端能做前缀 / glob 匹配，而无需反解 `at`。

- `origin` 全部字段可选；不带 `origin` 的旧 inventory 仍合法。
- 只改采集 / 序列化逻辑，**上传链路未动**。
- 服务端只在 `datasourceBinding` 存在时读取 `origin`；缺省时忽略。

## 输出示例

```json
{
  "at": "com.acme.OrderMapper.findOrders(OrderMapper.xml:12)",
  "sql": "select * from order_main where order_id = #{orderId}",
  "origin": {
    "sourceType": "mybatis_xml",
    "namespace": "com.acme.OrderMapper",
    "statementId": "findOrders",
    "resourcePath": "mapper/order/OrderMapper.xml",
    "sourcePath": "src/main/resources/mapper/order/OrderMapper.xml"
  }
}
```

## sourceType → 字段映射

`sourceType` 由 scanner 内部 `SourceKind` 映射而来，决定哪些字段有值（与 binding schema
「sourceType → 可匹配字段」一致，不同来源用不同字段，不强行套同一套）：

| SourceKind (内部) | sourceType (输出) | 输出字段 |
|---|---|---|
| `MYBATIS_XML` | `mybatis_xml` | `namespace` / `statementId` / `resourcePath` / `sourcePath` |
| `MYBATIS_ANNOTATION` | `mybatis_annotation` | `namespace` / `statementId` / `sourcePath` |
| `JAVA_JDBC` | `java_literal_sql` | `enclosingClass` / `sourcePath` |

- `mybatis_annotation` 来源是 `.java` 接口，不是 classpath 资源，故**不产 `resourcePath`**。
- null / 空白字段一律省略，保持 JSON 精简。
- 未识别的 `SourceKind`（如 `FUTURE_IBATIS_XML`）不产 `origin`。

## 路径基准

`origin.file` 内部是绝对路径；契约要求相对路径，故引入 project-root 基准：

- `sourcePath`：相对 project-root，正斜杠分隔；file 不在 root 下时省略。
- `resourcePath`：从 `src/main/resources/`、`src/test/resources/`、`src/main/java/`、`src/test/java/`
  锚点之后切出的 classpath 相对路径；无锚点时省略。

project-root 解析优先级（`ScanCommand`）：

```text
1. --project-root 显式传入
2. 配置文件 projectRoot 字段
3. 单个目录型 --path 兜底（常见场景仍能产出相对路径）
4. 否则为 null → sourcePath / resourcePath 省略，其余字段仍在
```

CLI 用法：

```bash
java -jar route-sql-scanner.jar scan \
  --path /path/to/project \
  --project-root /path/to/project \
  --output route-sql-inventory.json
```

## 改动文件

| 文件 | 改动 |
|---|---|
| `report/CompactInventoryReporter.java` | 核心序列化点：每行追加可选 `origin`，按 `sourceType` 分发字段 |
| `util/OriginPaths.java`（新） | 纯路径逻辑：`sourcePath` / `resourcePath` 归一化 |
| `cli/ScanCommand.java` | 新增 `--project-root` 选项 + 解析优先级 |
| `config/ScannerConfig.java` | 新增 `projectRoot` 字段（可从 config 提供） |
| `ScanEngineTest.java` | 更新 v2 断言 + 新增按 sourceType 的字段口径测试 |

## 向后兼容

- `origin` 全字段可选，旧 inventory 合法。
- 未识别来源 / 无 project-root 时优雅降级（省略字段，不报错）。
- 未改上传链路与 `at` 字段格式。
