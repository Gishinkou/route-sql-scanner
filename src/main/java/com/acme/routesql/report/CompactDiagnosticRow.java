package com.acme.routesql.report;

import java.util.List;

public record CompactDiagnosticRow(
    String sourceKey,
    String logicalName,
    String normalizedSql,
    String severity,
    String message,
    String tableName,
    List<String> expectedRouteFields,
    List<String> columns
) {}
