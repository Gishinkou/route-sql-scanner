package com.acme.routesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.report.JsonReporter;
import com.acme.routesql.report.JsonlReporter;
import com.acme.routesql.report.MarkdownReporter;
import com.acme.routesql.report.NormalizedSqlReporter;
import java.nio.file.Path;
import java.util.List;
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
