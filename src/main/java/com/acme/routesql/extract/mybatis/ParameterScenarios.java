package com.acme.routesql.extract.mybatis;

import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assign explicit parameter inputs only to uniquely matched source definitions. */
public final class ParameterScenarios {
  public static final String CAPABILITY = "native-parameter-witnesses/v1";
  private static final long MAX_BYTES = 1024L * 1024L;
  private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("statements", "helperClassHashes"));
  private static final Set<String> WITNESS_FIELDS = new HashSet<>(Arrays.asList("id", "parameters"));

  private ParameterScenarios() {}

  @SuppressWarnings("unchecked")
  public static Map<String, Object> load(Path path) throws IOException {
    if (!Files.isRegularFile(path) || Files.size(path) == 0 || Files.size(path) > MAX_BYTES) {
      throw new IllegalArgumentException("Parameter scenarios must be a non-empty file of at most 1 MiB");
    }
    Object value = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .readValue(path.toFile(), Object.class);
    if (!(value instanceof Map)) throw new IllegalArgumentException("Parameter scenarios must be an object");
    Map<String, Object> scenarios = (Map<String, Object>) value;
    validateSchema(scenarios);
    return scenarios;
  }

  public static MatchResult match(Map<String, Object> scenarios, ScanReport report) {
    if (scenarios == null || scenarios.isEmpty()) {
      return new MatchResult(Collections.emptyMap(), Collections.emptyList());
    }
    validateSchema(scenarios);
    Map<?, ?> statements = (Map<?, ?>) scenarios.get("statements");
    Map<String, Integer> matches = new LinkedHashMap<>();
    for (SqlObject sql : report.sqlObjects()) {
      String key = statementKey(sql.origin());
      if (statements.containsKey(key)) matches.merge(key, 1, Integer::sum);
    }
    Map<String, List<?>> assigned = new LinkedHashMap<>();
    List<Map<String, Object>> gaps = new ArrayList<>();
    for (Map.Entry<?, ?> entry : statements.entrySet()) {
      String key = (String) entry.getKey();
      List<?> witnesses = (List<?>) entry.getValue();
      int count = matches.getOrDefault(key, 0);
      if (count == 1) {
        assigned.put(key, witnesses);
        continue;
      }
      for (Object witness : witnesses) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("statement", key);
        gap.put("scenarioId", ((Map<?, ?>) witness).get("id"));
        gap.put("reason", count == 0 ? "UNMATCHED_STATEMENT" : "AMBIGUOUS_STATEMENT");
        gap.put("matchedStatementCount", count);
        gaps.add(gap);
      }
    }
    return new MatchResult(assigned, gaps);
  }

  private static String statementKey(SqlOrigin origin) {
    return origin == null || origin.namespace() == null || origin.statementId() == null
        ? null : origin.namespace() + "." + origin.statementId();
  }

  public static final class MatchResult {
    private final Map<String, List<?>> assigned;
    private final List<Map<String, Object>> gaps;

    private MatchResult(Map<String, List<?>> assigned, List<Map<String, Object>> gaps) {
      this.assigned = assigned;
      this.gaps = gaps;
    }

    public List<?> witnessesFor(SqlOrigin origin) {
      return assigned.get(statementKey(origin));
    }

    public List<Map<String, Object>> gaps() {
      return gaps;
    }
  }

  private static void validateSchema(Map<String, Object> scenarios) {
    for (String field : scenarios.keySet()) {
      if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown parameter scenarios field: " + field);
    }
    Object statementsValue = scenarios.get("statements");
    if (!(statementsValue instanceof Map) || ((Map<?, ?>) statementsValue).isEmpty()) {
      throw new IllegalArgumentException("Parameter scenarios require a non-empty statements object");
    }
    int total = 0;
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) statementsValue).entrySet()) {
      if (!(entry.getKey() instanceof String) || ((String) entry.getKey()).trim().isEmpty()) {
        throw new IllegalArgumentException("Parameter statement keys must be non-empty strings");
      }
      if (!(entry.getValue() instanceof List) || ((List<?>) entry.getValue()).isEmpty()) {
        throw new IllegalArgumentException("Parameter statement requires a non-empty witness array: " + entry.getKey());
      }
      Set<String> ids = new HashSet<>();
      for (Object item : (List<?>) entry.getValue()) {
        if (++total > 4096) throw new IllegalArgumentException("Parameter scenarios exceed the 4096 witness limit");
        if (!(item instanceof Map)) throw new IllegalArgumentException("Parameter witness must be an object");
        Map<?, ?> witness = (Map<?, ?>) item;
        for (Object field : witness.keySet()) {
          if (!WITNESS_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown parameter witness field: " + field);
        }
        Object id = witness.get("id");
        if (!(id instanceof String) || ((String) id).trim().isEmpty() || !ids.add((String) id)) {
          throw new IllegalArgumentException("Parameter witness IDs must be non-empty and unique within a statement");
        }
        if (!(witness.get("parameters") instanceof Map)) {
          throw new IllegalArgumentException("Parameter witness requires an explicit parameters object");
        }
      }
    }
    Object helpers = scenarios.get("helperClassHashes");
    if (helpers != null) {
      if (!(helpers instanceof Map)) throw new IllegalArgumentException("helperClassHashes must be an object");
      for (Map.Entry<?, ?> helper : ((Map<?, ?>) helpers).entrySet()) {
        if (!(helper.getKey() instanceof String) || !(helper.getValue() instanceof String)
            || !((String) helper.getValue()).matches("[0-9a-f]{64}")) {
          throw new IllegalArgumentException("helperClassHashes requires SHA-256 strings");
        }
      }
    }
  }
}
