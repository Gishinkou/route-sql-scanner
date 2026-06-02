package com.acme.routesql.cli;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.report.CompactInventoryReporter;
import com.acme.routesql.report.Reporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "scan", description = "Scan MyBatis XML and Java JDBC SQL.")
public class ScanCommand implements Callable<Integer> {
  @Option(names = "--help", usageHelp = true, description = "Show this help message and exit.")
  boolean help;

  @Option(names = "--path", required = true, description = "File or directory to scan. Repeatable.")
  private List<Path> paths = new ArrayList<>();

  @Option(names = "--config", description = "YAML or JSON scanner config.")
  private Path configPath;

  @Option(names = "--output", description = "Output file. Defaults to stdout.")
  private Path output;

  @Option(names = "--include", description = "Glob include. Repeatable.")
  private List<String> includes = new ArrayList<>();

  @Option(names = "--exclude", description = "Glob exclude. Repeatable.")
  private List<String> excludes = new ArrayList<>();

  @Override
  public Integer call() {
    try {
      ScannerConfig config = ScannerConfig.load(configPath);
      ScanReport report = new ScanEngine(config).scan(paths, includes, excludes);
      Reporter reporter = new CompactInventoryReporter();
      byte[] rendered = reporter.renderBytes(report);
      if (output == null) {
        System.out.write(rendered);
      } else {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.write(output, rendered);
      }
      return 0;
    } catch (Exception e) {
      System.err.println("scan failed: " + e.getMessage());
      e.printStackTrace(System.err);
      return 3;
    }
  }
}
