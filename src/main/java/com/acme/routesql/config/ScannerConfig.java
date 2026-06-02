package com.acme.routesql.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;

public class ScannerConfig {
  private String project;

  public static ScannerConfig load(Path path) throws IOException {
    if (path == null) {
      return new ScannerConfig();
    }
    ObjectMapper mapper = path.toString().endsWith(".json")
        ? new ObjectMapper()
        : new ObjectMapper(new YAMLFactory());
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    ScannerConfig config = mapper.readValue(path.toFile(), ScannerConfig.class);
    return config == null ? new ScannerConfig() : config;
  }

  public String getProject() {
    return project;
  }

  public void setProject(String project) {
    this.project = project;
  }
}
