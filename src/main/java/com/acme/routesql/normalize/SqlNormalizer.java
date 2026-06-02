package com.acme.routesql.normalize;

public class SqlNormalizer {
  public String normalize(String sql) {
    if (sql == null) {
      return "";
    }
    String collapsed = sql.contains("/*?")
        ? sql.replaceAll("/\\*(?!\\?)[^*]*?\\*/", " ")
        : sql.replaceAll("/\\*.*?\\*/", " ");
    return collapsed
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
