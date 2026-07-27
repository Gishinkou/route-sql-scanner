package com.acme.routesql.model;

import java.util.List;
import java.util.Objects;

public final class ScanReport {
  private final String project;
  private final List<SqlObject> sqlObjects;

  public ScanReport(String project, List<SqlObject> sqlObjects) {
    this.project = project;
    this.sqlObjects = sqlObjects;
  }

  public String project() {
    return project;
  }

  public List<SqlObject> sqlObjects() {
    return sqlObjects;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ScanReport)) {
      return false;
    }
    ScanReport that = (ScanReport) o;
    return Objects.equals(project, that.project) && Objects.equals(sqlObjects, that.sqlObjects);
  }

  @Override
  public int hashCode() {
    return Objects.hash(project, sqlObjects);
  }

  @Override
  public String toString() {
    return "ScanReport[project=" + project + ", sqlObjects=" + sqlObjects + "]";
  }
}
