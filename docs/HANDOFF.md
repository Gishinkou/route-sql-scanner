# route-sql-scanner 交接文档

本文面向后续接手的 AI agent。目标是快速理解当前项目架构、关键实现位置，以及修改后应如何验证。

> 本轮迭代后，扫描器被收敛为 **"纯 SQL 清单生产者"**：移除了 parser/rule/normalize 公共输出、只保留 `compact-json` 一种输出格式、CLI 选项大幅精简。所有诊断逻辑由服务端完成（详见 `docs/compact-sql-scanner-iteration-plan.md`）。

## 1. 项目定位

`route-sql-scanner` 是一个 Java 17 CLI/fat jar 工具，当前职责仅有：

- 扫描文件或目录。
- 从 MyBatis XML mapper、MyBatis 注解 mapper 与 Java JDBC-like 调用中静态提取 SQL。
- 把每条 SQL 与其出处位置（`at` 字段）打包成 `compact-json`（schema v=2）。

**不再做**：SQL 解析（JSqlParser）、SQL 规范化输出、路由字段规则诊断、Excel/Markdown/JSONL 报告、`--fail-on` ERROR/WARN 退出码判定。

## 2. 当前技术栈

- Java 17
- Maven single module
- CLI: picocli
- Java parser: JavaParser 3.28.0
- JSON/YAML: Jackson 2.21.2
- Test: JUnit 5
- Fat jar: maven-shade-plugin

依赖见 `pom.xml`。已移除 `jsqlparser`。

## 3. 快速验证命令

```bash
mvn test
mvn package
java -jar target/route-sql-scanner-0.1.0.jar scan \
  --path src/test/resources/fixtures
```

预期输出：一份 `"v": 2` 的 JSON，`sqls` 数组包含 MyBatis XML、MyBatis 注解、Java JDBC 三类来源各自的 `at` + `sql`。

注意：第一次 Maven 运行可能需要写 `~/.m2` 下载依赖或插件。

## 4. 高层数据流

```text
Main
  -> ScanCommand
    -> ScannerConfig.load()       (只读 project 名)
    -> ScanEngine.scan()
      -> discover files
      -> SqlExtractor.extract()
         - MyBatisXmlExtractor
         - MyBatisAnnotationExtractor
         - JavaJdbcStatementExtractor
    -> CompactInventoryReporter.render()
```

核心数据模型：

```text
SqlObject
  origin: SqlOrigin
  rawSql
  dynamic

ScanReport
  project
  sqlObjects
```

输出 schema：

```text
{
  v: 2
  project?: string
  scannedAt: string (ISO local datetime)
  sqls: [{
    at: string        // IDEA Copy Reference 风格
    sql: string       // raw SQL（保留 #{x}/${x}/__DYNAMIC__）
    dynamic?: true    // 仅在 true 时出现
  }]
}
```

## 5. 关键文件地图

入口与 CLI：

- `src/main/java/com/acme/routesql/Main.java`
  picocli 根命令，注册 `scan` 子命令。
- `src/main/java/com/acme/routesql/cli/ScanCommand.java`
  解析 CLI 参数（`--path/--config/--output/--include/--exclude`），加载配置，调用 `ScanEngine`，固定使用 `CompactInventoryReporter` 渲染。

配置：

- `src/main/java/com/acme/routesql/config/ScannerConfig.java`
  仅有一个字段 `project`，用 Jackson 读 YAML/JSON。`FAIL_ON_UNKNOWN_PROPERTIES` 已禁用，能容忍历史字段。

扫描编排：

- `src/main/java/com/acme/routesql/core/ScanEngine.java`
  注册三个 extractor，遍历文件、调用 extractor、拼装 `ScanReport`。

提取器 SPI：

- `src/main/java/com/acme/routesql/extract/SqlExtractor.java`
  所有 SQL extractor 的接口。
- `src/main/java/com/acme/routesql/extract/ExtractionContext.java`
  当前持有 `SqlNormalizer`（仅供 MyBatis extractor 内部做变体去重，不出现在公共输出里）。

MyBatis XML：

- `src/main/java/com/acme/routesql/extract/mybatis/MyBatisXmlExtractor.java`
  DOM 解析 XML，不依赖 MyBatis runtime。
  支持 `<select>/<insert>/<update>/<delete>/<sql>`、`<include>`、常见动态标签枚举展开、`#{}` 转 `?`、`${}` 转 `__DYNAMIC__`。
  `SqlOrigin` 会保留 namespace、statementId、file、line、column。
- `src/main/java/com/acme/routesql/extract/mybatis/MyBatisAnnotationExtractor.java`
  使用 JavaParser 读取 mapper 方法上的 `@Select/@Insert/@Update/@Delete`。
  支持单字符串、字符串数组、字符串拼接、text block。
  注解内 `<script>/<where>/<if>` 等动态标签复用 `MyBatisSqlScriptBuilder` 枚举展开。
- `src/main/java/com/acme/routesql/extract/mybatis/MyBatisSqlScriptBuilder.java`
  XML mapper 和注解 mapper 共用的 MyBatis 动态 SQL 变体重建逻辑。

Java JDBC：

- `src/main/java/com/acme/routesql/extract/java/JavaJdbcStatementExtractor.java`
  JavaParser。当前识别 `prepareStatement/execute/executeQuery/executeUpdate/query/update` 的第一个参数。
  支持字符串字面量、text block、同方法内简单 `String sql = ...`、字符串常量拼接。
  拼接中遇到无法静态求值的表达式会用 `__DYNAMIC__` 近似。

内部规范化（不再对外暴露）：

- `src/main/java/com/acme/routesql/normalize/SqlNormalizer.java`
  仅供 MyBatis extractor 内部做"按 normalized SQL 去重"，避免同一 statement 重复入清单。
  公共输出（compact-json 的 `sql` 字段）始终是 raw SQL。

报告：

- `src/main/java/com/acme/routesql/report/Reporter.java`
  Reporter 接口。
- `src/main/java/com/acme/routesql/report/CompactInventoryReporter.java`
  当前唯一实现。常量 `SCHEMA_VERSION = 2`。

模型：

- `src/main/java/com/acme/routesql/model/SqlObject.java`（origin, rawSql, dynamic）
- `src/main/java/com/acme/routesql/model/SqlOrigin.java`（保留全部 origin 字段供 AtFormatter 使用）
- `src/main/java/com/acme/routesql/model/ScanReport.java`（project, sqlObjects）
- `src/main/java/com/acme/routesql/model/SourceKind.java`

工具：

- `src/main/java/com/acme/routesql/util/AtFormatter.java`
  根据 `SourceKind` 把 `SqlOrigin` 折叠成 IDEA "Copy Reference" 风格的单字段定位。
- `src/main/java/com/acme/routesql/util/SqlObjects.java`
  统一构造 `SqlObject(origin, rawSql, dynamic)`。

OpenCode wrapper：

- `tools/opencode-sqlscan/src/index.ts`
  薄包装。`scanRouteSqlSkill` 是面向 AI agent 的最小接口。

测试与 fixtures：

- `src/test/java/com/acme/routesql/ScanEngineTest.java`
  端到端测试，覆盖三类来源、compact-json 输出、`at` 字段格式。
- `src/test/resources/fixtures/*.xml`、`*.java`
  MyBatis mapper 和 Java JDBC fixture。
- `src/test/resources/fixtures/route-sql.yml`
  保留历史字段（dialect / routeRules），扫描器不解析这些字段，但允许文件存在；仅 `project` 字段（如果写）会被读到。

## 6. `at` 字段构造规则

由 `AtFormatter.format(SqlOrigin)` 生成：

| 来源 | 格式 |
| --- | --- |
| MYBATIS_XML | `<namespace>.<statementId>(<file>:<line>)` |
| MYBATIS_ANNOTATION | `<className>#<methodName>(<file>:<line>)` |
| JAVA_JDBC | `<className>#<methodName>(<file>:<line>)` |

文件路径只取 `Path.getFileName()`，与 IDEA "Copy Reference" 行为一致。column 不再输出（IDEA 跳转用不到）。

## 7. CLI 行为

命令形态：

```bash
java -jar target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/project \
  --output route-sql-inventory.json
```

参数（`ScanCommand`）：

- `--path`：可重复，文件或目录。
- `--config`：可选，YAML/JSON，仅用于读 `project` 名。
- `--output`：不填则 stdout。
- `--include`：glob，可重复。
- `--exclude`：glob，可重复。

退出码：

- `0`：扫描完成。
- `3`：I/O 失败或解析异常。

旧版本的 `--format/--failed-only/--fail-on` 已经移除。

## 8. 常见需求改动指南

新增 SQL 来源：

1. 新建类实现 `SqlExtractor`。
2. 在 `supports(Path)` 中限定文件类型或路径。
3. 提取后用 `SqlObjects.create(rawSql, origin, dynamic)` 生成 `SqlObject`。
4. 补 `SourceKind`，如果是新来源；同步在 `AtFormatter` 加 case。
5. 在 `ScanEngine` 的 `extractors` 列表注册。
6. 增加 fixture 和 `ScanEngineTest` 断言。

增强 MyBatis 注解 SQL：

1. 主要改 `MyBatisAnnotationExtractor`。
2. 注解内动态标签重建逻辑在 `MyBatisSqlScriptBuilder`。
3. Provider 注解如果要支持，需要决定是否静态解析 provider 方法返回值，避免误判运行时 SQL。

增强 MyBatis 动态 SQL：

1. 主要改 `MyBatisSqlScriptBuilder`。
2. 目标是枚举可能变体；两个 `<if>` 会生成 4 条 SQL。
3. 保持每个 raw SQL 变体可解析，并在 extractor 层按 normalized SQL 去重（这是 `SqlNormalizer` 仅存的内部用法）。
4. 枚举出的变体仍标记 `dynamic=true`。同一 statement 的多个变体共享 `at`，仅 `sql` 不同。

增强 Java 字符串求值：

1. 主要改 `JavaJdbcStatementExtractor.evaluate()` 和 `collectStringVariables()`。
2. 当前不做跨文件符号求解。若要做跨文件常量，建议明确范围和性能成本。
3. 遇到不确定变量时用 `__DYNAMIC__`，不要静默伪装成确定 SQL。

新增报告格式：

> 当前架构刻意只保留一种格式。新增格式前请先在 `docs/compact-sql-scanner-iteration-plan.md` 的语义边界内确认是否真有需要——服务端是诊断/可读报告的统一出口。

如确实必须新增：

1. 实现 `Reporter`。
2. 在 `ScanCommand` 中替换或并列暴露（建议保持单一默认）。
3. 用 `ScanEngineTest` 新增测试覆盖。

## 9. 当前简化点与风险

- MyBatis 动态 SQL 会枚举 `<if>` 包含/省略分支和 `<choose>` 分支，但仍是静态近似，不执行 OGNL。
- MyBatis 注解 mapper 只提取 `@Select/@Insert/@Update/@Delete`，不提取 provider 注解。
- `<foreach>` 当前输出 `(__FOREACH__)` 并标记 dynamic。
- Java 只做同方法内简单字符串求值，不做完整符号解析。
- Java 对 `jdbcTemplate.query/update` 做 JDBC-like 支持，但没有验证接收者类型。
- `<sql>` fragment 会作为来源进入 SQL 清单，多数 fragment 不是完整 SQL；这是预期行为，由服务端识别处理。

## 10. 工作区注意事项

- `target/` 是构建产物，已加入 `.gitignore`。
- 手动编辑文件优先用 patch，避免无关格式化。
- 修改后至少跑 `mvn test`；涉及 CLI 或 shade 配置时跑 `mvn package` 和一次 `java -jar ... scan`。

## 11. 用户偏好和需求理解

用户的核心诉求是：让客户端退化为"纯 SQL 清单生产者"，把所有诊断逻辑收敛到服务端，使客户端→服务端的传输负载和字段数量降到最小可用集。后续响应需求时优先保持：

- 端到端可运行：`mvn package` + 一次 `java -jar ... scan` 必须能输出有效 v=2 JSON。
- 出处定位不丢：`at` 字段必须能让 IDEA 直接跳转。
- 不要让客户端重新承担解析/规范化/规则诊断职责——这些都是服务端的事。
- 架构保留 extractor 扩展点。

如果新需求要扩展客户端能力，先回看 `docs/compact-sql-scanner-iteration-plan.md` 第 0 节"目标与非目标"。
