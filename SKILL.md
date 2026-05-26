---
name: DongDalRouteSqlScan
type: 诊断工具
description: DongDAL Route SQL Scanner 薄封装。用于用户明确要求扫描/体检/诊断 Java 工程中的 MyBatis XML、Mapper、JDBC 或裸 SQL 是否缺少分库分表路由字段。流程固定为：通过 DongDalMCP 拉取发布态 scanner 配置，落盘为 JSON/YAML，调用插件内置 opencode-sqlscan wrapper 和 route-sql-scanner fat-jar，输出 xlsx 报告路径与失败摘要。该 Skill 不创建、不发布、不修改 DongDAL 管控端配置，也不直接改业务代码。
author: weichenguang.kasei
domain: 中间件
abstract: 通过 DongDalMCP 配置和内置 route-sql-scanner 生成 SQL 路由字段诊断报告。
---

# DongDAL Route SQL Scanner

## 边界

本 Skill 是一张独立薄能力卡背后的执行规则，只负责扫描和报告：

- 通过 `DongDalMCP` 读取已发布数据源的 scanner 配置。
- 将配置保存到本地临时文件。
- 调用插件随 skills 同步的 `dongdal/tools/opencode-sqlscan` wrapper 和 `route-sql-scanner-0.1.0.jar`。
- 输出 `.xlsx` 报告路径和失败项摘要。

不做：

- 不创建、发布、编辑、删除 DongDAL 数据源或序列。
- 不把 `appDsId` 直接传给 scanner；scanner 只接收已经落盘的规则文件。
- 不默认修改业务代码；若用户要求修复 SQL，再切换/协同 `DongDAL` 主 Skill。

## 输入

用户可提供任意一种配置定位信息：

- 管控端详情 URL。
- `appDsId + env`。
- `appName + appDsName + env`。

扫描项目默认使用当前工作区根目录。用户显式指定模块/目录时，以用户指定路径为准，但必须是本地绝对路径或可解析到本地绝对路径。

## 执行流程

1. 建立 todo：
   - 确认目标项目目录。
   - 解析发布态 DongDAL 配置。
   - 拉取或生成 scanner 配置。
   - 执行 route-sql-scanner。
   - 输出报告路径和失败摘要。
2. 调用 `DongDalMCP` 前先读取工具 Schema，并按 [../dongdal/references/mcp-tools-guide.md](../dongdal/references/mcp-tools-guide.md) 组装参数。
3. 优先调用 `resolveAppDataSource`，确保目标唯一且 `status=published`。
4. 优先调用 `getRouteSqlScannerConfig`；若 MCP 不提供该工具，则调用 `getAppDataSourceSummary(includeScannerConfig=true)` 并取 `scannerConfig`。
5. 将 scanner 配置写入本地临时文件，推荐：
   - `${projectPath}/.dongdal/route-sql-scanner-config.json`
6. 调用 wrapper：

```bash
node ~/.config/opencode/skills/dongdal/tools/opencode-sqlscan/src/scan-route-sql.mjs \
  --projectPath /path/to/java-project \
  --configPath /path/to/route-sql-scanner-config.json
```

用户指定报告位置时追加：

```bash
--outputPath /path/to/report.xlsx
```

默认报告输出到：

```text
${projectPath}/route-sql-diagnostics.xlsx
```

## 配置格式

MCP 返回或 Agent 生成的 JSON 应符合 scanner 支持的轻量规则：

```json
{
  "dialect": "mysql",
  "defaultSeverity": "ERROR",
  "tables": [
    {
      "name": "orders",
      "requiredColumns": ["tenant_id", "order_id"],
      "operations": ["SELECT", "UPDATE", "DELETE"]
    }
  ]
}
```

若 MCP 返回 DongDAL 主 Skill 里的 `routeRules.tables` 结构，也可直接保存；wrapper 不改写业务规则，兼容性交给 scanner 处理。需要人工转换时，按 [../dongdal/references/route-metadata.md](../dongdal/references/route-metadata.md) 的 `scanner 配置映射` 执行。

## 输出

扫描完成后向用户输出：

- 数据源：`appDsId/appName/appDsName/env/version/status`
- 扫描项目：本地路径
- 配置文件：本地路径
- 报告文件：`.xlsx` 绝对路径
- 摘要：报告只包含未通过分库分表字段完整性校验的 SQL；如 wrapper 输出了 stdout/stderr，摘取核心失败项或异常。

若扫描器异常退出：

- 展示 stderr 中的核心错误。
- 保留 `configPath` 和命令，方便复现。
- 不把执行失败包装成“扫描通过”。
