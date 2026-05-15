# route-sql-scanner 交接文档

本文面向后续接手的 AI agent。目标是快速理解当前项目架构、用户真实意图、关键实现位置，以及修改后应如何验证。

## 1. 项目定位

`route-sql-scanner` 是一个 Java 17 CLI/fat jar 工具，当前 V0 目标是：

- 扫描文件或目录。
- 从 MyBatis XML mapper、MyBatis 注解 mapper 与 Java JDBC-like 调用中静态提取 SQL。
- 建立统一 `SqlObject` 模型，保留 stable id、source origin、mapper statement id 等身份信息。
- 用 JSqlParser 解析 SQL，提取 statement type、表名、基础字段信息。
- 按配置诊断指定表是否缺少路由字段。
- 输出 JSON、JSONL、Markdown、normalized SQL 文本报告。

`README.md` 是项目设计文档，不一定等同于当前实现的完整事实。做改动时先读本交接文档和代码，再回看 README 作为需求来源。

## 2. 当前技术栈

- Java 17
- Maven single module
- CLI: picocli
- SQL parser: JSqlParser 5.3
- Java parser: JavaParser 3.28.0
- JSON/YAML: Jackson 2.21.2
- Test: JUnit 5
- Fat jar: maven-shade-plugin

核心配置见 `pom.xml`。

## 3. 快速验证命令

常用验证：

```bash
mvn test
mvn package
java -jar target/route-sql-scanner-0.1.0.jar scan \
  --path src/test/resources/fixtures \
  --config src/test/resources/fixtures/route-sql.yml \
  --format json \
  --fail-on NEVER
```

当前 fixture 预期：

- 扫描文件数：6
- SQL/fragment 数：10
- 诊断数：3
- fat jar 路径：`target/route-sql-scanner-0.1.0.jar`

注意：第一次 Maven 运行可能需要写 `~/.m2` 下载依赖或插件。如果在沙箱里失败，需要申请提升权限后重跑同一 Maven 命令。

## 4. 高层数据流

```text
Main
  -> ScanCommand
    -> ScannerConfig.load()
    -> ScanEngine.scan()
      -> discover files
      -> SqlExtractor.extract()
         - MyBatisXmlExtractor
         - MyBatisAnnotationExtractor
         - JavaJdbcStatementExtractor
      -> SqlParserFacade.parse()
      -> RouteFieldRule.apply()
      -> Reporter.render()
         - JsonReporter
         - JsonlReporter
         - MarkdownReporter
```

核心数据模型：

```text
SqlObject
  identity: SqlIdentity
  rawSql
  normalizedSql
  origin: SqlOrigin
  parse: SqlParseResult
  dynamic
  tags
  attributes

ScanReport
  version
  dialect
  summary
  sqlObjects
  diagnostics
```

## 5. 关键文件地图

入口与 CLI：

- `src/main/java/com/acme/routesql/Main.java`
  picocli 根命令，注册 `scan` 子命令。
- `src/main/java/com/acme/routesql/cli/ScanCommand.java`
  解析 CLI 参数，加载配置，调用 `ScanEngine`，选择报告格式，控制退出码。

配置：

- `src/main/java/com/acme/routesql/config/ScannerConfig.java`
  读取 YAML/JSON 配置，默认 dialect 为 `mysql`。读取后会先经过 `ConfigPreprocessors`，再绑定到 `ScannerConfig`。
- `src/main/java/com/acme/routesql/config/RouteRuleConfig.java`
  `routeRules` 配置模型，对应 `tables.<table>.routeFields/operations/requireAllRouteFields`。
- `src/main/java/com/acme/routesql/config/ConfigPreprocessor.java`
  配置预处理 hook。后续如果用户输入 JSON 和内部模型不一致，在这里扩展适配器。
- `src/main/java/com/acme/routesql/config/ConfigPreprocessors.java`
  预处理器注册表。
- `src/main/java/com/acme/routesql/config/RequiredColumnsJsonPreprocessor.java`
  当前内置轻量 JSON 适配器，支持 `tables[].name + requiredColumns[]`，并默认转换成 `requireAllRouteFields=true`。

扫描编排：

- `src/main/java/com/acme/routesql/core/ScanEngine.java`
  项目主流程。这里注册 extractor、parser、rule。新增提取器或规则时通常从这里接入。
- `src/main/java/com/acme/routesql/core/SqlRepository.java`
  当前是轻量容器，尚未深度使用。
- `src/main/java/com/acme/routesql/core/ScanSession.java`
  当前是预留模型，尚未深度使用。

提取器 SPI：

- `src/main/java/com/acme/routesql/extract/SqlExtractor.java`
  所有 SQL extractor 的接口。
- `src/main/java/com/acme/routesql/extract/ExtractionContext.java`
  当前只带 `SqlNormalizer`。

MyBatis XML：

- `src/main/java/com/acme/routesql/extract/mybatis/MyBatisXmlExtractor.java`
  使用 DOM 解析 XML，不依赖 MyBatis runtime。
  当前支持 `<select>/<insert>/<update>/<delete>/<sql>`、`<include>`、常见动态标签近似展开、`#{}` 转 `?`、`${}` 转 `__DYNAMIC__`。
  `SqlOrigin` 会保留 namespace、statementId、statementType、file、line、column。

MyBatis 注解：

- `src/main/java/com/acme/routesql/extract/mybatis/MyBatisAnnotationExtractor.java`
  使用 JavaParser 读取 mapper 方法上的 `@Select/@Insert/@Update/@Delete`。
  支持单字符串、字符串数组、字符串拼接、text block。
  注解内 `<script>/<where>/<if>` 等动态标签复用 `MyBatisSqlScriptBuilder` 近似展开。
  暂不支持 `@SelectProvider/@InsertProvider/@UpdateProvider/@DeleteProvider`。
- `src/main/java/com/acme/routesql/extract/mybatis/MyBatisSqlScriptBuilder.java`
  XML mapper 和注解 mapper 共用的 MyBatis 动态 SQL 近似重建逻辑。

Java JDBC：

- `src/main/java/com/acme/routesql/extract/java/JavaJdbcStatementExtractor.java`
  使用 JavaParser。
  当前识别 `prepareStatement/execute/executeQuery/executeUpdate/query/update` 的第一个参数。
  支持字符串字面量、text block、同方法内简单 `String sql = ...`、字符串常量拼接。
  拼接中遇到无法静态求值的表达式会用 `__DYNAMIC__` 近似，但只有整体看起来像 SQL 时才产出对象。

解析与归一化：

- `src/main/java/com/acme/routesql/normalize/SqlNormalizer.java`
  去注释、压缩空白、MyBatis 参数替换。
- `src/main/java/com/acme/routesql/parse/SqlParserFacade.java`
  JSqlParser parse，提取 statement type 和 table list。字段提取目前主要靠文本 fallback。

规则：

- `src/main/java/com/acme/routesql/rule/SqlRule.java`
  规则接口。
- `src/main/java/com/acme/routesql/rule/RouteFieldRule.java`
  当前唯一诊断规则。
  SELECT/UPDATE/DELETE 检查 WHERE 片段中是否出现路由字段。
  INSERT 检查 insert column list 是否包含路由字段。
  当 `requireAllRouteFields=false` 时保持旧语义：出现任意一个 route field 即满足。
  当 `requireAllRouteFields=true` 时使用必要列语义：所有 route fields 都必须出现，否则诊断会列出缺少字段。
  AST 解析失败时也会基于 normalized SQL 做文本兜底。

报告：

- `src/main/java/com/acme/routesql/report/Reporter.java`
- `src/main/java/com/acme/routesql/report/JsonReporter.java`
- `src/main/java/com/acme/routesql/report/JsonlReporter.java`
- `src/main/java/com/acme/routesql/report/MarkdownReporter.java`
- `src/main/java/com/acme/routesql/report/NormalizedSqlReporter.java`
  只输出 `normalizedSql`，一行一个 SQL，不带元数据。

模型：

- `src/main/java/com/acme/routesql/model/SqlObject.java`
- `src/main/java/com/acme/routesql/model/SqlIdentity.java`
- `src/main/java/com/acme/routesql/model/SqlOrigin.java`
- `src/main/java/com/acme/routesql/model/SqlParseResult.java`
- `src/main/java/com/acme/routesql/model/Diagnostic.java`
- `src/main/java/com/acme/routesql/model/ScanReport.java`
- `src/main/java/com/acme/routesql/model/ScanSummary.java`
- `src/main/java/com/acme/routesql/model/SourceKind.java`

工具：

- `src/main/java/com/acme/routesql/util/Hashing.java`
  SHA-256。
- `src/main/java/com/acme/routesql/util/SqlObjects.java`
  统一生成 `SqlObject`、contentHash、stableId、logicalName。

OpenCode wrapper：

- `tools/opencode-sqlscan/src/index.ts`
  薄包装，使用 `child_process.spawn` 调 `java -jar ... scan`。`scanRouteSqlSkill` 是面向 AI agent 的最小接口，默认输出 `.xlsx`；`scanRouteSql` 保留为高级调试接口。

测试与 fixtures：

- `src/test/java/com/acme/routesql/ScanEngineTest.java`
  端到端测试，覆盖提取、stable id、解析、诊断和报告格式。
- `src/test/resources/fixtures/*.xml`
  MyBatis mapper fixture。
- `src/test/resources/fixtures/*.java`
  Java JDBC fixture。
- `src/test/resources/fixtures/route-sql.yml`
  路由规则 fixture。

## 6. Stable ID 规则

由 `SqlObjects.create()` 生成：

- `contentHash = sha256(normalizedSql)`
- `sourceKey = filePath + ":" + line + ":" + column`
- MyBatis XML/annotation logicalName: `namespace + "." + statementId`
- Java logicalName: `className + "#" + methodName + ":" + line`
- `stableId = sha256(sourceKind + "|" + logicalName + "|" + contentHash)`

任何改动只要影响 `normalizedSql`、origin 位置或 logicalName，都可能改变 stable id。改模型或归一化逻辑时要同步调整测试预期。

## 7. CLI 行为

AI agent skill 调用不要直接暴露全部 CLI 参数，使用 `docs/AGENT_SKILL.md` 中定义的最小接口：`projectPath/configPath/outputPath?`。skill 默认固定 `--format excel --failed-only --fail-on NEVER`，报告文件为 `.xlsx`。

命令形态：

```bash
java -jar target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/project \
  --config route-sql.yml \
  --format json \
  --output scan-result.json
```

参数位置在 `ScanCommand`：

- `--path`：可重复，文件或目录。
- `--config`：YAML/JSON。
- `--format`：`json | compact-json | excel | jsonl | markdown | normalized`。
- `--output`：不填则 stdout。
- `--include`：glob，可重复。
- `--exclude`：glob，可重复。
- `--failed-only`：输出时只保留未通过校验规则的 SQL；支持 `json`、`compact-json`、`excel` 和 `normalized`。
- `--fail-on`：`ERROR | WARN | NEVER`。

可在目标项目根目录放 `route-sql-report.yml`，无需 CLI 参数即可控制报告形态：

```yaml
format: compact-json
```

退出码：

- `0`：没有达到 fail 条件。
- `1`：`--fail-on WARN` 且存在 WARN。
- `2`：达到 ERROR fail 条件。
- `3`：程序执行异常。

## 8. 常见需求改动指南

新增 SQL 来源：

1. 新建类实现 `SqlExtractor`。
2. 在 `supports(Path)` 中限定文件类型或路径。
3. 提取后用 `SqlObjects.create()` 生成 `SqlObject`。
4. 补 `SourceKind`，如果是新来源。
5. 在 `ScanEngine` 的 `extractors` 列表注册。
6. 增加 fixture 和 `ScanEngineTest` 断言。

增强 MyBatis 注解 SQL：

1. 主要改 `MyBatisAnnotationExtractor`。
2. 注解内动态标签重建逻辑在 `MyBatisSqlScriptBuilder`。
3. Provider 注解如果要支持，需要决定是否静态解析 provider 方法返回值，避免误判运行时 SQL。

增强 MyBatis 动态 SQL：

1. 主要改 `MyBatisSqlScriptBuilder`。
2. 保持 raw SQL 近似可解析。
3. 遇到运行时不确定内容时标记 `dynamic=true`。
4. 保持 namespace、statementId、line、column 不丢。

新增输入 JSON 适配：

1. 实现 `ConfigPreprocessor`。
2. 在 `ConfigPreprocessors` 注册。
3. 输出必须是内部 `ScannerConfig` 兼容 JSON tree。
4. 如果表达“必要列全部出现”，设置 `routeRules.tables.<table>.requireAllRouteFields=true`。

增强 Java 字符串求值：

1. 主要改 `JavaJdbcStatementExtractor.evaluate()` 和 `collectStringVariables()`。
2. V0 不做跨文件符号求解。若要做跨文件常量，建议明确范围和性能成本。
3. 遇到不确定变量时用 `__DYNAMIC__`，不要静默伪装成确定 SQL。

增强 SQL AST 分析：

1. 主要改 `SqlParserFacade`。
2. 当前 table list 用 JSqlParser，columns 主要是文本 fallback。
3. 若引入 visitor，注意 JSqlParser 5.3 API 和泛型兼容。
4. parse 失败不能丢 SQL，必须返回 `parsed=false` 的 `SqlParseResult`。

新增规则：

1. 实现 `SqlRule`。
2. 在 `ScanEngine.rules` 注册。
3. 诊断统一返回 `Diagnostic`。
4. 测试至少覆盖命中、不命中、parse fallback 三类场景中的相关部分。

新增报告格式：

1. 实现 `Reporter`。
2. 在 `ScanCommand.reporter()` switch 中注册。
3. 用 `ScanEngineTest.rendersAllReportFormats()` 或新增测试覆盖。

## 9. 当前 V0 简化点和风险

- MyBatis 动态 SQL 是近似重建，不模拟所有运行时分支。
- MyBatis 注解 mapper 当前只提取 `@Select/@Insert/@Update/@Delete`，不提取 provider 注解。
- `<choose>` 当前把分支文本合并，可能出现不真实但有利于静态诊断的 SQL。
- `<foreach>` 当前输出 `(__FOREACH__)` 并标记 dynamic。
- Java 只做同方法内简单字符串求值，不做完整符号解析。
- Java 对 `jdbcTemplate.query/update` 做 JDBC-like 支持，但没有验证接收者类型。
- 字段提取不完整，规则实际主要依赖 normalized SQL 的 WHERE/INSERT 文本片段。
- 多表 SQL 只做“字段出现即满足/必要字段出现”的文本级判断，没有做谓词可达性、表别名字段归属和 join 条件严谨分析。
- `<sql>` fragment 会作为 `FRAGMENT` 进入 SQL 清单，但多数 fragment 不是完整 SQL，parse 可能失败；这是预期行为。

## 10. 工作区注意事项

- `README.md` 在最初实现前就已经是 dirty 状态；不要误以为它是本轮代码改动的一部分。
- `target/` 是构建产物，已加入 `.gitignore`。
- 手动编辑文件优先用 patch，避免无关格式化。
- 修改后至少跑 `mvn test`；涉及 CLI 或 shade 配置时跑 `mvn package` 和一次 `java -jar ... scan`。

## 11. 用户偏好和需求理解

用户的核心诉求不是做一个通用 SQL 安全扫描器，而是围绕“静态提取 SQL，并基于表名到路由字段的配置诊断 SQL 是否缺少路由字段”快速落地一个可执行 V0。

后续响应需求时优先保持：

- 端到端可运行。
- 身份信息不丢，尤其是 MyBatis namespace/statement id 和 Java class/method/line。
- 规则诊断可解释，报告可被脚本消费。
- 架构保留 extractor/rule/reporter 扩展点。

如果需求与 README 冲突，以用户最新消息为准；如果需求不明确，先根据当前 V0 架构做小步、可验证的实现。
