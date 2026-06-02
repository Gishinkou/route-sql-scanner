# route-sql-scanner 使用文档

这份文档面向"我要在另一个 Java 项目里跑一下工具，拿到一份 SQL 清单"的场景。

当前版本职责被收敛为：**纯 SQL 清单生产者**。不再做规则诊断、不再产出 xlsx/markdown/normalized 等格式，唯一输出是 `compact-json`（v=2）。诊断逻辑在服务端完成。

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

## 2. 可选：准备一个配置文件

配置目前只剩一个可选字段，用于在输出里标记项目名：

```yaml
project: my-service
```

也支持 JSON：

```json
{ "project": "my-service" }
```

不传 `--config` 时，输出里不会包含 `project` 字段。扫描器不再读取任何路由规则、dialect、severity 等配置；这些已经全部移到服务端。

## 3. 扫描目标 Java 项目

假设：

- 工具项目路径：`/path/to/route-sql-scanner`
- 目标 Java 项目路径：`/path/to/your-java-project`
- 输出位置：`/path/to/your-java-project/route-sql-inventory.json`

执行：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --output /path/to/your-java-project/route-sql-inventory.json
```

带 project 名：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --config /path/to/your-java-project/route-sql.yml \
  --output /path/to/your-java-project/route-sql-inventory.json
```

不指定 `--output` 时，结果会写到 stdout：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project
```

## 4. 输出格式：compact-json (v=2)

顶层结构：

```json
{
  "v": 2,
  "project": "my-service",
  "scannedAt": "2026-06-01T12:34:56",
  "sqls": [
    {
      "at": "com.example.OrderMapper#selectById(OrderMapper.java:42)",
      "sql": "select * from order_main where order_id = #{orderId}"
    },
    {
      "at": "com.example.OrderMapper.selectByStatus(OrderMapper.xml:88)",
      "sql": "select * from order_main where status = #{status}",
      "dynamic": true
    }
  ]
}
```

字段语义（严格）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `v` | int | 是 | inventory schema 版本，恒为 `2` |
| `project` | string | 否 | 项目名，仅展示用；由 `--config` 中的 `project` 字段填充 |
| `scannedAt` | string | 是 | 扫描时间，ISO 本地时间字符串 |
| `sqls` | array | 是 | SQL 清单 |
| `sqls[].at` | string | 是 | IDEA "Copy Reference" 风格的单字段出处定位 |
| `sqls[].sql` | string | 是 | raw SQL，保留 MyBatis 占位符与动态片段；服务端再做规范化 |
| `sqls[].dynamic` | bool | 否 | 是否动态 SQL；缺省 `false` |

`at` 字段格式按来源类型固定为：

| 来源 | 格式 | 例 |
| --- | --- | --- |
| MyBatis XML | `<namespace>.<statementId>(<file>:<line>)` | `com.example.OrderMapper.selectById(OrderMapper.xml:42)` |
| MyBatis 注解 | `<className>#<methodName>(<file>:<line>)` | `com.example.OrderMapper#selectById(OrderMapper.java:88)` |
| Java JDBC | `<className>#<methodName>(<file>:<line>)` | `com.example.OrderDao#countByStatus(OrderDao.java:123)` |

文件路径只取文件名（IDEA 行为），方便直接粘进 "Navigate → File"。

## 5. 只扫描部分文件

可以传多个 `--path`：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project/src/main/resources/mapper \
  --path /path/to/your-java-project/src/main/java \
  --output /path/to/your-java-project/route-sql-inventory.json
```

用 `--include`/`--exclude` 控制 glob：

```bash
java -jar /path/to/route-sql-scanner/target/route-sql-scanner-0.1.0.jar scan \
  --path /path/to/your-java-project \
  --include "**/*.xml" \
  --include "**/*.java" \
  --exclude "**/target/**" \
  --exclude "**/build/**" \
  --output /path/to/your-java-project/route-sql-inventory.json
```

默认不传 include 时，工具会扫描 `.xml` 和 `.java` 文件。

## 6. 退出码

- `0`：扫描完成（始终返回 0，无论 sqls 是否为空）。
- `3`：I/O 失败或解析异常。

扫描器不再判定 ERROR/WARN —— 没诊断就没等级，等级判定由服务端给出。

## 7. 当前能识别什么

MyBatis XML：

- `<select>` / `<insert>` / `<update>` / `<delete>` / `<sql>` / `<include refid="..."/>`
- 常见动态标签的近似枚举：`if/choose/when/otherwise/foreach/trim/where/set/bind`
- `<if>` 会展开成"包含/省略"两种分支；同一个 statement 可能产出多条 SQL，都共享同一 `at`，仅 `sql` 不同

MyBatis 注解：

- `@Select` / `@Insert` / `@Update` / `@Delete`
- 单字符串：`@Select("SELECT ...")`
- 字符串数组：`@Select({"SELECT ...", "FROM ..."})`
- 字符串拼接、text block
- 注解内 `<script>/<where>/<if>` 等动态标签近似展开

Java JDBC-like：

- `connection.prepareStatement("...")`
- `statement.execute/executeQuery/executeUpdate("...")`
- `jdbcTemplate.query/update("...")`
- 同方法内简单 `String sql = "..."; prepareStatement(sql)`
- 简单字符串拼接、Java text block

## 8. 当前限制

- 静态扫描，不连接数据库、不执行 SQL。
- MyBatis 动态 SQL 是近似重建，不模拟运行时所有分支。
- `@SelectProvider/@InsertProvider/...` 暂不提取。
- Java 代码不做跨文件符号解析。
- `<sql>` fragment 会进入 SQL 清单，但 fragment 往往不是完整 SQL，由服务端识别处理。

扫描器不再做任何路由字段校验。校验、规范化、表名提取等全部由服务端在收到 inventory 后完成。

## 9. 用内置 fixture 试跑

在工具项目根目录可用内置测试数据试跑：

```bash
java -jar target/route-sql-scanner-0.1.0.jar scan \
  --path src/test/resources/fixtures
```

预期能看到一份 `v: 2` 的 JSON，`sqls` 数组里包含 MyBatis XML、MyBatis 注解、Java JDBC 三类来源各自的 `at` + `sql`。
