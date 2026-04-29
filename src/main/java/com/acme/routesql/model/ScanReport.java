package com.acme.routesql.model;

import java.util.List;

public record ScanReport(
    String version,
    String dialect,
    ScanSummary summary,
    List<SqlObject> sqlObjects,
    List<Diagnostic> diagnostics
) {}
