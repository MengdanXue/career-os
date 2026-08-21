package com.careeros.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OfficialWorkbookIdentityTest {
    @Test
    void ignoresOpaqueRotatingTokensButKeepsStableNestedObjectPaths() {
        String base = "https://example.gov.cn/download?fileName=jobs.xlsx&fileUrl=";
        assertThat(OfficialWorkbookIdentity.of(base + "token-one"))
            .isEqualTo(OfficialWorkbookIdentity.of(base + "token-two"));

        String first = URLEncoder.encode("https://storage.example/a/jobs.xlsx?signature=one", StandardCharsets.UTF_8);
        String refreshed = URLEncoder.encode("https://storage.example/a/jobs.xlsx?signature=two", StandardCharsets.UTF_8);
        String second = URLEncoder.encode("https://storage.example/b/jobs.xlsx?signature=one", StandardCharsets.UTF_8);
        assertThat(OfficialWorkbookIdentity.of(base + first))
            .isEqualTo(OfficialWorkbookIdentity.of(base + refreshed))
            .isNotEqualTo(OfficialWorkbookIdentity.of(base + second));
    }
}
