package com.careeros.application;

import java.util.Optional;
import java.util.UUID;

public final class AgentSessionPorts {
    private AgentSessionPorts() {}

    public interface Sessions {
        Optional<AgentSession> find(UUID sessionId);
        AgentSession save(AgentSession session);
        /** Atomically replace only the exact candidate-owned session observation. */
        boolean compareAndSet(AgentSession expected, AgentSession replacement);
    }
}
