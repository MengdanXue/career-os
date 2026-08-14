package com.careeros.agent;

import com.careeros.agent.service.CareerOsAgentService;
import com.careeros.agent.service.CandidateProfileRepository;
import com.careeros.agent.tool.CareerOsTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "career-os.samples-root=../../output/career-os-samples",
        "career-os.output-root=target/agent-test-output",
        "career-os.profile-path=../config/candidate-profile.json"
})
class CareerOsAgentApplicationTest {
    @Autowired
    private CareerOsTools tools;

    @Autowired
    private CareerOsAgentService agent;

    @Autowired
    private CandidateProfileRepository profiles;

    @Test
    void loadsAgentWithSevenRegisteredToolsAndNoRequiredModelKey() throws Exception {
        assertNotNull(tools);
        assertEquals(7, tools.names().size());
        assertFalse(agent.modelAvailable());
        assertEquals(1992, profiles.load().birthDate().year());
    }
}
