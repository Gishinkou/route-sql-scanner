package com.acme.routesql.normalize;

public class SqlNormalizer {
  public String normalize(String sql) {
    if (sql == null) {
      return "";
    }
    return sql
        .replaceAll("/\\*.*?\\*/", " ")
        .replaceAll("--[^\\r\\n]*", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  public String normalizeMyBatisParameters(String sql) {
    return sql
        .replaceAll("#\\{[^}]+}", "?")
        .replaceAll("\\$\\{[^}]+}", "__DYNAMIC__");
  }
}
