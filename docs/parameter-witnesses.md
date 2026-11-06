# MyBatis 参数见证

2026-11-05：原生模板采集覆盖 MyBatis 注解。字面量、字符串数组及当前编译单元内可证明的 `static final String` / 接口字符串常量会保留原始 SQL 或 `<script>`；不执行常量初始化方法或 Provider。跨文件未解析常量、循环引用和 Provider 仍输出原语句身份及 `ANNOTATION_SQL_UNRESOLVED` 缺口，不静默丢弃。明确 JSON 参数或字符串字面量可证明接收者类型时，支持原生 `String.equals/equalsIgnoreCase`；其他业务方法仍不开放。

同样允许原生 `String.split` 的单参数形式，但分隔符必须为单个字面字符，且不含正则元字符。动态参数分隔符、正则、二参数重载及接收者上的业务方法链仍拒绝。显式 JSON null 只保留原生分支保护/空值异常语义；缺失属性不能视为 null。安全检查仍覆盖全部表达式，不因分支当前未选中而执行或开放任意方法。补充见证仍为 `SAMPLED`，不能据此将整个模板判为支持。

多模块或同仓不同部署可传 `--mapper-resource-context factory-resources.json`，文件格式为：

```json
{
  "version": "mybatis-resource-context/v1",
  "contexts": [
    {
      "contextId": "deployment-a/sqlSessionFactory",
      "resourcePaths": ["app-a/src/main/resources/mapper/Orders.xml", "common/src/main/resources/mapper/Columns.xml"]
    }
  ]
}
```

资源必须是相对于 `--project-root` 的实际文件，范围来自已证明的 Factory 注册配置，不能仅凭目录邻近猜测。此输入仅限制原生模板 include 的可见片段；全量定义、归一 SQL、`sqlKey` 和 `sourceStatementId` 保持原身份，后续再用原有 allowlist 选择送检定义。一个语句文件只命中一个 Factory 时按该资源集展开；同一个 Factory 内真正的同名片段冲突仍未解析。未命中任何显式资源集的语句保留缺口。一个语句被多个 Factory 加载且展开结果不同，`template.templatesByContext` 保留各上下文模板，总体 `UNRESOLVED`；按 Factory 分别提供资源集后渲染，不任意选一个。资源上下文不证明实际部署已经加载。

日期：2026-10-20。状态：本地源码及真实四应用模板回归已验证，未发布。

MyBatis XML 的 compact v2 条目新增可选 `template`，保存原始条件树、空 otherwise、原生 include 展开后的脚本、条件路径、片段来源和 SHA256。静态占位语句及独立 selectKey 也可使用参数见证。include 按 MyBatis namespace/property 规则展开；缺失、循环或被引用的重名冲突片段标 `UNRESOLVED`。原有 `sql`、`sqlKey`、`sourceStatementId` 保持旧合同，不将匿名分支拼接结果当作参数见证。

通过 `--parameter-scenarios parameters.json` 提供显式参数：

```json
{
  "statements": {
    "Orders.find": [
      {"id": "id-7", "parameters": {"id": 7, "flag": 1, "ids": [3, 5]}}
    ]
  }
}
```

键严格为 `namespace + "." + statementId`，即使旧 statementId 自带 namespace 也保留。参数只能是 JSON 数据；不构造业务对象或执行 Mapper/JDBC。

匹配范围是本次实际采集的定义，包括 SQL allowlist 的限制。一个键匹配零条或多条定义时，每个受影响样本在 inventory 顶层 `parameterScenarioGaps` 记录 `statement`、`scenarioId`、`reason` 和 `matchedStatementCount`。原因分别为 `UNMATCHED_STATEMENT` 或 `AMBIGUOUS_STATEMENT`。其余定义继续采集，唯一匹配的样本继续渲染。参数文件结构、重复键和重复样本 ID 仍按原约定校验。

```sh
java -jar target/route-sql-scanner-1.1.3.jar scan \
  --path /absolute/project/src/main/resources/mapper \
  --project-root /absolute/project \
  --parameter-scenarios /absolute/parameters.json \
  --output /absolute/inventory.json
```

原生 `XMLScriptBuilder`/`SqlSource.getBoundSql` 负责条件、choose、where、trim、foreach 和 bind 的语义；按 MyBatis ParameterMapping 顺序读取实参及 foreach additional parameters。每个 `renderedScenarios` 记录 id、输入 witness、SQL、JDBC parameters 和 properties；失败为 `UNRENDERABLE`，不猜测替代 SQL。仅接受有限的纯 OGNL 节点，遇到不支持表达式保留缺口。

遗留 `@Ognl@isEmpty/isNotEmpty` 需提供应用实际 helper JAR，并在 profile 的 `helperClassHashes.Ognl` 指定已核对的 class SHA256。渲染前验证实际加载的 class 字节；缺失或不一致则拒绝渲染。scanner 不包含或仿写该业务 helper。需要外部 helper 时用 `java -cp 'scanner.jar:实际helper.jar:helper依赖' com.acme.routesql.Main scan ...`；Java `-jar` 不会加载外加 classpath。其他任意静态方法不可执行。

`scenarioCoverage=SAMPLED` 始终表示显式参数样本。它证明这些 JSON 参数在当前 MyBatis 渲染配置下可生成相应 SQL，不证明业务入口接受该参数、生产实际调用过它、应用原 MyBatis 版本完全一致，或已覆盖全部组合。Date、enum、自定义 TypeHandler、动态数据库方言等仍需明确的运行配置，不能用 JSON 数字冒充对象。

server 应直接使用原生 SQL 和 JDBC 参数实跑当前 SDK，禁止对 BoundSql 做正则改写或回退到匿名分支/Long(1)。有样本通过仍不得认定模板所有分支支持；本轮 server 保留部分覆盖状态。

证据：评估仓 `iteration/024-sql评估迭代/internal-corpus-20261019/round4/`，38 条 / 4 应用；SDK 1.4.9 源码、JAR 和版本均不修改。SDK 本身的能力结论以该版本 AST/Planner/target SQL 的真实输出为准。
