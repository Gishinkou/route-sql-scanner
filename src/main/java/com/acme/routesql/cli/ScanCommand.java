package com.acme.routesql.cli;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.core.ScanEngine;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.report.JsonReporter;
import com.acme.routesql.report.JsonlReporter;
import com.acme.routesql.report.MarkdownReporter;
import com.acme.routesql.report.Reporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "scan", description = "Scan MyBatis XML and Java JDBC SQL.", mixinStandardHelpOptions = true)
public class ScanCommand implements Callable<Integer> {
  @Option(names = {"--path", "-p"}, required = true, description = "File or directory to scan. Repeatable.")
  private List<Path> paths = new ArrayList<>();

  @Option(names = {"--config", "-c"}, description = "YAML or JSON scanner config.")
  private Path configPath;

  @Option(names = {"--format", "-f"}, defaultValue = "json", description = "json | jsonl | markdown")
  private String format;

  @Option(names = {"--output", "-o"}, description = "Output file. Defaults to stdout.")
  private Path output;

  @Option(names = "--include", description = "Glob include. Repeatable.")
  private List<String> includes = new ArrayList<>();

  @Option(names = "--exclude", description = "Glob exclude. Repeatable.")
  private List<String> excludes = new ArrayList<>();

  @Option(names = "--fail-on", defaultValue = "ERROR", description = "ERROR | WARN | NEVER")
  private String failOn;

  @Override
  public Integer call() {
    try {
      ScannerConfig config = ScannerConfig.load(configPath);
      ScanReport report = new ScanEngine(config).scan(paths, includes, excludes);
      String rendered = reporter(format).render(report);
      if (output == null) {
        System.out.print(rendered);
      } else {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(output, rendered, StandardCharsets.UTF_8);
      }
      return exitCode(report, failOn);
    } catch (Exception e) {
      System.err.println("scan failed: " + e.getMessage());
      e.printStackTrace(System.err);
      return 3;
    }
  }

  private Reporter reporter(String requested) {
    return switch (requested.toLowerCase(Locale.ROOT)) {
      case "json" -> new JsonReporter();
      case "jsonl" -> new JsonlReporter();
      case "markdown", "md" -> new MarkdownReporter();
      default -> throw new IllegalArgumentException("unsupported format: " + requested);
    };
  }

  private int exitCode(ScanReport report, String failOn) {
    String normalized = failOn.toUpperCase(Locale.ROOT);
    if ("NEVER".equals(normalized)) {
      return 0;
    }
    boolean hasError = report.diagnostics().stream().anyMatch(d -> "ERROR".equalsIgnoreCase(d.severity()));
    boolean hasWarn = report.diagnostics().stream().anyMatch(d -> "WARN".equalsIgnoreCase(d.severity()));
    if (hasError && ("ERROR".equals(normalized) || "WARN".equals(normalized))) {
      return 2;
    }
    if (hasWarn && "WARN".equals(normalized)) {
      return 1;
    }
    return 0;
  }
}
