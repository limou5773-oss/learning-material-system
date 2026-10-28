package com.learningmaterial.system.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.dto.ContentReviewDtos.ReviewContent;
import com.learningmaterial.system.dto.ContentReviewDtos.ReviewView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.dto.StoredFile;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.service.ContentReviewService;
import com.learningmaterial.system.util.ReviewErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.learningmaterial.system.util.ReviewErrors.*;

@RestController
@RequestMapping("/api/v1")
public class ContentReviewController {
    private final ContentReviewService service;
    private final ObjectReader json;
    public ContentReviewController(ContentReviewService service,ObjectMapper json){this.service=service;this.json=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    @PostMapping("/materials/{id}/review-requests")
    public ResponseEntity<ApiResponse<ReviewView>> submit(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String id,@RequestBody String body,HttpServletRequest request) {
        JsonNode input=parse(body,Set.of("version","reason"));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.submit(actor.userId(),id(id),version(input),reason(input)),request));
    }
    @GetMapping("/materials/{id}/review-requests")
    public ApiResponse<PageView<ReviewView>> history(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest request) {
        return ApiResponse.of(service.history(actor.userId(),id(id),page,pageSize),request);
    }
    @GetMapping("/admin/reviews")
    public ApiResponse<PageView<ReviewView>> list(@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest request) {
        return ApiResponse.of(service.list(status,page,pageSize),request);
    }
    @PostMapping("/admin/reviews/{id}/content-accesses")
    public ApiResponse<ReviewContent> content(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String id,@RequestBody String body,HttpServletRequest request) {
        JsonNode input=parse(body,Set.of("reason"));
        return ApiResponse.of(service.content(actor,id(id),reason(input)),request);
    }
    @PutMapping("/admin/reviews/{id}/decision")
    public ApiResponse<ReviewView> decide(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String id,@RequestBody String body,HttpServletRequest request) {
        JsonNode input=parse(body,Set.of("version","decision","reason"));
        String decision=input.path("decision").textValue();
        if(decision==null)throw ReviewErrors.invalid();
        return ApiResponse.of(service.decide(actor,id(id),version(input),decision,reason(input)),request);
    }
    @GetMapping("/admin/reviews/{id}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String id) {
        StoredFile file=service.file(actor,id(id));
        String extension="application/pdf".equals(file.mimeType())?"pdf":"image/png".equals(file.mimeType())?"png":"jpg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType())).contentLength(file.size())
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .headers(headers->headers.setContentDisposition(ContentDisposition.inline().filename("material."+extension).build()))
            .body(new FileSystemResource(file.path()));
    }
    JsonNode parse(String body,Set<String> fields) {
        JsonNode input;
        try{input=json.readTree(body);}catch(JsonProcessingException ex){throw badJson();}
        if(input==null||!input.isObject())throw badJson();
        input.fieldNames().forEachRemaining(f->{if(!fields.contains(f))throw badJson();});
        for(String f:fields)if(input.hasNonNull(f)&&!(f.equals("version")?input.path(f).isIntegralNumber():input.path(f).isTextual()))throw badJson();
        return input;
    }
    static int version(JsonNode input){JsonNode v=input.path("version");if(!v.canConvertToInt()||v.asInt()<1)throw ReviewErrors.invalid();return v.asInt();}
    static String reason(JsonNode input){String r=input.path("reason").textValue();if(r==null||r.isBlank()||r.length()>1000)throw ReviewErrors.invalid();return r.trim();}
    static long id(String id){try{if(!id.matches("[1-9][0-9]*"))throw new NumberFormatException();return Long.parseLong(id);}catch(NumberFormatException ex){throw ReviewErrors.notFound();}}
    static ApiException badJson(){return new ApiException(HttpStatus.BAD_REQUEST,"BAD_JSON","请求格式错误");}
}
