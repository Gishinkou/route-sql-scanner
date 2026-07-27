package com.acme.routesql.core;

import com.acme.routesql.config.ScannerConfig;
import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.extract.java.JavaJdbcStatementExtractor;
import com.acme.routesql.extract.mybatis.MyBatisAnnotationExtractor;
import com.acme.routesql.extract.mybatis.MyBatisXmlExtractor;
import com.acme.routesql.model.ScanReport;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.normalize.SqlNormalizer;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScanEngine {
  private final ScannerConfig config;
  private final List<SqlExtractor> extractors;

  public ScanEngine(ScannerConfig config) {
    this.config = config;
    this.extractors = Arrays.asList(
        new MyBatisXmlExtractor(),
        new MyBatisAnnotationExtractor(),
        new JavaJdbcStatementExtractor()
    );
  }

  public ScanReport scan(List<Path> paths, List<String> includes, List<String> excludes) throws Exception {
    List<Path> files = discover(paths, includes, excludes);
    ExtractionContext context = new ExtractionContext(new SqlNormalizer());
    List<SqlObject> sqlObjects = new ArrayList<>();
    for (Path file : files) {
      for (SqlExtractor extractor : extractors) {
        if (!extractor.supports(file)) {
          continue;
        }
        try {
          sqlObjects.addAll(extractor.extract(file, context));
        } catch (Exception e) {
          System.err.println("warn: " + extractor.name() + " failed on " + file + ": " + e.getMessage());
        }
      }
    }
    return new ScanReport(config.getProject(), sqlObjects);
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
        try (Stream<Path> stream = Files.walk(path)) {
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
      return Collections.emptyList();
    }
    return globs.stream()
        .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
        .collect(Collectors.toList());
  }
}
