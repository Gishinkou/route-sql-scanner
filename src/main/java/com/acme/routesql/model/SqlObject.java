package com.acme.routesql.model;

import java.util.Objects;

public final class SqlObject {
  private final SqlOrigin origin;
  private final String rawSql;
  private final boolean dynamic;

  public SqlObject(SqlOrigin origin, String rawSql, boolean dynamic) {
    this.origin = origin;
    this.rawSql = rawSql;
    this.dynamic = dynamic;
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
