package com.acme.routesql.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.nio.file.Path;

public record SqlOrigin(
    SourceKind kind,
    @JsonSerialize(using = ToStringSerializer.class)
    Path file,
    int line,
    int column,
    String namespace,
    String statementId,
    String statementType,
    String className,
    String methodName
) {}
