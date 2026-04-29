package com.acme.routesql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public class ConfigPreprocessors {
  private final List<ConfigPreprocessor> preprocessors;

  public ConfigPreprocessors() {
    this(List.of(new RequiredColumnsJsonPreprocessor()));
  }

  public ConfigPreprocessors(List<ConfigPreprocessor> preprocessors) {
    this.preprocessors = preprocessors;
  }

  public JsonNode preprocess(JsonNode input, ObjectMapper mapper) {
    for (ConfigPreprocessor preprocessor : preprocessors) {
      if (preprocessor.supports(input)) {
        return preprocessor.preprocess(input, mapper);
      }
    }
    return input;
  }
}
