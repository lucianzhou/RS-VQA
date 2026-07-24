package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rsvqa.gateway.domain.AgentActionProposalEntity;
import com.rsvqa.gateway.domain.UserEntity;

class AgentActionProposalEntityTest {

    @Test
    void anExpiredProposalCannotEnterExecution() {
        AgentActionProposalEntity proposal = new AgentActionProposalEntity(
                mock(UserEntity.class), null, "archive_project", "{}", "归档项目",
                UUID.randomUUID().toString(), Instant.now().minusSeconds(1)
        );

        assertThatThrownBy(proposal::confirm)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ACTION_PROPOSAL_EXPIRED");
        assertThat(proposal.getStatus()).isEqualTo("EXPIRED");
    }
}
