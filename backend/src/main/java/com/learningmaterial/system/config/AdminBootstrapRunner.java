package com.learningmaterial.system.config;

import com.learningmaterial.system.service.AdminBootstrapService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {
    private final AdminBootstrapService bootstrap;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminBootstrapRunner(AdminBootstrapService bootstrap,
        @Value("${lms.admin-bootstrap.enabled:false}") boolean enabled,
        @Value("${lms.admin-bootstrap.username:}") String username,
        @Value("${lms.admin-bootstrap.password:}") String password,
        @Value("${lms.admin-bootstrap.display-name:}") String displayName) {
        this.bootstrap = bootstrap;
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override public void run(ApplicationArguments args) {
        if (enabled) bootstrap.initialize(username, password, displayName);
    }
}
