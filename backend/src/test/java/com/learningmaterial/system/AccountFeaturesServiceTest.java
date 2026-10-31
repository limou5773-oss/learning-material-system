package com.learningmaterial.system;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.config.JwtProperties;
import com.learningmaterial.system.dto.AuthDtos.ClientType;
import com.learningmaterial.system.dto.AuthDtos.LoginInput;
import com.learningmaterial.system.dto.UserPatch;
import com.learningmaterial.system.dto.WechatIdentity;
import com.learningmaterial.system.entity.SessionRow;
import com.learningmaterial.system.entity.UserRow;
import com.learningmaterial.system.entity.WechatBindingRow;
import com.learningmaterial.system.integration.wechat.WechatLoginClient;
import com.learningmaterial.system.mapper.SessionMapper;
import com.learningmaterial.system.mapper.UserMapper;
import com.learningmaterial.system.mapper.WechatBindingMapper;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.security.JwtTokenProvider;
import com.learningmaterial.system.service.impl.AuthServiceImpl;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountFeaturesServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final SessionMapper sessions = mock(SessionMapper.class);
    private final WechatBindingMapper bindings = mock(WechatBindingMapper.class);
    private final WechatLoginClient wechat = mock(WechatLoginClient.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuthPrincipal principal = new AuthPrincipal(7, 9, "USER");
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 28, 0, 0);
    private AuthServiceImpl auth;
    private UserRow user;

    @BeforeEach
    void setup() {
        Clock clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var jwt = new JwtTokenProvider(new JwtProperties("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            "learning-material-test", Duration.ofDays(7)), clock);
        auth = new AuthServiceImpl(users, sessions, encoder, clock, bindings, wechat, manager, jwt);
        user = new UserRow();
        user.setId(7L); user.setUsername("account_test"); user.setDisplayName("原昵称");
        user.setRole("USER"); user.setStatus("ENABLED"); user.setCreatedAt(now);
        user.setPasswordHash(encoder.encode("OldPassword1"));
        when(users.findByIdForUpdate(7)).thenReturn(user);
        when(sessions.findActiveById(9, 7, now)).thenReturn(new SessionRow());
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void loginReturnsJwtWithRevocableSession() {
        when(users.findByUsernameForUpdate("account_test")).thenReturn(user);
        doAnswer(call -> { ((SessionRow) call.getArgument(0)).setId(9L); return 1; }).when(sessions).insert(any());
        var issued = auth.login(new LoginInput("account_test", "OldPassword1", ClientType.WEB));
        assertThat(issued.accessToken()).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        ArgumentCaptor<SessionRow> saved = ArgumentCaptor.forClass(SessionRow.class);
        verify(sessions).insert(saved.capture());
        assertThat(saved.getValue().getTokenHash()).hasSize(64).isNotEqualTo(issued.accessToken());
        when(sessions.findActive(saved.getValue().getTokenHash(), now)).thenReturn(saved.getValue());
        when(users.findById(7)).thenReturn(user);
        assertThat(auth.authenticate(issued.accessToken())).isEqualTo(principal);
        saved.getValue().setUserId(8L);
        assertThat(auth.authenticate(issued.accessToken())).isNull();
        saved.getValue().setUserId(7L);
        auth.logout(principal);
        verify(sessions).revoke(9, now);
        when(sessions.findActive(saved.getValue().getTokenHash(), now)).thenReturn(null);
        assertThat(auth.authenticate(issued.accessToken())).isNull();
    }

    @Test
    void nicknameChangesDoNotRevokeSessionsButPasswordChangesDo() {
        assertThat(auth.updateProfile(principal, new UserPatch(" 新昵称 ", null, null)).displayName()).isEqualTo("新昵称");
        verify(users).updateProfile(7, "新昵称", null, now);
        verify(sessions, never()).revokeAll(anyLong(), any());
        auth.updateProfile(principal, new UserPatch(null, "OldPassword1", "NewPassword2"));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users, times(2)).updateProfile(eq(7L), eq("新昵称"), hash.capture(), eq(now));
        assertThat(encoder.matches("NewPassword2", hash.getValue())).isTrue();
        verify(sessions).revokeAll(7, now);
    }

    @Test
    void incorrectCurrentPasswordLeavesProfileAndSessionsUnchanged() {
        assertThatThrownBy(() -> auth.updateProfile(principal, new UserPatch("不能保存", "incorrect", "NewPassword2")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("CURRENT_PASSWORD_INCORRECT");
                });
        verify(users, never()).updateProfile(anyLong(), anyString(), any(), any());
        verify(sessions, never()).revokeAll(anyLong(), any());
    }

    @Test
    void blankNewPasswordsUseTheSameDefinitionAsPasswordProof() {
        for (String blank : new String[]{" ".repeat(8), "\u3000".repeat(8)}) {
            assertThatThrownBy(() -> auth.updateProfile(principal, new UserPatch(null, "OldPassword1", blank)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_INPUT"));
        }
        verify(users, never()).updateProfile(anyLong(), anyString(), any(), any());
    }

    @Test
    void bindingConflictNeverReplacesTheExistingIdentity() {
        when(wechat.exchange("fresh-code")).thenReturn(new WechatIdentity("wx_app", "new-open-id"));
        when(bindings.findByUserForUpdate(7)).thenReturn(new WechatBindingRow(1, 7, "wx_app", "old-open-id", now, now));
        assertThatThrownBy(() -> auth.bindWechat(principal, "fresh-code"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("USER_WECHAT_ALREADY_BOUND"));
        verify(bindings, never()).insert(anyLong(), anyString(), anyString(), any());
        verify(bindings, never()).deleteByUser(anyLong());
    }

    @Test
    void duplicateWechatIdentityBecomesAConflictRatherThanAccountMerge() {
        when(wechat.exchange("fresh-code")).thenReturn(new WechatIdentity("wx_app", "claimed-open-id"));
        when(bindings.insert(7, "wx_app", "claimed-open-id", now)).thenThrow(new DuplicateKeyException("unique identity"));
        assertThatThrownBy(() -> auth.bindWechat(principal, "fresh-code"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("WECHAT_BIND_CONFLICT"));
        verify(bindings, never()).deleteByUser(anyLong());
        verify(sessions, never()).insert(any());
    }

    @Test
    void concurrentUnbindPreventsAStaleWechatLookupFromCreatingASession() {
        when(wechat.exchange("fresh-code")).thenReturn(new WechatIdentity("wx_app", "test-open-id"));
        when(bindings.findByIdentity("wx_app", "test-open-id")).thenReturn(new WechatBindingRow(1, 7, "wx_app", "test-open-id", now, now));
        when(bindings.findByUserForUpdate(7)).thenReturn(null);
        assertThatThrownBy(() -> auth.loginWechat("fresh-code"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("WECHAT_BIND_REQUIRED"));
        verify(sessions, never()).insert(any());
    }

    @Test
    void unbindRequiresPasswordAndRevokesOnlyMiniprogramSessions() {
        assertThatThrownBy(() -> auth.unbindWechat(principal, "incorrect")).isInstanceOf(ApiException.class);
        verify(bindings, never()).deleteByUser(anyLong());
        auth.unbindWechat(principal, "OldPassword1");
        verify(bindings).deleteByUser(7);
        verify(sessions).revokeMiniprogram(7, now);
        verify(sessions, never()).revokeAll(anyLong(), any());
    }
}
