package com.acme.routesql.extract.mybatis;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Converts an iBATIS 2 SQL Map DOM to the small MyBatis-compatible DOM consumed by the scanner. */
final class IBatisSqlMapNormalizer {
  private static final Set<String> STATEMENT_TAGS = new HashSet<>(Arrays.asList(
      "select", "insert", "update", "delete", "statement", "procedure"));
  private static final Set<String> FRAGMENT_TAGS = new HashSet<>(Arrays.asList("sql"));
  private static final Set<String> DOCUMENT_METADATA = new HashSet<>(Arrays.asList(
      "typeAlias", "resultMap", "result", "parameterMap", "cacheModel"));
  private static final Set<String> CONDITIONALS = new HashSet<>(Arrays.asList(
      "isNull", "isNotNull", "isEqual", "isNotEqual", "isGreaterThan",
      "isGreaterEqual", "isLessThan", "isLessEqual", "isEmpty", "isNotEmpty",
      "isParameterPresent", "isNotParameterPresent", "isPropertyAvailable",
      "isNotPropertyAvailable"));
  private static final Set<String> KNOWN_PASSTHROUGH = new HashSet<>(Arrays.asList(
      "mapper", "select", "insert", "update", "delete", "statement", "sql", "include",
      "if", "choose", "when", "otherwise", "foreach", "where", "set", "trim", "bind",
      "selectKey"));
  private static final Pattern HASH_PARAMETER = Pattern.compile("#([^#]+)#");
  private static final Pattern DOLLAR_PARAMETER = Pattern.compile("\\$([^$]+)\\$");
  private static final Pattern ITERATE_PARAMETER = Pattern.compile("([#$])([^#$]*?)\\[\\]([^#$]*?)\\1");

  private IBatisSqlMapNormalizer() {}

  static boolean isIBatis(Document document) {
    return document != null && document.getDocumentElement() != null
        && "sqlMap".equals(document.getDocumentElement().getTagName());
  }

  static Document normalize(Document document, Path path) {
    Element root = document.getDocumentElement();
    document.renameNode(root, null, "mapper");
    normalizeDocumentChildren(document, root, path);
    return document;
  }

  /**
   * Traverses only SQL-bearing top-level nodes. iBATIS metadata may contain text and nested
   * elements, but none of it is executable SQL and it must never reach the script builder.
   */
  private static void normalizeDocumentChildren(Document document, Element root, Path path) {
    NodeList nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
        // Formatting between document-level declarations is not SQL.
        node.setNodeValue("");
        continue;
      }
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element element = (Element) node;
      String tag = element.getTagName();
      if (DOCUMENT_METADATA.contains(tag) || (!STATEMENT_TAGS.contains(tag) && !FRAGMENT_TAGS.contains(tag))) {
        if (!DOCUMENT_METADATA.contains(tag)) {
          warn(path, null, tag, "unknown document-level iBATIS element was skipped");
        }
        root.removeChild(element);
        i--;
        continue;
      }
      String statementId = element.getAttribute("id");
      if ("procedure".equals(tag)) {
        warn(path, statementId, tag, "stored procedures are unsupported and were skipped");
        root.removeChild(element);
        i--;
        continue;
      }
      if (STATEMENT_TAGS.contains(tag)) {
        Map<String, Integer> conditionUses = new HashMap<>();
        if (collectConditionUses(element, conditionUses)) {
          normalizeComplementaryConditions(document, element, conditionUses);
        }
      }
      normalizeSqlChildren(document, element, path, statementId);
    }
  }

  /** Includes and opaque expressions can hide another use of a condition outside this DOM. */
  private static boolean collectConditionUses(Element parent, Map<String, Integer> uses) {
    for (Element child : MyBatisSqlScriptBuilder.childElements(parent)) {
      String tag = child.getTagName();
      if ("include".equals(tag) || "if".equals(tag) || "when".equals(tag)
          || (!CONDITIONALS.contains(tag) && !KNOWN_PASSTHROUGH.contains(tag)
              && !"dynamic".equals(tag) && !"iterate".equals(tag))) {
        return false;
      }
      if (CONDITIONALS.contains(tag)) {
        for (String attribute : Arrays.asList("property", "compareProperty")) {
          String property = child.getAttribute(attribute);
          if (!property.isEmpty()) {
            uses.put(property, uses.getOrDefault(property, 0) + 1);
          }
        }
      }
      if (!collectConditionUses(child, uses)) {
        return false;
      }
    }
    return true;
  }

  /** Only isolated, adjacent null/not-null pairs can safely use independent choose markers. */
  private static void normalizeComplementaryConditions(
      Document document, Element parent, Map<String, Integer> conditionUses) {
    for (Element first : MyBatisSqlScriptBuilder.childElements(parent)) {
      if (first.getParentNode() != parent) {
        continue;
      }
      Element second = nextAdjacentElement(first);
      String property = first.getAttribute("property");
      boolean complementary = second != null
          && (("isNull".equals(first.getTagName()) && "isNotNull".equals(second.getTagName()))
              || ("isNotNull".equals(first.getTagName()) && "isNull".equals(second.getTagName())));
      if (complementary && !property.trim().isEmpty()
          && property.equals(second.getAttribute("property"))
          && Integer.valueOf(2).equals(conditionUses.get(property))
          && isPlainCondition(first) && isPlainCondition(second)) {
        Element choose = document.createElement("choose");
        parent.insertBefore(choose, first);
        String test = condition(first.getTagName(), first);
        document.renameNode(first, null, "when");
        first.removeAttribute("property");
        first.setAttribute("test", test);
        document.renameNode(second, null, "otherwise");
        second.removeAttribute("property");
        choose.appendChild(first);
        choose.appendChild(second);
      } else {
        normalizeComplementaryConditions(document, first, conditionUses);
      }
    }
  }

  private static Element nextAdjacentElement(Element element) {
    for (Node node = element.getNextSibling(); node != null; node = node.getNextSibling()) {
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        return (Element) node;
      }
      if (node.getNodeType() != Node.COMMENT_NODE
          && !((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
              && node.getNodeValue().trim().isEmpty())) {
        return null;
      }
    }
    return null;
  }

  private static boolean isPlainCondition(Element element) {
    // prepend/open/close and nested tags need their own runtime semantics; leave them unchanged.
    if (element.getAttributes().getLength() != 1 || element.getTextContent().trim().isEmpty()) {
      return false;
    }
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node.getNodeType() != Node.TEXT_NODE && node.getNodeType() != Node.CDATA_SECTION_NODE
          && node.getNodeType() != Node.COMMENT_NODE) {
        return false;
      }
    }
    return true;
  }

  private static void normalizeSqlChildren(Document document, Element parent, Path path, String statementId) {
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
        node.setNodeValue(normalizeParameters(node.getNodeValue()));
        continue;
      }
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element element = (Element) node;
      String tag = element.getTagName();
      if ("procedure".equals(tag)) {
        warn(path, statementId, tag, "stored procedures are unsupported and were skipped");
        parent.removeChild(element);
        i--;
        continue;
      }
      if ("dynamic".equals(tag)) {
        String prepend = element.getAttribute("prepend");
        document.renameNode(element, null, "trim");
        // iBATIS only removes the first child's prepend when the dynamic tag supplies one.
        if (!prepend.isEmpty()) {
          element.setAttribute("prefixOverrides", "AND|OR|,");
        }
        if ("WHERE".equalsIgnoreCase(prepend)) {
          element.setAttribute("prefix", "WHERE");
        } else if (!prepend.isEmpty()) {
          element.setAttribute("prefix", prepend);
        }
        element.removeAttribute("prepend");
      } else if (CONDITIONALS.contains(tag)) {
        String prepend = element.getAttribute("prepend");
        document.renameNode(element, null, "if");
        element.setAttribute("test", condition(tag, element));
        element.removeAttribute("prepend");
        if (!prepend.isEmpty()) {
          element.insertBefore(document.createTextNode(" " + prepend + " "), element.getFirstChild());
        }
      } else if ("iterate".equals(tag)) {
        normalizeIterate(document, element);
      } else if (!KNOWN_PASSTHROUGH.contains(tag)) {
        warn(path, statementId, tag, "unknown iBATIS tag; body was discarded");
        Node marker = document.createTextNode(" __IBATIS_UNKNOWN_TAG_" + markerName(tag) + "__ ");
        parent.replaceChild(marker, element);
        continue;
      }
      normalizeSqlChildren(document, element, path, statementId);
    }
  }

  private static void normalizeIterate(Document document, Element element) {
    String property = element.getAttribute("property");
    String prepend = element.getAttribute("prepend");
    document.renameNode(element, null, "foreach");
    element.setAttribute("collection", property);
    element.setAttribute("item", "__ibatis_item");
    if (element.hasAttribute("conjunction")) {
      element.setAttribute("separator", element.getAttribute("conjunction"));
      element.removeAttribute("conjunction");
    }
    element.removeAttribute("property");
    element.removeAttribute("prepend");
    if (!prepend.isEmpty()) {
      element.insertBefore(document.createTextNode(" " + prepend + " "), element.getFirstChild());
    }
    replaceIterateParameters(element);
  }

  private static void replaceIterateParameters(Node parent) {
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
        Matcher matcher = ITERATE_PARAMETER.matcher(node.getNodeValue());
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
          String replacement = matcher.group(1) + "__ibatis_item" + matcher.group(3) + matcher.group(1);
          matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        node.setNodeValue(result.toString());
      } else if (node.getNodeType() == Node.ELEMENT_NODE) {
        replaceIterateParameters(node);
      }
    }
  }

  private static String condition(String tag, Element element) {
    String property = element.getAttribute("property");
    String right = element.hasAttribute("compareProperty")
        ? element.getAttribute("compareProperty") : quote(element.getAttribute("compareValue"));
    if ("isNull".equals(tag)) return property + " == null";
    if ("isNotNull".equals(tag)) return property + " != null";
    if ("isEqual".equals(tag)) return property + " == " + right;
    if ("isNotEqual".equals(tag)) return property + " != " + right;
    if ("isGreaterThan".equals(tag)) return property + " > " + right;
    if ("isGreaterEqual".equals(tag)) return property + " >= " + right;
    if ("isLessThan".equals(tag)) return property + " < " + right;
    if ("isLessEqual".equals(tag)) return property + " <= " + right;
    if ("isEmpty".equals(tag)) return property + " == null || " + property + ".isEmpty()";
    if ("isNotEmpty".equals(tag)) return property + " != null && !" + property + ".isEmpty()";
    if ("isParameterPresent".equals(tag)) return "_parameter != null";
    if ("isNotParameterPresent".equals(tag)) return "_parameter == null";
    if ("isPropertyAvailable".equals(tag)) return "__ibatis_property_available_" + property;
    return "__ibatis_property_unavailable_" + property;
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "\\'") + "'";
  }

  private static String normalizeParameters(String text) {
    if (text == null || text.isEmpty()) return text;
    String normalized = replace(HASH_PARAMETER, text, "#{", "}");
    return replace(DOLLAR_PARAMETER, normalized, "${", "}");
  }

  private static String replace(Pattern pattern, String text, String open, String close) {
    Matcher matcher = pattern.matcher(text);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String token = matcher.group(1).trim();
      int comma = token.indexOf(',');
      String property = comma < 0 ? token : token.substring(0, comma).trim();
      matcher.appendReplacement(result, Matcher.quoteReplacement(open + property + close));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static String markerName(String tag) {
    return tag == null ? "UNKNOWN" : tag.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase();
  }

  private static void warn(Path path, String statementId, String tag, String reason) {
    System.err.println("warn: framework=iBATIS stage=IBATIS_NORMALIZATION file=" + path
        + " statement=" + (statementId == null ? "" : statementId)
        + " tag=<" + tag + "> reason=" + reason);
  }
}
