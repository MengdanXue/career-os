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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class NetworkntProposalValidator implements ProposalValidator {
    private static final String SCHEMA_RESOURCE_PATTERN = "/schema/recruitment-extraction-proposal-%s.json";

    private final ObjectMapper objectMapper;
    /** 每个可读版本一份 schema：载荷按它自己声明的 schemaVersion 校验，而不是一律按当前版本。 */
    private final Map<String, Schema> schemasByVersion;

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
        Map<String, Schema> loaded = new LinkedHashMap<>();
        for (String version : RecruitmentExtractionProposal.SUPPORTED_SCHEMA_VERSIONS) {
            String resource = String.format(SCHEMA_RESOURCE_PATTERN, version);
            try (InputStream input = NetworkntProposalValidator.class.getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing schema resource " + resource);
                loaded.put(version, registry.getSchema(input));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not load proposal schema " + version, exception);
            }
        }
        this.schemasByVersion = Map.copyOf(loaded);
    }

    @Override
    public void validate(RecruitmentExtractionProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        validateNode(objectMapper.valueToTree(proposal), proposal.schemaVersion());
    }

    public JsonNode validateJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            validateNode(node, declaredVersion(node));
            return node;
        } catch (ExtractionExceptions.InvalidProposalException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ExtractionExceptions.InvalidProposalException("Invalid JSON: " + exception.getMessage());
        }
    }

    /** 版本先于结构校验：不认识的版本要给出明确原因，而不是几十条 schema 报错。 */
    private static String declaredVersion(JsonNode node) {
        JsonNode version = node == null ? null : node.get("schemaVersion");
        if (version == null || !version.isTextual()) {
            throw new ExtractionExceptions.InvalidProposalException("Proposal is missing a textual schemaVersion");
        }
        return version.asText();
    }

    private void validateNode(JsonNode node, String schemaVersion) {
        Schema schema = schemasByVersion.get(schemaVersion);
        if (schema == null) {
            throw new ExtractionExceptions.InvalidProposalException(
                "Unsupported proposal schemaVersion " + schemaVersion
                    + "; supported versions are " + new java.util.TreeSet<>(schemasByVersion.keySet()));
        }
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
