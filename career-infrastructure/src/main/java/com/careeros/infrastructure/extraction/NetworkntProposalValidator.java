package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.ProposalValidator;
import com.careeros.domain.RecruitmentExtractionProposal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class NetworkntProposalValidator implements ProposalValidator {
    private static final String SCHEMA_RESOURCE = "/schema/recruitment-extraction-proposal-1.0.0.json";

    private final ObjectMapper objectMapper;
    private final Schema schema;

    public NetworkntProposalValidator() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public NetworkntProposalValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
            .copy()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SchemaRegistryConfig config = new SchemaRegistryConfig.Builder()
            .formatAssertionsEnabled(true)
            .build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(config));
        try (InputStream input = NetworkntProposalValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing schema resource " + SCHEMA_RESOURCE);
            this.schema = registry.getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load proposal schema", exception);
        }
    }

    @Override
    public void validate(RecruitmentExtractionProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        validateNode(objectMapper.valueToTree(proposal));
    }

    public JsonNode validateJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            validateNode(node);
            return node;
        } catch (ExtractionExceptions.InvalidProposalException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ExtractionExceptions.InvalidProposalException("Invalid JSON: " + exception.getMessage());
        }
    }

    private void validateNode(JsonNode node) {
        List<com.networknt.schema.Error> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                .map(error -> error.getInstanceLocation() + " " + error.getKeyword() + ": " + error.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
            throw new ExtractionExceptions.InvalidProposalException("Proposal schema validation failed: " + detail);
        }
    }
}
