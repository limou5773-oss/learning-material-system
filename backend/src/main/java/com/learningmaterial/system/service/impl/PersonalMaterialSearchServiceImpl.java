package com.learningmaterial.system.service.impl;

import com.learningmaterial.system.dto.PersonalMaterialMatch;
import com.learningmaterial.system.entity.CardRow;
import com.learningmaterial.system.mapper.PersonalMaterialSearchMapper;
import com.learningmaterial.system.service.PersonalMaterialSearchService;
import com.learningmaterial.system.task.TaskExecutionException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PersonalMaterialSearchServiceImpl implements PersonalMaterialSearchService {
    private final PersonalMaterialSearchMapper cards;

    public PersonalMaterialSearchServiceImpl(PersonalMaterialSearchMapper cards) {
        this.cards = cards;
    }

    @Override
    public List<PersonalMaterialMatch> search(long userId, String keywords, String subject, int limit) {
        if (keywords == null
                || keywords.isBlank()
                || keywords.length() > 80
                || limit < 1
                || limit > 10
                || subject != null && (subject.isBlank() || subject.length() > 64))
            throw new TaskExecutionException("INVALID_TOOL_ARGUMENTS");
        return cards.search(userId, keywords.trim(), subject, limit).stream()
                .map(
                        card -> {
                            String content = card.getContent();
                            int end = Math.min(1200, content.length());
                            if (end < content.length()
                                    && Character.isHighSurrogate(content.charAt(end - 1))
                                    && Character.isLowSurrogate(content.charAt(end))) end--;
                            return new PersonalMaterialMatch(
                                    card.getId().toString(),
                                    card.getVersion(),
                                    card.getTitle(),
                                    content.substring(0, end),
                                    end < content.length());
                        })
                .toList();
    }

    @Override
    public void checkAvailable(long userId, List<PersonalMaterialMatch> matches) {
        for (PersonalMaterialMatch match : matches)
            check(cards.findAvailable(userId, Long.parseLong(match.cardId())), match);
    }

    /** Pre-lock primary and related materials together, before task/card locks. */
    @Override
    public void lockMaterials(long userId, long primaryMaterialId, List<Long> cardIds) {
        cards.lockMaterials(userId, primaryMaterialId, cardIds);
    }

    @Override
    public void lockAndCheck(long userId, List<PersonalMaterialMatch> matches) {
        if (matches.isEmpty()) return;
        var cardIds = matches.stream().map(match -> Long.parseLong(match.cardId())).toList();
        for (var material : cards.lockSourceMaterials(userId, cardIds)) {
            if ("DELETED".equals(material.getStatus())
                    || !"NORMAL".equals(material.getAccessState()))
                throw new TaskExecutionException("AGENT_RELATED_CHANGED");
        }
        for (PersonalMaterialMatch match :
                matches.stream()
                        .sorted(Comparator.comparingLong(m -> Long.parseLong(m.cardId())))
                        .toList())
            check(cards.lockAvailable(userId, Long.parseLong(match.cardId())), match);
    }

    private static void check(CardRow current, PersonalMaterialMatch expected) {
        if (current == null || current.getVersion() != expected.cardVersion())
            throw new TaskExecutionException("AGENT_RELATED_CHANGED");
    }
}
