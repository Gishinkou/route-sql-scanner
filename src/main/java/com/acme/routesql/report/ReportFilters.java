package com.acme.routesql.report;

import com.acme.routesql.model.Diagnostic;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.ScanSummary;
import com.acme.routesql.model.SqlObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReportFilters {
  private ReportFilters() {}

  public static ScanReport failedSqlOnly(ScanReport report) {
    Set<String> failedSqlIds = new HashSet<>();
    for (Diagnostic diagnostic : report.diagnostics()) {
      if (diagnostic.sqlStableId() != null) {
        failedSqlIds.add(diagnostic.sqlStableId());
      }
    }

    List<SqlObject> failedSqlObjects = report.sqlObjects().stream()
        .filter(sql -> failedSqlIds.contains(sql.identity().stableId()))
        .toList();
    List<Diagnostic> failedDiagnostics = report.diagnostics().stream()
        .filter(diagnostic -> failedSqlIds.contains(diagnostic.sqlStableId()))
        .toList();

    ScanSummary summary = new ScanSummary(
        report.summary().filesScanned(),
        failedSqlObjects.size(),
        failedDiagnostics.size()
    );
    return new ScanReport(
        report.version(),
        report.dialect(),
        summary,
        failedSqlObjects,
        failedDiagnostics
    );
  }
}
