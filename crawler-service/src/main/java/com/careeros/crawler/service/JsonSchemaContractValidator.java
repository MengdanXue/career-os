package com.careeros.crawler.service;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class JsonSchemaContractValidator {
    private final Schema schema;

    public JsonSchemaContractValidator(Path schemaPath) throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.schema = registry.getSchema(Files.readString(schemaPath));
        this.schema.initializeValidators();
    }

    public void validate(String json) {
        List<com.networknt.schema.Error> errors = schema.validate(
                json,
                InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))
        );
        if (!errors.isEmpty()) {
            String message = errors.stream().map(Object::toString).collect(Collectors.joining(System.lineSeparator()));
            throw new IllegalArgumentException("JSON 未通过 Schema 校验:" + System.lineSeparator() + message);
        }
    }
}
