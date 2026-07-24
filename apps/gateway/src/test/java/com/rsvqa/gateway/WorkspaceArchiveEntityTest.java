package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.UserEntity;

class WorkspaceArchiveEntityTest {

    @Test
    void projectArchiveIsReversible() {
        ProjectEntity project = new ProjectEntity(new UserEntity("owner", "Owner", "USER", false), "项目");

        project.archive();
        assertThat(project.isArchived()).isTrue();

        project.restore();
        assertThat(project.isArchived()).isFalse();
    }

    @Test
    void conversationCanBeRenamedMovedArchivedAndRestored() {
        UserEntity owner = new UserEntity("owner", "Owner", "USER", false);
        ProjectEntity source = new ProjectEntity(owner, "来源");
        ProjectEntity destination = new ProjectEntity(owner, "目标");
        ConversationEntity conversation = new ConversationEntity(source, "原标题");

        conversation.rename("新标题");
        conversation.moveTo(destination);
        conversation.archive();

        assertThat(conversation.getTitle()).isEqualTo("新标题");
        assertThat(conversation.getProject()).isSameAs(destination);
        assertThat(conversation.isArchived()).isTrue();

        conversation.restore();
        assertThat(conversation.isArchived()).isFalse();
    }
}
