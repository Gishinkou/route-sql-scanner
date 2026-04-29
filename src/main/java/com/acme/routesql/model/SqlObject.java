package com.acme.routesql.model;

import java.util.List;
import java.util.Map;

public record SqlObject(
    SqlIdentity identity,
    String rawSql,
    String normalizedSql,
    SqlOrigin origin,
    SqlParseResult parse,
    boolean dynamic,
    List<String> tags,
    Map<String, Object> attributes
) {
  public SqlObject withParse(SqlParseResult result) {
    return new SqlObject(identity, rawSql, normalizedSql, origin, result, dynamic, tags, attributes);
  }
}
