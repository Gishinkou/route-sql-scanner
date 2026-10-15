package com.acme.routesql.extract.mybatis;

import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.SqlObjects;
import com.acme.routesql.util.Strings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class MyBatisXmlExtractor implements SqlExtractor {
  private static final List<String> STATEMENT_TAGS =
      Arrays.asList("select", "insert", "update", "delete");

  @Override
  public String name() {
    return "mybatis-xml";
  }

  @Override
  public boolean supports(Path path) {
    return path.getFileName().toString().endsWith(".xml");
  }

  public static Map<String, Element> collectGlobalFragments(List<Path> files) {
    Map<String, Element> global = new HashMap<>();
    for (Path file : files) {
      if (!file.getFileName().toString().endsWith(".xml")) {
        continue;
      }
      try {
        String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!xml.contains("<mapper") && !xml.contains("<sqlMap")) {
          continue;
        }
        Document doc = parseDocument(xml);
        if (IBatisSqlMapNormalizer.isIBatis(doc)) {
          doc = IBatisSqlMapNormalizer.normalize(doc, file);
        }
        Element mapper = doc.getDocumentElement();
        if (!"mapper".equals(mapper.getTagName())) {
          continue;
        }
        String namespace = mapper.getAttribute("namespace");
        for (Element child : MyBatisSqlScriptBuilder.childElements(mapper)) {
          if ("sql".equals(child.getTagName())) {
            String id = child.getAttribute("id");
            global.putIfAbsent(id, child);
            if (namespace != null && !namespace.isEmpty()) {
              global.putIfAbsent(namespace + "." + id, child);
            }
          }
        }
      } catch (Exception ignored) {
        // skip unparseable files
      }
    }
    return global;
  }

  @Override
  public List<SqlObject> extract(Path path, ExtractionContext context) throws Exception {
    String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    if (!xml.contains("<mapper") && !xml.contains("<sqlMap")) {
      return Collections.emptyList();
    }

    Document document = parseDocument(xml);
    boolean ibatis = IBatisSqlMapNormalizer.isIBatis(document);
    if (ibatis) {
      document = IBatisSqlMapNormalizer.normalize(document, path);
    }
    Element mapper = document.getDocumentElement();
    if (!"mapper".equals(mapper.getTagName())) {
      return Collections.emptyList();
    }

    String namespace = mapper.getAttribute("namespace");
    Map<String, Element> fragments = collectFragments(mapper, namespace, context);
    List<SqlObject> objects = new ArrayList<>();
    for (Element statement : MyBatisSqlScriptBuilder.childElements(mapper)) {
      String tagName = statement.getTagName();
      if (!STATEMENT_TAGS.contains(tagName) && !"statement".equals(tagName)) {
        continue;
      }
      String statementId = statement.getAttribute("id");
      MyBatisSqlScriptBuilder.BuildResult built =
          MyBatisSqlScriptBuilder.buildChildren(statement, fragments);
      String raw = context.normalizer().normalizeMyBatisParameters(built.sql());
      boolean dynamic = built.dynamic() || raw.contains("__DYNAMIC__")
          || raw.contains("__IBATIS_UNKNOWN_TAG_");
      String commandType = "statement".equals(tagName) ? inferCommandType(raw) : tagName.toUpperCase(Locale.ROOT);
      LineColumn location = locate(xml, tagName, statementId);
      SqlOrigin origin = new SqlOrigin(
          ibatis ? SourceKind.IBATIS_XML : SourceKind.MYBATIS_XML,
          path.toAbsolutePath().normalize(),
          location.line(),
          location.column(),
          namespace,
          statementId,
          commandType,
          null,
          null
      );
      objects.add(SqlObjects.create(raw, origin, dynamic));

      for (Element selectKey : MyBatisSqlScriptBuilder.childElements(statement)) {
        if (!"selectKey".equals(selectKey.getTagName())) {
          continue;
        }
        MyBatisSqlScriptBuilder.BuildResult keyBuilt =
            MyBatisSqlScriptBuilder.buildChildren(selectKey, fragments);
        String keyRaw = context.normalizer().normalizeMyBatisParameters(keyBuilt.sql());
        if (Strings.isBlank(keyRaw)) {
          continue;
        }
        boolean keyDynamic = keyBuilt.dynamic() || keyRaw.contains("__DYNAMIC__");
        LineColumn keyLocation = locateSelectKey(xml, location.line());
        SqlOrigin keyOrigin = new SqlOrigin(
            ibatis ? SourceKind.IBATIS_XML : SourceKind.MYBATIS_XML,
            path.toAbsolutePath().normalize(),
            keyLocation.line(),
            keyLocation.column(),
            namespace,
            statementId + "!selectKey",
            "SELECT",
            null,
            null
        );
        objects.add(SqlObjects.create(keyRaw, keyOrigin, keyDynamic));
      }
    }
    return objects;
  }

  private static Document parseDocument(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setNamespaceAware(false);
    factory.setIgnoringComments(true);
    return factory.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private Map<String, Element> collectFragments(Element mapper, String namespace, ExtractionContext context) {
    Map<String, Element> fragments = new HashMap<>(context.globalFragments());
    for (Element child : MyBatisSqlScriptBuilder.childElements(mapper)) {
      if ("sql".equals(child.getTagName())) {
        String id = child.getAttribute("id");
        fragments.put(id, child);
        if (namespace != null && !Strings.isBlank(namespace)) {
          fragments.put(namespace + "." + id, child);
        }
      }
    }
    return fragments;
  }

  private String inferCommandType(String sql) {
    Matcher matcher = Pattern.compile("(?i)^\\s*(SELECT|INSERT|UPDATE|DELETE)\\b").matcher(sql);
    return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
  }

  private LineColumn locate(String xml, String tagName, String id) {
    Pattern pattern = Pattern.compile("<" + tagName + "\\b[^>]*\\bid\\s*=\\s*['\"]" + Pattern.quote(id) + "['\"]");
    Matcher matcher = pattern.matcher(xml);
    if (!matcher.find()) {
      return new LineColumn(1, 1);
    }
    return offsetToLineColumn(xml, matcher.start());
  }

  private LineColumn locateSelectKey(String xml, int fromLine) {
    int offset = 0;
    int line = 1;
    while (line < fromLine && offset < xml.length()) {
      if (xml.charAt(offset) == '\n') {
        line++;
      }
      offset++;
    }
    Matcher matcher = Pattern.compile("<selectKey\\b").matcher(xml);
    if (!matcher.find(offset)) {
      return new LineColumn(fromLine, 1);
    }
    return offsetToLineColumn(xml, matcher.start());
  }

  private LineColumn offsetToLineColumn(String xml, int end) {
    int line = 1;
    int column = 1;
    for (int i = 0; i < end; i++) {
      if (xml.charAt(i) == '\n') {
        line++;
        column = 1;
      } else {
        column++;
      }
    }
    return new LineColumn(line, column);
  }

  private static final class LineColumn {
    private final int line;
    private final int column;

    LineColumn(int line, int column) {
      this.line = line;
      this.column = column;
    }

    int line() {
      return line;
    }

    int column() {
      return column;
    }
  }
}
