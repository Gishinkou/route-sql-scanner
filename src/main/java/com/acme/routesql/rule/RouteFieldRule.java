package com.acme.routesql.rule;

import com.acme.routesql.config.RouteRuleConfig;
import com.acme.routesql.model.Diagnostic;
import com.acme.routesql.model.SqlObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RouteFieldRule implements SqlRule {
  private static final String ID = "ROUTE-MISSING-001";
  private final RouteRuleConfig config;

  public RouteFieldRule(RouteRuleConfig config) {
    this.config = config == null ? new RouteRuleConfig() : config;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<Diagnostic> apply(List<SqlObject> sqlObjects) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    if (config.getTables() == null || config.getTables().isEmpty()) {
      return diagnostics;
    }
    for (SqlObject sql : sqlObjects) {
      String operation = sql.parse().statementType();
      for (Map.Entry<String, RouteRuleConfig.TableRule> entry : config.getTables().entrySet()) {
        String table = entry.getKey().toLowerCase(Locale.ROOT);
        RouteRuleConfig.TableRule tableRule = entry.getValue();
        if (!referencesTable(sql, table) || !operationEnabled(tableRule, operation)) {
          continue;
        }
        List<String> missingFields = missingRouteFields(sql, tableRule, operation);
        if (!missingFields.isEmpty()) {
          diagnostics.add(new Diagnostic(
              ID,
              config.getDefaultSeverity(),
              message(table, tableRule, missingFields),
              sql.identity().stableId(),
              sql.origin(),
              table,
              tableRule.getRouteFields(),
              snippet(sql.normalizedSql())
          ));
        }
      }
    }
    return diagnostics;
  }

  private boolean referencesTable(SqlObject sql, String table) {
    if (sql.parse().tables().stream().anyMatch(t -> t.equalsIgnoreCase(table))) {
      return true;
    }
    return Pattern.compile("(?i)\\b" + Pattern.quote(table) + "\\b").matcher(sql.normalizedSql()).find();
  }

  private boolean operationEnabled(RouteRuleConfig.TableRule rule, String operation) {
    if (rule.getOperations() == null || rule.getOperations().isEmpty()) {
      return true;
    }
    return rule.getOperations().stream().anyMatch(op -> op.equalsIgnoreCase(operation));
  }

  private List<String> missingRouteFields(SqlObject sql, RouteRuleConfig.TableRule tableRule, String operation) {
    List<String> routeFields = tableRule.getRouteFields();
    if (routeFields == null || routeFields.isEmpty()) {
      return List.of();
    }
    String searchable = "INSERT".equalsIgnoreCase(operation)
        ? insertColumnSegment(sql.normalizedSql())
        : whereSegment(sql.normalizedSql());
    List<String> missing = new ArrayList<>();
    for (String routeField : routeFields) {
      Pattern fieldPattern = Pattern.compile("(?i)(?:\\b|`|\\.)" + Pattern.quote(routeField) + "(?:\\b|`)");
      Matcher matcher = fieldPattern.matcher(searchable);
      boolean found = matcher.find();
      if (!tableRule.isRequireAllRouteFields() && found) {
        return List.of();
      }
      if (tableRule.isRequireAllRouteFields() && !found) {
        missing.add(routeField);
      }
    }
    return tableRule.isRequireAllRouteFields() ? missing : routeFields;
  }

  private String whereSegment(String sql) {
    Matcher matcher = Pattern.compile("(?i)\\bwhere\\b").matcher(sql);
    return matcher.find() ? sql.substring(matcher.end()) : "";
  }

  private String insertColumnSegment(String sql) {
    Matcher matcher = Pattern.compile("(?i)\\binsert\\s+into\\s+[`\\w.]+\\s*\\(([^)]*)\\)").matcher(sql);
    return matcher.find() ? matcher.group(1) : sql;
  }

  private String snippet(String sql) {
    if (sql.length() <= 220) {
      return sql;
    }
    return sql.substring(0, 217) + "...";
  }

  private String message(String table, RouteRuleConfig.TableRule tableRule, List<String> missingFields) {
    if (tableRule.isRequireAllRouteFields()) {
      return "SQL references table `" + table + "` but WHERE/INSERT columns miss required route fields: "
          + String.join(", ", missingFields);
    }
    return "SQL references table `" + table + "` but does not constrain any route field: "
        + String.join(", ", tableRule.getRouteFields());
  }
}
