package com.acme.routesql.model;

import java.util.Objects;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public final class SqlObject {
  private final SqlOrigin origin;
  private final String rawSql;
  private final boolean dynamic;
  private final Map<String, Object> template;

  public SqlObject(SqlOrigin origin, String rawSql, boolean dynamic) {
    this(origin, rawSql, dynamic, null);
  }

  private SqlObject(SqlOrigin origin, String rawSql, boolean dynamic, Map<String, Object> template) {
    this.origin = origin;
    this.rawSql = rawSql;
    this.dynamic = dynamic;
    this.template = template == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(template));
  }

  public SqlOrigin origin() {
    return origin;
  }

  public String rawSql() {
    return rawSql;
  }

  public boolean dynamic() {
    return dynamic;
  }

  public Map<String, Object> template() { return template; }

  public SqlObject withTemplate(Map<String, Object> template) {
    return new SqlObject(origin, rawSql, dynamic, template);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SqlObject)) {
      return false;
    }
    SqlObject that = (SqlObject) o;
    return dynamic == that.dynamic
        && Objects.equals(origin, that.origin)
        && Objects.equals(rawSql, that.rawSql);
  }

  @Override
  public int hashCode() {
    return Objects.hash(origin, rawSql, dynamic);
  }

  @Override
  public String toString() {
    return "SqlObject[origin=" + origin + ", rawSql=" + rawSql + ", dynamic=" + dynamic + "]";
  }
}
