package com.acme.routesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.routesql.config.ReportConfig;
import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.report.CompactJsonReporter;
import com.acme.routesql.report.ExcelReporter;
import com.acme.routesql.report.JsonReporter;
import com.acme.routesql.report.JsonlReporter;
import com.acme.routesql.report.MarkdownReporter;
import com.acme.routesql.report.NormalizedSqlReporter;
import com.acme.routesql.report.ReportFilters;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ScanEngineTest {
  @Test
  void scansFixturesAndReportsRouteDiagnostics() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = ScannerConfig.load(fixtureDir.resolve("route-sql.yml"));

    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());

    assertEquals(10, report.summary().sqlCount());
    assertTrue(report.sqlObjects().stream()
        .anyMatch(sql -> "com.acme.OrderMapper.findOrders".equals(sql.identity().logicalName())));
    assertTrue(report.sqlObjects().stream().anyMatch(sql -> sql.origin().kind().name().equals("JAVA_JDBC")));
    assertTrue(report.sqlObjects().stream()
        .anyMatch(sql -> "fixtures.AnnotationOrderMapper.findDynamic".equals(sql.identity().logicalName())
            && sql.origin().kind().name().equals("MYBATIS_ANNOTATION")
            && sql.dynamic()));
    assertTrue(report.sqlObjects().stream().allMatch(sql -> sql.identity().stableId().length() == 64));
    assertTrue(report.sqlObjects().stream()
        .anyMatch(sql -> sql.parse().tables().contains("orders")));
    assertFalse(report.diagnostics().isEmpty());
    assertTrue(report.diagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.snippet().contains("WHERE id = ?")));
    assertEquals(3, report.summary().diagnosticCount());
  }

  @Test
  void rendersAllReportFormats() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = ScannerConfig.load(fixtureDir.resolve("route-sql.yml"));
    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());

    assertTrue(new JsonReporter().render(report).contains("\"diagnostics\""));
    assertTrue(new JsonlReporter().render(report).contains("\"type\":\"summary\""));
    assertTrue(new MarkdownReporter().render(report).contains("Route SQL Scan Report"));
    String normalizedSqlLines = new NormalizedSqlReporter().render(report);
    assertTrue(normalizedSqlLines.contains("SELECT id, tenant_id, order_id FROM orders WHERE tenant_id = ?"));
    assertEquals(report.summary().sqlCount(), normalizedSqlLines.lines().count());
  }

  @Test
  void rendersOnlySqlObjectsWithRuleDiagnostics() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = ScannerConfig.load(fixtureDir.resolve("route-sql.yml"));
    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());

    ScanReport failedOnly = ReportFilters.failedSqlOnly(report);

    assertEquals(3, failedOnly.summary().sqlCount());
    assertEquals(3, failedOnly.summary().diagnosticCount());
    assertTrue(failedOnly.sqlObjects().stream()
        .allMatch(sql -> failedOnly.diagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.sqlStableId().equals(sql.identity().stableId()))));

    String normalizedSqlLines = new NormalizedSqlReporter().render(failedOnly);
    assertEquals(failedOnly.summary().sqlCount(), normalizedSqlLines.lines().count());
    assertTrue(normalizedSqlLines.contains("WHERE id = ?"));
    assertFalse(normalizedSqlLines.contains("WHERE tenant_id = ?"));

    JsonNode json = new ObjectMapper().readTree(new JsonReporter().render(failedOnly));
    assertEquals(3, json.get("sqlObjects").size());
    assertEquals(3, json.get("summary").get("sqlCount").asInt());
    assertEquals(3, json.get("diagnostics").size());
  }

  @Test
  void rendersCompactJsonFromDiagnosticsPerspective() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = ScannerConfig.load(fixtureDir.resolve("route-sql.yml"));
    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());

    JsonNode json = new ObjectMapper().readTree(new CompactJsonReporter().render(report));
    JsonNode diagnostic = json.get("diagnostics").get(0);

    assertEquals(3, json.get("diagnostics").size());
    assertTrue(diagnostic.has("identity"));
    assertTrue(diagnostic.get("identity").has("sourceKey"));
    assertTrue(diagnostic.get("identity").has("logicalName"));
    assertTrue(diagnostic.has("normalizedSql"));
    assertTrue(diagnostic.has("severity"));
    assertTrue(diagnostic.has("message"));
    assertTrue(diagnostic.has("tableName"));
    assertTrue(diagnostic.has("expectedRouteFields"));
    assertTrue(diagnostic.has("columns"));
    assertFalse(diagnostic.has("rawSql"));
    assertFalse(diagnostic.has("origin"));
  }

  @Test
  void rendersExcelWithCompactJsonFields() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = ScannerConfig.load(fixtureDir.resolve("route-sql.yml"));
    ScanReport report = new ScanEngine(config).scan(List.of(fixtureDir), List.of(), List.of());

    byte[] workbook = new ExcelReporter().renderBytes(ReportFilters.failedSqlOnly(report));
    String sheetXml = worksheetXml(workbook);

    assertTrue(workbook.length > 0);
    assertTrue(sheetXml.contains("identity.sourceKey"));
    assertTrue(sheetXml.contains("identity.logicalName"));
    assertTrue(sheetXml.contains("normalizedSql"));
    assertTrue(sheetXml.contains("severity"));
    assertTrue(sheetXml.contains("message"));
    assertTrue(sheetXml.contains("tableName"));
    assertTrue(sheetXml.contains("expectedRouteFields"));
    assertTrue(sheetXml.contains("columns"));
    assertTrue(sheetXml.contains("UPDATE orders SET status = ? WHERE id = ?"));
    assertFalse(sheetXml.contains("rawSql"));
    assertFalse(sheetXml.contains("origin"));
  }

  private String worksheetXml(byte[] workbook) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
          return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }
    throw new AssertionError("sheet1.xml not found");
  }

  @Test
  void discoversReportConfigFromProjectDirectory() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();

    ReportConfig reportConfig = ReportConfig.discover(
        fixtureDir.resolve("route-sql.yml"),
        List.of(fixtureDir)
    );

    assertEquals("compact-json", reportConfig.effectiveFormat(null));
    assertEquals("normalized", reportConfig.effectiveFormat("normalized"));
  }

  @Test
  void acceptsRequiredColumnsJsonAndRequiresEveryRouteColumn() throws Exception {
    Path fixtureDir = Path.of("src/test/resources/fixtures").toAbsolutePath();
    ScannerConfig config = ScannerConfig.load(fixtureDir.resolve("required-columns.json"));

    ScanReport report = new ScanEngine(config)
        .scan(List.of(fixtureDir.resolve("AnnotationOrderMapper.java")), List.of(), List.of());

    assertEquals(3, report.summary().sqlCount());
    assertEquals(3, report.summary().diagnosticCount());
    assertTrue(report.diagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.origin().statementId().equals("findByTenant")
            && diagnostic.message().contains("order_id")));
    assertTrue(report.diagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.origin().statementId().equals("updateWithoutRoute")
            && diagnostic.message().contains("tenant_id")
            && diagnostic.message().contains("order_id")));
  }
}
