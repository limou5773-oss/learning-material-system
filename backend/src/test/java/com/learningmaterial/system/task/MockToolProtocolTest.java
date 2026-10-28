package com.learningmaterial.system.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.entity.AiTaskRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockToolProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void pdfExtractionRequiresExactTaskPageRangeAndIdentity() throws Exception {
        AiTaskRow task = new AiTaskRow(); task.setId(11L); task.setMaterialId(22L); task.setMaterialVersion(3);
        task.setGoalType("REVIEW_CARDS");
        task.setConfigJson("{\"inputSnapshot\":{\"type\":\"PDF\"},\"pageRange\":{\"from\":2,\"to\":4}}");
        var valid = json.readTree("{\"materialId\":\"22\",\"materialVersion\":3,\"pageRange\":{\"from\":2,\"to\":4}}");
        assertThatCode(() -> MockToolProtocol.validate("extract_pdf_text", valid, task)).doesNotThrowAnyException();
        for (String bad : new String[] {
            "{\"materialId\":\"23\",\"materialVersion\":3,\"pageRange\":{\"from\":2,\"to\":4}}",
            "{\"materialId\":\"22\",\"materialVersion\":4294967299,\"pageRange\":{\"from\":2,\"to\":4}}",
            "{\"materialId\":\"22\",\"materialVersion\":3,\"pageRange\":{\"from\":1,\"to\":4}}",
            "{\"materialId\":\"22\",\"materialVersion\":3,\"pageRange\":{\"from\":2,\"to\":4,\"path\":\"C:/private\"}}",
            "{\"materialId\":\"22\",\"materialVersion\":3,\"pageRange\":{\"from\":\"2\",\"to\":4}}",
            "{\"materialId\":\"22\",\"materialVersion\":3,\"pageRange\":{\"from\":2,\"to\":24}}"
        }) {
            var args = json.readTree(bad);
            assertThatThrownBy(() -> MockToolProtocol.validate("extract_pdf_text", args, task))
                .isInstanceOf(TaskExecutionException.class).hasMessage("INVALID_TOOL_ARGUMENTS");
        }
        var args = json.createObjectNode().put("goalType", "REVIEW_CARDS");
        var ids = args.putArray("chunkIds"); for (int i = 1; i <= 30; i++) ids.add(Integer.toString(i));
        assertThatCode(() -> MockToolProtocol.validate("generate_summary_cards", args, task)).doesNotThrowAnyException();
        ids.add("31");
        assertThatThrownBy(() -> MockToolProtocol.validate("generate_summary_cards", args, task))
            .isInstanceOf(TaskExecutionException.class).hasMessage("INVALID_TOOL_ARGUMENTS");
    }

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
