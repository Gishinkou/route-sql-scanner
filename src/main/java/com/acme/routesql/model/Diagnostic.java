package com.acme.routesql.model;

import java.util.List;

public record Diagnostic(
    String id,
    String severity,
    String message,
    String sqlStableId,
    SqlOrigin origin,
    String tableName,
    List<String> expectedRouteFields,
    String snippet
) {}
