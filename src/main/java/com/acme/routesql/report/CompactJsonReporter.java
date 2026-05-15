package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CompactJsonReporter implements Reporter {
  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  @Override
  public String render(ScanReport report) throws Exception {
    List<Map<String, Object>> diagnostics = CompactDiagnosticRows.from(report).stream()
        .map(this::compactDiagnostic)
        .toList();
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("diagnostics", diagnostics);
    return mapper.writeValueAsString(output) + System.lineSeparator();
  }

  private Map<String, Object> compactDiagnostic(CompactDiagnosticRow diagnostic) {
    Map<String, Object> row = new LinkedHashMap<>();
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("sourceKey", diagnostic.sourceKey());
    identity.put("logicalName", diagnostic.logicalName());
    row.put("identity", identity);
    row.put("normalizedSql", diagnostic.normalizedSql());
    row.put("severity", diagnostic.severity());
    row.put("message", diagnostic.message());
    row.put("tableName", diagnostic.tableName());
    row.put("expectedRouteFields", diagnostic.expectedRouteFields());
    row.put("columns", diagnostic.columns());
    return row;
  }
}
