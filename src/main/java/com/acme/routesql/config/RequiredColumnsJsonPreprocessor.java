package com.acme.routesql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class RequiredColumnsJsonPreprocessor implements ConfigPreprocessor {
  private static final List<String> TABLE_ARRAY_FIELDS = List.of("tables", "routeTables", "rules");
  private static final List<String> TABLE_NAME_FIELDS = List.of("name", "table", "tableName");
  private static final List<String> COLUMN_FIELDS = List.of("requiredColumns", "columns", "routeColumns", "routeFields");

  @Override
  public boolean supports(JsonNode input) {
    if (input == null || !input.isObject() || input.has("routeRules")) {
      return false;
    }
    return findArray(input) != null;
  }

  @Override
  public JsonNode preprocess(JsonNode input, ObjectMapper mapper) {
    ObjectNode root = mapper.createObjectNode();
    root.put("dialect", text(input, "dialect", "mysql"));

    ObjectNode routeRules = root.putObject("routeRules");
    routeRules.put("defaultSeverity", text(input, "defaultSeverity", "ERROR"));
    ObjectNode tables = routeRules.putObject("tables");

    ArrayNode tableArray = findArray(input);
    if (tableArray != null) {
      for (JsonNode tableSpec : tableArray) {
        addTable(tables, tableSpec);
      }
    }
    return root;
  }

  private void addTable(ObjectNode tables, JsonNode tableSpec) {
    if (!tableSpec.isObject()) {
      return;
    }
    String tableName = firstText(tableSpec, TABLE_NAME_FIELDS);
    JsonNode columns = firstNode(tableSpec, COLUMN_FIELDS);
    if (tableName == null || tableName.isBlank() || columns == null || !columns.isArray()) {
      return;
    }

    ObjectNode tableRule = tables.putObject(tableName);
    ArrayNode routeFields = tableRule.putArray("routeFields");
    columns.forEach(column -> {
      if (column.isTextual() && !column.asText().isBlank()) {
        routeFields.add(column.asText());
      }
    });

    JsonNode operations = tableSpec.get("operations");
    if (operations != null && operations.isArray()) {
      ArrayNode operationArray = tableRule.putArray("operations");
      operations.forEach(operation -> {
        if (operation.isTextual() && !operation.asText().isBlank()) {
          operationArray.add(operation.asText());
        }
      });
    } else {
      tableRule.putArray("operations")
          .add("SELECT")
          .add("UPDATE")
          .add("DELETE");
    }

    tableRule.put("requireAllRouteFields", bool(tableSpec, "requireAll", true));
  }

  private ArrayNode findArray(JsonNode input) {
    for (String field : TABLE_ARRAY_FIELDS) {
      JsonNode node = input.get(field);
      if (node != null && node.isArray()) {
        return (ArrayNode) node;
      }
    }
    return null;
  }

  private String firstText(JsonNode node, List<String> fields) {
    JsonNode value = firstNode(node, fields);
    return value == null || !value.isTextual() ? null : value.asText();
  }

  private JsonNode firstNode(JsonNode node, List<String> fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String text(JsonNode node, String field, String defaultValue) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank()
        ? value.asText()
        : defaultValue;
  }

  private boolean bool(JsonNode node, String field, boolean defaultValue) {
    JsonNode value = node.get(field);
    return value != null && value.isBoolean() ? value.asBoolean() : defaultValue;
  }
}
