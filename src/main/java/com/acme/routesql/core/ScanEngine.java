package com.acme.routesql.core;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.extract.java.JavaJdbcStatementExtractor;
import com.acme.routesql.extract.mybatis.MyBatisAnnotationExtractor;
import com.acme.routesql.extract.mybatis.MyBatisXmlExtractor;
import com.acme.routesql.model.Diagnostic;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.ScanSummary;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.normalize.SqlNormalizer;
import com.acme.routesql.parse.SqlParserFacade;
import com.acme.routesql.rule.RouteFieldRule;
import com.acme.routesql.rule.SqlRule;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ScanEngine {
  private final ScannerConfig config;
  private final List<SqlExtractor> extractors;
  private final SqlParserFacade parser;
  private final List<SqlRule> rules;

  public ScanEngine(ScannerConfig config) {
    this.config = config;
    this.extractors = List.of(
        new MyBatisXmlExtractor(),
        new MyBatisAnnotationExtractor(),
        new JavaJdbcStatementExtractor()
    );
    this.parser = new SqlParserFacade(config.getDialect());
    this.rules = List.of(new RouteFieldRule(config.getRouteRules()));
  }

  public ScanReport scan(List<Path> paths, List<String> includes, List<String> excludes) throws Exception {
    List<Path> files = discover(paths, includes, excludes);
    ExtractionContext context = new ExtractionContext(new SqlNormalizer());
    List<SqlObject> parsedObjects = new ArrayList<>();
    for (Path file : files) {
      for (SqlExtractor extractor : extractors) {
        if (extractor.supports(file)) {
          for (SqlObject object : extractor.extract(file, context)) {
            parsedObjects.add(object.withParse(parser.parse(object.rawSql())));
          }
        }
      }
    }

    List<Diagnostic> diagnostics = new ArrayList<>();
    for (SqlRule rule : rules) {
      diagnostics.addAll(rule.apply(parsedObjects));
    }
    ScanSummary summary = new ScanSummary(files.size(), parsedObjects.size(), diagnostics.size());
    return new ScanReport("0.1.0", config.getDialect(), summary, parsedObjects, diagnostics);
  }

  private List<Path> discover(List<Path> paths, List<String> includes, List<String> excludes) throws IOException {
    List<PathMatcher> includeMatchers = matchers(includes);
    List<PathMatcher> excludeMatchers = matchers(excludes);
    List<Path> files = new ArrayList<>();
    for (Path requested : paths) {
      Path path = requested.toAbsolutePath().normalize();
      if (Files.isRegularFile(path)) {
        if (accepted(path, includeMatchers, excludeMatchers)) {
          files.add(path);
        }
      } else if (Files.isDirectory(path)) {
        try (var stream = Files.walk(path)) {
          stream
              .filter(Files::isRegularFile)
              .filter(candidate -> accepted(candidate, includeMatchers, excludeMatchers))
              .forEach(files::add);
        }
      }
    }
    files.sort(Comparator.comparing(Path::toString));
    return files;
  }

  private boolean accepted(Path path, List<PathMatcher> includes, List<PathMatcher> excludes) {
    String name = path.getFileName().toString();
    boolean defaultSupported = name.endsWith(".xml") || name.endsWith(".java");
    boolean included = includes.isEmpty() ? defaultSupported : includes.stream().anyMatch(m -> m.matches(path));
    boolean excluded = excludes.stream().anyMatch(m -> m.matches(path));
    return included && !excluded;
  }

  private List<PathMatcher> matchers(List<String> globs) {
    if (globs == null || globs.isEmpty()) {
      return List.of();
    }
    return globs.stream()
        .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
        .toList();
  }
}
