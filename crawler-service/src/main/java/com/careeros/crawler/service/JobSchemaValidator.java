package com.careeros.crawler.service;

import java.io.IOException;
import java.nio.file.Path;

public final class JobSchemaValidator {
    private final JsonSchemaContractValidator delegate;

    public JobSchemaValidator(Path schemaPath) throws IOException {
        this.delegate = new JsonSchemaContractValidator(schemaPath);
    }

    public void validate(String json) {
        delegate.validate(json);
    }
}
