package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;

public class NormalizedSqlReporter implements Reporter {
  @Override
  public String render(ScanReport report) {
    StringBuilder builder = new StringBuilder();
    for (var sql : report.sqlObjects()) {
      builder.append(sql.normalizedSql()).append('\n');
    }
    return builder.toString();
  }
}
