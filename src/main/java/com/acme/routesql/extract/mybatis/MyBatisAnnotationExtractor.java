package com.acme.routesql.extract.mybatis;

import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.SqlObjects;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MyBatisAnnotationExtractor implements SqlExtractor {
  private static final Set<String> SQL_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete");

  @Override
  public String name() {
    return "mybatis-annotation";
  }

  @Override
  public boolean supports(Path path) {
    return path.getFileName().toString().endsWith(".java");
  }

  @Override
  public List<SqlObject> extract(Path path, ExtractionContext context) throws Exception {
    CompilationUnit unit = StaticJavaParser.parse(path);
    String namespace = fullyQualifiedPrimaryType(unit, path);
    List<SqlObject> objects = new ArrayList<>();

    for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
      for (AnnotationExpr annotation : method.getAnnotations()) {
        String statementType = statementType(annotation);
        if (statementType == null) {
          continue;
        }
        Optional<String> sqlValue = sqlValue(annotation);
        if (sqlValue.isEmpty()) {
          continue;
        }

        List<SqlVariant> variants = sqlVariants(
            MyBatisSqlScriptBuilder.buildAnnotationScripts(sqlValue.get()),
            context
        );
        int line = annotation.getBegin().map(p -> p.line).orElse(method.getBegin().map(p -> p.line).orElse(1));
        int column = annotation.getBegin().map(p -> p.column).orElse(method.getBegin().map(p -> p.column).orElse(1));
        SqlOrigin origin = new SqlOrigin(
            SourceKind.MYBATIS_ANNOTATION,
            path.toAbsolutePath().normalize(),
            line,
            column,
            namespace,
            method.getNameAsString(),
            statementType,
            namespace,
            method.getNameAsString()
        );
        for (int i = 0; i < variants.size(); i++) {
          SqlVariant variant = variants.get(i);
          objects.add(SqlObjects.create(
              variant.raw(),
              variant.normalized(),
              origin,
              variant.dynamic(),
              variant.dynamic() ? List.of("mybatis", "annotation", "dynamic") : List.of("mybatis", "annotation"),
              attributes(annotation, i, variants.size())
          ));
        }
      }
    }
    return objects;
  }

  private List<SqlVariant> sqlVariants(
      List<MyBatisSqlScriptBuilder.BuildResult> buildResults,
      ExtractionContext context
  ) {
    Map<String, SqlVariant> variants = new LinkedHashMap<>();
    for (MyBatisSqlScriptBuilder.BuildResult built : buildResults) {
      String raw = context.normalizer().normalizeMyBatisParameters(built.sql());
      boolean dynamic = built.dynamic() || raw.contains("__DYNAMIC__");
      String normalized = context.normalizer().normalize(raw);
      SqlVariant existing = variants.get(normalized);
      if (existing == null) {
        variants.put(normalized, new SqlVariant(raw, normalized, dynamic));
      } else if (!existing.dynamic() && dynamic) {
        variants.put(normalized, new SqlVariant(existing.raw(), existing.normalized(), true));
      }
    }
    return new ArrayList<>(variants.values());
  }

  private Map<String, Object> attributes(AnnotationExpr annotation, int variantIndex, int variantCount) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("extractor", name());
    attributes.put("annotation", annotation.getNameAsString());
    if (variantCount > 1) {
      attributes.put("variantIndex", variantIndex + 1);
      attributes.put("variantCount", variantCount);
    }
    return attributes;
  }

  private String statementType(AnnotationExpr annotation) {
    String simpleName = simpleName(annotation.getNameAsString());
    if (!SQL_ANNOTATIONS.contains(simpleName)) {
      return null;
    }
    return simpleName.toUpperCase(Locale.ROOT);
  }

  private Optional<String> sqlValue(AnnotationExpr annotation) {
    if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
      return evaluate(singleMember.getMemberValue());
    }
    if (annotation instanceof NormalAnnotationExpr normal) {
      for (MemberValuePair pair : normal.getPairs()) {
        if ("value".equals(pair.getNameAsString())) {
          return evaluate(pair.getValue());
        }
      }
    }
    return Optional.empty();
  }

  private Optional<String> evaluate(Expression expression) {
    if (expression instanceof StringLiteralExpr literal) {
      return Optional.of(literal.asString());
    }
    if (expression instanceof TextBlockLiteralExpr textBlock) {
      return Optional.of(textBlock.asString());
    }
    if (expression instanceof ArrayInitializerExpr array) {
      List<String> parts = new ArrayList<>();
      for (Expression value : array.getValues()) {
        Optional<String> evaluated = evaluate(value);
        if (evaluated.isEmpty()) {
          return Optional.empty();
        }
        parts.add(evaluated.get());
      }
      return Optional.of(String.join(" ", parts));
    }
    if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
      Optional<String> left = evaluate(binary.getLeft());
      Optional<String> right = evaluate(binary.getRight());
      if (left.isEmpty() || right.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(left.get() + right.get());
    }
    if (expression instanceof EnclosedExpr enclosed) {
      return evaluate(enclosed.getInner());
    }
    if (expression instanceof NameExpr) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private String fullyQualifiedPrimaryType(CompilationUnit unit, Path path) {
    String className = unit.getPrimaryTypeName()
        .orElse(path.getFileName().toString().replaceFirst("\\.java$", ""));
    return unit.getPackageDeclaration()
        .map(packageDeclaration -> packageDeclaration.getNameAsString() + "." + className)
        .orElse(className);
  }

  private String simpleName(String name) {
    int dot = name.lastIndexOf('.');
    return dot >= 0 ? name.substring(dot + 1) : name;
  }

  private record SqlVariant(String raw, String normalized, boolean dynamic) {}
}
