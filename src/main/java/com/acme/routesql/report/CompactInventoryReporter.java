package com.acme.routesql.report;

import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.AtFormatter;
import com.acme.routesql.util.OriginPaths;
import com.acme.routesql.util.SqlKey;
import com.acme.routesql.util.Strings;
import com.acme.routesql.extract.mybatis.MyBatisTemplateRenderer;
import com.acme.routesql.extract.mybatis.MyBatisResourceContexts;
import com.acme.routesql.extract.mybatis.ParameterScenarios;
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
  private final Map<String, Object> scenarios;
  private final boolean factoryScoped;

  public CompactInventoryReporter() {
    this(null);
  }

  public CompactInventoryReporter(Path projectRoot) {
    this(projectRoot, java.util.Collections.emptyMap());
  }

  public CompactInventoryReporter(Path projectRoot, Map<String, Object> scenarios) {
    this(projectRoot, scenarios, false);
  }

  public CompactInventoryReporter(Path projectRoot, Map<String, Object> scenarios, boolean factoryScoped) {
    this.projectRoot = projectRoot;
    this.scenarios = scenarios;
    this.factoryScoped = factoryScoped;
  }

  @Override
  public String render(ScanReport report) throws Exception {
    ParameterScenarios.validateFor(scenarios, report);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("v", SCHEMA_VERSION);
    if (factoryScoped) out.put("factoryScopedSql", MyBatisResourceContexts.VERSION);
    if (!scenarios.isEmpty() || report.sqlObjects().stream().anyMatch(this::hasScopedGap)) {
      out.put("requiredCapabilities", java.util.Collections.singletonList(ParameterScenarios.CAPABILITY));
    }
    if (report.project() != null && !Strings.isBlank(report.project())) {
      out.put("project", report.project());
    }
    out.put("scannedAt", LocalDateTime.now().toString());
    List<Map<String, Object>> sqls = new ArrayList<>();
    for (SqlObject so : report.sqlObjects()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sqlKey", SqlKey.of(projectRoot, so));
      row.put("sourceStatementId", SqlKey.sourceStatementId(projectRoot, so));
      row.put("at", AtFormatter.format(so.origin()));
      row.put("sql", so.rawSql());
      if (so.template() != null) row.put("template", so.template());
      addScenarios(row, so);
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

  @SuppressWarnings("unchecked")
  private void addScenarios(Map<String, Object> row, SqlObject sql) {
    Object byStatement = scenarios.get("statements");
    Object witnesses = null;
    if (byStatement instanceof Map && sql.origin() != null) {
      String key = sql.origin().namespace() + "." + sql.origin().statementId();
      witnesses = ((Map<?, ?>) byStatement).get(key);
    }
    if (!(witnesses instanceof List)) {
      if (hasScopedGap(sql)) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("id", "mapper-resource-context-unresolved");
        gap.put("status", "UNRENDERABLE");
        gap.put("error", sql.template().get("error"));
        row.put("renderedScenarios", java.util.Collections.singletonList(gap));
        row.put("scenarioCoverage", "UNRESOLVED_RESOURCE_CONTEXT");
      }
      return;
    }
    List<Map<String, Object>> rendered = new ArrayList<>();
    for (Object value : (List<?>) witnesses) {
      if (!(value instanceof Map)) throw new IllegalArgumentException("Parameter scenario must be an object");
      Map<String, Object> witness = (Map<String, Object>) value;
      Map<String, Object> scenario = MyBatisTemplateRenderer.render(sql.template(),
          (Map<String, Object>) witness.get("parameters"), (Map<String, String>) scenarios.get("helperClassHashes"));
      scenario.put("id", witness.get("id"));
      if (hasScopedGap(sql)) scenario.put("error", sql.template().get("error"));
      rendered.add(scenario);
    }
    row.put("renderedScenarios", rendered);
    row.put("scenarioCoverage", "SAMPLED");
  }

  private boolean hasScopedGap(SqlObject sql) {
    return sql.template() != null && sql.template().containsKey("resourceContextIds")
        && !"READY".equals(sql.template().get("status"));
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
        out.put("framework", "mybatis");
        putIfPresent(out, "resourcePath", OriginPaths.resourcePath(sourcePath));
        putIfPresent(out, "sourcePath", sourcePath);
        break;
      case IBATIS_XML:
        putIfPresent(out, "namespace", origin.namespace());
        putIfPresent(out, "statementId", origin.statementId());
        out.put("framework", "ibatis");
        out.put("frameworkVersion", "2");
        out.put("normalizerVersion", "1");
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
        if (origin.jdbcCallsite() != null) out.put("jdbcCallsite", origin.jdbcCallsite());
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
      case IBATIS_XML:
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
