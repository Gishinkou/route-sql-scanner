package com.acme.routesql.report;

import com.acme.routesql.model.Diagnostic;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SqlObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CompactDiagnosticRows {
  private CompactDiagnosticRows() {}

  public static List<CompactDiagnosticRow> from(ScanReport report) {
    Map<String, SqlObject> sqlByStableId = report.sqlObjects().stream()
        .collect(Collectors.toMap(
            sql -> sql.identity().stableId(),
            Function.identity(),
            (first, ignored) -> first,
            LinkedHashMap::new
        ));

    return report.diagnostics().stream()
        .map(diagnostic -> row(diagnostic, sqlByStableId.get(diagnostic.sqlStableId())))
        .toList();
  }

  private static CompactDiagnosticRow row(Diagnostic diagnostic, SqlObject sql) {
    return new CompactDiagnosticRow(
        sql == null ? null : sql.identity().sourceKey(),
        sql == null ? null : sql.identity().logicalName(),
        sql == null ? null : sql.normalizedSql(),
        diagnostic.severity(),
        diagnostic.message(),
        diagnostic.tableName(),
        diagnostic.expectedRouteFields(),
        sql == null ? List.of() : sql.parse().columns()
    );
  }
}
