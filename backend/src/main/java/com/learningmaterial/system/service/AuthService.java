package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.AuthDtos.LoginInput;
import com.learningmaterial.system.dto.AuthDtos.RegistrationInput;
import com.learningmaterial.system.dto.AuthDtos.SessionToken;
import com.learningmaterial.system.dto.AuthDtos.UserView;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.dto.UserPatch;

/** AuthService 的业务契约。 */
public interface AuthService {
    UserView register(RegistrationInput input);

    SessionToken login(LoginInput input);

    AuthPrincipal authenticate(String token);

    UserView currentUser(AuthPrincipal principal);

    void logout(AuthPrincipal principal);

    UserView updateProfile(AuthPrincipal principal, UserPatch input);

    SessionToken loginWechat(String code);

    UserView bindWechat(AuthPrincipal principal, String code);

    void unbindWechat(AuthPrincipal principal, String currentPassword);

}
