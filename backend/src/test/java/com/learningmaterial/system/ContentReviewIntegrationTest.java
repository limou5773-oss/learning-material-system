package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.integration.storage.FileStorage;
import com.learningmaterial.system.service.TaskCandidateService;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.task.MockTaskProcessor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties="lms.task.processor.mode=mock")
@EnabledIfEnvironmentVariable(named="DB_URL", matches=".*learning_material_auth_test.*")
class ContentReviewIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired FileStorage storage;
    @Autowired TaskLeaseService leases;
    @Autowired TaskCandidateService candidates;
    @Autowired MockTaskProcessor processor;
    @MockitoSpyBean Clock clock;
    final List<String> files=new ArrayList<>();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Long> users=new ArrayList<>();
    Account owner, other, admin;
    String material;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        owner=account(false); other=account(false); admin=account(true);
        Reply created=request("POST","/materials",Map.of("type","NOTE","title","待审核标题","noteText","private body 送审正文"),owner.token());
        assertThat(created.status()).isEqualTo(201);
        material=created.data().path("id").asText();
    }
    @AfterEach void clean() {
        reset(clock);
        for(String trigger:List.of("d22_fail_audit","d22_fail_notification","d22_fail_cancel")) jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(long id:users) {
            jdbc.update("DELETE FROM review_record WHERE operator_id=? OR target_user_id=?",id,id);
            if(table("content_review")) {
                jdbc.update("DELETE FROM notification WHERE user_id=?",id);
                jdbc.update("DELETE FROM review_record WHERE material_id IN (SELECT id FROM material WHERE user_id=?)",id);
                jdbc.update("DELETE FROM content_review WHERE user_id=?",id);
            }
            jdbc.update("DELETE FROM card_relation WHERE user_id=?",id);
            jdbc.update("DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE user_id=?)",id);
            jdbc.update("UPDATE knowledge_card SET parent_card_id=NULL WHERE user_id=?",id);
            jdbc.update("DELETE FROM knowledge_card WHERE user_id=?",id);
            for(String table:List.of("ai_result","ai_tool_call","ocr_attempt","model_attempt")) jdbc.update("DELETE FROM "+table+" WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)",id);
            jdbc.update("DELETE FROM ai_task WHERE user_id=?",id);
            jdbc.update("DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE user_id=?)",id);
            jdbc.update("DELETE FROM material_tag WHERE material_id IN (SELECT id FROM material WHERE user_id=?)",id);
            jdbc.update("DELETE FROM material WHERE user_id=?",id);
            jdbc.update("DELETE FROM tag WHERE user_id=?",id);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?",id);
        }
        for(long id:users) jdbc.update("DELETE FROM app_user WHERE id=?",id);
        files.forEach(storage::deleteQuietly);
    }
    boolean table(String name) { return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,name)>0; }

    @Test void ownerExplicitlyAuthorizesVersionAndListsOnlySafeMetadata() throws Exception {
        Reply sent=submit(1);
        assertThat(sent.status()).isEqualTo(201);
        JsonNode review=sent.data();
        assertThat(review.fieldNames()).toIterable().containsExactlyInAnyOrder("id","materialId","materialVersion","title","triggerType","status","version","reason","decisionReason","createdAt","ownerId","type","accessScope","isCurrent");
        assertThat(review.path("ownerId").asText()).isEqualTo(Long.toString(owner.id()));
        assertThat(review.path("reason").asText()).isEqualTo("本人授权");
        assertThat(review.path("createdAt").asText()).endsWith("Z");
        assertThat(review.path("id").isTextual()).isTrue();
        assertThat(review.path("isCurrent").asBoolean()).isTrue();
        assertThat(submit(1).status()).isEqualTo(409);
        Reply list=request("GET","/admin/reviews?status=PENDING",null,admin.token());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.data().path("items").toString()).doesNotContain("private body","noteText","storage","summary","fileAccessPath");
        assertThat(request("GET",history(),null,owner.token()).data().path("total").asInt()).isEqualTo(1);
        request("PATCH","/materials/"+material,Map.of("version",1,"title","修改标题","noteText","new body"),owner.token());
        assertThat(submit(1).status()).isEqualTo(409);
        assertThat(request("GET",history(),null,owner.token()).data().path("items").get(0).path("isCurrent").asBoolean()).isFalse();
        assertThat(submit(2).status()).isEqualTo(201);
        assertThat(request("GET",history(),null,owner.token()).data().path("items").get(1).path("title").asText()).isEqualTo("待审核标题");
    }
    @Test void authenticationOwnershipIdsAndPaginationAreExplicit() throws Exception {
        assertThat(request("GET","/admin/reviews",null,null).status()).isEqualTo(401);
        assertThat(request("GET","/admin/reviews",null,owner.token()).status()).isEqualTo(403);
        assertThat(request("POST",history(),Map.of("version",1,"reason","他人"),other.token()).status()).isEqualTo(404);
        assertThat(request("GET",history(),null,other.token()).status()).isEqualTo(404);
        for(String id:List.of("0","-1","abc","01","999999999999999999999999")) {
            assertThat(request("GET","/materials/"+id+"/review-requests",null,owner.token()).status()).isEqualTo(404);
        }
        for(String q:List.of("page=0","pageSize=101","status=UNKNOWN")) assertThat(request("GET","/admin/reviews?"+q,null,admin.token()).status()).isEqualTo(422);
        for(String q:List.of("page=x","pageSize=1.5")) assertThat(request("GET","/admin/reviews?"+q,null,admin.token()).status()).isEqualTo(400);
    }
    @Test void strictRequestsDistinguishJsonTypesFromSemanticValidation() throws Exception {
        for(String body:List.of("[]","null","{","{} {}","{\"version\":1,\"reason\":1}","{\"version\":\"1\",\"reason\":\"a\"}","{\"version\":1,\"reason\":\"a\",\"x\":1}"))
            assertThat(raw("POST",history(),body,owner.token()).status()).as(body).isEqualTo(400);
        for(String body:List.of("{}","{\"version\":1}","{\"version\":0,\"reason\":\"a\"}","{\"version\":1,\"reason\":null}","{\"version\":1,\"reason\":\"  \"}"))
            assertThat(raw("POST",history(),body,owner.token()).status()).as(body).isEqualTo(422);
        assertThat(request("POST",history(),Map.of("version",1,"reason","理".repeat(1001)),owner.token()).status()).isEqualTo(422);
    }
    @Test void contentAndDecisionRequireAuditedSameSessionAndPreserveContentVersion() throws Exception {
        String review=submit(1).data().path("id").asText(),path="/admin/reviews/"+review;
        assertThat(decide(review,1,"REJECTED",admin.token()).status()).isEqualTo(403);
        Reply access=view(review,admin.token());
        assertThat(access.status()).isEqualTo(200);
        assertThat(access.data().path("material").path("noteText").asText()).contains("private body");
        assertThat(access.data().path("fileAccessPath").isNull()).isTrue();
        assertThat(access.data().path("auditId").isTextual()).isTrue();
        String fresh=login(admin.username(),admin.password());
        assertThat(decide(review,1,"REJECTED",fresh).status()).isEqualTo(403);
        assertThat(decide(review,2,"REJECTED",admin.token()).status()).isEqualTo(409);
        Reply decision=decide(review,1,"REJECTED",admin.token());
        assertThat(decision.status()).isEqualTo(200);
        assertThat(decision.data().path("version").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT access_state,version FROM material WHERE id=?",material)).containsEntry("access_state","RESTRICTED").containsEntry("version",1);
        assertThat(decide(review,1,"REJECTED",admin.token()).status()).isEqualTo(409);
        assertThat(view(review,admin.token()).status()).isEqualTo(409);
        assertThat(submit(1).status()).isEqualTo(409);
        JsonNode event=request("GET","/notifications",null,owner.token()).data().path("items").get(0);
        assertThat(event.path("type").asText()).isEqualTo("REVIEW_DECIDED");
        assertThat(event.path("target").fieldNames()).toIterable().containsExactlyInAnyOrder("type","materialId","reviewId");
        assertThat(event.path("target").path("type").asText()).isEqualTo("MATERIAL");
        assertThat(event.path("target").path("materialId").asText()).isEqualTo(material);
        assertThat(event.path("target").path("reviewId").asText()).isEqualTo(review);
        assertThat(event.path("channelStatus").asText()).isEqualTo("NOT_REQUESTED");
        assertThat(request("PUT","/notifications/"+event.path("id").asText()+"/read-state",Map.of("isRead",true),owner.token()).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=? AND target_user_id IS NULL",Integer.class,review)).isEqualTo(2);
        request("PATCH","/materials/"+material,Map.of("version",1,"noteText","修正正文"),owner.token());
        String next=submit(2).data().path("id").asText();view(next,admin.token());
        assertThat(decide(next,1,"APPROVED",admin.token()).status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT access_state,version FROM material WHERE id=?",material)).containsEntry("access_state","NORMAL").containsEntry("version",2);
    }
    @Test void oldOrDeletedAuthorizationsCannotExposeOrDecideContent() throws Exception {
        String review=submit(1).data().path("id").asText();view(review,admin.token());
        request("PATCH","/materials/"+material,Map.of("version",1,"noteText","changed"),owner.token());
        assertThat(view(review,admin.token()).status()).isEqualTo(409);
        assertThat(decide(review,1,"APPROVED",admin.token()).status()).isEqualTo(409);
        String next=submit(2).data().path("id").asText();
        assertThat(request("DELETE","/materials/"+material+"?version=2",null,owner.token()).status()).isEqualTo(204);
        assertThat(view(next,admin.token()).status()).isEqualTo(409);
        assertThat(request("GET",history(),null,owner.token()).status()).isEqualTo(404);
    }
    @Test void strictAdminReasonsDecisionAndVersions() throws Exception {
        String review=submit(1).data().path("id").asText(),path="/admin/reviews/"+review;
        for(String body:List.of("null","[]","{\"reason\":1}","{\"reason\":\"a\",\"x\":1}","{\"reason\":\"a\"} {}"))
            assertThat(raw("POST",path+"/content-accesses",body,admin.token()).status()).isEqualTo(400);
        for(String body:List.of("{}","{\"reason\":null}","{\"reason\":\"  \"}"))
            assertThat(raw("POST",path+"/content-accesses",body,admin.token()).status()).isEqualTo(422);
        assertThat(request("POST",path+"/content-accesses",Map.of("reason","理".repeat(1001)),admin.token()).status()).isEqualTo(422);
        view(review,admin.token());
        for(String body:List.of("{\"version\":\"1\",\"decision\":\"REJECTED\",\"reason\":\"a\"}","{\"version\":1.5,\"decision\":\"REJECTED\",\"reason\":\"a\"}","{\"version\":1,\"decision\":1,\"reason\":\"a\"}"))
            assertThat(raw("PUT",path+"/decision",body,admin.token()).status()).isEqualTo(400);
        for(Object body:List.of(Map.of("version",0,"decision","REJECTED","reason","a"),Map.of("version",1,"decision","PENDING","reason","a"),Map.of("version",1,"decision","REJECTED","reason","  "),Map.of("decision","REJECTED","reason","a")))
            assertThat(request("PUT",path+"/decision",body,admin.token()).status()).isEqualTo(422);
    }
    @Test void auditAndNotificationFailuresRollbackRestrictionDecisionAndTasks() throws Exception {
        String task=task();TaskLease lease=leases.claim();assertThat(lease.taskId()).isEqualTo(Long.parseLong(task));
        leases.startStep(lease,"generate_summary_cards","MODEL");
        String review=submit(1).data().path("id").asText();view(review,admin.token());
        for(String part:List.of("audit","notification","cancel")) {
            String table=part.equals("audit")?"review_record":part.equals("cancel")?"ai_task":"notification";
            jdbc.execute("CREATE TRIGGER d22_fail_"+part+" BEFORE "+(part.equals("cancel")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='D22 rollback test'");
            try {
                assertThat(decide(review,1,"REJECTED",admin.token()).status()).isEqualTo(500);
                assertThat(jdbc.queryForObject("SELECT status FROM content_review WHERE id=?",String.class,review)).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("SELECT access_state FROM material WHERE id=?",String.class,material)).isEqualTo("NORMAL");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE review_id=?",Integer.class,review)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=?",Integer.class,review)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM ai_task WHERE id=?",String.class,task)).isEqualTo("RUNNING");
                assertThat(jdbc.queryForObject("SELECT status FROM ai_tool_call WHERE task_id=?",String.class,task)).isEqualTo("RUNNING");
            } finally {jdbc.execute("DROP TRIGGER d22_fail_"+part);}
        }
    }
    @Test void fileRequiresSameSessionViewAuditsEachReadAndOwnerCanReadRestrictedFile() throws Exception {
        byte[] bytes=fileMaterial();
        String review=submit(1).data().path("id").asText(),path="/admin/reviews/"+review+"/file";
        assertThat(binary(path,admin.token()).statusCode()).isEqualTo(403);
        assertThat(view(review,admin.token()).data().path("fileAccessPath").asText()).isEqualTo("/api/v1"+path);
        Account second=account(true);
        assertThat(binary(path,second.token()).statusCode()).isEqualTo(403);
        assertThat(binary(path,login(admin.username(),admin.password())).statusCode()).isEqualTo(403);
        for(int i=0;i<2;i++) {
            HttpResponse<byte[]> response=binary(path,admin.token());
            assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(bytes);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
            assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("material.png");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=? AND action='FILE_VIEW'",Integer.class,review)).isEqualTo(2);
        assertThat(decide(review,1,"REJECTED",admin.token()).status()).isEqualTo(200);
        assertThat(binary(path,admin.token()).statusCode()).isEqualTo(409);
        assertThat(binary("/materials/"+material+"/file",owner.token()).statusCode()).isEqualTo(200);
        assertThat(binary("/materials/"+material+"/file",other.token()).statusCode()).isEqualTo(404);
    }
    @Test void missingOrUnsafeFilesNeverCreateSuccessfulFileAudits() throws Exception {
        fileMaterial();String key=files.get(0),review=submit(1).data().path("id").asText();view(review,admin.token());
        jdbc.update("UPDATE material SET storage_key='../private.png' WHERE id=?",material);
        assertThat(binary("/admin/reviews/"+review+"/file",admin.token()).statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=? AND action='FILE_VIEW'",Integer.class,review)).isZero();
        jdbc.update("UPDATE material SET storage_key=? WHERE id=?",key,material);
        storage.deleteQuietly(key);
        assertThat(binary("/admin/reviews/"+review+"/file",admin.token()).statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=? AND action='FILE_VIEW'",Integer.class,review)).isZero();
    }
    @Test void failedAccessAuditNeverReturnsControlledBodyOrFile() throws Exception {
        fileMaterial();String review=submit(1).data().path("id").asText();
        jdbc.execute("CREATE TRIGGER d22_fail_audit BEFORE INSERT ON review_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='D22 access audit failure'");
        try {
            Reply failed=view(review,admin.token());assertThat(failed.status()).isEqualTo(500);assertThat(failed.body().toString()).doesNotContain("private body","fileAccessPath");
        } finally {jdbc.execute("DROP TRIGGER d22_fail_audit");}
        view(review,admin.token());
        jdbc.execute("CREATE TRIGGER d22_fail_audit BEFORE INSERT ON review_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='D22 file audit failure'");
        try {assertThat(binary("/admin/reviews/"+review+"/file",admin.token()).statusCode()).isEqualTo(500);}
        finally {jdbc.execute("DROP TRIGGER d22_fail_audit");}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=? AND action='FILE_VIEW'",Integer.class,review)).isZero();
    }
    @Test void concurrentSubmissionsSerializeAndUnseenEditCannotBeAuthorized() throws Exception {
        CompletableFuture<Reply> first,second;
        try(Connection blocker=block("SELECT id FROM material WHERE id="+material+" FOR UPDATE")) {
            first=async(()->submit(1));second=async(()->submit(1));
            try {awaitWaiters("material",2);}finally{blocker.commit();}
        }
        assertThat(List.of(first.get(10,TimeUnit.SECONDS).status(),second.get(10,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE material_id=?",Integer.class,material)).isEqualTo(1);
        CompletableFuture<Reply> unseen;
        try(Connection blocker=block("SELECT id FROM material WHERE id="+material+" FOR UPDATE")) {
            unseen=async(()->submit(2));
            try {awaitWaiters("material",1);execute(blocker,"UPDATE material SET version=3,note_text='unseen concurrent edit' WHERE id="+material);}finally{blocker.commit();}
        }
        assertThat(unseen.get(10,TimeUnit.SECONDS).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE material_id=?",Integer.class,material)).isEqualTo(1);
    }
    @Test void editBeforeDecisionInvalidatesConsentAndDecisionBeforeEditPreservesRestriction() throws Exception {
        String review=submit(1).data().path("id").asText();view(review,admin.token());
        CompletableFuture<Reply> deciding;
        try(Connection blocker=block("SELECT id FROM material WHERE id="+material+" FOR UPDATE")) {
            deciding=async(()->decide(review,1,"REJECTED",admin.token()));
            try {awaitWaiters("material",1);execute(blocker,"UPDATE material SET version=2,note_text='first edit' WHERE id="+material);}finally{blocker.commit();}
        }
        assertThat(deciding.get(10,TimeUnit.SECONDS).status()).isEqualTo(409);
        String next=submit(2).data().path("id").asText();view(next,admin.token());
        CompletableFuture<Reply> rejected,edited;
        try(Connection blocker=blockSession(admin.token())) {
            rejected=async(()->decide(next,1,"REJECTED",admin.token()));awaitWaiters("auth_session",1);
            edited=async(()->request("PATCH","/materials/"+material,Map.of("version",2,"noteText","after decision"),owner.token()));
            try {awaitWaiters("material",1);}finally{blocker.commit();}
        }
        assertThat(rejected.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);assertThat(edited.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT version,access_state FROM material WHERE id=?",material)).containsEntry("version",3).containsEntry("access_state","RESTRICTED");
    }
    @Test void rejectionDoesNotDeadlockConcurrentTaskSubmissionForSameAdminOwner() throws Exception {
        useOwnerAsAdmin();String review=submit(1).data().path("id").asText();view(review,admin.token());
        CompletableFuture<Reply> rejected,submitted;
        try(Connection blocker=blockSession(admin.token())) {
            rejected=async(()->decide(review,1,"REJECTED",admin.token()));awaitWaiters("auth_session",1);
            submitted=async(()->request("POST","/ai-tasks",taskInput(),owner.token()));
            try {awaitWaiters("app_user",1);}finally{blocker.commit();}
        }
        assertThat(rejected.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);assertThat(submitted.get(10,TimeUnit.SECONDS).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE material_id=?",Integer.class,material)).isZero();
    }
    @Test void confirmationAndRejectionSerializeInBothOrdersWithoutActorOwnerDeadlock() throws Exception {
        useOwnerAsAdmin();String task=task();processor.execute(leases.claim());
        JsonNode input=confirmation(task);String review=submit(1).data().path("id").asText();view(review,admin.token());
        CompletableFuture<Reply> confirmed,rejected;
        try(Connection blocker=block("SELECT task_id FROM ai_result WHERE task_id="+task+" FOR UPDATE")) {
            confirmed=async(()->request("PUT","/ai-tasks/"+task+"/confirmation",input,owner.token()));awaitWaiters("ai_result",1);
            rejected=async(()->decide(review,1,"REJECTED",admin.token()));
            try {awaitWaiters("material",1);}finally{blocker.commit();}
        }
        assertThat(confirmed.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);assertThat(rejected.get(10,TimeUnit.SECONDS).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task WHERE id=?",String.class,task)).isEqualTo("SUCCEEDED");
        // A fresh current-version candidate reverses the ordering: rejection holds material first.
        Map<String,Object> taskInput=new HashMap<>(taskInput());taskInput.put("materialVersion",2);
        String nextTask=request("POST","/ai-tasks",taskInput,owner.token()).data().path("id").asText();processor.execute(leases.claim());
        JsonNode nextInput=confirmation(nextTask);String nextReview=submit(2).data().path("id").asText();view(nextReview,admin.token());
        try(Connection blocker=blockSession(admin.token())) {
            rejected=async(()->decide(nextReview,1,"REJECTED",admin.token()));awaitWaiters("auth_session",1);
            confirmed=async(()->request("PUT","/ai-tasks/"+nextTask+"/confirmation",nextInput,owner.token()));
            try {awaitWaiters("material",1);}finally{blocker.commit();}
        }
        assertThat(rejected.get(10,TimeUnit.SECONDS).status()).isEqualTo(200);assertThat(confirmed.get(10,TimeUnit.SECONDS).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task WHERE id=?",String.class,nextTask)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card WHERE user_id=?",Integer.class,owner.id())).isEqualTo(1);
    }
    @Test void administratorWithdrawalWhileWaitingIsRecheckedBeforeContentAccess() throws Exception {
        String review=submit(1).data().path("id").asText();
        for(String change:List.of("role","status","session")) {
            jdbc.update("UPDATE app_user SET role='ADMIN',status='ENABLED' WHERE id=?",admin.id());
            String token=login(admin.username(),admin.password());CompletableFuture<Reply> pending;
            try(Connection blocker=block("SELECT id FROM admin_control_lock WHERE id=1 FOR UPDATE")) {
                pending=async(()->view(review,token));
                try {
                    awaitWaiters("admin_control_lock",1);
                    if(change.equals("role"))jdbc.update("UPDATE app_user SET role='USER' WHERE id=?",admin.id());
                    else if(change.equals("status"))jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",admin.id());
                    else jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(6) WHERE token_hash=SHA2(?,256)",token);
                }finally{blocker.commit();}
            }
            assertThat(pending.get(10,TimeUnit.SECONDS).status()).as(change).isEqualTo(change.equals("role")?403:401);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=?",Integer.class,review)).isZero();
    }
    @Test void sessionExpiryUsesClockAfterFinalSessionLockReturns() throws Exception {
        String review=submit(1).data().path("id").asText();Instant before=Instant.now();doReturn(before).when(clock).instant();
        jdbc.update("UPDATE auth_session SET expires_at=? WHERE token_hash=SHA2(?,256)",java.time.LocalDateTime.ofInstant(before.plusSeconds(30),java.time.ZoneOffset.UTC),admin.token());
        CompletableFuture<Reply> pending;
        try(Connection blocker=blockSession(admin.token())) {
            pending=async(()->view(review,admin.token()));
            try {awaitWaiters("auth_session",1);doReturn(before.plusSeconds(60)).when(clock).instant();}finally{blocker.commit();}
        }
        assertThat(pending.get(10,TimeUnit.SECONDS).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE content_review_id=?",Integer.class,review)).isZero();
    }
    @Test void databaseOwnerForeignKeysAndTwoTargetConstraintEnforceNotificationInvariants() throws Exception {
        String review=submit(1).data().path("id").asText(),task=task();
        assertThatThrownBy(()->jdbc.update("INSERT INTO content_review(material_id,user_id,material_version,title,material_type,trigger_type,access_scope,status,version,reason,created_at) VALUES(?,?,2,'x','NOTE','USER_SUBMITTED','MATERIAL_CONTENT','PENDING',1,'x',UTC_TIMESTAMP(6))",material,other.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO notification(user_id,review_id,type,dedupe_key,content,target_json,channel_status,created_at) VALUES(?,?,'REVIEW_DECIDED','bad-owner','x','{}','NOT_REQUESTED',UTC_TIMESTAMP(6))",other.id(),review))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO notification(user_id,task_id,review_id,type,dedupe_key,content,target_json,channel_status,created_at) VALUES(?,?,?,'REVIEW_DECIDED','both-targets','x','{}','NOT_REQUESTED',UTC_TIMESTAMP(6))",owner.id(),task,review))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause()
            .isInstanceOfSatisfying(java.sql.SQLException.class, cause -> assertThat(cause.getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(()->jdbc.update("INSERT INTO notification(user_id,type,dedupe_key,content,target_json,channel_status,created_at) VALUES(?,'REVIEW_DECIDED','no-target','x','{}','NOT_REQUESTED',UTC_TIMESTAMP(6))",owner.id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause()
            .isInstanceOfSatisfying(java.sql.SQLException.class, cause -> assertThat(cause.getErrorCode()).isEqualTo(3819));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=?",Integer.class,other.id())).isZero();
    }
    JsonNode confirmation(String task)throws Exception {
        JsonNode candidate=request("GET","/ai-tasks/"+task+"/result",null,owner.token()).data();
        var input=json.createObjectNode();input.put("resultVersion",candidate.path("resultVersion").asInt()).put("sourceVersion",candidate.path("sourceVersion").asInt()).put("summary","审核并发测试摘要").put("tagMode","MERGE");
        input.putArray("tags");input.putArray("relations");input.putArray("cards").add(candidate.path("cards").get(0).deepCopy());return input;
    }
    void useOwnerAsAdmin()throws Exception {jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?",owner.id());owner=new Account(owner.id(),owner.username(),owner.password(),login(owner.username(),owner.password()));admin=owner;}
    Connection blockSession(String token)throws Exception {
        long id=jdbc.queryForObject("SELECT id FROM auth_session WHERE token_hash=SHA2(?,256)",Long.class,token);return block("SELECT id FROM auth_session WHERE id="+id+" FOR UPDATE");
    }
    Connection block(String sql)throws Exception {Connection connection=dataSource.getConnection();connection.setAutoCommit(false);execute(connection,sql);return connection;}
    static void execute(Connection connection,String sql)throws Exception {try(var statement=connection.createStatement()){statement.execute(sql);}}
    void awaitWaiters(String table,int expected) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME=?",Integer.class,table);
            if(count>=expected)return;LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Database barrier timed out: expected "+expected+" waiters on "+table);
    }
    static CompletableFuture<Reply> async(RequestCall request){return CompletableFuture.supplyAsync(()->{try{return request.call();}catch(Exception ex){throw new RuntimeException(ex);}});}
    @FunctionalInterface interface RequestCall{Reply call()throws Exception;}
    @Test void rejectionCancelsEveryUnfinishedStateAndMarksActiveCallsUnknown() throws Exception {
        String waiting=task();jdbc.update("UPDATE ai_task SET status='WAIT_CONFIRM' WHERE id=?",waiting);
        String running=task();TaskLease lease=leases.claim();assertThat(lease.taskId()).isEqualTo(Long.parseLong(running));
        Long call=leases.startStep(lease,"generate_summary_cards","MODEL");assertThat(call).isNotNull();
        jdbc.update("INSERT INTO ocr_attempt(task_id,execution_epoch,page_no,attempt_no,provider_key,dispatched,status,started_at) VALUES(?,1,0,1,'MOCK',1,'RUNNING',UTC_TIMESTAMP(6))",running);
        jdbc.update("INSERT INTO model_attempt(task_id,execution_epoch,attempt_no,provider_key,requested_model,prompt_version,dispatched,status,started_at) VALUES(?,1,1,'MOCK','mock','test',1,'RUNNING',UTC_TIMESTAMP(6))",running);
        String queued=task(),review=submit(1).data().path("id").asText();view(review,admin.token());
        assertThat(decide(review,1,"REJECTED",admin.token()).status()).isEqualTo(200);
        for(String id:List.of(waiting,running,queued)) {
            assertThat(jdbc.queryForMap("SELECT status,stage,error_code,cancel_flag,lease_until FROM ai_task WHERE id=?",id)).containsEntry("status","CANCELLED").containsEntry("stage","SOURCE_RESTRICTED").containsEntry("error_code","SOURCE_RESTRICTED").containsEntry("lease_until",null);
        }
        for(String table:List.of("ai_tool_call","ocr_attempt","model_attempt"))
            assertThat(jdbc.queryForMap("SELECT status,error_code FROM "+table+" WHERE task_id=?",running)).containsEntry("status","UNKNOWN").containsEntry("error_code","SOURCE_RESTRICTED");
        assertThat(leases.isCurrent(lease)).isFalse();
        assertThat(leases.completeStep(lease,call,true,"late result",null)).isFalse();
        assertThat(candidates.finish(lease,null)).isFalse();
        assertThat(request("POST","/ai-tasks",taskInput(),owner.token()).status()).isEqualTo(403);
    }
    Map<String,Object> taskInput(){return Map.of("materialId",material,"materialVersion",1,"requestKey",UUID.randomUUID().toString(),"goalType","REVIEW_CARDS","externalProcessingConsent",true);}
    String task()throws Exception{Reply result=request("POST","/ai-tasks",taskInput(),owner.token());assertThat(result.status()).isEqualTo(202);return result.data().path("id").asText();}
    byte[] fileMaterial()throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);
        byte[] bytes=output.toByteArray();String key=storage.store(bytes,"png");files.add(key);
        jdbc.update("UPDATE material SET material_type='IMAGE',note_text=NULL,storage_key=?,mime_type='image/png',file_size=? WHERE id=?",key,bytes.length,material);
        return bytes;
    }
    HttpResponse<byte[]> binary(String path,String token)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    Reply view(String review,String token)throws Exception{return request("POST","/admin/reviews/"+review+"/content-accesses",Map.of("reason","核对正文"),token);}
    Reply decide(String review,int version,String decision,String token)throws Exception{return request("PUT","/admin/reviews/"+review+"/decision",Map.of("version",version,"decision",decision,"reason","  处置原因  "),token);}
    String history(){return "/materials/"+material+"/review-requests";}
    Reply submit(int version)throws Exception{return request("POST",history(),Map.of("version",version,"reason","  本人授权  "),owner.token());}
    Account account(boolean isAdmin)throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12), username="d22_"+suffix,password="Safe_"+suffix;
        Reply result=request("POST","/auth/registrations",Map.of("username",username,"password",password,"displayName","D22 fixture"),null);
        assertThat(result.status()).isEqualTo(201); long id=result.data().path("id").asLong(); users.add(id);
        if(isAdmin)jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?",id);
        return new Account(id,username,password,login(username,password));
    }
    String login(String username,String password)throws Exception{return request("POST","/auth/sessions",Map.of("username",username,"password",password,"clientType","WEB"),null).data().path("accessToken").asText();}
    Reply request(String method,String path,Object body,String token)throws Exception{return raw(method,path,body==null?null:json.writeValueAsString(body),token);}
    Reply raw(String method,String path,String body,String token)throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(20));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        HttpResponse<String> response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.body().isBlank()?json.nullNode():json.readTree(response.body()));
    }
    record Reply(int status,JsonNode body){JsonNode data(){return body.path("data");}}
    record Account(long id,String username,String password,String token){@Override public String toString(){return "Account[id="+id+"]";}}
}
