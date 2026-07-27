package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.AtFormatter;
import com.acme.routesql.util.OriginPaths;
import com.acme.routesql.util.Strings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CompactInventoryReporter implements Reporter {
  public static final int SCHEMA_VERSION = 2;

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final Path projectRoot;

  public CompactInventoryReporter() {
    this(null);
  }

  public CompactInventoryReporter(Path projectRoot) {
    this.projectRoot = projectRoot;
  }

  @Override
  public String render(ScanReport report) throws Exception {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("v", SCHEMA_VERSION);
    if (report.project() != null && !Strings.isBlank(report.project())) {
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
      Map<String, Object> origin = origin(so.origin());
      if (origin != null) {
        row.put("origin", origin);
      }
      sqls.add(row);
    }
    out.put("sqls", sqls);
    return mapper.writeValueAsString(out);
  }

  private Map<String, Object> origin(SqlOrigin origin) {
    if (origin == null || origin.kind() == null) {
      return null;
    }
    String sourceType = sourceType(origin.kind());
    if (sourceType == null) {
      return null;
    }
    String sourcePath = OriginPaths.sourcePath(projectRoot, origin.file());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sourceType", sourceType);
    switch (origin.kind()) {
      case MYBATIS_XML:
        putIfPresent(out, "namespace", origin.namespace());
        putIfPresent(out, "statementId", origin.statementId());
        putIfPresent(out, "resourcePath", OriginPaths.resourcePath(sourcePath));
        putIfPresent(out, "sourcePath", sourcePath);
        break;
      case MYBATIS_ANNOTATION:
        putIfPresent(out, "namespace", origin.namespace());
        putIfPresent(out, "statementId", origin.statementId());
        putIfPresent(out, "sourcePath", sourcePath);
        break;
      case JAVA_JDBC:
        putIfPresent(out, "enclosingClass", origin.className());
        putIfPresent(out, "sourcePath", sourcePath);
        break;
      default:
        // unreachable: sourceType(...) already filtered unknown kinds
        break;
    }
    return out;
  }

  private static String sourceType(SourceKind kind) {
    switch (kind) {
      case MYBATIS_XML:
        return "mybatis_xml";
      case MYBATIS_ANNOTATION:
        return "mybatis_annotation";
      case JAVA_JDBC:
        return "java_literal_sql";
      default:
        return null;
    }
  }

  private static void putIfPresent(Map<String, Object> map, String key, String value) {
    if (value != null && !Strings.isBlank(value)) {
      map.put(key, value);
    }
  }
}
