package com.acme.routesql.parse;

import com.acme.routesql.model.SqlParseResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

@SuppressWarnings({"deprecation", "unchecked"})
public class SqlParserFacade {
  private static final Pattern TABLE_PATTERN = Pattern.compile(
      "(?i)\\b(?:from|join|update|into)\\s+`?([a-zA-Z_][\\w$]*)`?");
  private static final Pattern COLUMN_PATTERN = Pattern.compile(
      "(?i)(?:^|[^.\\w])`?([a-zA-Z_][\\w$]*)`?\\s*(?:=|>|<|>=|<=|<>|!=|\\bin\\b|\\blike\\b)");

  private final String dialect;

  public SqlParserFacade(String dialect) {
    this.dialect = dialect == null ? "mysql" : dialect;
  }

  public SqlParseResult parse(String sql) {
    try {
      Statement statement = CCJSqlParserUtil.parse(sql);
      String statementType = statementType(statement);
      List<String> tables = tables(statement, sql);
      List<String> columns = columns(statement, sql);
      return new SqlParseResult(true, dialect, statementType, tables, columns, statement, null);
    } catch (Exception e) {
      return new SqlParseResult(
          false,
          dialect,
          statementTypeByText(sql),
          fallbackTables(sql),
          fallbackColumns(sql),
          null,
          e.getMessage()
      );
    }
  }

  private String statementType(Statement statement) {
    if (statement instanceof Select) {
      return "SELECT";
    }
    if (statement instanceof Insert) {
      return "INSERT";
    }
    if (statement instanceof Update) {
      return "UPDATE";
    }
    if (statement instanceof Delete) {
      return "DELETE";
    }
    return statementTypeByText(statement.toString());
  }

  private List<String> tables(Statement statement, String sql) {
    try {
      TablesNamesFinder finder = new TablesNamesFinder();
      return normalizeNames(finder.getTableList(statement));
    } catch (Exception e) {
      return fallbackTables(sql);
    }
  }

  private List<String> columns(Statement statement, String sql) {
    Set<String> names = new LinkedHashSet<>();
    names.addAll(fallbackColumns(sql));
    return new ArrayList<>(names);
  }

  private List<String> fallbackTables(String sql) {
    Set<String> tables = new LinkedHashSet<>();
    Matcher matcher = TABLE_PATTERN.matcher(sql);
    while (matcher.find()) {
      tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }
    return new ArrayList<>(tables);
  }

  private List<String> fallbackColumns(String sql) {
    Set<String> columns = new LinkedHashSet<>();
    Matcher matcher = COLUMN_PATTERN.matcher(sql);
    while (matcher.find()) {
      columns.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }
    return new ArrayList<>(columns);
  }

  private List<String> normalizeNames(List<String> names) {
    return names.stream()
        .map(name -> name.replace("`", "").toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  private String statementTypeByText(String sql) {
    String normalized = sql == null ? "" : sql.stripLeading().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("SELECT") || normalized.startsWith("WITH")) {
      return "SELECT";
    }
    if (normalized.startsWith("INSERT")) {
      return "INSERT";
    }
    if (normalized.startsWith("UPDATE")) {
      return "UPDATE";
    }
    if (normalized.startsWith("DELETE")) {
      return "DELETE";
    }
    return "UNKNOWN";
  }
}
