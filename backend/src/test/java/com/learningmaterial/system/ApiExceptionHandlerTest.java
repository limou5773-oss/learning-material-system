package com.learningmaterial.system;

import com.learningmaterial.system.common.ApiExceptionHandler;
import com.learningmaterial.system.common.RequestIdFilter;
import com.learningmaterial.system.controller.CardController;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.service.CardHierarchyService;
import com.learningmaterial.system.service.CardService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ApiExceptionHandlerTest {
    private final CardService cards = mock(CardService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CardController(cards, mock(CardHierarchyService.class)))
        .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                return parameter.getParameterType() == AuthPrincipal.class;
            }
            public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                    org.springframework.web.method.support.ModelAndViewContainer container,
                    org.springframework.web.context.request.NativeWebRequest request,
                    org.springframework.web.bind.support.WebDataBinderFactory binder) {
                return new AuthPrincipal(7, 9, "USER");
            }
        }).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();

    @Test
    void missingVersionIsAClientErrorAndNeverDeletesTheCard() throws Exception {
        mvc.perform(delete("/api/v1/cards/1")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("BAD_QUERY"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("version")))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(cards);
    }

    @Test
    void invalidVersionTypeKeepsExistingBadQueryContract() throws Exception {
        mvc.perform(delete("/api/v1/cards/1").param("version", "abc")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("BAD_QUERY"));
        verifyNoInteractions(cards);
    }

    @Test
    void unexpectedFailureLogsItsOriginalStack(org.springframework.boot.test.system.CapturedOutput output) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        new ApiExceptionHandler().unexpected(new IllegalStateException("diagnostic-marker"), request);
        org.assertj.core.api.Assertions.assertThat(output.getAll())
            .contains("java.lang.IllegalStateException: diagnostic-marker", "at com.learningmaterial.system.ApiExceptionHandlerTest");
    }
}
