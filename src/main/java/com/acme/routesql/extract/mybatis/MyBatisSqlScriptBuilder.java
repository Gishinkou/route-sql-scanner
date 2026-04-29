package com.acme.routesql.extract.mybatis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class MyBatisSqlScriptBuilder {
  private MyBatisSqlScriptBuilder() {}

  static BuildResult buildChildren(Element element, Map<String, Element> fragments) {
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

  static BuildResult buildAnnotationScript(String sql) {
    String trimmed = sql.trim();
    if (!looksLikeXmlScript(trimmed)) {
      return new BuildResult(sql, false);
    }
    try {
      String wrapped = trimmed.matches("(?is)^\\s*<script\\b.*")
          ? trimmed
          : "<script>" + trimmed + "</script>";
      Document document = newDocument(wrapped);
      Element script = document.getDocumentElement();
      return new BuildResult(buildChildren(script, Map.of()).sql(), true);
    } catch (Exception ignored) {
      return new BuildResult(stripDynamicTags(sql), true);
    }
  }

  static List<Element> childElements(Element parent) {
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

  private static BuildResult buildNode(Node node, Map<String, Element> fragments) {
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
      case "script", "choose", "when", "otherwise", "if", "bind" -> {
        BuildResult children = buildChildren(element, fragments);
        yield new BuildResult(children.sql(), true);
      }
      default -> {
        BuildResult children = buildChildren(element, fragments);
        yield new BuildResult(children.sql(), true);
      }
    };
  }

  private static BuildResult include(Element element, Map<String, Element> fragments) {
    Element fragment = fragments.get(element.getAttribute("refid"));
    if (fragment == null) {
      return new BuildResult(" __MISSING_INCLUDE__ ", true);
    }
    return buildChildren(fragment, fragments);
  }

  private static BuildResult trim(Element element, Map<String, Element> fragments) {
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

  private static BuildResult keywordBlock(String keyword, String body, boolean dynamic) {
    if (body == null || body.isBlank()) {
      return new BuildResult("", dynamic);
    }
    return new BuildResult(keyword + " " + body, dynamic);
  }

  private static String stripLeadingBoolean(String sql) {
    return sql.replaceFirst("(?i)^\\s*(AND|OR)\\b", " ").trim();
  }

  private static String stripTrailingComma(String sql) {
    return sql.replaceFirst(",\\s*$", "").trim();
  }

  private static boolean looksLikeXmlScript(String sql) {
    return sql.matches("(?is).*<\\s*(script|where|set|trim|foreach|choose|when|otherwise|if|bind)\\b.*");
  }

  private static String stripDynamicTags(String sql) {
    return sql
        .replaceAll("(?is)</?script\\b[^>]*>", " ")
        .replaceAll("(?is)</?(if|choose|when|otherwise|bind)\\b[^>]*>", " ")
        .replaceAll("(?is)<where\\b[^>]*>", " WHERE ")
        .replaceAll("(?is)</where>", " ")
        .replaceAll("(?is)<set\\b[^>]*>", " SET ")
        .replaceAll("(?is)</set>", " ")
        .replaceAll("(?is)</?trim\\b[^>]*>", " ")
        .replaceAll("(?is)<foreach\\b[^>]*>.*?</foreach>", " (__FOREACH__) ");
  }

  private static Document newDocument(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setNamespaceAware(false);
    factory.setIgnoringComments(true);
    return factory.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  record BuildResult(String sql, boolean dynamic) {}
}
