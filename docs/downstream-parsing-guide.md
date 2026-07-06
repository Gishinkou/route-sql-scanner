# 下游解析改造指南（本轮 scanner 输出变更）

面向对象：消费 `route-sql-scanner` 的 `compact-json` 输出、并据此解析 SQL、
判定分库分表路由字段、生成报表的**服务端解析方**。

本轮 scanner 有两处改动会改变你收到的 `sqls[].sql` 内容形态。scanner 的
契约不变（仍是 schema `v=2`，字段仍是 `at` / `sql` / `dynamic?`），变的只是
`sql` 字符串本身。下面逐条说明「变了什么」「你该怎么调」。

## 契约回顾

```jsonc
{
  "v": 2,
  "project": "acme",
  "scannedAt": "2026-07-01T21:01:55",
  "sqls": [
    { "at": "namespace.statementId(File.xml:line)", "sql": "...", "dynamic": true }
  ]
}
```

- `sql` 始终是 **raw SQL**：`#{x}` 已被替换为 `?`；`${x}` 被替换为 `__DYNAMIC__`。
- `dynamic: true` 仅在该条 SQL 来自动态标签（`<if>/<foreach>/<choose>/<where>/<set>`）
  或含 `__DYNAMIC__` 时出现；否则字段缺省。

---

## 变更 1：`<sql>` 片段不再作为独立 SQL 行输出

### 变了什么

以前，每个 `<sql id="...">` 片段（如 `Base_Column_List`）会被当成一条独立语句
输出，产生一行只有裸列名、没有表名的「SQL」：

```jsonc
// 旧输出（已废弃）
{ "at": "outStockProcessDao.Base_Column_List(...)",
  "sql": "id,biz_uuid,biz_order_id,...,update_date" }
```

现在这类行**不再出现**。片段只在被 `<include refid="...">` 引用时，就地内联进
真正的 `select/insert/update/delete` 语句里。

```jsonc
// 新输出
{ "at": "outStockProcessDao.queryOutStockProcessByBizUuid(...)",
  "sql": "select id,biz_uuid,...,update_date from out_stock_process_? where biz_uuid = ?" }
```

### 下游该怎么调

- **删掉针对「裸列名行」的兜底/过滤逻辑**（如果你之前专门丢弃过没有
  `from`/`into`/`update`/`delete` 关键字的行，用于剔除片段噪声）。现在 scanner
  已在源头剔除，你的过滤器不会再命中这类行——保留它无害，但已成死代码。
- **不要**再期望能从输出里单独拿到某个 `Base_Column_List` 片段。片段内容只会
  出现在引用它的语句里。若你的报表曾用片段行做「列清单」展示，改为从内联后的
  完整语句里提取列。
- 行数会减少：一个含 N 个 `<sql>` 片段的 mapper，输出行数少 N 行。若你有
  基于行数的断言/校验，需同步更新。

---

## 变更 2：`<foreach>` 保留元组模板，不再塌成 `(__FOREACH__)`

### 变了什么

以前，任何 `<foreach>` 整体被替换成占位符 `(__FOREACH__)`，元组/列结构丢失：

```jsonc
// 旧输出（已废弃）
{ "sql": "insert into orders (biz_uuid, biz_type) values (__FOREACH__)", "dynamic": true }
{ "sql": "select id from orders where biz_uuid in (__FOREACH__)", "dynamic": true }
```

现在 scanner 会 build 出 `<foreach>` body 的**一份模板**，并用其 `open`/`close`
属性包裹，产出可解析的 SQL：

```jsonc
// 新输出
{ "sql": "insert into orders (biz_uuid, biz_type, node_name) values (?, ?, ?)", "dynamic": true }
{ "sql": "select id from orders where biz_uuid in (?)", "dynamic": true }
{ "sql": "delete from orders where id in (?)", "dynamic": true }
```

要点：

- **批量 insert**：`values (?, ?, ?)` —— 列/占位结构完整保留，可正常做「插入了
  哪些列」的分析。
- **IN 子句**：`in (?)` —— 一个 `?`，代表「一个或多个绑定值」，不代表恰好一个。
- **行数是虚构的**：模板只展开**一份**。scanner 不知道运行时集合的真实大小，
  所以**不要**把 `(?, ?, ?)` 理解为「恰好 3 行」，它只是「一行元组的列结构」。
- `dynamic: true` 仍然保留，作为「此处有 foreach 展开、行数运行时决定」的标记。

### 下游该怎么调

- **移除对 `__FOREACH__` 的特判**。这个 token 在正常路径下不再出现（仅在
  foreach body 为空这种畸形 mapper 下才回退保留）。若你之前把含 `__FOREACH__`
  的行直接跳过或标记为「无法解析」，现在应改为**正常送进 SQL parser**。
- **IN 子句**：`in (?)` 是合法可解析的语法，交给 JSqlParser 之类正常解析即可。
  做路由字段判定时，`in (?)` 与 `= ?` 等价——只要 IN 的列是分片键，就算命中路由。
  ⚠️ 注意：`?` 个数不再有意义，不要用它推断绑定参数数量。
- **批量 insert**：`insert ... (col1, col2, col3) values (?, ?, ?)` 现在能被解析出
  完整插入列集合。路由字段判定应检查**插入列里是否包含分片键**（而非像以前那样
  因为 `(__FOREACH__)` 无法解析而漏判/跳过）。
- 若你的报表按 SQL 文本去重，注意去重键变化：同一条 batch-insert 的文本从
  `(__FOREACH__)` 变为 `(?, ?, ?)`，历史缓存/指纹会 miss，属预期。

---

## 判定分片键时的通用提醒（两处改动共同影响）

1. **`?` 是不透明绑定占位**，不含列名信息。判定路由字段要靠 SQL 结构里的**列名**
   （`where shard_col = ?`、`in (...)` 的列、insert 的列清单），不是靠 `?`。
2. **`__DYNAMIC__` 仍代表 `${}` 拼接**（如动态表名/动态列），无法静态确定，按你
   既有策略处理（通常标记为需人工确认）。本轮未改动此行为。
3. **`dynamic: true` 的语义未变**：表示该语句含运行时决定的动态成分（分支或
   foreach 行数），不代表 SQL 不可解析。现在绝大多数 `dynamic` 行都是可解析的
   合法 SQL 了。

---

## 快速回归清单（下游自测）

拿一个含 `<sql>+<include>`、batch-insert、`in <foreach>` 的 mapper 跑一遍，确认：

- [ ] 输出里没有「裸列名、无表名」的行。
- [ ] batch-insert 行形如 `insert into T (...) values (?, ?, ...)`，能被 SQL parser 解析。
- [ ] IN 行形如 `... in (?)`，能被 SQL parser 解析。
- [ ] 输出里不再出现 `(__FOREACH__)`（畸形空 foreach 除外）。
- [ ] 分片键判定对 batch-insert 和 IN 子句都能正确命中/告警。
