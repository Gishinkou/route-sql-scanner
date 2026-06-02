package com.acme.routesql.extract.mybatis;

import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.JavaSourceParser;
import com.acme.routesql.util.SqlObjects;
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
import java.util.List;
import java.util.Locale;
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
    CompilationUnit unit = JavaSourceParser.parse(path);
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

        MyBatisSqlScriptBuilder.BuildResult built =
            MyBatisSqlScriptBuilder.buildAnnotationScript(sqlValue.get());
        String raw = context.normalizer().normalizeMyBatisParameters(built.sql());
        boolean dynamic = built.dynamic() || raw.contains("__DYNAMIC__");
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
        objects.add(SqlObjects.create(raw, origin, dynamic));
      }
    }
    return objects;
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
}
