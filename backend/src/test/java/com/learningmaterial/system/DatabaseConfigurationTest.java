package com.learningmaterial.system;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseConfigurationTest {
    private MockEnvironment yamlEnvironment() throws Exception {
        String yaml = """
                spring:
                  datasource:
                    url: jdbc:mysql://127.0.0.1:3306/learning_material_dev
                    username: root
                    password: test-password
                """;
        MockEnvironment environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("test-yaml",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @Test
    void yamlDatasourceSettingsDoNotRequireLegacyEnvironmentVariables() throws Exception {
        MockEnvironment environment = yamlEnvironment();
        assertThat(environment.getProperty("DB_URL")).isNull();
        assertThat(environment.getProperty("DB_USERNAME")).isNull();
        assertThat(environment.getProperty("DB_PASSWORD")).isNull();

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(
                LearningMaterialApplication.class, "requireDatabaseSettings", environment))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.url", "spring.datasource.username", "spring.datasource.password"})
    void blankDatasourceSettingStillFailsWithItsCanonicalName(String setting) throws Exception {
        MockEnvironment environment = yamlEnvironment().withProperty(setting, " ");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                LearningMaterialApplication.class, "requireDatabaseSettings", environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing required database setting: " + setting);
    }
}
