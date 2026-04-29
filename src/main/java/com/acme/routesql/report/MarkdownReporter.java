package com.acme.routesql.report;

import com.acme.routesql.model.Diagnostic;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SqlObject;

public class MarkdownReporter implements Reporter {
  @Override
  public String render(ScanReport report) {
    StringBuilder builder = new StringBuilder();
    builder.append("# Route SQL Scan Report\n\n");
    builder.append("## Summary\n\n");
    builder.append("| Metric | Value |\n");
    builder.append("| --- | ---: |\n");
    builder.append("| Files scanned | ").append(report.summary().filesScanned()).append(" |\n");
    builder.append("| SQL count | ").append(report.summary().sqlCount()).append(" |\n");
    builder.append("| Diagnostics | ").append(report.summary().diagnosticCount()).append(" |\n\n");

    builder.append("## SQL Inventory\n\n");
    builder.append("| Stable ID | Origin | Statement | Tables | Dynamic |\n");
    builder.append("| --- | --- | --- | --- | --- |\n");
    for (SqlObject sql : report.sqlObjects()) {
      builder.append("| ")
          .append(shortId(sql.identity().stableId()))
          .append(" | ")
          .append(escape(sql.identity().logicalName()))
          .append(" | ")
          .append(sql.parse().statementType())
          .append(" | ")
          .append(escape(String.join(", ", sql.parse().tables())))
          .append(" | ")
          .append(sql.dynamic())
          .append(" |\n");
    }

    builder.append("\n## Diagnostics\n\n");
    builder.append("| Severity | Rule | Origin | Table | Message |\n");
    builder.append("| --- | --- | --- | --- | --- |\n");
    for (Diagnostic diagnostic : report.diagnostics()) {
      builder.append("| ")
          .append(diagnostic.severity())
          .append(" | ")
          .append(diagnostic.id())
          .append(" | ")
          .append(escape(origin(diagnostic)))
          .append(" | ")
          .append(escape(diagnostic.tableName()))
          .append(" | ")
          .append(escape(diagnostic.message()))
          .append(" |\n");
      builder.append("\n```sql\n").append(diagnostic.snippet()).append("\n```\n\n");
    }
    return builder.toString();
  }

  private String origin(Diagnostic diagnostic) {
    return diagnostic.origin().file() + ":" + diagnostic.origin().line();
  }

  private String shortId(String stableId) {
    return stableId.length() <= 12 ? stableId : stableId.substring(0, 12);
  }

  private String escape(String value) {
    return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
  }
}
