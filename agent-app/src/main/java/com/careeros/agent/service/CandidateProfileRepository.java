package com.careeros.agent.service;

import com.careeros.agent.config.CareerOsProperties;
import com.careeros.agent.domain.CandidateProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;

@Repository
public class CandidateProfileRepository {
    private final CareerOsProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public CandidateProfileRepository(CareerOsProperties properties) {
        this.properties = properties;
    }

    public CandidateProfile load() throws IOException {
        if (!Files.isRegularFile(properties.profilePath())) {
            throw new IllegalStateException("Missing candidate profile: " + properties.profilePath().toAbsolutePath());
        }
        return mapper.readValue(properties.profilePath().toFile(), CandidateProfile.class);
    }
}
