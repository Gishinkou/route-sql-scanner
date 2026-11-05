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
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.FieldAccessExpr;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

public class MyBatisAnnotationExtractor implements SqlExtractor {
  private static final Set<String> SQL_ANNOTATIONS =
      new HashSet<>(Arrays.asList("Select", "Insert", "Update", "Delete",
          "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider"));

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
        MyBatisSqlScriptBuilder.BuildResult built =
            MyBatisSqlScriptBuilder.buildAnnotationScript(sqlValue.orElse("__DYNAMIC__"));
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
        Map<String, Object> template;
        if (sqlValue.isPresent()) {
          template = MyBatisTemplates.captureAnnotation(sqlValue.get(), path);
        } else {
          template = new LinkedHashMap<>();
          template.put("format", "mybatis-template/v1");
          template.put("sourcePath", path.toAbsolutePath().normalize().toString());
          template.put("status", "UNRESOLVED");
          template.put("errorCode", "ANNOTATION_SQL_UNRESOLVED");
          template.put("error", "Annotation SQL is not a resolved string constant; provider code is not executed");
          template.put("annotation", annotation.toString());
        }
        objects.add(SqlObjects.create(raw, origin, dynamic).withTemplate(template));
      }
    }
    return objects;
  }

  private String statementType(AnnotationExpr annotation) {
    String simpleName = simpleName(annotation.getNameAsString());
    if (!SQL_ANNOTATIONS.contains(simpleName)) {
      return null;
    }
    return simpleName.replace("Provider", "").toUpperCase(Locale.ROOT);
  }

  private Optional<String> sqlValue(AnnotationExpr annotation) {
    if (simpleName(annotation.getNameAsString()).endsWith("Provider")) return Optional.empty();
    if (annotation instanceof SingleMemberAnnotationExpr) {
      return evaluate(((SingleMemberAnnotationExpr) annotation).getMemberValue());
    }
    if (annotation instanceof NormalAnnotationExpr) {
      for (MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
        if ("value".equals(pair.getNameAsString())) {
          return evaluate(pair.getValue());
        }
      }
    }
    return Optional.empty();
  }

  private Optional<String> evaluate(Expression expression) {
    return evaluate(expression, new HashSet<VariableDeclarator>());
  }

  private Optional<String> evaluate(Expression expression, Set<VariableDeclarator> resolving) {
    if (expression instanceof StringLiteralExpr) {
      return Optional.of(((StringLiteralExpr) expression).asString());
    }
    if (expression instanceof TextBlockLiteralExpr) {
      return Optional.of(((TextBlockLiteralExpr) expression).asString());
    }
    if (expression instanceof ArrayInitializerExpr) {
      List<String> parts = new ArrayList<>();
      for (Expression value : ((ArrayInitializerExpr) expression).getValues()) {
        Optional<String> evaluated = evaluate(value, resolving);
        if (!evaluated.isPresent()) {
          return Optional.empty();
        }
        parts.add(evaluated.get());
      }
      return Optional.of(String.join(" ", parts));
    }
    if (expression instanceof BinaryExpr
        && ((BinaryExpr) expression).getOperator() == BinaryExpr.Operator.PLUS) {
      BinaryExpr binary = (BinaryExpr) expression;
      Optional<String> left = evaluate(binary.getLeft(), resolving);
      Optional<String> right = evaluate(binary.getRight(), resolving);
      if (!left.isPresent() || !right.isPresent()) {
        return Optional.empty();
      }
      return Optional.of(left.get() + right.get());
    }
    if (expression instanceof EnclosedExpr) {
      return evaluate(((EnclosedExpr) expression).getInner(), resolving);
    }
    if (expression instanceof NameExpr) {
      Node owner = expression;
      while (owner.getParentNode().isPresent()) {
        owner = owner.getParentNode().get();
        if (owner instanceof TypeDeclaration) {
          Optional<VariableDeclarator> field = constantField((TypeDeclaration<?>) owner,
              ((NameExpr) expression).getNameAsString());
          if (field.isPresent()) return evaluateConstant(field.get(), resolving);
        }
      }
    }
    if (expression instanceof FieldAccessExpr) {
      FieldAccessExpr access = (FieldAccessExpr) expression;
      Optional<CompilationUnit> unit = expression.findAncestor(CompilationUnit.class);
      if (unit.isPresent()) {
        List<VariableDeclarator> matches = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.get().findAll(TypeDeclaration.class)) {
          String qualified = typeName(type);
          String scope = access.getScope().toString();
          String pkg = unit.get().getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
          if (scope.equals(qualified) || scope.equals(pkg + qualified)) {
            constantField(type, access.getNameAsString()).ifPresent(matches::add);
          }
        }
        if (matches.size() == 1) return evaluateConstant(matches.get(0), resolving);
      }
    }
    return Optional.empty();
  }

  private Optional<VariableDeclarator> constantField(TypeDeclaration<?> type, String name) {
    for (FieldDeclaration field : type.getFields()) {
      for (VariableDeclarator variable : field.getVariables()) {
        if (name.equals(variable.getNameAsString())) return Optional.of(variable);
      }
    }
    return Optional.empty();
  }

  private Optional<String> evaluateConstant(VariableDeclarator variable, Set<VariableDeclarator> resolving) {
    Optional<FieldDeclaration> declaration = variable.findAncestor(FieldDeclaration.class);
    if (!declaration.isPresent()) return Optional.empty();
    Optional<TypeDeclaration> owner = declaration.get().findAncestor(TypeDeclaration.class);
    boolean inInterface = owner.isPresent() && owner.get() instanceof ClassOrInterfaceDeclaration
        && ((ClassOrInterfaceDeclaration) owner.get()).isInterface();
    String declaredType = variable.getType().asString();
    if ((!inInterface && !(declaration.get().isStatic() && declaration.get().isFinal()))
        || !("String".equals(declaredType) || "java.lang.String".equals(declaredType))) return Optional.empty();
    if (!variable.getInitializer().isPresent() || !resolving.add(variable)) return Optional.empty();
    try { return evaluate(variable.getInitializer().get(), resolving); }
    finally { resolving.remove(variable); }
  }

  private String typeName(TypeDeclaration<?> type) {
    String name = type.getNameAsString();
    Optional<Node> parent = type.getParentNode();
    while (parent.isPresent()) {
      if (parent.get() instanceof TypeDeclaration) name = ((TypeDeclaration<?>) parent.get()).getNameAsString() + "." + name;
      parent = parent.get().getParentNode();
    }
    return name;
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
