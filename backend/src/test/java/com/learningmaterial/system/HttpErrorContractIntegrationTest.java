package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="DB_URL",matches=".*learning_material_auth_test.*")
class HttpErrorContractIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test void authenticatedFrameworkErrorsKeepTheirHttpMeaning() {
        String username="http_"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        var user=rest.postForEntity(url("/auth/registrations"),Map.of("username",username,"password","Contract_test_1030","displayName",username),JsonNode.class);
        assertThat(user.getStatusCode().value()).isEqualTo(201);
        long id=user.getBody().path("data").path("id").asLong();
        try {
            String token=rest.postForEntity(url("/auth/sessions"),Map.of("username",username,"password","Contract_test_1030","clientType","WEB"),JsonNode.class)
                .getBody().path("data").path("accessToken").asText();
            for(String path:java.util.List.of("/does-not-exist","/ai-tasks/999999/cancel","/ai-tasks/999999/cancellations")) {
                var response=request(path,path.equals("/does-not-exist")?HttpMethod.GET:HttpMethod.POST,token,MediaType.APPLICATION_JSON);
                assertThat(response.getStatusCode().value()).as(path).isEqualTo(404);
                assertThat(response.getBody().path("requestId").asText()).isNotBlank();
            }
            var method=request("/materials",HttpMethod.PUT,token,MediaType.APPLICATION_JSON);
            assertThat(method.getStatusCode().value()).isEqualTo(405);
            assertThat(method.getHeaders().getAllow()).contains(HttpMethod.POST,HttpMethod.GET);
            var media=request("/materials",HttpMethod.POST,token,MediaType.TEXT_PLAIN);
            assertThat(media.getStatusCode().value()).isEqualTo(415);
            assertThat(media.getBody().path("error").path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        } finally {
            jdbc.update("DELETE FROM auth_session WHERE user_id=?",id);
            jdbc.update("DELETE FROM app_user WHERE id=?",id);
        }
    }
    ResponseEntity<JsonNode> request(String path,HttpMethod method,String token,MediaType contentType) {
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);headers.setContentType(contentType);
        return rest.exchange(url(path),method,new HttpEntity<>("{}",headers),JsonNode.class);
    }
    String url(String path) {return "http://127.0.0.1:"+port+"/api/v1"+path;}
}
