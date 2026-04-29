package com.acme.routesql.util;

import com.acme.routesql.model.SqlIdentity;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.model.SqlParseResult;
import java.util.List;
import java.util.Map;

public final class SqlObjects {
  private SqlObjects() {}

  public static SqlObject create(
      String rawSql,
      String normalizedSql,
      SqlOrigin origin,
      boolean dynamic,
      List<String> tags,
      Map<String, Object> attributes
  ) {
    String sourceKey = origin.file() + ":" + origin.line() + ":" + origin.column();
    String logicalName = logicalName(origin);
    String contentHash = Hashing.sha256(normalizedSql);
    String stableId = Hashing.sha256(origin.kind() + "|" + logicalName + "|" + contentHash);
    SqlIdentity identity = new SqlIdentity(stableId, contentHash, sourceKey, logicalName);
    SqlParseResult parse = SqlParseResult.unparsed("mysql", "not parsed yet");
    return new SqlObject(identity, rawSql, normalizedSql, origin, parse, dynamic, tags, attributes);
  }

  private static String logicalName(SqlOrigin origin) {
    return switch (origin.kind()) {
      case MYBATIS_XML -> joinDot(origin.namespace(), origin.statementId());
      case JAVA_JDBC -> origin.className() + "#" + origin.methodName() + ":" + origin.line();
      default -> origin.file() + ":" + origin.line();
    };
  }

  private static String joinDot(String left, String right) {
    if (left == null || left.isBlank()) {
      return right;
    }
    return left + "." + right;
  }
}
