package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CanonicalUriTest {
    @ParameterizedTest
    @CsvSource({
        "HTTPS://HRSS.HANGZHOU.GOV.CN:443/a#top, https://hrss.hangzhou.gov.cn/a",
        "https://host/path?b=2&a=1&utm_source=x, https://host/path?a=1&b=2",
        "https://host, https://host/",
        "http://HOST:80/a/../b?from=feed&id=7, http://host/b?id=7"
    })
    void normalizesWithoutDroppingBusinessParameters(String raw, String expected) {
        assertThat(CanonicalUri.normalize(URI.create(raw))).hasToString(expected);
    }
}
