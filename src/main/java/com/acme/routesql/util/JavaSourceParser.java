package com.acme.routesql.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;

public final class JavaSourceParser {
  private static final JavaParser PARSER = create();

  private JavaSourceParser() {}

  private static JavaParser create() {
    ParserConfiguration configuration = new ParserConfiguration()
        .setLanguageLevel(LanguageLevel.BLEEDING_EDGE);
    return new JavaParser(configuration);
  }

  public static CompilationUnit parse(Path path) throws Exception {
    ParseResult<CompilationUnit> result = PARSER.parse(path);
    return result.getResult().orElseThrow(() -> new IllegalStateException(
        "Failed to parse " + path + ": " + result.getProblems()));
  }
}
