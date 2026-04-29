package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonlReporter implements Reporter {
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String render(ScanReport report) throws Exception {
    StringBuilder builder = new StringBuilder();
    for (var sql : report.sqlObjects()) {
      builder.append(mapper.writeValueAsString(Map.of("type", "sql", "sql", sql))).append('\n');
    }
    for (var diagnostic : report.diagnostics()) {
      builder
          .append(mapper.writeValueAsString(Map.of("type", "diagnostic", "diagnostic", diagnostic)))
          .append('\n');
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("type", "summary");
    summary.put("version", report.version());
    summary.put("dialect", report.dialect());
    summary.put("summary", report.summary());
    builder.append(mapper.writeValueAsString(summary)).append('\n');
    return builder.toString();
  }
}
