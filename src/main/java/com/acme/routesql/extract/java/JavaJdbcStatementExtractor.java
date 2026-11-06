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
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class JavaJdbcStatementExtractor implements SqlExtractor {
  private static final int MAX_BRANCH_STATES = 32;
  private static final List<String> SQL_METHODS = Arrays.asList(
      "prepareStatement",
      "prepareCall",
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
    String sourceSha256 = null;

    int callsiteOrdinal = 0;
    for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
      for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
        if (!SQL_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()
            || call.findAncestor(MethodDeclaration.class).orElse(null) != method) {
          continue;
        }
        String siteId = method.getSignature().asString() + "#" + (++callsiteOrdinal);
        Map<EvalResult, List<Map<String, Object>>> alternatives = new LinkedHashMap<>();
        Flow flow = new Flow(relevantVariables(method, call.getArgument(0)));
        for (BranchState state : flow.at(method, call)) {
          EvalResult evaluated = evaluate(call.getArgument(0), state.variables);
          if (evaluated == null || !looksLikeSql(evaluated.sql())) {
            continue;
          }
          alternatives.merge(evaluated, new ArrayList<>(state.branches), (previous, pathBranches) -> {
            previous.retainAll(pathBranches);
            return previous;
          });
        }
        if (alternatives.isEmpty()) continue;
        Map<String, Object> connection = JdbcConnectionEvidence.find(unit, method, call);
        if (connection != null && sourceSha256 == null) {
          byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
          sourceSha256 = String.format("%064x", new BigInteger(1, digest));
        }
        for (Map.Entry<EvalResult, List<Map<String, Object>>> alternative : alternatives.entrySet()) {
          Map<String, Object> site = new LinkedHashMap<>();
          site.put("id", siteId);
          site.put("method", call.getNameAsString());
          call.getScope().map(this::receiver).ifPresent(scope -> site.put("receiver", scope));
          site.put("branches", alternative.getValue());
          if (connection != null) {
            site.put("connectionSource", connection);
            site.put("sourceSha256", sourceSha256);
          }
          if (flow.truncated) {
            site.put("coverageTruncated", true);
          }
          SqlOrigin origin = new SqlOrigin(
              SourceKind.JAVA_JDBC,
              path.toAbsolutePath().normalize(),
              call.getBegin().map(p -> p.line).orElse(1),
              call.getBegin().map(p -> p.column).orElse(1),
              null,
              null,
              null,
              className,
              method.getNameAsString(),
              site
          );
          EvalResult result = alternative.getKey();
          objects.add(SqlObjects.create(result.sql(), origin, result.dynamic()));
        }
      }
    }
    return objects;
  }

  private Set<String> relevantVariables(MethodDeclaration method, Expression argument) {
    Set<String> relevant = new HashSet<>();
    addNames(argument, relevant);
    List<VariableDeclarator> declarations = method.findAll(VariableDeclarator.class);
    List<AssignExpr> assignments = method.findAll(AssignExpr.class);
    int previous;
    do {
      previous = relevant.size();
      for (VariableDeclarator declaration : declarations) {
        if (relevant.contains(declaration.getNameAsString())) {
          declaration.getInitializer().ifPresent(value -> addNames(value, relevant));
        }
      }
      for (AssignExpr assignment : assignments) {
        if (relevant.contains(targetName(assignment))) {
          addNames(assignment.getValue(), relevant);
        }
      }
    } while (relevant.size() != previous);
    return relevant;
  }

  private static void addNames(Expression expression, Set<String> names) {
    expression.findAll(NameExpr.class).forEach(name -> names.add(name.getNameAsString()));
  }

  private static String targetName(AssignExpr assignment) {
    return assignment.getTarget().isNameExpr() ? assignment.getTarget().asNameExpr().getNameAsString() : null;
  }

  private String receiver(Expression expression) {
    if (expression.isNameExpr() || expression.isThisExpr() || expression.isSuperExpr()) {
      return expression.toString();
    }
    if (expression.isEnclosedExpr()) {
      return receiver(expression.asEnclosedExpr().getInner());
    }
    if (expression.isFieldAccessExpr()) {
      String scope = receiver(expression.asFieldAccessExpr().getScope());
      return scope == null ? null : scope + "." + expression.asFieldAccessExpr().getNameAsString();
    }
    if (expression.isMethodCallExpr()) {
      MethodCallExpr method = expression.asMethodCallExpr();
      String call = method.getNameAsString() + "()";
      return method.getScope().map(this::receiver).map(scope -> scope + "." + call).orElse(call);
    }
    return null;
  }

  private final class Flow {
    private final Set<String> relevant;
    private boolean truncated;

    Flow(Set<String> relevant) {
      this.relevant = relevant;
    }

    List<BranchState> at(MethodDeclaration method, Node target) {
      List<Node> path = new ArrayList<>();
      for (Node cursor = target; cursor != method; cursor = cursor.getParentNode().get()) {
        path.add(cursor);
      }
      path.add(method);
      Collections.reverse(path);
      List<BranchState> states = Collections.singletonList(new BranchState());
      for (int index = 1; index < path.size(); index++) {
        Node parent = path.get(index - 1);
        Node child = path.get(index);
        if (parent instanceof BlockStmt) {
          for (Statement preceding : ((BlockStmt) parent).getStatements()) {
            if (preceding == child) break;
            states = transfer(preceding, states);
          }
        } else if (parent instanceof IfStmt) {
          IfStmt conditional = (IfStmt) parent;
          if (conditional.getCondition() != child) {
            states = branch(conditional, states, conditional.getThenStmt() == child);
          }
        }
      }
      return states;
    }

    private List<BranchState> transfer(Statement statement, List<BranchState> states) {
      if (states.isEmpty() || statement.isReturnStmt() || statement.isThrowStmt()
          || statement.isBreakStmt() || statement.isContinueStmt()) {
        return Collections.emptyList();
      }
      if (statement.isBlockStmt()) {
        Set<String> locals = new HashSet<>();
        for (Statement child : statement.asBlockStmt().getStatements()) {
          if (child.isExpressionStmt() && child.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
            child.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables()
                .forEach(local -> locals.add(local.getNameAsString()));
          }
          states = transfer(child, states);
        }
        for (BranchState state : states) locals.forEach(state.variables::remove);
        return states;
      }
      if (statement.isIfStmt()) {
        IfStmt conditional = statement.asIfStmt();
        // Unrelated conditionals do not multiply the possible SQL values.
        if (!hasRelevantWrite(conditional) && conditional.findAll(Statement.class).stream()
            .noneMatch(child -> child.isReturnStmt() || child.isThrowStmt()
                || child.isBreakStmt() || child.isContinueStmt())) {
          return states;
        }
        List<BranchState> result = new ArrayList<>(transfer(conditional.getThenStmt(), branch(conditional, states, true)));
        List<BranchState> otherwise = branch(conditional, states, false);
        result.addAll(conditional.getElseStmt().isPresent()
            ? transfer(conditional.getElseStmt().get(), otherwise) : otherwise);
        if (result.size() > MAX_BRANCH_STATES) {
          truncated = true;
          return new ArrayList<>(result.subList(0, MAX_BRANCH_STATES));
        }
        return result;
      }
      if (statement.isExpressionStmt()) {
        Expression expression = statement.asExpressionStmt().getExpression();
        if (expression.isVariableDeclarationExpr()) {
          for (VariableDeclarator variable : expression.asVariableDeclarationExpr().getVariables()) {
            if (!relevant.contains(variable.getNameAsString()) || !isStringish(variable)) continue;
            for (BranchState state : states) {
              EvalResult value = variable.getInitializer().isPresent()
                  ? evaluate(variable.getInitializer().get(), state.variables) : null;
              state.variables.put(variable.getNameAsString(), value);
            }
          }
          return states;
        }
        if (expression.isAssignExpr()) {
          AssignExpr assignment = expression.asAssignExpr();
          String target = targetName(assignment);
          if (relevant.contains(target)) {
            for (BranchState state : states) {
              EvalResult value = evaluate(assignment.getValue(), state.variables);
              if (assignment.getOperator() == AssignExpr.Operator.PLUS) {
                value = concatenate(state.variables.get(target), value);
              } else if (assignment.getOperator() != AssignExpr.Operator.ASSIGN) {
                value = null;
              }
              state.variables.put(target, value);
            }
            return states;
          }
        }
      }
      // Unsupported control flow can change a tracked variable, so the earlier value no longer
      // proves a shape at a later call.
      forgetAssignments(statement, states);
      return states;
    }

    private List<BranchState> branch(IfStmt conditional, List<BranchState> states, boolean then) {
      Expression condition = conditional.getCondition();
      if (condition.isBooleanLiteralExpr()) {
        return condition.asBooleanLiteralExpr().getValue() == then ? states : Collections.emptyList();
      }
      Map<String, Object> selected = new LinkedHashMap<>();
      condition.getBegin().ifPresent(position -> {
        selected.put("line", position.line);
        selected.put("column", position.column);
      });
      selected.put("arm", then ? "then" : "else");
      List<BranchState> result = new ArrayList<>(states.size());
      for (BranchState state : states) {
        result.add(new BranchState(state, selected));
      }
      forgetAssignments(condition, result);
      return result;
    }

    private boolean hasRelevantWrite(Node statement) {
      return statement.findAll(AssignExpr.class).stream()
          .anyMatch(assignment -> relevant.contains(targetName(assignment)));
    }

    private void forgetAssignments(Node node, List<BranchState> states) {
      for (AssignExpr assignment : node.findAll(AssignExpr.class)) {
        for (BranchState state : states) {
          state.variables.remove(targetName(assignment));
        }
      }
    }
  }

  private static final class BranchState {
    private final Map<String, EvalResult> variables;
    private final List<Map<String, Object>> branches;

    BranchState() {
      variables = new HashMap<>();
      branches = new ArrayList<>();
    }

    BranchState(BranchState previous, Map<String, Object> selected) {
      variables = new HashMap<>(previous.variables);
      branches = new ArrayList<>(previous.branches);
      branches.add(selected);
    }
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
      return concatenate(evaluate(binary.getLeft(), variables), evaluate(binary.getRight(), variables));
    }
    return null;
  }

  private EvalResult concatenate(EvalResult left, EvalResult right) {
    if (left == null && right == null) {
      return null;
    }
    String leftSql = left == null ? "__DYNAMIC__" : left.sql();
    String rightSql = right == null ? "__DYNAMIC__" : right.sql();
    boolean dynamic = left == null || right == null || left.dynamic() || right.dynamic();
    return new EvalResult(leftSql + rightSql, dynamic);
  }

  private boolean looksLikeSql(String sql) {
    String normalized = stripLeading(sql).toUpperCase(Locale.ROOT);
    return normalized.startsWith("SELECT ")
        || normalized.startsWith("INSERT ")
        || normalized.startsWith("UPDATE ")
        || normalized.startsWith("DELETE ")
        || normalized.startsWith("WITH ")
        || normalized.startsWith("CALL ")
        || normalized.startsWith("{CALL ");
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

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof EvalResult)) return false;
      EvalResult result = (EvalResult) other;
      return dynamic == result.dynamic && sql.equals(result.sql);
    }

    @Override
    public int hashCode() {
      return Objects.hash(sql, dynamic);
    }
  }
}
