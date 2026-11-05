package com.acme.routesql.extract.mybatis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Explicit SqlSessionFactory resource sets; directory proximity is not binding evidence. */
public final class MyBatisResourceContexts {
  public static final String VERSION = "mybatis-resource-context/v1";
  private final Map<String, Set<Path>> contexts;

  private MyBatisResourceContexts(Map<String, Set<Path>> contexts) {
    this.contexts = contexts;
  }

  @SuppressWarnings("unchecked")
  public static MyBatisResourceContexts load(Path file, Path projectRoot) throws Exception {
    if (projectRoot == null) throw new IllegalArgumentException("Mapper resource contexts require --project-root");
    if (!Files.isRegularFile(file) || Files.size(file) == 0 || Files.size(file) > 4 * 1024 * 1024) {
      throw new IllegalArgumentException("Mapper resource contexts must be a non-empty file of at most 4 MiB");
    }
    Object input = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .readValue(file.toFile(), Object.class);
    if (!(input instanceof Map)) throw new IllegalArgumentException("Mapper resource contexts must be an object");
    Map<?, ?> root = (Map<?, ?>) input;
    if (!VERSION.equals(root.get("version")) || !(root.get("contexts") instanceof List)) {
      throw new IllegalArgumentException("Expected " + VERSION + " contexts array");
    }
    Path base = projectRoot.toAbsolutePath().normalize();
    Map<String, Set<Path>> contexts = new LinkedHashMap<>();
    for (Object value : (List<?>) root.get("contexts")) {
      if (!(value instanceof Map)) throw new IllegalArgumentException("Mapper resource context must be an object");
      Map<?, ?> context = (Map<?, ?>) value;
      Object id = context.get("contextId");
      Object paths = context.get("resourcePaths");
      if (!(id instanceof String) || ((String) id).trim().isEmpty() || contexts.containsKey(id)
          || !(paths instanceof List) || ((List<?>) paths).isEmpty()) {
        throw new IllegalArgumentException("Mapper resource context requires a unique contextId and resourcePaths");
      }
      Set<Path> resources = new LinkedHashSet<>();
      for (Object path : (List<?>) paths) {
        if (!(path instanceof String) || ((String) path).trim().isEmpty()) {
          throw new IllegalArgumentException("Mapper resource paths must be non-empty strings");
        }
        Path relative = Paths.get((String) path);
        Path resolved = base.resolve(relative).normalize();
        if (relative.isAbsolute() || !resolved.startsWith(base) || !Files.isRegularFile(resolved)) {
          throw new IllegalArgumentException("Mapper resource path must identify an existing project-relative file: " + path);
        }
        resources.add(resolved);
      }
      contexts.put((String) id, resources);
    }
    return new MyBatisResourceContexts(contexts);
  }

  public List<Path> resources() {
    Set<Path> all = new LinkedHashSet<>();
    for (Set<Path> paths : contexts.values()) all.addAll(paths);
    return new ArrayList<>(all);
  }

  public Map<String, Object> capture(Element statement, Map<String, Element> fragments) {
    Path source = Paths.get(statement.getOwnerDocument().getDocumentURI()).toAbsolutePath().normalize();
    Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
    for (Map.Entry<String, Set<Path>> context : contexts.entrySet()) {
      if (!context.getValue().contains(source)) continue;
      Map<String, Element> scoped = new LinkedHashMap<>();
      for (Map.Entry<String, Element> fragment : fragments.entrySet()) {
        String uri = fragment.getValue().getOwnerDocument().getDocumentURI();
        if (uri != null && context.getValue().contains(Paths.get(uri).toAbsolutePath().normalize())) {
          scoped.put(fragment.getKey(), fragment.getValue());
        }
      }
      templates.put(context.getKey(), MyBatisTemplates.capture(statement, scoped));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    if (templates.isEmpty()) {
      result.put("format", "mybatis-template/v1");
      result.put("sourcePath", source.toString());
      result.put("status", "UNRESOLVED");
      result.put("error", "No explicit SqlSessionFactory resource context contains this statement");
    } else {
      Map<String, Object> first = templates.values().iterator().next();
      result.putAll(first);
      for (Map<String, Object> template : templates.size() == 1
          ? java.util.Collections.<Map<String, Object>>emptyList() : templates.values()) {
        if (!"READY".equals(template.get("status"))
            || !java.util.Objects.equals(first.get("scriptSha256"), template.get("scriptSha256"))) {
          result.put("status", "UNRESOLVED");
          result.put("error", "SqlSessionFactory contexts do not provide one resolved template; select a context explicitly");
          result.remove("script");
          result.remove("scriptSha256");
          break;
        }
      }
    }
    result.put("resourceContextIds", new ArrayList<>(templates.keySet()));
    if (templates.size() > 1) result.put("templatesByContext", templates);
    return result;
  }
}
