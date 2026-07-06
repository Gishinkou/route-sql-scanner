# SQL Remote Analysis API 契约

## 目标

给服务端 / 客户端 Agent 开发使用：定义"接收本地 route-sql-scanner 生成的 compact-json
inventory，同步分析并直接返回 xlsx"的最小接口。本文只定义接口契约，不展开服务端内部诊断逻辑
（诊断分层详见 `sql-analyze-input-and-diagnostics.md`）。

客户端调用链路固定为：

```text
本地 route-sql-scanner 生成 route-sql-inventory.json (compact-json v=2)
  -> curl multipart/form-data 上传 json
  -> 服务端同步分析（含 dongdal 路由 / SDK 兼容性校验）
  -> HTTP 响应体直接返回 report.xlsx
  -> 客户端保存为本地 xlsx 文件
```

服务端**只接受**一种 inventory 编码：`compact-json v=2`。

## 接口

```http
POST /api/sql/analyze
Content-Type: multipart/form-data
Accept: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
```

客户端固定使用：

```text
http://pre.dal.jd.local/api/sql/analyze
```

## 请求字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | file | 是 | 本地 `route-sql-inventory.json`（compact-json v=2），`Content-Type` 建议 `application/json`。 |
| `projectName` | string | 否 | 项目展示名，用于 xlsx Summary。 |
| `projectPath` | string | 否 | 客户端本地项目根路径，只作展示/排查，服务端不会访问。 |
| `scanPath` | string | 否 | 客户端实际扫描范围，只作展示/排查。 |
| `diagnosticScope` | string | 否 | 诊断输出范围；默认 `issues` 仅输出问题行，传 `all` 输出全量诊断行。 |
| `appDsId` | string | 否 | 一个或多个 dongdal 应用数据源 id，多个用英文逗号分隔；传入即启用 dongdal 路由字段检测 + SDK 兼容性校验。 |
| `routeRules` | string | 否 | 内联规则 JSON：可以是数组，也可以是含 `rules` 数组的对象；用于没有 `appDsId` 的 AutoOnboard 场景，也可与 `appDsId` 同传做交叉校验。 |
| `routeRulesSource` | string | 否 | 内联规则来源标签，默认 `inline`，例如 `auto-onboard`。 |
| `datasourceBinding` | string | 否 | 内联 `datasource-binding.json`（对象，见下）：把 SQL 出处按 `namespace/resourcePath` 前缀映射到某个 dataSource；服务端据此**先归属、再按该 dataSource 的单库/分库形态选对应校验器**，避免单库 SQL 被分库规则误判。 |

| `env` | string | 否 | 与 `appDsId` 指向的数据源环境不匹配 → `400 ENV_MISMATCH`。 |
| `version` | string | 否 | 仅透传 / 写日志。 |

`appDsId` 或 `routeRules` 存在 → 启用 dongdal 增强诊断；两者同时存在 → 交叉校验同名表路由字段冲突；都不传 → 跳过路由字段增强诊断，xlsx 只剩 Summary + SQL Inventory 两张 sheet。

`datasourceBinding` 存在时，服务端改为**按数据源归属**诊断：每条 SQL 先经 binding 归属到某个 `dataSourceId`，只加载该数据源的分片配置做校验，而不是把所有 `appDsId` / `routeRules` 平铺 OR。binding 缺省时沿用旧的「平铺 OR」链路（向后兼容）。契约与归属分级见本文「§ datasource-binding 归属与单库/分库分流」。

客户端示例：

```bash
curl -f -sS \
  -X POST "http://pre.dal.jd.local/api/sql/analyze" \
  -H "Accept: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  -F "file=@/path/to/route-sql-inventory.json;type=application/json" \
  -F "projectName=my-service" \
  -F "projectPath=/Users/me/work/my-service" \
  -F "scanPath=/Users/me/work/my-service" \
  -F "diagnosticScope=all" \
  -F "appDsId=12345,67890" \
  --form-string "routeRules={\"rules\":[{\"logicTableName\":\"t_order\",\"routeFields\":[\"user_id\"]}]}" \
  -F "routeRulesSource=auto-onboard" \
  -F "env=test" \
  -o /path/to/route-sql-analysis.xlsx
```

## Inventory 文件格式（compact-json v=2）

服务端要求文件内容是合法 JSON 对象，顶层必须含 `v=2` 与 `sqls` 数组。完整结构示例：

```json
{
  "v": 2,
  "project": "my-service",
  "scannedAt": "2026-06-01T12:34:56",
  "sqls": [
    {
      "at": "com.example.OrderMapper.selectById(OrderMapper.xml:42)",
      "sql": "select * from order_main where order_id = #{orderId}",
      "dynamic": false
    }
  ]
}
```

字段规范见 `compact-sql-scanner-iteration-plan.md §1`。服务端只读取 `v / project /
scannedAt / sqls[].{at,sql,dynamic}`，其余字段会被忽略；任一必填字段缺失 / 类型不匹配 →
`422 UNPARSEABLE_INVENTORY`。

### enriched origin 字段（v=2，可选，供 datasource-binding 归属）

启用 `datasourceBinding` 归属时，服务端需要能把每条 SQL 反查到出处。仅靠展示用的 `at` 字符串反解
namespace/path 不可靠，因此 scanner 可为每条 SQL 追加可选的结构化 `origin`：

```json
{
  "at": "com.example.order.OrderMapper.selectById(OrderMapper.xml:42)",
  "sql": "select * from order_main where order_id = #{orderId}",
  "dynamic": false,
  "origin": {
    "sourceType": "mybatis_xml",
    "namespace": "com.example.order.OrderMapper",
    "statementId": "selectById",
    "resourcePath": "mapper/order/OrderMapper.xml",
    "sourcePath": "src/main/resources/mapper/order/OrderMapper.xml",
    "enclosingClass": null
  }
}
```

- `origin` 全部字段可选；服务端只在 `datasourceBinding` 存在时读取它做前缀 / glob 匹配。
- `origin` 缺失或字段不足时，该 SQL 归属退化为 `UNRESOLVED`，服务端按 §归属分级降级处理，不报错。
- `sourceType` 取值：`mybatis_xml` / `mybatis_annotation` / `java_literal_sql`，决定可匹配字段（见 binding schema）。
- **向后兼容**：不带 `origin` 的旧 inventory 仍合法；不传 `datasourceBinding` 时 `origin` 被忽略。

- `sql` 字段保留 raw SQL（含 `#{x}` / `${x}` 占位符与注释），服务端会自行规范化后再喂给
  dongdal SDK / 路由字段检测。
- `dynamic=true` 的 SQL 仍会写入 `SQL Inventory`；dongdal SDK 兼容性校验会跳过它，且仅在
  `diagnosticScope=all` 时额外写入 `SQL Diagnostics`
  （`Error Type=DynamicSqlSkipped` / `DongDal Supported=true`），避免动态片段误报。
- 文件硬限制 20 MiB，超出 → `413 INVENTORY_TOO_LARGE`。

## 成功响应

服务端必须同步返回 xlsx 二进制内容。

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="route-sql-analysis.xlsx"
Cache-Control: no-store
```

响应体：

```text
<xlsx binary>
```

客户端直接把响应体保存为 `route-sql-analysis.xlsx`，因此成功响应不能返回 JSON。

xlsx 最小包含 `Summary` + `SQL Inventory` 两张 sheet；若启用了 dongdal 增强诊断，会按
`diagnosticScope` 追加 `SQL Diagnostics` 一张增强诊断 sheet。Sheet 详情见
`sql-analyze-input-and-diagnostics.md §5`。

`diagnosticScope` 控制增强诊断 sheet 的行范围：

- `issues`（默认）：`SQL Diagnostics` 只返回存在路由字段问题（`ERROR/WARN/CONFLICT`）或
  DongDAL 兼容性失败（`DongDal Supported=false`）的 SQL；同一条 SQL 的路由字段诊断与
  DongDAL 兼容性诊断会合并到同一行；没有问题时不生成该 sheet。
- `all`：返回全量诊断行，包含通过行、失败行以及 `dynamic=true` 的跳过行；即使没有问题也会生成
  `SQL Diagnostics` 表头，便于 Agent 客户端做自动化核对。

`SQL Diagnostics` sheet 列定义：

| 列 | 说明 |
| --- | --- |
| `At` | SQL 来源位置，来自 inventory 的 `sqls[].at`。 |
| `Rule Sources` | 参与诊断的规则来源；例如 `appDsId=12345`、`inline:auto-onboard`，多个用英文逗号分隔。 |
| `Tables` | SQL 命中的逻辑表；多表用英文逗号分隔。 |
| `Route Severity` | 路由字段诊断结果：`OK/WARN/ERROR/CONFLICT`。 |
| `Route Message` | 路由字段诊断明细；同一 SQL 多条明细使用换行合并。 |
| `DongDal Supported` | DongDAL parser/planner 兼容性结果：`true/false`。 |
| `Statement Type` | DongDAL 解析出的语句类型。 |
| `Error Type` | DongDAL 兼容性失败或跳过类型，例如 `DynamicSqlSkipped`。 |
| `Error Message` | DongDAL 兼容性失败错误信息。 |
| `Remarks` | 兼容性校验补充说明。 |
| `Normalized SQL` | 服务端规范化后的 SQL。 |

启用 `datasourceBinding` 归属时，`SQL Diagnostics` 追加两列：

| 列 | 说明 |
| --- | --- |
| `DataSource` | 归属到的 `dataSourceId`；`UNRESOLVED` 时为空。 |
| `Binding Status` | 归属分级：`CONFIRMED` / `INFERRED` / `CONFLICT` / `UNRESOLVED`。 |

## datasource-binding 归属与单库/分库分流

> 解决旧链路痛点：所有 `appDsId` / `routeRules` 平铺 OR，单库单表 SQL 被分库分表规则套用 →
> 误报「缺分库分表路由字段」。传入 `datasourceBinding` 后，服务端**先按出处归属数据源，再按该数据源
> 的分片形态选校验器**。

### binding 入参结构

`datasourceBinding` 是一个 JSON 对象，契约见
[../../DongDalDataSourceMapping/references/datasource-binding-schema.md](../../DongDalDataSourceMapping/references/datasource-binding-schema.md)。
最小要求：`version` + `dataSources[].{dataSourceId,kind}` + `rules[].{dataSourceId,matchers}`。

### 服务端归属流程

```text
for each sql in inventory:
  origin = sql.origin                         # enriched origin；缺失则 UNRESOLVED
  matched = []                                # (dataSourceId, matcher.field)
  for each rule in binding.rules:
    for each matcher in rule.matchers:        # 同 rule 内 matcher 之间 OR
      if match(origin[matcher.field], matcher.op, matcher.value):
        matched += (rule.dataSourceId, matcher.field)
  status = classify(matched)                  # CONFIRMED / INFERRED / CONFLICT / UNRESOLVED
  dataSourceId = pick(matched)                # CONFLICT/UNRESOLVED 见下
```

`classify` 规则：

- `CONFIRMED`：命中同一 `dataSourceId`，且既有 namespace/statementId 前缀命中又有 resourcePath glob 命中。
- `INFERRED`：命中同一 `dataSourceId`，但只有一类字段命中。
- `CONFLICT`：命中**不同** `dataSourceId`。按 matcher 优先级（namespace/statementId > resourcePath > sourcePath）取最高优先级者归属，并在 `Binding Status=CONFLICT` 标注全部候选。
- `UNRESOLVED`：无命中或 `origin` 不足。降级为「整仓单一上下文」（沿用旧平铺 OR）或标注待人工归属，不报错。

### 按 dataSource 选校验器（核心）

归属到某 `dataSource` 后，**只加载该 dataSource 的分片配置**，按其形态选校验器：

| dataSource 形态判定 | 用哪个校验器 | 校验语义 |
|---|---|---|
| `kind=appDsId` 且 `isSingleDbSingleTable`（发布态配置无分片规则） | `DongDalSingleTableValidator` | 单库主链路 `SinglePlanBuilder`，零配置，不做分片规划、不检缺分库字段 |
| `kind=appDsId` 且有分片规则 | `DongDalCompatibilityValidator(routingKey)` | 真跑 Planner 路由规划，检缺路由字段 |
| `kind=virtual`（AutoOnboard 草案）无有效路由字段 | `DongDalSingleTableValidator` | 同单库 |
| `kind=virtual` 有路由字段 | `DongDalCompatibilityValidator(routingKey)` | 用草案的 `logicTableName.routeField` 构造路由键 |

- `shardingShape` 是 binding 给的**提示**；`kind=appDsId` 以发布态 `isSingleDbSingleTable` 为准，`kind=virtual` 以 `route-sql-rule-list.json` 是否含有效路由字段为准。
- 校验器选型两者接口一致（`SqlValidator.validate(sql)` / `validateAll(...)`），落点见
  [../../../../../docs/scan-mapper-datasource/dongdal-validator-usage-guide.md](../../../../../docs/scan-mapper-datasource/dongdal-validator-usage-guide.md)。
- 路由字段缺失检测**只对归属到的分库 dataSource 的规则**跑，单库 dataSource 跳过 → 消除误报。

### 实现落地状态（重要）

- 本节为**契约层**：定义客户端 → 服务端的入参、归属分级、校验器选型口径。
- 服务端 `DongDalMCPServiceImpl` 当前 `runCompatibilityCheck` 仍走「合并/平铺 OR」，尚未实现按 binding 归属与 `DongDalSingleTableValidator` 分流；scanner 尚未产出 `origin` 字段与 `datasource-binding.json`。
- **依赖**：`DongDalSingleTableValidator` 需要更新版 `SQLReview-JED` jar（本地 m2 的 `1.0.0-SNAPSHOT` 尚不含该类）。jar 到位后再落服务端 Java 与 scanner 改动。

## 错误响应

错误时返回非 2xx 状态码，响应体使用 JSON，便于客户端 wrapper 直接输出错误详情。

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/json
Cache-Control: no-store
```

```json
{
  "success": false,
  "code": "UNPARSEABLE_INVENTORY",
  "message": "UNPARSEABLE_INVENTORY: 仅支持 compact-json v2，收到 v=null",
  "traceId": "optional-trace-id"
}
```

错误码对应表：

| 状态码 | code | 场景 |
| --- | --- | --- |
| `400` | `INVALID_INVENTORY` | 缺少 `file`、文件为空、文本为空白。 |
| `400` | `UNSUPPORTED_DIAGNOSTIC_SCOPE` | `diagnosticScope` 非 `issues/all`。 |
| `400` | `INVALID_ROUTE_RULES` | `routeRules` 不是 JSON 数组或含 `rules` 数组的对象，或字段类型不符合契约。 |
| `400` | `ROUTE_RULES_TOO_LARGE` | `routeRules` 超过 1 MiB。 |
| `400` | `INVALID_DATASOURCE_BINDING` | `datasourceBinding` 不是合法 JSON 对象，或缺 `dataSources[].dataSourceId/kind` / `rules[].dataSourceId/matchers`。 |
| `400` | `DATASOURCE_BINDING_TOO_LARGE` | `datasourceBinding` 超过 1 MiB。 |
| `400` | `ENV_MISMATCH` | `appDsId` 指向的数据源 env 与请求 `env` 不一致。 |
| `413` | `INVENTORY_TOO_LARGE` | 上传文件超过 20 MiB 限制。 |
| `422` | `UNPARSEABLE_INVENTORY` | JSON 结构非法、`v != 2`、`sqls` 缺失/非数组、`sqls[i].sql` 为空。 |
| `500` | `ANALYZE_FAILED` / `EMPTY_REPORT` / `INTERNAL_ERROR` | 分析阶段抛异常或生成空 xlsx。 |

## 服务端实现约束

- 同步接口：请求线程内完成解析 + dongdal 增强诊断 + xlsx 组装后直接返回。
- 服务端不读取 `projectPath` 指向的本地文件；该字段来自客户端机器，只能用于展示、审计或排查。
- 服务端把上传文件当作不可信输入：上限 20 MiB；UTF-8 文本之外的内容不保证可读。
- 成功响应必须设置 xlsx `Content-Type`，避免客户端把错误 JSON 保存成 xlsx。
- 分析失败必须返回非 200，不要返回 200 + JSON；客户端 wrapper 显式检查 HTTP 状态码，非 2xx 会输出错误 JSON 并判定失败。
- xlsx 至少保留：项目名、扫描范围、scanner 摘要（project / scannedAt / SQL count）、
  inventory 平铺表，以及（启用 dongdal 增强诊断时）`SQL Diagnostics` sheet。

## 客户端脚本

客户端侧 Agent 固定调用：

```bash
bash .claude/.claude/skills/dongdal/tools/opencode-sqlscan/src/generate-remote-sql-report.sh \
  --projectPath /path/to/java-project \
  --serverUrl http://pre.dal.jd.local/api/sql/analyze \
  --outputPath /path/to/java-project/route-sql-analysis.xlsx
```

脚本默认会先生成：

```text
/path/to/java-project/route-sql-inventory.json
```

再上传给服务端，并同步等待 xlsx 保存到 `--outputPath`。

未发布 `appDsId` 但已有 AutoOnboard 规则清单时：

```bash
bash .claude/.claude/skills/dongdal/tools/opencode-sqlscan/src/generate-remote-sql-report.sh \
  --projectPath /path/to/java-project \
  --serverUrl http://pre.dal.jd.local/api/sql/analyze \
  --routeRulesPath /path/to/java-project/dongdal-onboard/route-sql-rule-list.json \
  --routeRulesSource auto-onboard \
  --diagnosticScope all
```
