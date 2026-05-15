# route-sql-scanner 使用文档

这份文档面向“我要快速在另一个 Java 项目里跑一下工具，并拿到一份输出”的场景。

## 1. 准备工具 jar

在 `route-sql-scanner` 项目根目录执行：

```bash
mvn package
```

构建成功后，可执行 fat jar 会生成在：

```text
target/route-sql-scanner-0.1.0.jar
```

可以先确认命令可用：

```bash
java -jar target/route-sql-scanner-0.1.0.jar --help
java -jar target/route-sql-scanner-0.1.0.jar scan --help
```

要求：

- 本机有 Java 17 或更高版本。
- 首次 `mvn package` 需要能下载 Maven 依赖。

## 2. 在目标 Java 项目里准备规则配置

在你要扫描的 Java 项目根目录创建一个配置文件，比如 `route-sql.yml`：

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

按你的业务表修改：

- `orders`、`order_item`：需要检查的表名。
- `routeFields`：这些表允许使用的路由字段。SQL 中出现任意一个即视为满足 V0 规则。
- `operations`：要检查的 SQL 类型。
- `defaultSeverity`：诊断级别，通常用 `ERROR`。

如果只是想先提取 SQL，不关心诊断，可以不传 `--config`，工具会使用默认空规则。

也可以使用更轻量的 JSON 格式表达“指定表名和必要列名”。这种格式会经过内置预处理 hook 转换成内部规则模型：

```json
{
  "dialect": "mysql",
  "defaultSeverity": "ERROR",
  "tables": [
    {
      "name": "orders",
      "requiredColumns": ["tenant_id", "order_id"],
      "operations": ["SELECT", "UPDATE", "DELETE"]
    },
    {
      "name": "order_item",
      "requiredColumns": ["tenant_id", "order_id"]
    }
  ]
}
```

轻量 JSON 语义：

- `name`：表名，也兼容 `table`、`tableName`。
- `requiredColumns`：WHERE 中必须出现的路由列，也兼容 `columns`、`routeColumns`、`routeFields`。
- `operations`：默认是 `SELECT/UPDATE/DELETE`。
- `requireAll`：默认是 `true`，表示必要列必须全部出现。设为 `false` 时，出现任意一个路由列即可。

旧的 `routeRules.tables.*.routeFields` 配置默认仍是“任意一个命中即可”，不会被这个新格式改变语义。

## 3. 扫描另一个 Java 项目

假设：

- 工具项目路径：`/path/to/route-sql-scanner`
- 目标 Java 项目路径：`/path/to/your-java-project`
- 目标项目配置：`/path/to/your-java-project/route-sql.yml`

在任意目录执行：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format json \
  --output /path/to/your-java-project/route-sql-report.json \
  --fail-on NEVER
```

执行完成后，查看输出文件：

```text
/path/to/your-java-project/route-sql-report.json
```

## 4. 常用输出格式

输出 JSON，适合后续脚本处理：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format json \
  --output /path/to/your-java-project/route-sql-report.json \
  --fail-on NEVER
```

输出 Markdown，适合人工阅读：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format markdown \
  --output /path/to/your-java-project/route-sql-report.md \
  --fail-on NEVER
```

输出 JSONL，适合流式消费：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format jsonl \
  --output /path/to/your-java-project/route-sql-report.jsonl \
  --fail-on NEVER
```

输出 normalized SQL，一行一条，适合密集查看、grep 或 diff：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format normalized \
  --output /path/to/your-java-project/route-sql-normalized.txt \
  --fail-on NEVER
```

`normalized` 只输出 `sqlObjects[].normalizedSql`，不包含 summary、诊断、来源位置等元数据。

精简诊断 JSON 用 `--format compact-json`，输出以 diagnostics 为主视角：每条诊断直接带上对应 SQL 的 `identity.sourceKey`、`identity.logicalName`、`normalizedSql`、`severity`、`message`、`tableName`、`expectedRouteFields`、`columns`。

Excel 输出用 `--format excel`，字段和 `compact-json` 完全一致，一条诊断一行：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format excel \
  --output /path/to/your-java-project/route-sql-diagnostics.xlsx \
  --failed-only \
  --fail-on NEVER
```

如果不想在构建命令里传 `--format`，可以在目标项目根目录放 `route-sql-report.yml`：

```yaml
format: compact-json
```

这个文件只控制报告输出形态，不控制扫描规则。扫描规则仍然放在 `route-sql.yml` 或 `--config` 指向的配置文件里。

只输出未通过校验规则的 SQL 时，加 `--failed-only`。该选项支持 `json`、`compact-json`、`excel` 和 `normalized` 输出；`json` 会保留报告结构，但只包含有诊断的 `sqlObjects` 以及对应 diagnostics：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format compact-json \
  --failed-only \
  --fail-on NEVER
```

不指定 `--output` 时，报告会输出到 stdout：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --format json \
  --fail-on NEVER
```

## 5. 只扫描部分文件

可以传多个 `--path`：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project/src/main/resources/mapper \
  --path /path/to/your-java-project/src/main/java \
  --config /path/to/your-java-project/route-sql.yml \
  --format json \
  --output /path/to/your-java-project/route-sql-report.json \
  --fail-on NEVER
```

可以用 `--include` 和 `--exclude` 控制 glob：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --include "**/*.xml" \
  --include "**/*.java" \
  --exclude "**/target/**" \
  --exclude "**/build/**" \
  --config /path/to/your-java-project/route-sql.yml \
  --format markdown \
  --output /path/to/your-java-project/route-sql-report.md \
  --fail-on NEVER
```

默认不传 include 时，工具会扫描 `.xml` 和 `.java` 文件。

## 6. 输出内容怎么看

JSON 顶层大致是：

```json
{
  "version": "0.1.0",
  "dialect": "mysql",
  "summary": {
    "filesScanned": 6,
    "sqlCount": 10,
    "diagnosticCount": 3
  },
  "sqlObjects": [],
  "diagnostics": []
}
```

重点看：

- `summary.filesScanned`：扫描到的文件数。
- `summary.sqlCount`：提取出的 SQL 或 MyBatis `<sql>` fragment 数。
- `summary.diagnosticCount`：诊断数。
- `sqlObjects[].identity.logicalName`：SQL 的逻辑身份，比如 `com.foo.OrderMapper.findById`。
- `sqlObjects[].origin.file/line/column`：SQL 来源位置。
- `sqlObjects[].normalizedSql`：归一化后的 SQL。
- `sqlObjects[].parse.tables`：解析出的表名。
- `diagnostics[]`：缺路由字段等问题。

一条缺路由字段诊断通常会包含：

```json
{
  "id": "ROUTE-MISSING-001",
  "severity": "ERROR",
  "message": "SQL references table `orders` but does not constrain any route field: tenant_id, order_id",
  "tableName": "orders",
  "expectedRouteFields": ["tenant_id", "order_id"],
  "snippet": "SELECT id, status FROM orders WHERE id = ?"
}
```

`snippet` 字段当前保存完整 normalized SQL，不再做长度截断。

如果使用轻量 JSON 的 `requiredColumns` 且缺少其中一部分字段，诊断消息会指出实际缺少哪些必要列：

```json
{
  "id": "ROUTE-MISSING-001",
  "message": "SQL references table `orders` but WHERE/INSERT columns miss required route fields: order_id",
  "tableName": "orders",
  "expectedRouteFields": ["tenant_id", "order_id"]
}
```

## 7. 退出码和 CI 用法

`--fail-on` 控制扫描结果是否让进程失败：

- `--fail-on NEVER`：永远返回 0，适合本地先拿报告。
- `--fail-on ERROR`：存在 ERROR 诊断时返回 2。
- `--fail-on WARN`：存在 WARN 返回 1，存在 ERROR 返回 2。

本地快速生成报告建议使用：

```bash
--fail-on NEVER
```

CI 阶段想拦截缺路由字段，可以使用：

```bash
--fail-on ERROR
```

## 8. 当前能识别什么

MyBatis XML：

- `<select>`
- `<insert>`
- `<update>`
- `<delete>`
- `<sql>`
- `<include refid="..."/>`
- 常见动态标签的近似展开：`if/choose/when/otherwise/foreach/trim/where/set/bind`

MyBatis 注解：

- `@Select`
- `@Insert`
- `@Update`
- `@Delete`
- 支持单个字符串：`@Select("SELECT ...")`
- 支持字符串数组：`@Select({"SELECT ...", "FROM ..."})`
- 支持注解里的 `<script>/<where>/<if>` 等动态标签近似展开

Java：

- `connection.prepareStatement("SELECT ...")`
- `statement.execute("UPDATE ...")`
- `statement.executeQuery("SELECT ...")`
- `statement.executeUpdate("UPDATE ...")`
- `jdbcTemplate.query("SELECT ...")`
- `jdbcTemplate.update("UPDATE ...")`
- 同方法内简单 `String sql = "..."; prepareStatement(sql)`
- 简单字符串拼接
- Java text block

## 9. 当前限制

- 工具是静态扫描，不连接数据库，也不执行 SQL。
- MyBatis 动态 SQL 是近似重建，不模拟真实运行时所有分支。
- MyBatis 注解的 `@SelectProvider/@InsertProvider/@UpdateProvider/@DeleteProvider` 暂不提取，因为 SQL 来自 provider 方法。
- Java 代码不做完整跨文件符号解析。
- 路由字段规则 V0 是“字段出现即满足”，不会完整判断谓词可达性。
- `<sql>` fragment 会进入 SQL 清单，但 fragment 往往不是完整 SQL，解析失败是正常现象。

## 10. 用内置 fixture 试跑

在工具项目根目录可用内置测试数据试跑：

```bash
java -jar target/route-sql-scanner-0.1.0.jar scan \
  --path src/test/resources/fixtures \
  --config src/test/resources/fixtures/route-sql.yml \
  --format json \
  --fail-on NEVER
```

预期能看到：

- `filesScanned = 6`
- `sqlCount = 10`
- `diagnosticCount = 3`
