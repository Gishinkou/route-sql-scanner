package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.util.AtFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CompactInventoryReporter implements Reporter {
  public static final int SCHEMA_VERSION = 2;

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  @Override
  public String render(ScanReport report) throws Exception {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("v", SCHEMA_VERSION);
    if (report.project() != null && !report.project().isBlank()) {
      out.put("project", report.project());
    }
    out.put("scannedAt", LocalDateTime.now().toString());
    List<Map<String, Object>> sqls = new ArrayList<>();
    for (SqlObject so : report.sqlObjects()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("at", AtFormatter.format(so.origin()));
      row.put("sql", so.rawSql());
      if (so.dynamic()) {
        row.put("dynamic", true);
      }
      sqls.add(row);
    }
    out.put("sqls", sqls);
    return mapper.writeValueAsString(out);
  }
}
