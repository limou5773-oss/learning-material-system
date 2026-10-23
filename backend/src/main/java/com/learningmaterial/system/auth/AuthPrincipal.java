package com.learningmaterial.system.auth;

public record AuthPrincipal(long userId, long sessionId, String role) { }
