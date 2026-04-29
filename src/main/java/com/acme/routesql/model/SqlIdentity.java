package com.acme.routesql.model;

public record SqlIdentity(
    String stableId,
    String contentHash,
    String sourceKey,
    String logicalName
) {}
