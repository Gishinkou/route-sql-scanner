# MyBatis 参数见证

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

```sh
java -jar target/route-sql-scanner-1.1.0.jar scan \
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
