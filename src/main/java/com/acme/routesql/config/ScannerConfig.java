package com.acme.routesql.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;

public class ScannerConfig {
  private String dialect = "mysql";
  private RouteRuleConfig routeRules = new RouteRuleConfig();

  public static ScannerConfig load(Path path) throws IOException {
    if (path == null) {
      return new ScannerConfig();
    }
    ObjectMapper mapper = path.toString().endsWith(".json")
        ? new ObjectMapper()
        : new ObjectMapper(new YAMLFactory());
    ScannerConfig config = mapper.readValue(path.toFile(), ScannerConfig.class);
    if (config.routeRules == null) {
      config.routeRules = new RouteRuleConfig();
    }
    if (config.dialect == null || config.dialect.isBlank()) {
      config.dialect = "mysql";
    }
    return config;
  }

  public String getDialect() {
    return dialect;
  }

  public void setDialect(String dialect) {
    this.dialect = dialect;
  }

  public RouteRuleConfig getRouteRules() {
    return routeRules;
  }

  public void setRouteRules(RouteRuleConfig routeRules) {
    this.routeRules = routeRules;
  }
}
