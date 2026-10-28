package com.learningmaterial.system.dto;



public record PersonalMaterialMatch(
        String cardId, int cardVersion, String title, String snippet, boolean truncated) {}
