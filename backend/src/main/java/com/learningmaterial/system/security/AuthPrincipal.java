package com.learningmaterial.system.security;

public record AuthPrincipal(long userId, long sessionId, String role) { }
