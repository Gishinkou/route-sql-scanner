package com.acme.routesql.util;

import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;

public final class SqlObjects {
  private SqlObjects() {}

  public static SqlObject create(String rawSql, SqlOrigin origin, boolean dynamic) {
    return new SqlObject(origin, rawSql, dynamic);
  }
}
