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
    assertFalse(first.has("origin"));
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
