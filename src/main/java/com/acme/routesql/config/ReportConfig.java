package com.acme.routesql.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ReportConfig {
  private static final List<String> CONFIG_NAMES = List.of(
      "route-sql-report.yml",
      "route-sql-report.yaml",
      "route-sql-report.json",
      ".route-sql-report.yml",
      ".route-sql-report.yaml",
      ".route-sql-report.json"
  );

  private String format;

  public static ReportConfig discover(Path scannerConfigPath, List<Path> scanPaths) throws IOException {
    for (Path base : candidateBases(scannerConfigPath, scanPaths)) {
      for (String name : CONFIG_NAMES) {
        Path candidate = base.resolve(name);
        if (Files.isRegularFile(candidate)) {
          return load(candidate);
        }
      }
    }
    return new ReportConfig();
  }

  private static ReportConfig load(Path path) throws IOException {
    ObjectMapper mapper = path.toString().endsWith(".json")
        ? new ObjectMapper()
        : new ObjectMapper(new YAMLFactory());
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    ReportConfig config = mapper.readValue(path.toFile(), ReportConfig.class);
    return config == null ? new ReportConfig() : config;
  }

  private static Set<Path> candidateBases(Path scannerConfigPath, List<Path> scanPaths) {
    Set<Path> bases = new LinkedHashSet<>();
    if (scannerConfigPath != null && scannerConfigPath.toAbsolutePath().getParent() != null) {
      bases.add(scannerConfigPath.toAbsolutePath().getParent().normalize());
    }
    if (scanPaths != null) {
      for (Path scanPath : scanPaths) {
        Path absolute = scanPath.toAbsolutePath().normalize();
        bases.add(Files.isDirectory(absolute) ? absolute : absolute.getParent());
      }
    }
    bases.add(Path.of("").toAbsolutePath().normalize());
    bases.remove(null);
    return bases;
  }

  public String effectiveFormat(String cliFormat) {
    if (cliFormat != null && !cliFormat.isBlank()) {
      return cliFormat;
    }
    if (format != null && !format.isBlank()) {
      return format;
    }
    return "json";
  }

  public String getFormat() {
    return format;
  }

  public void setFormat(String format) {
    this.format = format;
  }
}
