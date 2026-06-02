package com.acme.routesql.model;

import java.util.List;

public record ScanReport(
    String project,
    List<SqlObject> sqlObjects
) {}
