package com.acme.routesql.extract.mybatis;

import com.acme.routesql.extract.ExtractionContext;
import com.acme.routesql.extract.SqlExtractor;
import com.acme.routesql.model.SourceKind;
import com.acme.routesql.model.SqlObject;
import com.acme.routesql.model.SqlOrigin;
import com.acme.routesql.util.SqlObjects;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class MyBatisXmlExtractor implements SqlExtractor {
  private static final List<String> STATEMENT_TAGS = List.of("select", "insert", "update", "delete");

  @Override
  public String name() {
    return "mybatis-xml";
  }

  @Override
  public boolean supports(Path path) {
    return path.getFileName().toString().endsWith(".xml");
  }

  @Override
  public List<SqlObject> extract(Path path, ExtractionContext context) throws Exception {
    String xml = Files.readString(path);
    if (!xml.contains("<mapper")) {
      return List.of();
    }

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setNamespaceAware(false);
    factory.setIgnoringComments(true);
    Document document = factory.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    Element mapper = document.getDocumentElement();
    if (!"mapper".equals(mapper.getTagName())) {
      return List.of();
    }

    String namespace = mapper.getAttribute("namespace");
    Map<String, Element> fragments = collectFragments(mapper, namespace);
    List<SqlObject> objects = new ArrayList<>();
    for (Element statement : MyBatisSqlScriptBuilder.childElements(mapper)) {
      String tagName = statement.getTagName();
      if (!STATEMENT_TAGS.contains(tagName)) {
        continue;
      }
      String statementId = statement.getAttribute("id");
      MyBatisSqlScriptBuilder.BuildResult built =
          MyBatisSqlScriptBuilder.buildChildren(statement, fragments);
      String raw = context.normalizer().normalizeMyBatisParameters(built.sql());
      boolean dynamic = built.dynamic() || raw.contains("__DYNAMIC__");
      LineColumn location = locate(xml, tagName, statementId);
      SqlOrigin origin = new SqlOrigin(
          SourceKind.MYBATIS_XML,
          path.toAbsolutePath().normalize(),
          location.line(),
          location.column(),
          namespace,
          statementId,
          tagName.toUpperCase(Locale.ROOT),
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
        if (keyRaw.isBlank()) {
          continue;
        }
        boolean keyDynamic = keyBuilt.dynamic() || keyRaw.contains("__DYNAMIC__");
        LineColumn keyLocation = locateSelectKey(xml, location.line());
        SqlOrigin keyOrigin = new SqlOrigin(
            SourceKind.MYBATIS_XML,
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

  private Map<String, Element> collectFragments(Element mapper, String namespace) {
    Map<String, Element> fragments = new HashMap<>();
    for (Element child : MyBatisSqlScriptBuilder.childElements(mapper)) {
      if ("sql".equals(child.getTagName())) {
        String id = child.getAttribute("id");
        fragments.put(id, child);
        if (namespace != null && !namespace.isBlank()) {
          fragments.put(namespace + "." + id, child);
        }
      }
    }
    return fragments;
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

  private record LineColumn(int line, int column) {}
}
