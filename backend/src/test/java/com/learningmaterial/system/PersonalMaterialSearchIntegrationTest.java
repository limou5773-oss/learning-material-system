package com.learningmaterial.system;

import com.learningmaterial.system.dto.AuthDtos.RegistrationInput;
import com.learningmaterial.system.dto.PersonalMaterialMatch;
import com.learningmaterial.system.service.AiTaskService;
import com.learningmaterial.system.service.AuthService;
import com.learningmaterial.system.service.MaterialService;
import com.learningmaterial.system.service.PersonalMaterialSearchService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class PersonalMaterialSearchIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired PersonalMaterialSearchService search;
    @Autowired com.learningmaterial.system.service.MaterialService materials;
    @Autowired com.learningmaterial.system.service.AiTaskService tasks;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    long user, other;

    @BeforeEach
    void setup() {
        user = register();
        other = register();
    }

    long register() {
        String name = "search_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return Long.parseLong(
                auth.register(new RegistrationInput(name, "Test-password-123", name)).id());
    }

    @AfterEach
    void clean() {
        jdbc.update(
                "DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE"
                    + " user_id IN (?,?))",
                user,
                other);
        jdbc.update("DELETE FROM knowledge_card WHERE user_id IN (?,?)", user, other);
        jdbc.update("DELETE FROM ai_task WHERE user_id IN (?,?)", user, other);
        jdbc.update(
                "DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE"
                    + " user_id IN (?,?))",
                user,
                other);
        jdbc.update("DELETE FROM material WHERE user_id IN (?,?)", user, other);
        jdbc.update("DELETE FROM app_user WHERE id IN (?,?)", user, other);
    }

    long card(long owner, String title, String subject, String content) {
        var material =
                materials.createNote(
                        owner,
                        new com.learningmaterial.system.dto.MaterialDtos.NoteInput(
                                com.learningmaterial.system.dto.MaterialDtos.NoteType.NOTE,
                                title,
                                subject,
                                List.of(),
                                content));
        var task =
                tasks.submit(
                        owner,
                        new com.learningmaterial.system.dto.AiTaskDtos.TaskInput(
                                material.id(),
                                material.version(),
                                UUID.randomUUID().toString(),
                                com.learningmaterial.system.dto.AiTaskDtos.GoalType.REVIEW_CARDS,
                                null,
                                null,
                                false,
                                true));
        jdbc.update("UPDATE ai_task SET status='SUCCEEDED' WHERE id=?", task.id());
        jdbc.update(
                "INSERT INTO"
                    + " knowledge_card(user_id,task_id,candidate_key,title,content,subject,tags_json,version,origin,generation_mode,is_edited,status,created_at,updated_at)"
                    + " VALUES(?,?,'search-fixture',?,?,?,JSON_ARRAY('标签测试'),1,'AI','MOCK',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                owner,
                task.id(),
                title,
                content,
                subject);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM knowledge_card WHERE user_id=?", Long.class, owner);
    }

    void source(long cardId, String access, String status) {
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,material_type,note_text,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'source','NOTE','原文',1,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                user,
                status,
                access);
        long material =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
        jdbc.update(
                "INSERT INTO"
                    + " material_chunk(material_id,material_version,chunk_no,content,extraction_method,created_at)"
                    + " VALUES(?,1,1,'原文','TEXT',UTC_TIMESTAMP(6))",
                material);
        long chunk =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM material_chunk WHERE material_id=?",
                        Long.class,
                        material);
        jdbc.update(
                "INSERT INTO"
                    + " card_source(card_id,chunk_id,quote_text,start_offset,end_offset,created_at)"
                    + " VALUES(?,?,'原文',0,2,UTC_TIMESTAMP(6))",
                cardId,
                chunk);
    }

    @Test
    void onlyOwnedAccessibleCardsAreRecalledWithSubjectAndLimit() {
        long own = card(user, "主动回忆", "学习", "本人可访问内容");
        card(other, "主动回忆", "学习", "他人内容");
        long restricted = card(user, "主动回忆", "学习", "受限来源");
        source(restricted, "RESTRICTED", "ACTIVE");
        long deleted = card(user, "主动回忆", "学习", "删除来源");
        source(deleted, "NORMAL", "DELETED");
        long deadCard = card(user, "主动回忆", "学习", "删除卡片");
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", deadCard);
        card(user, "主动回忆", "数学", "其他学科");
        var result = search.search(user, "主动回忆", "学习", 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).cardId()).isEqualTo(Long.toString(own));
        assertThat(result.get(0).cardVersion()).isEqualTo(1);
        assertThat(search.search(user, "主动回忆", null, 1)).hasSize(1);
        assertThat(search.search(user, "' OR 1=1 --", null, 10)).isEmpty();
        assertThat(search.search(user, "标签测试", "学习", 10)).hasSize(1);
    }

    @Test
    void snippetsDeclareTruncationAndDoNotSplitSurrogates() {
        card(user, "长片段", null, "a".repeat(1199) + "😀" + "z".repeat(100));
        var item = search.search(user, "长片段", null, 10).get(0);
        assertThat(item.truncated()).isTrue();
        assertThat(item.snippet().length()).isLessThanOrEqualTo(1200);
        assertThat(Character.isHighSurrogate(item.snippet().charAt(item.snippet().length() - 1)))
                .isFalse();
    }

    @Test
    void changedOrForeignTargetsAreRejectedWithoutReturningTheirContent() {
        long own = card(user, "关键词", null, "当前内容");
        var matches = search.search(user, "关键词", null, 10);
        search.checkAvailable(user, matches);
        jdbc.update("UPDATE knowledge_card SET version=version+1 WHERE id=?", own);
        assertThatThrownBy(() -> search.checkAvailable(user, matches))
                .hasMessage("AGENT_RELATED_CHANGED");
        long foreign = card(other, "他人关键词", null, "不能外发");
        var invented =
                List.of(
                        new PersonalMaterialMatch(
                                Long.toString(foreign), 1, "伪标题", "伪片段", false));
        assertThatThrownBy(() -> search.checkAvailable(user, invented))
                .hasMessage("AGENT_RELATED_CHANGED");
    }

    @Test
    void restrictedSourceAfterSearchIsRejectedBeforeModelUse() {
        long own = card(user, "关键词", null, "原始可见内容");
        source(own, "NORMAL", "ACTIVE");
        var matches = search.search(user, "关键词", null, 10);
        jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE user_id=?", user);
        assertThatThrownBy(() -> search.checkAvailable(user, matches))
                .hasMessage("AGENT_RELATED_CHANGED");
    }

    @Test
    void publicationLocksSourcePermissionsUntilItsTransactionCommits() throws Exception {
        long own = card(user, "锁定来源", null, "测试内容");
        source(own, "NORMAL", "ACTIVE");
        var matches = search.search(user, "锁定来源", null, 10);
        long sourceId =
                jdbc.queryForObject(
                        "SELECT c.material_id FROM card_source s JOIN material_chunk c ON"
                            + " c.id=s.chunk_id WHERE s.card_id=?",
                        Long.class,
                        own);
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            transaction.execute(
                    status -> {
                        search.lockAndCheck(user, matches);
                        var probe =
                                executor.submit(
                                        () -> {
                                            try {
                                                jdbc.queryForObject(
                                                        "SELECT id FROM material WHERE id=? FOR"
                                                            + " UPDATE NOWAIT",
                                                        Long.class,
                                                        sourceId);
                                                return 0;
                                            } catch (
                                                    org.springframework.dao.DataAccessException
                                                            failure) {
                                                if (failure.getMostSpecificCause()
                                                        instanceof java.sql.SQLException sql)
                                                    return sql.getErrorCode();
                                                throw failure;
                                            }
                                        });
                        try {
                            assertThat(probe.get(3, java.util.concurrent.TimeUnit.SECONDS))
                                    .isEqualTo(3572);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
        }
        jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE id=?", sourceId);
        assertThatThrownBy(() -> search.checkAvailable(user, matches))
                .hasMessage("AGENT_RELATED_CHANGED");
    }
}
