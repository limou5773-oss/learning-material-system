package com.learningmaterial.system.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.learningmaterial.system.auth.AuthDtos.UserView;
import com.learningmaterial.system.auth.AuthPrincipal;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AdminUserService users;
    private final ObjectReader json;

    public AdminUserController(AdminUserService users, ObjectMapper json) {
        this.users = users;
        this.json = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping
    public ApiResponse<PageView<UserView>> list(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                HttpServletRequest request) {
        return ApiResponse.of(users.list(keyword, status, page, pageSize), request);
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<UserView> status(@PathVariable String id, @RequestBody String body,
                                        @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        JsonNode input;
        try { input = json.readTree(body); }
        catch (JsonProcessingException ex) { throw badJson(); }
        if (input == null || !input.isObject()) throw badJson();
        input.fieldNames().forEachRemaining(field -> { if (!field.equals("status") && !field.equals("reason")) throw badJson(); });
        for (String field : new String[]{"status", "reason"}) {
            if (input.hasNonNull(field) && !input.path(field).isTextual()) throw badJson();
        }
        long target;
        try {
            if (!id.matches("[1-9][0-9]*")) throw new NumberFormatException();
            target = Long.parseLong(id);
        } catch (NumberFormatException ex) { throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"); }
        return ApiResponse.of(users.changeStatus(principal, target, input.path("status").textValue(), input.path("reason").textValue()), request);
    }
    private static ApiException badJson() { return new ApiException(HttpStatus.BAD_REQUEST, "BAD_JSON", "请求格式错误"); }
}
