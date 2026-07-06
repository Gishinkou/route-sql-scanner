# datasource-binding.json 字段契约

> `DongDalDataSourceMapping` 产出、`DongDalRouteSqlScan` 透传、服务端消费。定义把 SQL 出处映射到
> dataSource 的规则，以及每个 dataSource 的分片形态（决定服务端选单库还是分库校验器）。

## 顶层结构

```json
{
  "version": "datasource-binding/v1",
  "source": "DongDalDataSourceMapping",
  "generatedAt": "2026-07-02T10:00:00+08:00",
  "projectName": "<业务工程名>",
  "dataSources": [
    {
      "dataSourceId": "orderDataSource",
      "kind": "appDsId",
      "appDsId": 12345,
      "sqlSessionFactory": "orderSqlSessionFactory",
      "shardingShape": "sharded"
    },
    {
      "dataSourceId": "userDataSource",
      "kind": "appDsId",
      "appDsId": 67890,
      "shardingShape": "single"
    },
    {
      "dataSourceId": "onboardDraft",
      "kind": "virtual",
      "routeRulesRef": "route-sql-rule-list.json",
      "shardingShape": "sharded"
    }
  ],
  "rules": [
    {
      "ruleId": "order-ds-rule",
      "dataSourceId": "orderDataSource",
      "matchers": [
        { "field": "namespace",    "op": "startsWith", "value": "com.xxx.order.mapper." },
        { "field": "resourcePath", "op": "glob",       "value": "mapper/order/**/*.xml" }
      ],
      "confidence": "high",
      "notes": ""
    }
  ],
  "unresolved": [
    { "atHint": "com.xxx.util.RawJdbcDao#exec", "reason": "裸 JDBC，无 namespace/factory 归属" }
  ]
}
```

## dataSources[] 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `dataSourceId` | 是 | 绑定内唯一标识，`rules[].dataSourceId` 引用它。 |
| `kind` | 是 | `appDsId`（真实发布态数据源）/ `virtual`（AutoOnboard 规划草案，无 appDsId）。 |
| `appDsId` | kind=appDsId 时是 | 发布态应用数据源 id；服务端据此加载真实分片配置。 |
| `routeRulesRef` | kind=virtual 时是 | 指向同目录的 `route-sql-rule-list.json`（或内联规则来源标签），服务端用其逻辑表+路由字段构造 virtual 分片上下文。 |
| `sqlSessionFactory` | 否 | 归属来源的 factory bean 名，仅供展示/排查。 |
| `shardingShape` | 否 | `single`（单库单表，服务端走 `DongDalSingleTableValidator`）/ `sharded`（分库分表，走 `DongDalCompatibilityValidator`）。缺省时服务端按该 dataSource 的实际配置（`isSingleDbSingleTable`）自判。 |

> `shardingShape` 是**提示**，不是权威。对 `kind=appDsId`，服务端以发布态配置的单库/分库判定为准；
> 对 `kind=virtual`，以 `route-sql-rule-list.json` 是否含有效路由字段判定（无路由字段→single）。

## rules[] 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `dataSourceId` | 是 | 命中该 rule 的 SQL 归属到此 dataSource。 |
| `matchers` | 是 | 一组 matcher，**同一 rule 内 matcher 之间是 OR**（任一命中即归属）；priority 见下。 |
| `ruleId` | 否 | 展示/排查用。 |
| `confidence` | 否 | `high`（namespace+resourcePath 双源纯代码）/ `medium` / `low`（LLM 推断或弱信号），供分级。 |
| `notes` | 否 | 推断依据 / 待确认说明。 |

### matcher

| 字段 | 说明 |
|---|---|
| `field` | `namespace` / `statementId` / `resourcePath` / `sourcePath` / `sourceType` / `enclosingClass`。须与 inventory enriched origin 字段同名。 |
| `op` | `startsWith` / `glob` / `contains` / `equals`。 |
| `value` | 匹配值。 |

**matcher 优先级（服务端归属时的权重）**：

```text
1. namespace / statementId 前缀   （MyBatis 语义最强）
2. resourcePath glob              （工程组织，次之）
3. sourcePath contains / enclosingClass  （裸 SQL 弱信号）
```

## 归属分级（服务端输出，写入诊断 sheet）

| 状态 | 含义 |
|---|---|
| `CONFIRMED` | namespace 前缀与 resourcePath glob 同时命中同一 dataSource。 |
| `INFERRED` | 只有 namespace 或只有 resourcePath 命中。 |
| `CONFLICT` | 多条 rule 命中**不同** dataSource。 |
| `UNRESOLVED` | 无 rule 命中；服务端降级为「整仓单一上下文」或标注待人工归属。 |

服务端归属到某 dataSource 后，**只加载该 dataSource 的分片配置**，按 `shardingShape` 选校验器：

- `single` → `DongDalSingleTableValidator`（零配置单库主链路，不误报缺分库字段）。
- `sharded` → `DongDalCompatibilityValidator`（构造时传该表 `logicTableName.routeField` 路由键）。

这解决了旧链路「所有数据源规则平铺 OR、单库 SQL 被分库规则误判」的问题。

## sourceType → 可匹配字段（不同来源用不同字段）

| sourceType | 可靠 matchFields |
|---|---|
| `mybatis_xml` | `namespace`, `statementId`, `resourcePath` |
| `mybatis_annotation` | `namespace`(=接口全名), `statementId`(=方法名) |
| `java_literal_sql` | `sourcePath`, `enclosingClass` |

不要用同一套字段匹配所有 sourceType。

## 精简约束

- 必填仅 `version` + `dataSources[].dataSourceId/kind` + `rules[].dataSourceId/matchers`。其余可选，服务端忽略未知字段。
- 单数据源工程允许只有一条 catch-all rule（`{"field":"namespace","op":"startsWith","value":""}`）。
- 无法归属的出处进 `unresolved`，**不伪造归属**。
