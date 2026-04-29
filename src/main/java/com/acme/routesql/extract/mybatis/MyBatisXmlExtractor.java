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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
    for (Element statement : childElements(mapper)) {
      String tagName = statement.getTagName();
      if (!STATEMENT_TAGS.contains(tagName) && !"sql".equals(tagName)) {
        continue;
      }
      String statementId = statement.getAttribute("id");
      BuildResult built = buildChildren(statement, fragments);
      String raw = context.normalizer().normalizeMyBatisParameters(built.sql());
      boolean dynamic = built.dynamic() || raw.contains("__DYNAMIC__");
      String normalized = context.normalizer().normalize(raw);
      LineColumn location = locate(xml, tagName, statementId);
      SqlOrigin origin = new SqlOrigin(
          SourceKind.MYBATIS_XML,
          path.toAbsolutePath().normalize(),
          location.line(),
          location.column(),
          namespace,
          statementId,
          "sql".equals(tagName) ? "FRAGMENT" : tagName.toUpperCase(Locale.ROOT),
          null,
          null
      );
      objects.add(SqlObjects.create(
          raw,
          normalized,
          origin,
          dynamic,
          dynamic ? List.of("mybatis", "dynamic") : List.of("mybatis"),
          Map.of("extractor", name())
      ));
    }
    return objects;
  }

  private Map<String, Element> collectFragments(Element mapper, String namespace) {
    Map<String, Element> fragments = new HashMap<>();
    for (Element child : childElements(mapper)) {
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

  private BuildResult buildChildren(Element element, Map<String, Element> fragments) {
    StringBuilder sql = new StringBuilder();
    boolean dynamic = false;
    NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      BuildResult result = buildNode(nodes.item(i), fragments);
      sql.append(' ').append(result.sql());
      dynamic |= result.dynamic();
    }
    return new BuildResult(sql.toString(), dynamic);
  }

  private BuildResult buildNode(Node node, Map<String, Element> fragments) {
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      return new BuildResult(node.getTextContent(), false);
    }
    if (node.getNodeType() != Node.ELEMENT_NODE) {
      return new BuildResult("", false);
    }

    Element element = (Element) node;
    String tag = element.getTagName();
    return switch (tag) {
      case "include" -> include(element, fragments);
      case "where" -> keywordBlock("WHERE", stripLeadingBoolean(buildChildren(element, fragments).sql()), true);
      case "set" -> keywordBlock("SET", stripTrailingComma(buildChildren(element, fragments).sql()), true);
      case "trim" -> trim(element, fragments);
      case "foreach" -> new BuildResult("(__FOREACH__)", true);
      case "choose", "when", "otherwise", "if", "bind" -> {
        BuildResult children = buildChildren(element, fragments);
        yield new BuildResult(children.sql(), true);
      }
      default -> {
        BuildResult children = buildChildren(element, fragments);
        yield new BuildResult(children.sql(), true);
      }
    };
  }

  private BuildResult include(Element element, Map<String, Element> fragments) {
    Element fragment = fragments.get(element.getAttribute("refid"));
    if (fragment == null) {
      return new BuildResult(" __MISSING_INCLUDE__ ", true);
    }
    return buildChildren(fragment, fragments);
  }

  private BuildResult trim(Element element, Map<String, Element> fragments) {
    BuildResult children = buildChildren(element, fragments);
    String body = children.sql();
    String prefixOverrides = element.getAttribute("prefixOverrides");
    if (!prefixOverrides.isBlank()) {
      for (String token : prefixOverrides.split("\\|")) {
        body = body.replaceFirst("(?i)^\\s*" + Pattern.quote(token.trim()) + "\\b", " ");
      }
    }
    String suffixOverrides = element.getAttribute("suffixOverrides");
    if (!suffixOverrides.isBlank() && suffixOverrides.contains(",")) {
      body = stripTrailingComma(body);
    }
    String prefix = element.getAttribute("prefix");
    String suffix = element.getAttribute("suffix");
    return new BuildResult((prefix + " " + body + " " + suffix).trim(), true);
  }

  private BuildResult keywordBlock(String keyword, String body, boolean dynamic) {
    if (body == null || body.isBlank()) {
      return new BuildResult("", dynamic);
    }
    return new BuildResult(keyword + " " + body, dynamic);
  }

  private String stripLeadingBoolean(String sql) {
    return sql.replaceFirst("(?i)^\\s*(AND|OR)\\b", " ").trim();
  }

  private String stripTrailingComma(String sql) {
    return sql.replaceFirst(",\\s*$", "").trim();
  }

  private List<Element> childElements(Element parent) {
    List<Element> elements = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        elements.add((Element) node);
      }
    }
    return elements;
  }

  private LineColumn locate(String xml, String tagName, String id) {
    Pattern pattern = Pattern.compile("<" + tagName + "\\b[^>]*\\bid\\s*=\\s*['\"]" + Pattern.quote(id) + "['\"]");
    Matcher matcher = pattern.matcher(xml);
    if (!matcher.find()) {
      return new LineColumn(1, 1);
    }
    int line = 1;
    int column = 1;
    for (int i = 0; i < matcher.start(); i++) {
      if (xml.charAt(i) == '\n') {
        line++;
        column = 1;
      } else {
        column++;
      }
    }
    return new LineColumn(line, column);
  }

  private record BuildResult(String sql, boolean dynamic) {}

  private record LineColumn(int line, int column) {}
}
