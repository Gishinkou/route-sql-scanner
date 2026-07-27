package com.acme.routesql.extract.java;

import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.JavaSourceParser;
import com.acme.routesql.util.SqlObjects;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class JavaJdbcStatementExtractor implements SqlExtractor {
  private static final List<String> SQL_METHODS = Arrays.asList(
      "prepareStatement",
      "execute",
      "executeQuery",
      "executeUpdate",
      "query",
      "update"
  );

  @Override
  public String name() {
    return "java-jdbc";
  }

  @Override
  public boolean supports(Path path) {
    return path.getFileName().toString().endsWith(".java");
  }

  @Override
  public List<SqlObject> extract(Path path, ExtractionContext context) throws Exception {
    CompilationUnit unit = JavaSourceParser.parse(path);
    String className = unit.getPrimaryTypeName().orElse(path.getFileName().toString().replaceFirst("\\.java$", ""));
    List<SqlObject> objects = new ArrayList<>();

    for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
      Map<String, EvalResult> variables = collectStringVariables(method);
      method.accept(new VoidVisitorAdapter<Void>() {
        @Override
        public void visit(MethodCallExpr call, Void arg) {
          super.visit(call, arg);
          if (!SQL_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return;
          }
          EvalResult evaluated = evaluate(call.getArgument(0), variables);
          if (evaluated == null || !looksLikeSql(evaluated.sql())) {
            return;
          }
          int line = call.getBegin().map(p -> p.line).orElse(1);
          int column = call.getBegin().map(p -> p.column).orElse(1);
          String raw = evaluated.sql();
          SqlOrigin origin = new SqlOrigin(
              SourceKind.JAVA_JDBC,
              path.toAbsolutePath().normalize(),
              line,
              column,
              null,
              null,
              null,
              className,
              method.getNameAsString()
          );
          objects.add(SqlObjects.create(raw, origin, evaluated.dynamic()));
        }
      }, null);
    }
    return objects;
  }

  private Map<String, EvalResult> collectStringVariables(MethodDeclaration method) {
    Map<String, EvalResult> variables = new HashMap<>();
    for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
      if (variable.getInitializer().isPresent() && isStringish(variable)) {
        EvalResult result = evaluate(variable.getInitializer().get(), variables);
        if (result != null) {
          variables.put(variable.getNameAsString(), result);
        }
      }
    }
    for (AssignExpr assignment : method.findAll(AssignExpr.class)) {
      if (assignment.getTarget().isNameExpr()) {
        EvalResult result = evaluate(assignment.getValue(), variables);
        if (result != null) {
          variables.put(assignment.getTarget().asNameExpr().getNameAsString(), result);
        }
      }
    }
    return variables;
  }

  private boolean isStringish(VariableDeclarator variable) {
    return "String".equals(variable.getType().asString()) || variable.getInitializer()
        .map(this::isStringExpression)
        .orElse(false);
  }

  private boolean isStringExpression(Expression expression) {
    return expression.isStringLiteralExpr()
        || expression.isTextBlockLiteralExpr()
        || (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS);
  }

  private EvalResult evaluate(Expression expression, Map<String, EvalResult> variables) {
    if (expression instanceof StringLiteralExpr) {
      return new EvalResult(((StringLiteralExpr) expression).asString(), false);
    }
    if (expression instanceof TextBlockLiteralExpr) {
      return new EvalResult(((TextBlockLiteralExpr) expression).asString(), false);
    }
    if (expression instanceof EnclosedExpr) {
      return evaluate(((EnclosedExpr) expression).getInner(), variables);
    }
    if (expression instanceof NameExpr) {
      return variables.get(((NameExpr) expression).getNameAsString());
    }
    if (expression instanceof BinaryExpr
        && ((BinaryExpr) expression).getOperator() == BinaryExpr.Operator.PLUS) {
      BinaryExpr binary = (BinaryExpr) expression;
      EvalResult left = evaluate(binary.getLeft(), variables);
      EvalResult right = evaluate(binary.getRight(), variables);
      if (left == null && right == null) {
        return null;
      }
      String leftSql = left == null ? "__DYNAMIC__" : left.sql();
      String rightSql = right == null ? "__DYNAMIC__" : right.sql();
      boolean dynamic = left == null || right == null || left.dynamic() || right.dynamic();
      return new EvalResult(leftSql + rightSql, dynamic);
    }
    return null;
  }

  private boolean looksLikeSql(String sql) {
    String normalized = stripLeading(sql).toUpperCase(Locale.ROOT);
    return normalized.startsWith("SELECT ")
        || normalized.startsWith("INSERT ")
        || normalized.startsWith("UPDATE ")
        || normalized.startsWith("DELETE ")
        || normalized.startsWith("WITH ");
  }

  private static String stripLeading(String value) {
    int start = 0;
    while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
      start++;
    }
    return value.substring(start);
  }

  private static final class EvalResult {
    private final String sql;
    private final boolean dynamic;

    EvalResult(String sql, boolean dynamic) {
      this.sql = sql;
      this.dynamic = dynamic;
    }

    String sql() {
      return sql;
    }

    boolean dynamic() {
      return dynamic;
    }
  }
}
