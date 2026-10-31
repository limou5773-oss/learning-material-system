package com.learningmaterial.system.security;

import com.learningmaterial.system.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {
    private static final String SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtProperties settings = new JwtProperties(SECRET, "learning-material-test", Duration.ofDays(7));
    private final JwtTokenProvider jwt = new JwtTokenProvider(settings, clock);
    private final SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));

    @Test
    void bindsYamlStyleSettingsAndCreatesTheProviderWithoutDatabase() {
        new ApplicationContextRunner().withUserConfiguration(JwtConfiguration.class)
            .withPropertyValues("lms.jwt.secret=" + SECRET, "lms.jwt.issuer=learning-material-test", "lms.jwt.ttl=7d")
            .withBean(Clock.class, () -> clock).withBean(JwtTokenProvider.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(JwtProperties.class).ttl()).isEqualTo(Duration.ofDays(7));
                var provider = context.getBean(JwtTokenProvider.class);
                assertThat(provider.verify(provider.issue(7).token())).isEqualTo(7);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtConfiguration { }

    @Test
    void issuesUniqueSignedJwtWithUserAndSevenDayExpiry() {
        var issued = jwt.issue(7);
        assertThat(jwt.verify(issued.token())).isEqualTo(7);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(jwt.issue(7).token()).isNotEqualTo(issued.token());
        var signed = Jwts.parser().verifyWith(key).clock(() -> Date.from(NOW)).build()
            .parseSignedClaims(issued.token());
        assertThat(signed.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(signed.getPayload().getIssuer()).isEqualTo(settings.issuer());
        assertThat(signed.getPayload().getId()).isNotBlank();
        assertThat(signed.getPayload().getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(signed.getPayload().getExpiration().toInstant()).isEqualTo(issued.expiresAt());
    }

    @Test
    void rejectsAlteredSignatureAndDifferentSigningKey() {
        String token = jwt.issue(7).token();
        int signature = token.lastIndexOf('.') + 1;
        String altered = token.substring(0, signature) + (token.charAt(signature) == 'A' ? 'B' : 'A') + token.substring(signature + 1);
        assertThatThrownBy(() -> jwt.verify(altered)).isInstanceOf(JwtException.class);
        String foreign = Jwts.builder().issuer(settings.issuer()).subject("7")
            .expiration(Date.from(NOW.plusSeconds(60))).signWith(Jwts.SIG.HS256.key().build()).compact();
        assertThatThrownBy(() -> jwt.verify(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredJwt() {
        var later = new JwtTokenProvider(settings, Clock.fixed(NOW.plus(Duration.ofDays(7)).plusSeconds(1), ZoneOffset.UTC));
        assertThatThrownBy(() -> later.verify(jwt.issue(7).token())).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsWrongIssuerMissingExpiryUnsignedAndMalformedTokens() {
        String wrongIssuer = Jwts.builder().issuer("another-application").subject("7")
            .expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();
        String missingExpiry = Jwts.builder().issuer(settings.issuer()).subject("7").signWith(key).compact();
        String unsigned = Jwts.builder().issuer(settings.issuer()).subject("7")
            .expiration(Date.from(NOW.plusSeconds(60))).compact();
        for (String token : new String[]{wrongIssuer, missingExpiry, unsigned, "legacy-random-token", "abc.def.ghi"}) {
            assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsOtherAlgorithmsEvenWithTheSameStrongKey() {
        String longSecret = Base64.getEncoder().encodeToString(new byte[64]);
        var provider = new JwtTokenProvider(new JwtProperties(longSecret, settings.issuer(), settings.ttl()), clock);
        String token = Jwts.builder().issuer(settings.issuer()).subject("7")
            .expiration(Date.from(NOW.plusSeconds(60)))
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(longSecret)), Jwts.SIG.HS512).compact();
        assertThatThrownBy(() -> provider.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWeakSigningKeyAndInvalidLifetimeAtStartup() {
        String weak = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new JwtTokenProvider(new JwtProperties(weak, settings.issuer(), settings.ttl()), clock))
            .isInstanceOf(WeakKeyException.class);
        assertThatThrownBy(() -> new JwtProperties(SECRET, settings.issuer(), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
