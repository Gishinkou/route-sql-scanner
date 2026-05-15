# route-sql-scanner

这是一篇项目启动文档：定位是：先把“静态 SQL 提取 + 统一对象模型 + 表名/路由字段诊断 + 报告输出”跑通。

**项目文档 v0.1**
项目名：`route-sql-scanner`

目标：构建一个 Java fat jar 工具，专注 MySQL 方言，静态提取 MyBatis XML mapper 和 Java JDBC statement 中的 SQL，统一建模后输出结构化报告，并基于“表名 -> 路由字段”规则诊断 SQL 是否缺失路由字段。

**范围**
V0 必须实现：

- 单 Maven 项目，构建可执行 fat jar。
- 扫描目录或文件。
- 提取 MyBatis XML mapper 中的 `<select>`、`<insert>`、`<update>`、`<delete>`、`<sql>`。
- 提取 Java 代码中直接 JDBC statement/preparedStatement 使用的 SQL 字符串。
- 统一 SQL 对象抽象，保留 stable id、raw sql、normalized sql、source origin、mapper statement id 等身份信息。
- 使用 MySQL SQL parser 建 AST。
- 输出 JSON、JSONL、Markdown 三种文本报告。
- 诊断规则：按配置判断指定表是否缺少路由字段。
- 保留 extractor/rule/reporter 扩展点，未来支持 MyBatis 注解、iBatis、其他 ORM。

V0 暂不实现：

- MCP server。
- IDE plugin。
- 完整 Java 符号解析。
- 运行时连接数据库。
- 通用 SQL 风险诊断。
- MyBatis 动态 SQL 所有分支的真实执行模拟。

**技术选型**

- Java 17。
- Maven single module。
- CLI：`picocli`。
- SQL AST：`JSqlParser`，按 MySQL 语法目标解析。
- Java AST：`JavaParser`。
- JSON：Jackson。
- MyBatis XML：V0 使用自研静态 XML extractor，不依赖 MyBatis runtime 作为主路径。
- fat jar：`maven-shade-plugin`。

建议依赖版本，按 2026-04-29 查证：

```xml
<properties>
  <maven.compiler.release>17</maven.compiler.release>
  <jsqlparser.version>5.3</jsqlparser.version>
  <javaparser.version>3.28.0</javaparser.version>
  <jackson.version>2.21.2</jackson.version>
  <picocli.version>4.7.7</picocli.version>
  <mybatis.version>3.5.19</mybatis.version>
  <maven-shade-plugin.version>3.6.2</maven-shade-plugin.version>
</properties>
```

`mybatis` 依赖可以先不放进主实现；预留 `MyBatisRuntimeExtractor` SPI 即可。V0 用 DOM/StAX 自己静态展开 XML，更适合“无参数、无运行时”的扫描。

**目录结构**

```text
route-sql-scanner/
  pom.xml
  src/main/java/com/acme/routesql/
    Main.java
    cli/ScanCommand.java
    config/ScannerConfig.java
    config/RouteRuleConfig.java
    core/ScanEngine.java
    core/ScanSession.java
    core/SqlRepository.java
    model/SqlObject.java
    model/SqlIdentity.java
    model/SqlOrigin.java
    model/SqlParseResult.java
    model/Diagnostic.java
    model/ScanReport.java
    extract/SqlExtractor.java
    extract/ExtractionContext.java
    extract/mybatis/MyBatisXmlExtractor.java
    extract/java/JavaJdbcStatementExtractor.java
    parse/SqlParserFacade.java
    normalize/SqlNormalizer.java
    rule/SqlRule.java
    rule/RouteFieldRule.java
    report/Reporter.java
    report/JsonReporter.java
    report/JsonlReporter.java
    report/MarkdownReporter.java
  tools/opencode-sqlscan/
    package.json
    src/index.ts
```

**核心对象模型**
`SqlObject` 是项目最核心对象。所有规则只作用于它。

```java
public record SqlObject(
    SqlIdentity identity,
    String rawSql,
    String normalizedSql,
    SqlOrigin origin,
    SqlParseResult parse,
    boolean dynamic,
    List<String> tags,
    Map<String, Object> attributes
) {}
```

`SqlIdentity`：

```java
public record SqlIdentity(
    String stableId,
    String contentHash,
    String sourceKey,
    String logicalName
) {}
```

生成策略：

- `contentHash = sha256(normalizedSql)`。
- `sourceKey = filePath + ":" + line + ":" + column`。
- MyBatis XML：`logicalName = namespace + "." + statementId`。
- Java JDBC：`logicalName = className + "#" + methodName + ":" + line`。
- `stableId = sha256(sourceKind + "|" + logicalName + "|" + contentHash)`。

`SqlOrigin`：

```java
public record SqlOrigin(
    SourceKind kind,       // MYBATIS_XML, JAVA_JDBC, FUTURE_MYBATIS_ANNOTATION, FUTURE_IBATIS_XML
    Path file,
    int line,
    int column,
    String namespace,
    String statementId,
    String statementType,
    String className,
    String methodName
) {}
```

`SqlParseResult`：

```java
public record SqlParseResult(
    boolean parsed,
    String dialect,        // mysql
    String statementType,  // SELECT/INSERT/UPDATE/DELETE/UNKNOWN
    List<String> tables,
    List<String> columns,
    Object ast,            // runtime only, do not serialize directly
    String parseError
) {}
```

`Diagnostic`：

```java
public record Diagnostic(
    String id,             // ROUTE-MISSING-001
    String severity,       // ERROR/WARN/INFO
    String message,
    String sqlStableId,
    SqlOrigin origin,
    String tableName,
    List<String> expectedRouteFields,
    String snippet        // 完整 normalized SQL
) {}
```

**Extractor SPI**
所有提取器实现统一接口：

```java
public interface SqlExtractor {
    String name();
    boolean supports(Path path);
    List<SqlObject> extract(Path path, ExtractionContext context);
}
```

V0 注册：

- `MyBatisXmlExtractor`
- `JavaJdbcStatementExtractor`

未来新增：

- `MyBatisAnnotationExtractor`
- `IBatisXmlExtractor`
- `JooqExtractor`
- `HibernateNativeQueryExtractor`

**MyBatis XML 提取设计**
`MyBatisXmlExtractor` 不使用 sql parser 解析 XML。它只负责“提取并重建近似 SQL”。

支持：

- `<mapper namespace="...">`
- statement 标签：`select/insert/update/delete`
- fragment 标签：`sql`
- `<include refid="..."/>` 静态展开
- 动态标签：`if/choose/when/otherwise/foreach/trim/where/set/bind`
- 参数：
  - `#{x}` -> `?`
  - `${x}` -> `__DYNAMIC__`，并标记 `dynamic=true`

重建策略：

- `<where>`：补 `WHERE`，去掉开头 `AND/OR`。
- `<set>`：补 `SET`，去掉末尾逗号。
- `<trim prefix="..." prefixOverrides="...">`：按属性做基础重建。
- `<foreach>`：V0 产出 `(? )` 或 `(__FOREACH__)`，并标记 dynamic。
- `<choose>`：把所有分支文本 union 进 SQL，标记 dynamic。

MyBatis XML 输出的 `SqlOrigin` 必须保留：

- `namespace`
- `statementId`
- `statementType`
- `file`
- `line`
- `column`

这是 SlowQL 当前设计里缺失但我们要保留的关键身份信息。

**Java JDBC 提取设计**
使用 JavaParser 解析 `.java`。

V0 识别：

```java
connection.prepareStatement("SELECT ...")
connection.createStatement().executeQuery("SELECT ...")
statement.execute("UPDATE ...")
jdbcTemplate.query("SELECT ...") // 可选，作为 JDBC-like 支持
```

字符串求值支持：

- 字符串字面量。
- Java text block。
- 同一方法内简单 `String sql = "..."; prepareStatement(sql)`。
- 字符串常量拼接：`"SELECT " + "FROM"`。
- 拼接变量时替换为 `__DYNAMIC__`，标记 dynamic。

V0 不做跨文件符号求解。遇到无法静态求值的 SQL，记录 extraction warning，不产出 SQL 或产出 partial SQL，取决于置信度配置。

**SQL 解析**
`SqlParserFacade` 只接收 `SqlObject.rawSql`，输出 `SqlParseResult`。

职责：

- 调用 JSqlParser parse。
- 提取 statement type。
- 提取 tables。
- 提取 columns。
- 保留 parse error。
- parse 失败时 `SqlObject` 仍保留，规则可以选择跳过或做 fallback 文本判断。

**路由字段规则**
配置文件示例：

```yaml
dialect: mysql
routeRules:
  defaultSeverity: ERROR
  tables:
    orders:
      routeFields: [tenant_id, order_id]
      operations: [SELECT, UPDATE, DELETE, INSERT]
    order_item:
      routeFields: [tenant_id, order_id]
      operations: [SELECT, UPDATE, DELETE, INSERT]
```

`RouteFieldRule` 逻辑：

- 若 SQL 未引用配置表，不诊断。
- `INSERT`：插入列必须包含至少一个 route field。
- `SELECT/UPDATE/DELETE`：`WHERE` 条件中必须出现该表的任一路由字段。
- 有 alias 时识别 `alias.tenant_id`。
- 多表 SQL：对每个命中的配置表分别判断。
- AST 解析失败：降级为 normalized SQL 文本搜索，并把 diagnostic metadata 标记为 `fallback=true`。

V0 先接受“字段出现即满足”，不做完整谓词可达性判断。后续可升级为 AST predicate analyzer。

**CLI**
命令：

```bash
java -jar route-sql-scanner.jar scan \
  --path /path/to/project \
  --config route-sql.yml \
  --format json \
  --output scan-result.json
```

参数：

```text
scan
  --path           文件或目录，可多次
  --config         YAML/JSON 配置
  --format         json | compact-json | excel | jsonl | markdown | normalized
  --output         输出文件；不填则 stdout
  --include        glob，可多次
  --exclude        glob，可多次
  --failed-only    仅输出未通过校验规则的 SQL；支持 json、compact-json、excel 和 normalized
  --fail-on        ERROR | WARN | NEVER
```

退出码：

- `0`：无触发或仅 INFO。
- `1`：存在 WARN。
- `2`：存在 ERROR。
- `3`：程序执行失败。

**报告输出**
JSON 顶层结构：

```json
{
  "version": "0.1.0",
  "dialect": "mysql",
  "summary": {
    "filesScanned": 12,
    "sqlCount": 34,
    "diagnosticCount": 3
  },
  "sqlObjects": [],
  "diagnostics": []
}
```

JSONL：

- 每行一个对象。
- 类型包括：`sql`, `diagnostic`, `summary`。
- 方便 OpenCode/脚本流式消费。

Markdown：

- Summary。
- SQL inventory 表格。
- Diagnostics 表格。
- 每条诊断附完整 normalized SQL。

**OpenCode TS tool 适配层**
V0 只做本地扫描包装，不实现 MCP。面向 AI agent 的 skill 语义见 `docs/AGENT_SKILL.md`。

`tools/opencode-sqlscan/src/index.ts`：

- `scanRouteSqlSkill` 是推荐给 agent 的最小接口，只接收 `projectPath/configPath/outputPath?`。
- skill 默认输出 `.xlsx`，底层固定使用 `--format excel --failed-only --fail-on NEVER`。
- 不向 agent 暴露 `format/include/exclude/fail-on/jarPath` 等底层调试参数。
- `scanRouteSql` 保留为高级薄包装，用于调试、回归测试或内部工具链。

skill 接口：

```ts
export async function scanRouteSqlSkill(input: {
  projectPath: string;
  configPath: string;
  outputPath?: string;
}): Promise<{
  outputPath: string;
  format: "xlsx";
}>;
```

**验收标准**
必须有测试 fixture：

- `UserMapper.xml`：普通 select。
- `OrderMapper.xml`：`<where>`、`<if>`、`<include>`。
- `BadMapper.xml`：动态 `${}`。
- `JdbcDemo.java`：`prepareStatement` 字符串。
- `JdbcConcatDemo.java`：简单拼接。
- `route-sql.yml`：配置 `orders.tenant_id`。

V0 验收：

- 能提取 mapper XML SQL，并保留 `namespace.statementId`。
- 能提取 Java JDBC SQL。
- 能为每条 SQL 生成 stable id。
- 能解析 MySQL SQL 并提取表名。
- 能发现缺少路由字段的 SQL。
- 能输出 JSON、JSONL、Markdown。
- `mvn package` 产出可执行 fat jar。

**实现顺序**

1. 建 Maven 项目、CLI、fat jar。
2. 建 model：`SqlObject/SqlOrigin/Diagnostic/ScanReport`。
3. 建 extractor SPI 和 repository。
4. 实现 MyBatis XML extractor。
5. 实现 Java JDBC extractor。
6. 接 JSqlParser，填充 parse result。
7. 实现 route field rule。
8. 实现 reporter。
9. 加 fixtures 和端到端测试。
10. 加 OpenCode TS wrapper。

参考来源：MyBatis 最新版本与 XML/annotation SQL mapper 定位见 [Maven Central mybatis 3.5.19](https://central.sonatype.com/artifact/org.mybatis/mybatis)；JSqlParser 用 Java class hierarchy 和 Visitor Pattern 表示 SQL AST，见 [JSqlParser](https://jsqlparser.github.io/JSqlParser/)；JavaParser 最新版本见 [Maven Central javaparser-core 3.28.0](https://central.sonatype.com/artifact/com.github.javaparser/javaparser-core)；fat jar 使用 [Maven Shade Plugin 3.6.2](https://maven.apache.org/plugins/maven-shade-plugin/download.cgi)；CLI 可用 [picocli 4.7.7](https://central.sonatype.com/artifact/info.picocli/picocli)。
