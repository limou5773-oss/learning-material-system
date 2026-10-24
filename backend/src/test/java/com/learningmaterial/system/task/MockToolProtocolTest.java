package com.learningmaterial.system.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockToolProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsUnknownToolsExtraArgumentsAndCrossMaterialReferences() {
        AiTaskRow task = new AiTaskRow();
        task.setId(11L);
        task.setMaterialId(22L);
        task.setMaterialVersion(3);
        task.setGoalType("REVIEW_CARDS");

        MockToolProtocol.validate("prepare_source", json.createObjectNode()
            .put("materialId", "22").put("materialVersion", 3), task);
        MockToolProtocol.validate("generate_summary_cards", json.createObjectNode()
            .put("goalType", "REVIEW_CARDS").set("chunkIds", json.createArrayNode().add("7")), task);

        assertThatThrownBy(() -> MockToolProtocol.validate("delete_material", json.createObjectNode(), task))
            .isInstanceOf(TaskExecutionException.class).hasMessage("INVALID_TOOL_ARGUMENTS");
        assertThatThrownBy(() -> MockToolProtocol.validate("prepare_source", json.createObjectNode()
            .put("materialId", "23").put("materialVersion", 3), task))
            .isInstanceOf(TaskExecutionException.class).hasMessage("INVALID_TOOL_ARGUMENTS");
        assertThatThrownBy(() -> MockToolProtocol.validate("prepare_source", json.createObjectNode()
            .put("materialId", "22").put("materialVersion", 3).put("userId", 1), task))
            .isInstanceOf(TaskExecutionException.class).hasMessage("INVALID_TOOL_ARGUMENTS");
        assertThatThrownBy(() -> MockToolProtocol.validate("generate_summary_cards", json.createObjectNode()
            .put("goalType", "REVIEW_CARDS")
            .set("chunkIds", json.createArrayNode().add("7").add("7")), task))
            .isInstanceOf(TaskExecutionException.class).hasMessage("INVALID_TOOL_ARGUMENTS");
    }
}
