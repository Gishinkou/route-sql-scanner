# 客户端不展开 `<if>` 笛卡尔积、服务端按片段判定路由字段缺失 —— 改造方案

> 面向 coding agent 落地。在 [`compact-sql-scanner-iteration-plan.md`](./compact-sql-scanner-iteration-plan.md)
> 已落地的 `compact-json v=2` 契约之上，把"动态 SQL 笛卡尔积展开"从客户端拿掉，改由服务端按
> "片段是否提及路由字段"做轻量判定。
>
> 现状要解决的问题：
> - 客户端 `MyBatisSqlScriptBuilder` 对 `<if>/<choose>/<trim>/<where>/<set>` 做笛卡尔积变体展开，
>   一条含 N 个 `<if>` 的语句会产出最多 2^N 条变体 → inventory 体积爆炸（实测可超 20 MiB 上限）。
> - 服务端 `appendRouteFieldDiagnostics` 对每条变体各写一行，同一语句报错多次，噪音大。
> - 当前路由字段诊断只需要回答"该字段**有可能**没传"——这恰好等价于"路由字段只出现在 `<if>` 内部"，
>   并不需要枚举所有 `<if>` 取舍组合。

---

## 0. 目标与非目标

**目标**
1. 客户端 inventory 体积下降一个数量级：每个 statement 只输出 1 条记录，不再做笛卡尔积。
2. 客户端把 `<if>` / `<choose>/<when>` 等"条件分支"信息**显式保留**在 raw SQL 中，让服务端能识别
   哪些片段是"可能缺失"的。
3. 服务端对路由字段缺失判定升级为"片段感知"：路由字段若**只**出现在条件分支里，给出 WARNING（有可能缺失）；
   完全没出现，给出 ERROR（一定缺失）；在非条件分支里出现，OK（一定带上）。
4. 服务端 Route Field Check 输出"每条 statement × 每张命中表"最多 1 行；不再因变体数量翻倍。

**非目标**
- 不改 HTTP 契约（仍是 `compact-json v=2`，仅 `sql` 字段的内容形态变化，见 §1.2）。
- 不改 SDK 兼容性校验（`DongDalCompatibilityValidator` 的输入仍是 normalizedSql）；本次只重写
  Route Field Check 一段。
- 不动 JDBC 路径（`JavaJdbcStatementExtractor` 没有 `<if>` 问题）。

---

## 1. 契约调整（最小化）

### 1.1 不新增 schema 版本

仍是 `v=2`。客户端旧版本（每条变体一行）与新版本（每条 statement 一行）在服务端均可解析：
- 旧版本：`sql` 内不含我们约定的"标记片段"，服务端按"整句一段"处理，行为退化为现状的逐条判定。
- 新版本：`sql` 内嵌入下文 1.2 的标记，服务端识别后做片段判定。

这样客户端 / 服务端 PR 可以**任意顺序合入**，无需协调窗口。

### 1.2 `sql` 字段内嵌"条件片段"标记

客户端把动态 SQL 展开成**单条**字符串，但保留分支边界。约定两个轻量标记（ASCII，
SQL 解析器无歧义）：

| 片段类型 | 标记 |  示例输入 |  输出 |
| --- | --- | --- | --- |
| `<if>` | `/*?if*/ ... /*?endif*/` | `<if test="x != null"> AND x = #{x}</if>` | `/*?if*/ AND x = ? /*?endif*/` |
| `<choose>` 各分支 | `/*?branch*/ ... /*?endbranch*/`，多个分支用 `/*?or*/` 分隔，整体外面再包一层 `/*?choose*/ ... /*?endchoose*/` | `<choose><when test=...> A </when><otherwise> B </otherwise></choose>` | `/*?choose*/ /*?branch*/ A /*?endbranch*/ /*?or*/ /*?branch*/ B /*?endbranch*/ /*?endchoose*/` |

约束：
- 标记必须用 `/* */` 注释包住，确保对 JSqlParser / 服务端 SqlNormalizer 透明。
- 服务端 SqlNormalizer **不再折叠**这两个标记（见 §3.3），其他注释照旧折叠。
- `<foreach>` 仍展开为 `(__FOREACH__)`，不需要标记——它的展开内容里没有路由字段。
- `<trim>` / `<where>` / `<set>` 不需要标记——它们只是文本拼接修饰，不是分支。
- 嵌套：`<if>` 套 `<if>` → 多层 `/*?if*/.../*?endif*/` 嵌套，服务端解析时按栈匹配。

### 1.3 Route Field Check 输出结构（服务端）

| 列 | 旧 | 新 |
| --- | --- | --- |
| At | 同 | 同（单字段定位） |
| Table | 同 | 同 |
| Route Fields | 同 | 同 |
| Status | `OK` / `ERROR` | `OK` / `WARN` / `ERROR` |
| Detail | 自由文本 | 自由文本，新增 `字段 X 仅出现在 <if> 条件分支，可能不下发` |
| Normalized SQL | 同 | 同 |

`routeFieldDiagnosticCount` 的语义改为"`status != OK` 的行数"（ERROR + WARN 都计入），与
"任一变体出问题计 1 次"对齐。

---

## 2. 客户端改造（`ref/route-sql-scanner-src/`）

### 2.1 改 `MyBatisSqlScriptBuilder`：从"枚举变体"改为"线性拼接 + 标记分支"

文件：`main/java/com/acme/routesql/extract/mybatis/MyBatisSqlScriptBuilder.java`

把当前 `BuildResult` 的 `List` 流水线退化成**单个** `String` 拼接。改造点（按方法粒度）：

| 旧方法 | 新行为 |
| --- | --- |
| `buildChildrenVariants` | 改名 `buildChildren`，返回 `BuildResult`（单值，非列表）。循环节点改为字符串追加，不再 `combine`。 |
| `ifVariants` | 把内部 children 拼成单串 `body`，输出 `"/*?if*/ " + body + " /*?endif*/"`，`dynamic=true`。 |
| `chooseVariants` | 遍历 `<when>/<otherwise>`，每个分支 body 包 `/*?branch*/ ... /*?endbranch*/`，整体外包 `/*?choose*/ ... /*?endchoose*/`，分支间插 ` /*?or*/ `。无 `<otherwise>` 时也照常拼，服务端的 WARN 判定会基于"标记"而不是"是否兜底"。 |
| `trimVariants` / `keywordBlockVariants` | 退化为单条 trim 处理：直接拿到子串、去前/后缀、套关键字，**不**对每个变体重复算。 |
| `combine` / `dedupe` / `markDynamic`（列表版） | 删除。`dynamic` 标记保留，但变成单值递归向上。 |
| `buildAnnotationScripts` | 改名 `buildAnnotationScript`，返回单 `BuildResult`。注解 SQL 也走同样的标记。 |

注意：`dedupe` 这一层是为列表去重存在的，单值版本不需要；但要确保 `normalizeSpace` 收尾仍然
被调用一次（避免多空格泄漏到 inventory）。

### 2.2 改 `MyBatisXmlExtractor` / `MyBatisAnnotationExtractor`

文件：`main/java/com/acme/routesql/extract/mybatis/{MyBatisXmlExtractor,MyBatisAnnotationExtractor}.java`

旧逻辑：调用 `buildChildrenVariants` → 遍历 variants → 每条变体 `SqlObjects.create(...)`。

新逻辑：调用 `buildChildren` → 拿到单串 → 一个 `SqlObject` per statement。`sqlVariants(...)`
辅助方法删掉。`SqlVariant` record 删掉。

伪代码（XML 那侧）：

```java
MyBatisSqlScriptBuilder.BuildResult built =
    MyBatisSqlScriptBuilder.buildChildren(statement, fragments);
String raw = context.normalizer().normalizeMyBatisParameters(built.sql());
boolean dynamic = built.dynamic() || raw.contains("__DYNAMIC__");
objects.add(SqlObjects.create(raw, origin, dynamic));
```

`raw` 里就会包含 `/*?if*/ ... /*?endif*/` 这种标记。

### 2.3 客户端 normalizer 透传标记

文件：`main/java/com/acme/routesql/normalize/SqlNormalizer.java`

- `normalizeMyBatisParameters` 不动（只折叠 `#{}` / `${}`）。
- **`normalize` 不再被客户端调用**（compact-json v=2 已经不输出 normalized 字段），但保留方法
  即可；如客户端测试还在调用，给个守卫：若识别到 `/*?if*/` 等标记则跳过"折叠注释"那一步。

> 客户端不需要把 normalized 写进 inventory，所以这里只要保证 raw `sql` 字段里的标记不被吃掉。

### 2.4 单测增减

文件：`test/java/com/acme/routesql/extract/mybatis/MyBatisSqlScriptBuilderTest.java`（或对应位置）

- 删：所有"一个 if → 两条变体"、"两个 if → 四条变体"的断言。
- 增：
  - 单 `<if>`：输出含 `/*?if*/.../*?endif*/`，`dynamic=true`。
  - 嵌套 `<if>`：标记按栈正确成对。
  - `<choose>` 双分支：输出含 `/*?choose*/`、两段 `/*?branch*/`、中间 `/*?or*/`。
  - `<choose>` 无 `<otherwise>`：行为同上（标记不区分是否兜底）。
  - 普通 `<where>`：不出现标记。

### 2.5 退出码与 README

- 退出码无变化。
- README 增一句："动态 SQL 的 `<if>` / `<choose>` 分支在 SQL 中以 `/*?if*/…/*?endif*/`、
  `/*?choose*/…/*?endchoose*/` 注释形式保留，由服务端识别后做路由字段诊断；不再做笛卡尔积展开。"

---

## 3. 服务端改造

主要文件：

```
修改  dongdal-server-manager/.../sqlanalyze/CompactInventoryParser.java
       （或现有 parseCompactJson 所在类——见 compact-sql-scanner-iteration-plan §3.1）
修改  dongdal-server-manager/.../sqlanalyze/SqlNormalizer.java
新增  dongdal-server-manager/.../sqlanalyze/ConditionalFragmentAnalyzer.java
修改  dongdal-server-manager/src/main/java/com/jd/dal/server/manager/impl/DongDalMCPServiceImpl.java
       （仅 appendRouteFieldDiagnostics 与列定义）
```

### 3.1 `SqlNormalizer` 保留分支标记

文件：`dongdal-server-manager/.../sqlanalyze/SqlNormalizer.java`

现状的 normalizer 会把所有 `/* */` 注释折叠掉。需要例外：

```java
// 旧
sql = sql.replaceAll("/\\*.*?\\*/", " ");

// 新
sql = sql.replaceAll("/\\*(?!\\?)[^*]*?\\*/", " ");  // 不删 /*?...*/
```

并且保证 `normalize()` 输出后，`/*?if*/` 等标记仍以原文存在，便于下游正则识别。

> 兼容性校验那条链路（`DongDalCompatibilityValidator`）拿到含 `/*?if*/` 的 SQL 时，JSqlParser
> 会把它当注释忽略，行为不变。

### 3.2 新增 `ConditionalFragmentAnalyzer`：决定"字段是否一定下发"

新文件：`dongdal-server-manager/.../sqlanalyze/ConditionalFragmentAnalyzer.java`

输入：`normalizedSql`（小写化前/后均可，内部统一处理）、`identifier`（路由字段名）。

输出：枚举 `Presence` ∈ `{ALWAYS, CONDITIONAL, ABSENT}`。

算法（足够覆盖 §1.2 两类标记）：

1. 把 SQL 切成"段"。用栈扫一遍：
   - 遇到 `/*?if*/` → 入栈 `IF`，开新段。
   - 遇到 `/*?endif*/` → 出栈，关段。
   - 遇到 `/*?choose*/` → 入栈 `CHOOSE`，开新段。
   - 遇到 `/*?branch*/` / `/*?or*/` / `/*?endbranch*/` / `/*?endchoose*/` → 段分隔/出栈。
   - 其他文本累计到当前段。
2. 每段带一个深度字段：当前栈是否含 `IF` 或 `CHOOSE`。栈空 = 顶层段（必下发）。
3. 用现成的 `containsSqlIdentifier(segmentText, identifier)` 在每段里判定字段是否出现。
4. 汇总：
   - 顶层段命中 → `ALWAYS`（OK）。
   - 没有顶层命中，但有任一条件段命中 → `CONDITIONAL`（WARN）。
   - 都没命中 → `ABSENT`（ERROR）。

`<choose>` 特例：如果所有分支都命中了同一字段，理论上是 `ALWAYS`；但工程实现保守起见统一记 `CONDITIONAL`
即可，避免把"所有分支都命中"的判定复杂化（误报偏 WARN 而不是漏报 OK，可接受）。这条留作可选 TODO。

### 3.3 改 `appendRouteFieldDiagnostics`：每条 statement × 每张命中表 最多 1 行

文件：`DongDalMCPServiceImpl.java`，行 ~635。

伪代码：

```java
private void appendRouteFieldDiagnostics(SqlObjectMeta meta,
                                         List<McpTableRouteRule> rules,
                                         SqlAnalysisEnrichment enrichment) {
    String normalizedSql = meta.normalizedSql;
    if (StringUtils.isEmpty(normalizedSql)) return;
    String lower = normalizedSql.toLowerCase(Locale.ROOT);
    Set<String> tables = extractSqlTableNames(lower);

    for (McpTableRouteRule rule : rules) {
        String table = rule == null ? null : rule.getLogicTableName();
        if (StringUtils.isEmpty(table) || !tables.contains(table.toLowerCase(Locale.ROOT))) continue;
        List<String> routeFields = rule.getRouteFields();
        if (CollectionUtils.isEmpty(routeFields)) continue;

        // 取该表所有路由字段的最坏 Presence：ABSENT > CONDITIONAL > ALWAYS
        Presence worst = Presence.ALWAYS;
        String worstField = null;
        for (String f : routeFields) {
            Presence p = ConditionalFragmentAnalyzer.analyze(lower, f);
            if (p.ordinal() > worst.ordinal()) { worst = p; worstField = f; }
        }

        String status = switch (worst) {
            case ALWAYS -> "OK";
            case CONDITIONAL -> "WARN";
            case ABSENT -> "ERROR";
        };
        if ("OK".equals(status) && !enrichment.includeAllDiagnosticRows) continue;

        List<String> row = new ArrayList<>();
        row.add(emptyIfNull(meta.at));
        row.add(table);
        row.add(String.join(", ", routeFields));
        row.add(status);
        row.add(switch (worst) {
            case ALWAYS -> "路由字段一定下发";
            case CONDITIONAL -> "字段 " + worstField + " 仅出现在 <if>/<choose> 条件分支，可能不下发";
            case ABSENT -> "WHERE 条件缺少 " + table + " 的路由字段";
        });
        row.add(normalizedSql);
        enrichment.routeFieldRows.add(row);
        if (!"OK".equals(status)) enrichment.routeFieldDiagnosticCount++;
    }
}
```

`Presence` 是 §3.2 新枚举，按 `ALWAYS=0 / CONDITIONAL=1 / ABSENT=2` 排，方便取最坏。

### 3.4 兼容性校验的相对影响

`DongDalCompatibilityValidator.validate(normalizedSql)` 收到的 SQL 里现在有 `/*?if*/`
风格的注释。JSqlParser 默认把注释剥离，行为不变。需要新加一条单测确认：

```
fixture SQL: "select * from t /*?if*/ where x = ? /*?endif*/"
期望: 解析成功，statementType=SELECT。
```

如果 JSqlParser 在某些 dialect 下对 `/*?...*/` 有意见，备用方案：在 `validator.validate` 入口
预剥离这类标记（保留空白）后再交给 parser。但**不要**改 `meta.normalizedSql` 本身——
`ConditionalFragmentAnalyzer` 还要用。

### 3.5 xlsx 列改动

`Route Field Check` sheet 的 `Status` 列已经是字符串，新增 `WARN` 值无需 schema 改动。
`Detail` 列文案见 §3.3。

### 3.6 单测

- `SqlNormalizerTest`：标记保留 + 普通注释仍折叠。
- `ConditionalFragmentAnalyzerTest`：
  - 顶层 WHERE 命中 → `ALWAYS`。
  - 字段只在 `/*?if*/.../*?endif*/` 内 → `CONDITIONAL`。
  - `<choose>` 单分支命中 → `CONDITIONAL`（保守策略）。
  - 字段完全不出现 → `ABSENT`。
  - 嵌套 `<if>`：栈匹配正确。
- `DongDalMCPServiceImplTest`（或 `SqlAnalyzeController` 集成测）：
  - 单 statement 多变体潜在能力的旧 fixture，期望输出 1 行（不是 N 行）。
  - WARN / ERROR / OK 三类各覆盖一条 fixture。

### 3.7 兼容旧客户端

旧客户端发上来的 inventory 里**没有** `/*?if*/` 标记 → `ConditionalFragmentAnalyzer` 退化为
"整句一段"判定，结果只会是 `ALWAYS` 或 `ABSENT`，行为等价于改造前。不需要分支判断。

唯一退化点：旧客户端会把同一 statement 发成 N 条记录，服务端依旧每条出 1 行（最多 N 行），不会
天然合并。这是已知行为，等客户端跟上后自然消失；不引入额外去重逻辑（避免误合并真正不同的 SQL）。

---

## 4. 实施步骤（双端 PR 切分）

| # | PR | 作用域 | 上线后状态 |
| --- | --- | --- | --- |
| 1 | 服务端：`SqlNormalizer` 保留 `/*?...*/` 标记 + 新增 `ConditionalFragmentAnalyzer` + 改写 `appendRouteFieldDiagnostics` 输出三态 | server | 服务端先就绪，对旧客户端行为不变（无标记 = ALWAYS/ABSENT） |
| 2 | 客户端：改写 `MyBatisSqlScriptBuilder`、`MyBatisXmlExtractor`、`MyBatisAnnotationExtractor`，删变体列表、嵌入标记 | scanner | 客户端 inventory 体积下降；服务端识别标记，产出 WARN |
| 3 | 文档：更新 `sql-analyze-input-and-diagnostics.md` 描述三态 + 新标记；本计划文档归档 | docs | — |

每个 PR 必带：
- 单测覆盖 §2.4 / §3.6 列出的 case。
- 端到端：选一条历史上变体数最多的 statement（建议挑现网 `OrderQueryMapper` 之类），跑前后对比 inventory 体积。

---

## 5. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| `/*?if*/` 标记被某些 SQL 工具/中间件误处理（如 ShardingSphere hint 解析） | 标记仅在客户端→服务端链路存在；`DongDalCompatibilityValidator` 之后不再向下游传 normalizedSql；现网 SDK 不接触这串。 |
| 嵌套 `<if>` 栈匹配 bug 导致 Presence 误判 | `ConditionalFragmentAnalyzer` 单测覆盖嵌套；遇到栈不匹配时退化为"整句一段"判定 + 写 warning，不抛异常。 |
| `<choose>` 全分支均命中的真 ALWAYS 被报成 WARN | 已声明保守策略；后续如需精确，扩展 analyzer 增加"所有 branch 都命中 → ALWAYS"判定即可，向后兼容。 |
| 老客户端不升级，inventory 还是膨胀 | 体积压力主要在客户端→服务端 multipart；20 MiB 上限不变。可以观察 `inventoryFormat=compact-json` 请求体大小 P95，超过阈值时推动升级，不阻断业务。 |

回滚：服务端 PR 独立可 revert；客户端 PR 独立可 revert。两端独立部署、互不依赖。
