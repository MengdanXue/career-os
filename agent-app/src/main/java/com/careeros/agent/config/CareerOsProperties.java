package com.careeros.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "career-os")
public record CareerOsProperties(Path samplesRoot, Path outputRoot, Path profilePath) {}
