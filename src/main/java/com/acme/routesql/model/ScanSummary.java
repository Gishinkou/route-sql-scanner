package com.acme.routesql.model;

public record ScanSummary(
    int filesScanned,
    int sqlCount,
    int diagnosticCount
) {}
