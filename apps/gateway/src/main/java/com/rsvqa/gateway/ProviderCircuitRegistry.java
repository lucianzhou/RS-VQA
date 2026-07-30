package com.rsvqa.gateway;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ProviderCircuitRegistry {

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN_READY,
        HALF_OPEN_PROBE
    }

    record Key(String providerId, String modelId, ProviderWorkload workload) {
    }

    record Ticket(Key key, boolean halfOpenProbe) {
    }

    private final ProviderPolicyProperties.Circuit policy;
    private final Clock clock;
    private final Map<Key, Circuit> circuits = new ConcurrentHashMap<>();

    ProviderCircuitRegistry(ProviderPolicyProperties.Circuit policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    Ticket beforeCall(Key key) {
        Circuit circuit = circuits.computeIfAbsent(key, ignored -> new Circuit());
        synchronized (circuit) {
            Instant now = clock.instant();
            if (circuit.openUntil != null) {
                if (now.isBefore(circuit.openUntil)) {
                    throw new ProviderCircuitOpenException(
                            java.time.Duration.between(now, circuit.openUntil).toSeconds() + 1);
                }
                if (circuit.halfOpenProbe) {
                    throw new ProviderCircuitOpenException(1);
                }
                circuit.halfOpenProbe = true;
                return new Ticket(key, true);
            }
            return new Ticket(key, false);
        }
    }

    void success(Ticket ticket) {
        Circuit circuit = circuits.computeIfAbsent(ticket.key(), ignored -> new Circuit());
        synchronized (circuit) {
            circuit.failures = 0;
            circuit.openUntil = null;
            circuit.halfOpenProbe = false;
        }
    }

    void failure(Ticket ticket) {
        Circuit circuit = circuits.computeIfAbsent(ticket.key(), ignored -> new Circuit());
        synchronized (circuit) {
            circuit.halfOpenProbe = false;
            circuit.failures++;
            if (ticket.halfOpenProbe() || circuit.failures >= policy.failureThreshold()) {
                circuit.openUntil = clock.instant().plus(policy.openDuration());
            }
        }
    }

    void ignored(Ticket ticket) {
        if (!ticket.halfOpenProbe()) {
            return;
        }
        Circuit circuit = circuits.computeIfAbsent(ticket.key(), ignored -> new Circuit());
        synchronized (circuit) {
            circuit.halfOpenProbe = false;
            circuit.openUntil = null;
            circuit.failures = 0;
        }
    }

    void cancelled(Ticket ticket) {
        if (!ticket.halfOpenProbe()) {
            return;
        }
        Circuit circuit = circuits.computeIfAbsent(ticket.key(), ignored -> new Circuit());
        synchronized (circuit) {
            circuit.halfOpenProbe = false;
        }
    }

    State state(Key key) {
        Circuit circuit = circuits.get(key);
        if (circuit == null) {
            return State.CLOSED;
        }
        synchronized (circuit) {
            if (circuit.openUntil == null) {
                return State.CLOSED;
            }
            if (clock.instant().isBefore(circuit.openUntil)) {
                return State.OPEN;
            }
            return circuit.halfOpenProbe ? State.HALF_OPEN_PROBE : State.HALF_OPEN_READY;
        }
    }

    private static final class Circuit {
        private int failures;
        private Instant openUntil;
        private boolean halfOpenProbe;
    }
}
