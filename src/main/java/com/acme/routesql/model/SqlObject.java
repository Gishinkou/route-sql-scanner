package com.acme.routesql.model;

public record SqlObject(
    SqlOrigin origin,
    String rawSql,
    boolean dynamic
) {}
