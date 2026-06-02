# 精简 route-sql-scanner 客户端 + 服务端联动方案（迭代计划）

> **状态（2026-06-01）：已落地。** 双端 v2 迭代已实施，本文保留作设计原稿。
> 当前实际行为以以下文档为准：
> - 服务端契约：[`sql-remote-analysis-api.md`](./sql-remote-analysis-api.md)
> - 输入 & 诊断分层：[`sql-analyze-input-and-diagnostics.md`](./sql-analyze-input-and-diagnostics.md)
> - 客户端 skill 修改指南：[`agent-skill-compact-sql-modification-guide.md`](./agent-skill-compact-sql-modification-guide.md)
>
> 与原计划相比的实际差异：
> - 服务端**未保留过渡期**，直接只支持 compact-json v=2，旧 4 种格式分支与 `InventoryMarkdownParser`
    >   一并删除；非 compact-json 系列别名直接 `400 UNSUPPORTED_FORMAT`。
> - `dynamic=true` 时 SDK 兼容性校验被跳过（写一行 `errorType=DynamicSqlSkipped`，不计入
    >   `compatibilityFailureCount`），与 §6 风险表的兜底策略一致。

面向 coding agent 编程；目标是把客户端退化为”纯 SQL 清单生产者”，所有诊断逻辑收敛到服务端，并把客户端→服务端的传输负载和字段数量降到最小可用集。

参考：
- 客户端现状：`ref/route-sql-scanner-src/`（待修改副本，本仓库通过 skill 拉取后编译）
- 服务端入口：[`SqlAnalyzeController#analyze`](../dongdal-server-view/src/main/java/com/jd/dal/server/controller/SqlAnalyzeController.java)、`DongDalMCPServiceImpl#analyzeSqlInventory`
- 现状服务端诊断分层：`docs/sql-analyze-input-and-diagnostics.md`
- HTTP 契约：`docs/sql-remote-analysis-api.md`
- 同源讨论：`docs/compact-sql-scanner.md`

---

## 0. 目标与非目标

**目标**
1. 客户端只负责扫描出 SQL 清单 + 出处定位，不再做任何规则诊断、不再产出 xlsx/markdown。
2. 客户端只保留一种输出格式（`compact-json`，新版），删除 `json / jsonl / markdown / excel / normalized` 五种。
3. 每条 SQL 的 JSON 对象只保留“服务端做诊断必需的字段”，删掉 hash、parse、normalized 等冗余字段。
4. 出处定位收敛为**单字段** `at`，使用 IDEA "Copy Reference" 风格。
5. 服务端接口在不破坏现网客户端的前提下接受新格式，并在过渡期结束后下线旧 4 种格式分支。

**非目标**
- 不重写 SQL 抽取逻辑（MyBatis / JDBC 两路保持不变）。
- 不动现网 `appDsId / appName / appDsName / env / version` 等增强诊断的入参。
- 不引入异步 / 分片上传（HTTP 契约仍是同步 multipart）。

---

## 1. 新输入格式：`compact-json`

服务端新增并最终唯一支持的 inventory 格式。

### 1.1 顶层结构

```json
{
  "v": 2,
  "project": "my-service",
  "scannedAt": "2026-06-01T12:34:56",
  "sqls": [
    {
      "at": "com.example.OrderMapper#selectById(OrderMapper.java:42)",
      "sql": "select * from order_main where order_id = #{orderId}",
      "dynamic": false
    },
    {
      "at": "OrderMapper.xml:selectByStatus(OrderMapper.xml:88)",
      "sql": "select * from order_main where status = #{status}",
      "dynamic": true
    }
  ]
}
```

字段定义（**严格**——多余字段服务端会忽略，缺字段直接 422）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `v` | int | 是 | inventory schema 版本，新格式恒为 `2`（v1 = 旧 4 格式合集，由旧分支处理） |
| `project` | string | 否 | 项目名，仅展示用；缺失服务端从 `projectName` form 字段兜底 |
| `scannedAt` | string | 否 | 扫描时间，ISO 本地时间字符串；仅展示 |
| `sqls` | array | 是 | SQL 清单；空数组合法但触发 warning |
| `sqls[].at` | string | 是 | IDEA Copy Reference 风格的单字段出处定位（见 1.3） |
| `sqls[].sql` | string | 是 | **raw SQL**（保留 MyBatis 占位符与动态片段），服务端规范化后再做诊断 |
| `sqls[].dynamic` | bool | 否 | 是否动态 SQL；缺省 `false`，仅用于服务端兼容性校验时降噪误报 |

### 1.2 被显式删除的字段（与现状对照）

| 旧字段 | 删除原因 |
| --- | --- |
| `identity.stableId` / `identity.contentHash` | 哈希无可读性；服务端要重新算 hash 也只需基于 `sql + at`，由服务端自己负责 |
| `identity.sourceKey` / `identity.logicalName` | 与 `at` 重复 |
| `origin.namespace` / `origin.statementId` / `origin.className` / `origin.methodName` / `origin.column` / `origin.kind` | 全部合并进 `at` |
| `parse.statementType` / `parse.tables` / `parse.parseError` | 服务端要重做解析；客户端解析结果会过期 |
| `normalizedSql` | 服务端规范化更靠谱；只传 raw 反而让兼容性校验更贴近真实 |
| `tags` / `attributes` | 当前无消费方 |
| 顶层 `version` / `dialect` / `summary` / `diagnostics` | 诊断已收敛到服务端，scanner 不再做规则；`version` 由 `v` 替代 |

### 1.3 `at` 字段格式约定（IDEA Copy Reference 风格）

固定为 `<symbol>(<file>:<line>)`，方便 IDEA 直接粘进 "Navigate → File"。三种来源分别约定：

| 来源 | `at` 格式 | 例 |
| --- | --- | --- |
| `MYBATIS_XML` | `<namespace>.<statementId>(<file>:<line>)` | `com.example.OrderMapper.selectById(OrderMapper.xml:42)` |
| `MYBATIS_ANNOTATION` | `<className>#<methodName>(<file>:<line>)` | `com.example.OrderMapper#selectById(OrderMapper.java:88)` |
| `JAVA_JDBC` | `<className>#<methodName>(<file>:<line>)` | `com.example.OrderDao#countByStatus(OrderDao.java:123)` |

实现注意：
- `<file>` 仅取文件名（不带相对/绝对路径），与 IDEA 行为一致。
- 出现匿名内部类等无 `methodName` 时回退到 `<className>(<file>:<line>)`。
- column 信息直接丢弃（IDE 跳转用不到）。

### 1.4 大小预算

旧 JSON 一条 SQL 约 600–1200 字节；新 `compact-json` 一条约 80–200 字节，体积降幅 ~70–85%。20 MiB 文件上限保持不变。

---

## 2. 客户端迭代计划（`ref/route-sql-scanner-src/`）

变更范围概览：

```
新增  com/acme/routesql/report/CompactInventoryReporter.java
新增  com/acme/routesql/model/CompactSqlEntry.java  (record)
新增  com/acme/routesql/util/AtFormatter.java
修改  com/acme/routesql/cli/ScanCommand.java         （删格式选项）
修改  com/acme/routesql/config/ReportConfig.java     （默认格式 + 校验）
删除  com/acme/routesql/report/{Json,Jsonl,Markdown,Excel,NormalizedSql,CompactJson}Reporter.java
删除  com/acme/routesql/report/{CompactDiagnosticRow,CompactDiagnosticRows,ReportFilters}.java
删除  com/acme/routesql/rule/                          （客户端不再跑规则）
删除  com/acme/routesql/parse/SqlParserFacade.java     （客户端不再解析）
删除  com/acme/routesql/normalize/SqlNormalizer.java   （客户端不再规范化）
修改  com/acme/routesql/core/ScanEngine.java          （去掉 parse / rule / normalize 流水线）
修改  com/acme/routesql/model/SqlObject.java          （瘦身或仅作内部模型，不再被序列化）
```

### 2.1 第 1 步：新增 `CompactInventoryReporter`

伪代码：

```java
public class CompactInventoryReporter implements Reporter {
  private static final int SCHEMA_VERSION = 2;
  private final ObjectMapper mapper = new ObjectMapper();

  @Override public byte[] renderBytes(ScanReport report) throws Exception {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("v", SCHEMA_VERSION);
    if (report.project() != null) out.put("project", report.project());
    out.put("scannedAt", LocalDateTime.now().toString());
    List<Map<String, Object>> sqls = new ArrayList<>();
    for (SqlObject so : report.sqlObjects()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("at", AtFormatter.format(so.origin()));
      row.put("sql", so.rawSql());
      if (so.dynamic()) row.put("dynamic", true);
      sqls.add(row);
    }
    out.put("sqls", sqls);
    return mapper.writeValueAsBytes(out);
  }
}
```

`AtFormatter` 根据 `SourceKind` 走 1.3 的三种模板，文件名用 `origin.file().getFileName().toString()`。

### 2.2 第 2 步：精简 `ScanCommand` 选项

CLI 表面只保留 4 个开关，全部不再面向格式选择：

```text
--path        必填，可多次
--config      可选，YAML/JSON
--output      可选，缺省 stdout
--include / --exclude  可选，仍旧支持
```

删除：`--format`、`--failed-only`、`--fail-on`。

退出码规则改为：扫描完成 → 0；I/O 或解析失败 → 3。客户端不再判定 ERROR/WARN（没诊断就没等级）。

### 2.3 第 3 步：删客户端诊断/解析/规范化

`ScanEngine` 改为只跑 `extract` 一步：

```java
ScanReport scan(...) {
  List<SqlObject> objects = sqlExtractor.extract(paths, includes, excludes);
  return new ScanReport(projectName(), objects);  // 删 summary / diagnostics 字段
}
```

`SqlObject` 仅保留 `origin`, `rawSql`, `dynamic` 三个真用得到的字段；`parse` / `normalizedSql` / `identity` / `tags` / `attributes` 全删。`ScanReport` 同步瘦身。

依赖删除：JSqlParser、POI（Excel）、所有规则配置类。`pom.xml` 同步移除。

### 2.4 第 4 步：脚本与文档

- `src/main/resources/skills/dongdal/tools/opencode-sqlscan/src/generate-remote-sql-report.sh`：去掉 `--format` 透传，固定生成 `route-sql-inventory.json`；上传时 `inventoryFormat=compact-json`。
- 客户端 README 重写：只剩“扫描 → 上传”两步，移除规则/诊断章节。

---

## 3. 服务端迭代计划

主要文件：

```
修改  SqlAnalyzeController.java               （新增 compact-json 格式常量 + 嗅探）
修改  InventoryScannerParser.java              （新增 parseCompactJson 分支）
修改  DongDalMCPServiceImpl#analyzeSqlInventory（兼容 SqlObjectMeta 来源变化）
修改  InventoryMarkdownParser.SqlObjectMeta    （字段瘦身，见 3.2）
新增  InventoryAtParser.java                   （解析 "at" 字段，回填 originFile/originLine/logicalName）
```

### 3.1 第 1 步：新增 `compact-json` 格式

`SqlAnalyzeController`：

```java
private static final String FORMAT_COMPACT_JSON = "route-sql-scanner-compact-json";
// normalizeInventoryFormat 增加 case：
case "compact-json":
case "compact":
case FORMAT_COMPACT_JSON:
    return FORMAT_COMPACT_JSON;
// inferInventoryFormat 增加：以 '{' 开头且能解析出 `"v":2` 时 → FORMAT_COMPACT_JSON
```

`InventoryScannerParser.parse`：

```java
if (FORMAT_COMPACT_JSON.equals(normalizedFormat)) {
    return parseCompactJson(inventoryText);
}
```

`parseCompactJson` 行为：
- 校验 `v == 2`，否则抛 `UNPARSEABLE_INVENTORY`。
- 遍历 `sqls`，对每条产出一条 `SqlObjectMeta`：
  - `rawSql = sqls[i].sql`
  - `normalizedSql = SqlNormalizer.normalize(sqls[i].sql)`（服务端侧的规范化器，详见 3.3）
  - `(originFile, originLine, logicalName) = InventoryAtParser.parse(sqls[i].at)`
  - `stableId = null` / `sourceKey = null` / `tables = []`（服务端不再依赖）
- `Parsed.inventoryRows` 列改为：`At | Raw SQL | Normalized SQL | Dynamic`（与新 xlsx Sheet 对齐）。
- `Parsed.summary` 写入：`Project`、`Scanned At`、`SQL count`。`diagnostics` 段为空——客户端不再产出 scanner 自检诊断。

### 3.2 `SqlObjectMeta` 字段瘦身

```java
public static class SqlObjectMeta {
    public final String at;            // 新：单字段定位
    public final String normalizedSql;
    public final String rawSql;
    public final boolean dynamic;
    // 删：stableId / sourceKey / logicalName / originFile / originLine / tables
}
```

下游 `appendRouteFieldDiagnostics` / `enrichWithDongDalAnalysis` 使用方同步改造：
- `originText(file, line)` 改为直接读 `at`。
- 路由字段检测仍然用 `normalizedSql.toLowerCase()` + `extractSqlTableNames`，逻辑不变。

### 3.3 服务端规范化器

把 `ref/route-sql-scanner-src/.../normalize/SqlNormalizer.java` 迁到 `dongdal-server-manager/src/main/java/com/jd/dal/server/manager/impl/sqlanalyze/SqlNormalizer.java`（同 package 现有 `validator/`），保留必要功能：
- 折叠 MyBatis `#{x}` / `${x}` → `?`
- 去掉 `/* */` 与行注释
- 折叠连续空白

`DongDalCompatibilityValidator` 接收到的 SQL 已是规范化后的，行为与旧链路一致。

### 3.4 xlsx 报告改造

`SQL Inventory` sheet 列简化为：`At | Dynamic | Normalized SQL | Raw SQL`（4 列，去掉 stableId/sourceKey/logicalName/originFile/originLine/parseError/tables）。

`Diagnostics` sheet 在新格式下永远为空 → 直接跳过该 sheet（沿用现状判定：`!parsed.diagnostics.isEmpty()` 才输出）。

`Route Field Check` / `DongDal Compatibility` sheet 的首列由现状的 `Stable ID | Logical Name | Origin` 三列合并为单列 `At`，去掉冗余。

### 3.5 兼容期与切换策略

服务端**短期同时支持新旧 5 种格式**（旧 4 + 新 1）：

1. 新 controller 上线后，客户端 skill 一并切到 `compact-json`。
2. 一个发布周期后，监控旧格式实际请求量；为 0 时打开 deprecation 日志（旧格式仍可用，但每次记 WARN）。
3. 再一个发布周期后，删除旧 4 种格式分支与对应 parser 代码、删除 `InventoryMarkdownParser` 全文。

切换期间 controller 的 `inferInventoryFormat` 优先级：`compact-json (v==2) > json > markdown > normalized`，避免误判。

---

## 4. HTTP 契约调整（兼容补丁）

`docs/sql-remote-analysis-api.md` 同步：

| 字段 | 旧 | 新 |
| --- | --- | --- |
| `inventoryFormat` 推荐值 | `route-sql-scanner-markdown` | `compact-json` |
| `file` `Content-Type` 推荐 | `text/plain` | `application/json` |
| 默认文件名 | `route-sql-inventory.txt` | `route-sql-inventory.json` |

错误码扩充：

| 状态码 | 新增场景 |
| --- | --- |
| `422 UNPARSEABLE_INVENTORY` | `v` 字段缺失或 `!= 2`；`sqls` 不是数组；某条 `sql` 字段为空 |

`McpSqlAnalyzeRsp` 不变。

---

## 5. 实施步骤（双端 PR 切分）

按下面顺序合入，可以保证任何中间步骤都能跑通：

| # | PR | 作用域 | 上线后状态 |
| --- | --- | --- | --- |
| 1 | 服务端：新增 `compact-json` 解析、`SqlNormalizer` 内置、xlsx 列调整 | server | 同时支持新旧 5 种格式 |
| 2 | 客户端：新增 `CompactInventoryReporter` + `AtFormatter`，CLI 增加 `--format compact-json` 选项（暂不删旧格式） | scanner | 客户端默认仍旧格式，可手动切新格式自测 |
| 3 | 客户端：CLI 默认 `compact-json`，删除 `--format/--failed-only/--fail-on` 选项，删除旧 reporter / 规则 / 解析依赖 | scanner | 客户端只产出 compact-json |
| 4 | 客户端脚本：`generate-remote-sql-report.sh` 切到 compact-json + `inventoryFormat=compact-json` | skill | 端到端走新路径 |
| 5 | 服务端：观察周期结束后，删除旧 4 种格式分支与 `InventoryMarkdownParser` | server | 唯一格式 compact-json |
| 6 | 文档：更新 `sql-remote-analysis-api.md`、`sql-analyze-input-and-diagnostics.md` 至最终态 | docs | — |

每个 PR 都需要附带：

- 单测：`InventoryScannerParserTest` 增 compact-json fixture；`AtFormatter` 三种 SourceKind 各一例。
- 端到端：`generate-remote-sql-report.sh --dry-run` 跑通固定 fixture 工程，diff inventory 输出。
- 灰度：服务端 PR1 上线后用旧客户端跑一次回归，确认旧格式仍走通。

---

## 6. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| `at` 单字段在某些动态生成 SQL 处理路径里信息不足，影响人工溯源 | 保留 raw SQL 全文；`at` 不可解析时服务端回填 `"(unknown)"` 而不是报错 |
| 服务端规范化器与客户端旧规范化器行为差异导致 Compatibility 校验结果抖动 | PR1 上线后跑一次回归对比 `route-sql-analysis.xlsx` 的 `DongDal Compatibility` 列；如有差异先修服务端规范化器，再放量 |
| 旧客户端长期不升级，导致服务端无法清理旧分支 | 在过渡期内对旧格式请求加 WARN 日志 + 在响应头加 `X-Inventory-Format-Deprecated: true`，便于推动升级 |
| `dynamic=true` 时兼容性校验误报率上升 | `DongDalCompatibilityValidator` 在 `meta.dynamic` 为 true 时把结果 `errorType` 标为 `DynamicSqlSkipped` 并 `supported=true` 兜底（仅记录解析失败而不计入 `compatibilityFailureCount`） |

回滚：客户端 PR 单独 revert 即可；服务端只增不删的阶段（PR1/PR2/PR3/PR4）无破坏性变更；PR5 删旧分支前需要确认监控 7 天内旧格式请求为 0。
