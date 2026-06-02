package com.acme.routesql.util;

import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlOrigin;
import java.nio.file.Path;

public final class AtFormatter {
  private AtFormatter() {}

  public static String format(SqlOrigin origin) {
    String fileName = fileName(origin.file());
    int line = origin.line();
    return switch (origin.kind()) {
      case MYBATIS_XML -> mybatisXml(origin, fileName, line);
      case MYBATIS_ANNOTATION, JAVA_JDBC -> javaSymbol(origin, fileName, line);
      default -> fileName + ":" + line;
    };
  }

  private static String mybatisXml(SqlOrigin origin, String fileName, int line) {
    String namespace = origin.namespace();
    String statementId = origin.statementId();
    String symbol;
    if (statementId == null || statementId.isBlank()) {
      symbol = namespace == null || namespace.isBlank() ? fileName : namespace;
    } else if (namespace == null || namespace.isBlank()) {
      symbol = statementId;
    } else {
      symbol = namespace + "." + statementId;
    }
    return symbol + "(" + fileName + ":" + line + ")";
  }

  private static String javaSymbol(SqlOrigin origin, String fileName, int line) {
    String className = origin.className();
    String methodName = origin.methodName();
    String symbol;
    if (className == null || className.isBlank()) {
      symbol = fileName.replaceFirst("\\.java$", "");
    } else if (methodName == null || methodName.isBlank()) {
      symbol = className;
    } else {
      symbol = className + "#" + methodName;
    }
    return symbol + "(" + fileName + ":" + line + ")";
  }

  private static String fileName(Path file) {
    if (file == null) {
      return "(unknown)";
    }
    Path name = file.getFileName();
    return name == null ? file.toString() : name.toString();
  }
}
