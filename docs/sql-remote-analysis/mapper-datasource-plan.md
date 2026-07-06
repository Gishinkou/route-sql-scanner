我现在的主要担忧是 上下文爆炸以及ai 犯错。我觉得最靠谱的方法是用固定代码扫描所有代码文件里的 sql，并通过 LLM 挖掘一些公共前缀规则。然后把这些规则送到服务端做类似“前缀匹配”的计算。这样把逐一核对 sql 出处的上下文代价和犯错可能性降到最低。这个过程能否可行。比如我可能最后传给服务器的可能是所有SQL（包括mybatis等），这些sql的挖掘纯粹采用代码分析，不占用上下文。这些SQL同时带有一些其他采集信息，而我希望这些采集信息能够被 LLM 挖掘出的“公共前缀规则”直接匹配到。总之我只希望LLM建立一套覆盖面广、且精炼的匹配规则，这个规则最好能直接从sqlSessionFactory 等地方拿到而不必去再逐一读mapper接口代码。这个能做到吗

能做到，而且你这个方向比“让 LLM 逐个 SQL 判断归属”靠谱很多。

你的核心思路可以总结成：

```text id="tfm39k"
固定代码扫描器负责采集事实
LLM 只负责从配置中提炼少量公共匹配规则
服务端用规则做批量匹配和归属计算
```

这是可行的，而且很适合控制上下文爆炸和 AI 犯错。

---

## 结论先说

可以做到：

```text id="4bnltc"
不用逐一读 mapper 接口
不用把所有 SQL 都塞给 LLM
不用让 LLM 判断每条 SQL 属于哪个 DataSource
```

更好的做法是：

```text id="0y3rhw"
1. 代码扫描器抽取所有 SQL 和 SQL 所属文件/namespace/statementId 等事实
2. 代码扫描器抽取 MyBatis 配置里的 sqlSessionFactory / mapperLocations / mapperScan 规则
3. LLM 只看少量配置摘要，生成“公共前缀匹配规则”
4. 服务端用规则批量匹配所有 SQL
5. 对无法匹配或冲突的 SQL 单独输出
```

这样 LLM 处理的是：

```text id="yxltd1"
几十条配置规则
```

而不是：

```text id="czb41h"
几千条 SQL
几百个 Mapper 文件
```

这就是性价比最高的路线。

---

## 最推荐的目标模型

你最后传给服务端的 SQL 采集信息，可以长这样：

```json id="j2r5f5"
{
  "statementId": "com.xxx.order.mapper.OrderMapper.selectById",
  "namespace": "com.xxx.order.mapper.OrderMapper",
  "sqlId": "selectById",
  "sqlType": "select",
  "sourceType": "mybatis_xml",
  "sourcePath": "src/main/resources/mapper/order/OrderMapper.xml",
  "resourcePath": "mapper/order/OrderMapper.xml",
  "rawSql": "select * from order where id = ?"
}
```

LLM 不需要看 `rawSql`。
归属数据源时，主要看这些字段：

```text id="qlgk1d"
statementId
namespace
sourcePath
resourcePath
sourceType
```

然后规则可以长这样：

```json id="uv5hpk"
{
  "ruleId": "order-ds-rule-001",
  "dataSource": "orderDataSource",
  "sqlSessionFactory": "orderSqlSessionFactory",
  "matchers": [
    {
      "field": "namespace",
      "type": "prefix",
      "value": "com.xxx.order.mapper."
    },
    {
      "field": "resourcePath",
      "type": "glob",
      "value": "mapper/order/**/*.xml"
    }
  ],
  "confidence": "high"
}
```

服务端负责批量匹配：

```text id="cpgk1i"
SQL采集记录 -> 匹配规则 -> DataSource
```

这个过程完全不需要 LLM 逐条参与。

---

## LLM 应该挖掘什么规则？

LLM 不应该挖 SQL 本身，而应该挖这些配置关系：

```text id="3l3e0j"
SqlSessionFactory -> DataSource
SqlSessionFactory -> mapperBasePackages
SqlSessionFactory -> mapperXmlLocations
```

然后转换为匹配规则。

例如从配置里看到：

```java id="h0i2zq"
@MapperScan(
    basePackages = "com.xxx.order.mapper",
    sqlSessionFactoryRef = "orderSqlSessionFactory"
)

@Bean
public SqlSessionFactory orderSqlSessionFactory(
        @Qualifier("orderDataSource") DataSource dataSource) {
    SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
    bean.setDataSource(dataSource);
    bean.setMapperLocations(
        resolver.getResources("classpath*:mapper/order/**/*.xml")
    );
    return bean.getObject();
}
```

LLM 只需要产出：

```json id="cw162n"
{
  "sqlSessionFactory": "orderSqlSessionFactory",
  "dataSource": "orderDataSource",
  "mapperBasePackagePrefixes": [
    "com.xxx.order.mapper."
  ],
  "mapperXmlResourceGlobs": [
    "mapper/order/**/*.xml"
  ]
}
```

这就是公共规则。

---

## 最重要的设计点：LLM 只产规则，不产归属结果

不要让 LLM 直接输出：

```json id="jbsu52"
{
  "OrderMapper.selectById": "orderDataSource"
}
```

这会爆上下文，也容易错。

应该让 LLM 输出：

```json id="50qkmg"
{
  "namespacePrefix": "com.xxx.order.mapper.",
  "resourcePathGlob": "mapper/order/**/*.xml",
  "dataSource": "orderDataSource"
}
```

然后服务端自己跑：

```text id="efhqv3"
if namespace startsWith "com.xxx.order.mapper.":
    dataSource = orderDataSource
```

这样 AI 错误空间会小很多。

---

## 你说的“最好能直接从 sqlSessionFactory 等地方拿到”——可以

可以，而且这是最优先的来源。

配置里的 `SqlSessionFactoryBean` 本身通常已经有：

```text id="s7gxro"
DataSource
mapperLocations
```

`@MapperScan` 或 `MapperScannerConfigurer` 通常有：

```text id="99d736"
basePackages
sqlSessionFactoryRef
```

所以你可以通过固定扫描器先抽配置事实：

```json id="fcbffu"
{
  "mapperScans": [
    {
      "basePackages": [
        "com.xxx.order.mapper"
      ],
      "sqlSessionFactoryRef": "orderSqlSessionFactory",
      "sourcePath": "src/main/java/com/xxx/config/OrderMybatisConfig.java"
    }
  ],
  "sqlSessionFactories": [
    {
      "beanName": "orderSqlSessionFactory",
      "dataSourceRef": "orderDataSource",
      "mapperLocations": [
        "classpath*:mapper/order/**/*.xml"
      ],
      "sourcePath": "src/main/java/com/xxx/config/OrderMybatisConfig.java"
    }
  ]
}
```

然后让 LLM 整理为：

```json id="h16jfx"
{
  "rules": [
    {
      "dataSource": "orderDataSource",
      "sqlSessionFactory": "orderSqlSessionFactory",
      "namespacePrefixes": [
        "com.xxx.order.mapper."
      ],
      "resourcePathGlobs": [
        "mapper/order/**/*.xml"
      ]
    }
  ]
}
```

这一步上下文很小。

---

## 更进一步：这一步其实也可以不用 LLM

如果项目配置足够标准，完全可以纯代码生成规则：

```text id="2w6t01"
@MapperScan.sqlSessionFactoryRef
    -> 找同名 SqlSessionFactoryBean
    -> 找其中 dataSourceRef
    -> 组合 basePackages + mapperLocations
```

LLM 的价值主要在这些场景：

```text id="g35n9r"
配置写法不统一
Bean 名称需要推断
mapperLocations 来自变量拼接
basePackage 来自常量
公司框架封装了一层
多个配置文件之间需要合并理解
```

所以最稳的架构是：

```text id="e0qdbw"
纯代码扫描器先提取结构化事实
纯代码规则引擎先尝试生成映射
LLM 只处理“扫描器提取到但无法直接归一化”的配置摘要
```

不要一开始就让 LLM 看全项目。

---

## 服务端匹配规则建议

规则不要只支持一种前缀。建议支持这几类：

```json id="z337yk"
{
  "field": "namespace",
  "op": "startsWith",
  "value": "com.xxx.order.mapper."
}
```

```json id="rbtyfy"
{
  "field": "statementId",
  "op": "startsWith",
  "value": "com.xxx.order.mapper."
}
```

```json id="6bfcb7"
{
  "field": "resourcePath",
  "op": "glob",
  "value": "mapper/order/**/*.xml"
}
```

```json id="en3t68"
{
  "field": "sourcePath",
  "op": "contains",
  "value": "/mapper/order/"
}
```

优先级建议：

```text id="ai98hh"
1. namespace / statementId 前缀
2. resourcePath glob
3. sourcePath 文件路径
```

因为 `namespace` 更接近 MyBatis 语义，路径只是工程组织习惯。

---

## 匹配结果建议分级

服务端不要只输出成功/失败。建议输出：

```text id="mqufe5"
CONFIRMED
INFERRED
CONFLICT
UNRESOLVED
```

含义：

```text id="4xvs78"
CONFIRMED:
  namespace 前缀和 resourcePath glob 同时命中同一个 DataSource。

INFERRED:
  只有 namespace 或只有 resourcePath 命中。

CONFLICT:
  多条规则命中不同 DataSource。

UNRESOLVED:
  没有规则命中。
```

例如：

```json id="07zeqy"
{
  "statementId": "com.xxx.order.mapper.OrderMapper.selectById",
  "dataSource": "orderDataSource",
  "status": "CONFIRMED",
  "matchedRules": [
    "order-namespace-rule",
    "order-resource-rule"
  ]
}
```

这样你后续可以只对 `CONFIRMED` 自动分析，对 `CONFLICT / UNRESOLVED` 再让人或 LLM 处理。

---

## 这个方案最大的好处

你的方案把问题从：

```text id="mi83ub"
让 LLM 理解几千个 SQL 属于哪个库
```

降维成：

```text id="f29mdz"
让 LLM 理解十几个 MyBatis 配置，生成几十条规则
```

这是非常大的可靠性提升。

上下文成本也从：

```text id="d2s46h"
全量 Mapper + SQL
```

变成：

```text id="tdvf6i"
配置摘要 + 扫描器提取的候选关系
```

LLM 的任务也从“逐条判断”变成“归纳规则”，更适合它。

---

## 我建议的最终流水线

```text id="0oir44"
代码扫描阶段：

1. 扫描所有 SQL
   - XML SQL
   - 注解 SQL，如果后续要支持
   - SQL 文件
   - 其他框架 SQL

2. 每条 SQL 附带采集元信息
   - statementId
   - namespace
   - sourcePath
   - resourcePath
   - sqlType
   - rawSql

3. 扫描 MyBatis 配置
   - SqlSessionFactoryBean
   - @MapperScan
   - MapperScannerConfigurer
   - mapperLocations
   - dataSourceRef

4. 生成配置事实摘要

LLM 阶段：

5. 输入配置事实摘要
6. 输出公共匹配规则

服务端阶段：

7. 用规则批量匹配所有 SQL
8. 输出 statementId -> DataSource
9. 标记 CONFIRMED / INFERRED / CONFLICT / UNRESOLVED
10. 后续按 DataSource 策略分析 SQL
```

---

## 简化版规则 Schema

可以先用这个：

```json id="qdx718"
{
  "version": "1.0",
  "rules": [
    {
      "ruleId": "order-datasource",
      "dataSource": "orderDataSource",
      "sqlSessionFactory": "orderSqlSessionFactory",
      "match": {
        "namespacePrefixes": [
          "com.xxx.order.mapper."
        ],
        "statementIdPrefixes": [
          "com.xxx.order.mapper."
        ],
        "resourcePathGlobs": [
          "mapper/order/**/*.xml"
        ]
      },
      "source": {
        "mapperScan": "src/main/java/com/xxx/config/OrderMybatisConfig.java",
        "sqlSessionFactory": "src/main/java/com/xxx/config/OrderMybatisConfig.java"
      }
    }
  ]
}
```

服务端匹配时：

```text id="zdztkf"
namespace startsWith any namespacePrefixes
OR statementId startsWith any statementIdPrefixes
OR resourcePath matches any resourcePathGlobs
```

然后再根据命中数量分级。

---

## 原则上能不能“不读 mapper 接口代码”？

大多数情况下可以。

只要有：

```text id="jwu4bf"
@MapperScan.basePackages
SqlSessionFactoryBean.mapperLocations
XML namespace
```

就够了。

你不需要逐个读 Mapper 接口代码。

因为 XML 的：

```xml id="h2s5x3"
<mapper namespace="com.xxx.order.mapper.OrderMapper">
```

已经告诉你 Mapper 接口名了。

`@MapperScan` 的：

```java id="e9wnfm"
basePackages = "com.xxx.order.mapper"
```

已经告诉你这一批接口归哪个 `SqlSessionFactory`。

所以主流程可以不读 mapper 接口，只读：

```text id="pnxk9a"
MyBatis 配置
Mapper XML
```

这正是低成本路线。

---

## 但有一个关键提醒

如果你最终采集的是“所有 SQL，包括 MyBatis 等”，那么不同来源的 SQL 元信息质量不一样。

MyBatis XML SQL 很好归属：

```text id="x7xzz7"
namespace + resourcePath + statementId
```

注解 SQL 也还可以：

```text id="qq0zf6"
mapperInterface + methodName
```

普通代码里拼接的 SQL 就弱很多：

```text id="p0e62b"
sourcePath + enclosingClass + methodName
```

所以规则系统要允许不同 sourceType 用不同匹配字段。

例如：

```json id="tkiith"
{
  "sourceType": "mybatis_xml",
  "matchFields": ["namespace", "statementId", "resourcePath"]
}
```

```json id="glms7z"
{
  "sourceType": "java_literal_sql",
  "matchFields": ["sourcePath", "enclosingClass"]
}
```

不要强行用同一套字段匹配所有 SQL。

---

## 总体判断

你的方案非常可行，而且是更工程化、更低风险的方案。

推荐路线是：

```text id="0hwajn"
扫描器全量采集 SQL 和配置事实
LLM 只生成少量公共匹配规则
服务端负责批量匹配和冲突检测
```

其中 LLM 的输入最好是：

```text id="f3d2qy"
SqlSessionFactory 配置摘要
MapperScan 配置摘要
mapperLocations 摘要
少量候选路径样本
```

而不是全量 SQL。

这样既能控制上下文，又能降低 AI 幻觉和逐条误判概率。
