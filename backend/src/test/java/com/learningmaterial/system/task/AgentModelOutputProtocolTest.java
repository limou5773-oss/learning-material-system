package com.learningmaterial.system.task;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

class AgentModelOutputProtocolTest {
    final ObjectMapper json = new ObjectMapper();
    final ModelOutputProtocol protocol = new ModelOutputProtocol(json);
    final AiTaskRow task = new AiTaskRow();
    final MaterialChunkRow chunk = new MaterialChunkRow();
    final AiTaskDtos.GenerationInfo info =
            new AiTaskDtos.GenerationInfo("deepseek", "deepseek-flash", "d16-v1");

    AgentModelOutputProtocolTest() {
        task.setId(11L);
        task.setUserId(7L);
        task.setMaterialId(5L);
        task.setMaterialVersion(1);
        chunk.setId(17L);
        chunk.setMaterialId(5L);
        chunk.setMaterialVersion(1);
        chunk.setContent("自编测试原文😀");
    }

    TaskSourcePreparation.Prepared prepared() {
        return new TaskSourcePreparation.Prepared(List.of(chunk), null);
    }

    String body(String relations) {
        return "{\"summary\":\"摘要\",\"tags\":[],\"cards\":[{\"title\":\"卡片\",\"content\":\"内容\",\"summary\":null,\"subject\":null,\"tags\":[],\"sources\":[{\"chunkId\":\"17\",\"quoteText\":\"原文😀\"}]}],\"relations\":"
                + relations
                + "}";
    }

    List<PersonalMaterialSearchService.Match> matches() {
        return List.of(new PersonalMaterialSearchService.Match("41", 3, "已有卡片", "可见参考", false));
    }

    @Test
    void serverOwnsRelationKeysVersionsAndSourceOffsets() {
        var result =
                protocol.parseAgent(
                        body(
                                "[{\"sourceIndex\":0,\"targetCardId\":\"41\",\"evidenceText\":\"建议关联\"}]"),
                        task,
                        prepared(),
                        info,
                        matches());
        var relation = result.relations().get(0);
        assertThat(relation.source().candidateKey()).isEqualTo("model-1");
        assertThat(relation.target().cardId()).isEqualTo("41");
        assertThat(relation.target().cardVersion()).isEqualTo(3);
        assertThat(relation.type()).isEqualTo("RELATED");
        assertThat(result.cards().get(0).sources().get(0).startOffset()).isEqualTo(4);
    }

    @Test
    void unknownTargetsAndModelSuppliedVersionsAreRejected() {
        for (String relation :
                List.of(
                        "{\"sourceIndex\":0,\"targetCardId\":\"99\",\"evidenceText\":\"伪造\"}",
                        "{\"sourceIndex\":0,\"targetCardId\":\"41\",\"cardVersion\":3,\"evidenceText\":\"越权字段\"}",
                        "{\"sourceIndex\":1,\"targetCardId\":\"41\",\"evidenceText\":\"索引越界\"}"))
            assertThatThrownBy(
                            () ->
                                    protocol.parseAgent(
                                            body("[" + relation + "]"),
                                            task,
                                            prepared(),
                                            info,
                                            matches()))
                    .hasMessage("MODEL_OUTPUT_INVALID");
    }

    @Test
    void noRecallOnlyAllowsEmptyRelationsAndD15ContractStaysUnchanged() {
        assertThat(protocol.parseAgent(body("[]"), task, prepared(), info, List.of()).relations())
                .isEmpty();
        assertThatThrownBy(() -> protocol.parse(body("[]"), task, prepared(), info))
                .hasMessage("MODEL_OUTPUT_INVALID");
        assertThatThrownBy(
                        () ->
                                protocol.parseAgent(
                                        body("[]").replace(",\"relations\":[]", ""),
                                        task,
                                        prepared(),
                                        info,
                                        matches()))
                .hasMessage("MODEL_OUTPUT_INVALID");
    }

    @Test
    void duplicateRelationsAndTamperedPrimaryQuotesAreRejected() {
        String relation = "{\"sourceIndex\":0,\"targetCardId\":\"41\",\"evidenceText\":\"建议\"}";
        assertThatThrownBy(
                        () ->
                                protocol.parseAgent(
                                        body("[" + relation + "," + relation + "]"),
                                        task,
                                        prepared(),
                                        info,
                                        matches()))
                .hasMessage("MODEL_OUTPUT_INVALID");
        assertThatThrownBy(
                        () ->
                                protocol.parseAgent(
                                        body("[]").replace("原文😀", "伪造引用"),
                                        task,
                                        prepared(),
                                        info,
                                        matches()))
                .hasMessage("MODEL_OUTPUT_INVALID");
    }
}
