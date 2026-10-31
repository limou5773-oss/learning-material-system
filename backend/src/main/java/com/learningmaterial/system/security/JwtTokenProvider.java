package com.learningmaterial.system.security;

import com.learningmaterial.system.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {
    private final JwtProperties settings;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtTokenProvider(JwtProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(settings.secret()));
        this.parser = Jwts.parser().verifyWith(key).requireIssuer(settings.issuer())
            .clock(() -> Date.from(clock.instant()))
            .sig().clear().add(Jwts.SIG.HS256).and().build();
    }

    public IssuedJwt issue(long userId) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(settings.ttl()).truncatedTo(ChronoUnit.SECONDS);
        String token = Jwts.builder().header().type("JWT").and()
            .issuer(settings.issuer()).subject(Long.toString(userId)).id(UUID.randomUUID().toString())
            .issuedAt(Date.from(issuedAt)).expiration(Date.from(expiresAt))
            .signWith(key, Jwts.SIG.HS256).compact();
        return new IssuedJwt(token, expiresAt);
    }

    public long verify(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null) throw new MalformedJwtException("JWT expiration is required");
        return Long.parseLong(claims.getSubject());
    }

    public record IssuedJwt(String token, Instant expiresAt) { }
}
