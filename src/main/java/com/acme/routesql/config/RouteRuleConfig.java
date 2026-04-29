package com.acme.routesql.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RouteRuleConfig {
  private String defaultSeverity = "ERROR";
  private Map<String, TableRule> tables = new LinkedHashMap<>();

  public String getDefaultSeverity() {
    return defaultSeverity;
  }

  public void setDefaultSeverity(String defaultSeverity) {
    this.defaultSeverity = defaultSeverity;
  }

  public Map<String, TableRule> getTables() {
    return tables;
  }

  public void setTables(Map<String, TableRule> tables) {
    this.tables = tables;
  }

  public static class TableRule {
    private List<String> routeFields = new ArrayList<>();
    private List<String> operations = List.of("SELECT", "UPDATE", "DELETE", "INSERT");

    public List<String> getRouteFields() {
      return routeFields;
    }

    public void setRouteFields(List<String> routeFields) {
      this.routeFields = routeFields;
    }

    public List<String> getOperations() {
      return operations;
    }

    public void setOperations(List<String> operations) {
      this.operations = operations;
    }
  }
}
