package com.acme.routesql.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

public record SqlParseResult(
    boolean parsed,
    String dialect,
    String statementType,
    List<String> tables,
    List<String> columns,
    @JsonIgnore Object ast,
    String parseError
) {
  public static SqlParseResult unparsed(String dialect, String error) {
    return new SqlParseResult(false, dialect, "UNKNOWN", List.of(), List.of(), null, error);
  }
}
