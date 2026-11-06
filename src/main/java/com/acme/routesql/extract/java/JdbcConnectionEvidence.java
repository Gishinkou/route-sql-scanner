package com.acme.routesql.extract.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A deliberately narrow AST proof for a local JDBC connection and a directly injected field. */
final class JdbcConnectionEvidence {
  private static final String DATA_SOURCE = "javax.sql.DataSource";
  private static final String CONNECTION = "java.sql.Connection";

  private JdbcConnectionEvidence() {}

  static Map<String, Object> find(CompilationUnit unit, MethodDeclaration method, MethodCallExpr call) {
    if ((!call.getNameAsString().equals("prepareStatement") && !call.getNameAsString().equals("prepareCall"))
        || !(method.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration)) {
      return null;
    }
    ClassOrInterfaceDeclaration owner = (ClassOrInterfaceDeclaration) method.getParentNode().get();
    if (owner.getParentNode().orElse(null) != unit) return null;
    Expression receiver = call.getScope().orElse(null);
    if (receiver == null) return null;
    Expression connection = receiver.isNameExpr()
        ? assignedConnection(unit, method, call, receiver.asNameExpr().getNameAsString()) : receiver;
    if (connection == null || !connection.isMethodCallExpr()) return null;
    MethodCallExpr acquisition = connection.asMethodCallExpr();
    if (!"getConnection".equals(acquisition.getNameAsString()) || !acquisition.getArguments().isEmpty()) return null;
    Expression source = acquisition.getScope().orElse(null);
    String field = fieldName(source);
    if (field == null || (source.isNameExpr() && (method.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(field))
        || method.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(field))))) {
      return null;
    }
    List<VariableDeclarator> fields = owner.getFields().stream().flatMap(declaration -> declaration.getVariables().stream())
        .filter(variable -> field.equals(variable.getNameAsString())).collect(Collectors.toList());
    if (fields.size() != 1) return null;
    VariableDeclarator declared = fields.get(0);
    FieldDeclaration declaration = (FieldDeclaration) declared.getParentNode().get();
    if (declaration.isStatic() || declaration.getVariables().size() != 1
        || !isType(unit, declared.getTypeAsString(), DATA_SOURCE)
        || declared.getInitializer().filter(value -> !value.isNullLiteralExpr()).isPresent()) return null;

    String setterName = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    List<MethodDeclaration> setters = owner.getMethodsByName(setterName);
    AssignExpr setter = setters.size() == 1 ? setterAssignment(unit, setters.get(0), field) : null;
    List<AssignExpr> writes = owner.findAll(AssignExpr.class, assignment -> field.equals(fieldName(assignment.getTarget())));
    if (writes.stream().anyMatch(write -> write != setter)) return null;
    String namedBean = namedResource(unit, declaration);
    if (namedBean == null && setter == null) return null;

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("ownerClass", owner.getFullyQualifiedName().get());
    evidence.put("field", field);
    if (setter != null) evidence.put("setter", setterName);
    if (namedBean != null) evidence.put("namedResource", namedBean);
    acquisition.getBegin().ifPresent(pos -> evidence.put("line", pos.line));
    return evidence;
  }

  private static Expression assignedConnection(CompilationUnit unit, MethodDeclaration method, MethodCallExpr call,
                                               String name) {
    if (method.getParameters().stream().anyMatch(parameter -> name.equals(parameter.getNameAsString()))) return null;
    List<VariableDeclarator> variables = method.findAll(VariableDeclarator.class, v -> name.equals(v.getNameAsString()));
    if (variables.size() != 1 || !isType(unit, variables.get(0).getTypeAsString(), CONNECTION)
        || !before(variables.get(0).getBegin().orElse(null), call.getBegin().orElse(null))) return null;
    Expression initial = variables.get(0).getInitializer().orElse(null);
    List<AssignExpr> assignments = method.findAll(AssignExpr.class, assignment ->
        assignment.getTarget().isNameExpr() && name.equals(assignment.getTarget().asNameExpr().getNameAsString()));
    if (assignments.isEmpty()) return initial;
    AssignExpr only = assignments.get(0);
    return assignments.size() == 1 && only.getOperator() == AssignExpr.Operator.ASSIGN
        && (initial == null || initial.isNullLiteralExpr())
        && before(only.getBegin().orElse(null), call.getBegin().orElse(null)) ? only.getValue() : null;
  }

  private static boolean before(com.github.javaparser.Position left, com.github.javaparser.Position right) {
    return left != null && right != null && left.isBefore(right);
  }

  private static AssignExpr setterAssignment(CompilationUnit unit, MethodDeclaration setter, String field) {
    if (!setter.isPublic() || setter.isStatic() || setter.getParameters().size() != 1
        || !isType(unit, setter.getParameter(0).getTypeAsString(), DATA_SOURCE) || !setter.getBody().isPresent()
        || setter.getBody().get().getStatements().size() != 1) return null;
    return setter.getBody().get().getStatement(0).toExpressionStmt().map(statement -> statement.getExpression())
        .flatMap(Expression::toAssignExpr).filter(assignment -> {
          String parameter = setter.getParameter(0).getNameAsString();
          return assignment.getOperator() == AssignExpr.Operator.ASSIGN
              && field.equals(fieldName(assignment.getTarget()))
              && (!assignment.getTarget().isNameExpr() || !field.equals(parameter))
              && assignment.getValue().isNameExpr() && parameter.equals(assignment.getValue().asNameExpr().getNameAsString());
        }).orElse(null);
  }

  private static String namedResource(CompilationUnit unit, FieldDeclaration field) {
    List<AnnotationExpr> resources = field.getAnnotations().stream()
        .filter(annotation -> isType(unit, annotation.getNameAsString(), "javax.annotation.Resource")
            || isType(unit, annotation.getNameAsString(), "jakarta.annotation.Resource"))
        .collect(Collectors.toList());
    if (resources.size() != 1 || !resources.get(0).isNormalAnnotationExpr()) return null;
    NormalAnnotationExpr resource = resources.get(0).asNormalAnnotationExpr();
    if (resource.getPairs().size() != 1 || !"name".equals(resource.getPairs().get(0).getNameAsString())
        || !resource.getPairs().get(0).getValue().isStringLiteralExpr()) return null;
    return resource.getPairs().get(0).getValue().asStringLiteralExpr().asString();
  }

  private static boolean isType(CompilationUnit unit, String actual, String qualified) {
    if (qualified.equals(actual)) return true;
    String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
    return simple.equals(actual) && unit.getImports().stream()
        .anyMatch(declaration -> !declaration.isStatic() && !declaration.isAsterisk()
            && qualified.equals(declaration.getNameAsString()));
  }

  private static String fieldName(Expression expression) {
    if (expression == null) return null;
    if (expression.isNameExpr()) return expression.asNameExpr().getNameAsString();
    if (!expression.isFieldAccessExpr() || !expression.asFieldAccessExpr().getScope().isThisExpr()
        || expression.asFieldAccessExpr().getScope().asThisExpr().getTypeName().isPresent()) return null;
    return expression.asFieldAccessExpr().getNameAsString();
  }
}
