package com.careeros;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaServingTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();
    }

    @Test
    void forwardsOnlyBrowserRoutesToTheSinglePageApplication() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/opportunities/example-job"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void neverMasksBackendOrStaticResourceMisses() throws Exception {
        mvc.perform(get("/api/v1/not-a-resource"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/not-a-resource"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/assets/missing.js"))
            .andExpect(status().isNotFound());
    }
}
