package com.acme.routesql.util;

import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SqlObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates and applies a sqlKey allowlist to an already extracted report. */
public final class SqlKeyAllowlist {
  private SqlKeyAllowlist() {}

  public static ScanReport filter(ScanReport report, Path projectRoot, Path allowlistPath)
      throws IOException {
    if (report == null) {
      throw new IllegalArgumentException("report must not be null");
    }
    if (allowlistPath == null) {
      return report;
    }

    JsonNode root = new ObjectMapper().readTree(allowlistPath.toFile());
    if (root == null || !root.isArray()) {
      throw new IllegalArgumentException("sqlKey allowlist must be a JSON array: " + allowlistPath);
    }
    Set<String> requested = new LinkedHashSet<>();
    Set<String> duplicates = new LinkedHashSet<>();
    for (JsonNode item : root) {
      if (!item.isTextual()) {
        throw new IllegalArgumentException("sqlKey allowlist entries must be JSON strings");
      }
      String key = item.textValue();
      if (!SqlKey.isValid(key)) {
        throw new IllegalArgumentException("invalid sqlKey in allowlist: " + key);
      }
      if (!requested.add(key)) {
        duplicates.add(key);
      }
    }
    if (requested.isEmpty()) {
      throw new IllegalArgumentException("sqlKey allowlist is empty: " + allowlistPath);
    }
    if (!duplicates.isEmpty()) {
      throw new IllegalArgumentException("duplicate sqlKey(s) in allowlist: " + duplicates);
    }

    Map<String, SqlObject> extracted = new LinkedHashMap<>();
    Map<String, Integer> extractedCounts = new HashMap<>();
    for (SqlObject sqlObject : report.sqlObjects()) {
      String key = SqlKey.of(projectRoot, sqlObject);
      extracted.putIfAbsent(key, sqlObject);
      extractedCounts.put(key, extractedCounts.getOrDefault(key, 0) + 1);
    }

    Set<String> collisions = new LinkedHashSet<>();
    for (Map.Entry<String, Integer> entry : extractedCounts.entrySet()) {
      if (entry.getValue() > 1) {
        collisions.add(entry.getKey());
      }
    }
    if (!collisions.isEmpty()) {
      throw new IllegalArgumentException("sqlKey collision(s) in extracted SQL: " + collisions);
    }

    Set<String> unknown = new LinkedHashSet<>(requested);
    unknown.removeAll(extracted.keySet());
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("unknown sqlKey(s) in allowlist: " + unknown);
    }

    // Preserve extraction/report ordering rather than allowlist file ordering.
    Set<String> requestedSet = new HashSet<>(requested);
    List<SqlObject> filtered = new ArrayList<>();
    for (SqlObject sqlObject : report.sqlObjects()) {
      if (requestedSet.contains(SqlKey.of(projectRoot, sqlObject))) {
        filtered.add(sqlObject);
      }
    }
    return new ScanReport(report.project(), filtered);
  }
}
