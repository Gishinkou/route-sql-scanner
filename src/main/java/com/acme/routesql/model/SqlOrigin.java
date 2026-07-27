package com.acme.routesql.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.nio.file.Path;
import java.util.Objects;

public final class SqlOrigin {
  private final SourceKind kind;
  @JsonSerialize(using = ToStringSerializer.class)
  private final Path file;
  private final int line;
  private final int column;
  private final String namespace;
  private final String statementId;
  private final String statementType;
  private final String className;
  private final String methodName;

  public SqlOrigin(
      SourceKind kind,
      Path file,
      int line,
      int column,
      String namespace,
      String statementId,
      String statementType,
      String className,
      String methodName) {
    this.kind = kind;
    this.file = file;
    this.line = line;
    this.column = column;
    this.namespace = namespace;
    this.statementId = statementId;
    this.statementType = statementType;
    this.className = className;
    this.methodName = methodName;
  }

  public SourceKind kind() {
    return kind;
  }

  public Path file() {
    return file;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  public String namespace() {
    return namespace;
  }

  public String statementId() {
    return statementId;
  }

  public String statementType() {
    return statementType;
  }

  public String className() {
    return className;
  }

  public String methodName() {
    return methodName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SqlOrigin)) {
      return false;
    }
    SqlOrigin that = (SqlOrigin) o;
    return line == that.line
        && column == that.column
        && kind == that.kind
        && Objects.equals(file, that.file)
        && Objects.equals(namespace, that.namespace)
        && Objects.equals(statementId, that.statementId)
        && Objects.equals(statementType, that.statementType)
        && Objects.equals(className, that.className)
        && Objects.equals(methodName, that.methodName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        kind, file, line, column, namespace, statementId, statementType, className, methodName);
  }

  @Override
  public String toString() {
    return "SqlOrigin["
        + "kind=" + kind
        + ", file=" + file
        + ", line=" + line
        + ", column=" + column
        + ", namespace=" + namespace
        + ", statementId=" + statementId
        + ", statementType=" + statementType
        + ", className=" + className
        + ", methodName=" + methodName
        + "]";
  }
}
