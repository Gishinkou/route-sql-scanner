package com.acme.routesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.report.CompactInventoryReporter;
import com.acme.routesql.util.AtFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScanEngineTest {
  @Test
  void scansFixturesIntoCompactInventory() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = new ScannerConfig();
    config.setProject("acme");

    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());

    assertFalse(report.sqlObjects().isEmpty());
    assertTrue(report.sqlObjects().stream()
        .anyMatch(sql -> sql.origin().kind().name().equals("JAVA_JDBC")));
    assertTrue(report.sqlObjects().stream()
        .anyMatch(sql -> sql.origin().kind().name().equals("MYBATIS_XML")));
    assertTrue(report.sqlObjects().stream()
        .anyMatch(sql -> sql.origin().kind().name().equals("MYBATIS_ANNOTATION") && sql.dynamic()));
  }

  @Test
  void rendersCompactJsonV2() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = new ScannerConfig();
    config.setProject("acme");

    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());
    String rendered = new CompactInventoryReporter().render(report);
    JsonNode root = new ObjectMapper().readTree(rendered);

    assertEquals(2, root.get("v").asInt());
    assertEquals("acme", root.get("project").asText());
    assertTrue(root.has("scannedAt"));
    JsonNode sqls = root.get("sqls");
    assertTrue(sqls.isArray() && sqls.size() == report.sqlObjects().size());

    JsonNode first = sqls.get(0);
    assertTrue(first.has("at"));
    assertTrue(first.has("sql"));
    String at = first.get("at").asText();
    assertTrue(at.contains("(") && at.endsWith(")"));
    assertFalse(first.has("normalizedSql"));
    assertFalse(first.has("identity"));
    assertTrue(first.has("origin"));
    assertTrue(first.get("origin").has("sourceType"));
  }

  @Test
  void emitsEnrichedOriginPerSourceType() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = new ScannerConfig();
    config.setProject("acme");

    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());
    String rendered = new CompactInventoryReporter(fixtureDir).render(report);
    JsonNode sqls = new ObjectMapper().readTree(rendered).get("sqls");

    JsonNode xml = originOfType(sqls, "mybatis_xml");
    assertTrue(xml.has("namespace"), "xml origin should carry namespace");
    assertTrue(xml.has("statementId"), "xml origin should carry statementId");

    JsonNode annotation = originOfType(sqls, "mybatis_annotation");
    assertTrue(annotation.has("namespace"), "annotation origin should carry namespace");
    assertTrue(annotation.has("statementId"), "annotation origin should carry statementId");
    assertFalse(annotation.has("resourcePath"), "annotation origin should not carry resourcePath");

    JsonNode jdbc = originOfType(sqls, "java_literal_sql");
    assertTrue(jdbc.has("enclosingClass"), "jdbc origin should carry enclosingClass");
    assertFalse(jdbc.has("namespace"), "jdbc origin should not carry namespace");
  }

  @Test
  void splitsSelectKeyFromInsertStatement() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = new ScannerConfig();
    config.setProject("acme");

    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());

    List<String> inserts = report.sqlObjects().stream()
        .filter(sql -> "insertOrder".equals(sql.origin().statementId()))
        .map(sql -> sql.rawSql())
        .toList();
    assertEquals(1, inserts.size());
    String insert = inserts.get(0);
    assertTrue(insert.toUpperCase().contains("INSERT INTO ORDERS"),
        "insert body should remain: " + insert);
    assertFalse(insert.toUpperCase().contains("LAST_INSERT_ID"),
        "selectKey must not be concatenated into the insert: " + insert);

    List<String> selectKeys = report.sqlObjects().stream()
        .filter(sql -> "insertOrder!selectKey".equals(sql.origin().statementId()))
        .map(sql -> sql.rawSql())
        .toList();
    assertEquals(1, selectKeys.size());
    String selectKey = selectKeys.get(0);
    assertTrue(selectKey.toUpperCase().contains("SELECT LAST_INSERT_ID()"),
        "selectKey should be emitted as its own SQL: " + selectKey);
    assertFalse(selectKey.toUpperCase().contains("INSERT INTO"),
        "selectKey object should not contain the insert body: " + selectKey);
  }

  private static JsonNode originOfType(JsonNode sqls, String sourceType) {
    for (JsonNode sql : sqls) {
      JsonNode origin = sql.get("origin");
      if (origin != null && sourceType.equals(origin.path("sourceType").asText())) {
        return origin;
      }
    }
    throw new AssertionError("no origin with sourceType=" + sourceType);
  }

  @Test
  void atFormatterUsesIdeaCopyReferenceStyle() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScanReport report = new ScanEngine(new ScannerConfig()).scan(List.of(fixtureDir), List.of(), List.of());

    boolean xmlAt = report.sqlObjects().stream()
        .filter(sql -> sql.origin().kind().name().equals("MYBATIS_XML"))
        .map(sql -> AtFormatter.format(sql.origin()))
        .anyMatch(at -> at.contains(".xml:") || at.matches(".+\\.[^.]+\\(.+\\.xml:\\d+\\)"));
    assertTrue(xmlAt, "expected mybatis xml `at` to contain .xml file reference");

    boolean annotationAt = report.sqlObjects().stream()
        .filter(sql -> sql.origin().kind().name().equals("MYBATIS_ANNOTATION"))
        .map(sql -> AtFormatter.format(sql.origin()))
        .anyMatch(at -> at.contains("#") && at.contains(".java:"));
    assertTrue(annotationAt, "expected mybatis annotation `at` to use ClassName#method(file.java:line)");

    boolean jdbcAt = report.sqlObjects().stream()
        .filter(sql -> sql.origin().kind().name().equals("JAVA_JDBC"))
        .map(sql -> AtFormatter.format(sql.origin()))
        .anyMatch(at -> at.contains("#") && at.contains(".java:"));
    assertTrue(jdbcAt, "expected jdbc `at` to use ClassName#method(file.java:line)");
  }
}
