package com.learningmaterial.system.card;

import com.learningmaterial.system.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class CardHierarchyService {
    private final CardMapper cards;
    private final CardOrganizationLockMapper organizationLocks;
    private final CardService cardService;
    private final Clock clock;
    public CardHierarchyService(CardMapper cards, CardOrganizationLockMapper organizationLocks, CardService cardService, Clock clock) {
        this.cards = cards; this.organizationLocks = organizationLocks; this.cardService = cardService; this.clock = clock;
    }

    @Transactional
    public CardService.CardView setParent(long userId, String id, CardDtos.ParentInput input) {
        if (!input.hasParentCardId()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "必须提供父卡片，null表示根节点");
        long childId = CardService.parseId(id);
        Long parentId = input.getParentCardId() == null ? null : CardService.parseId(input.getParentCardId());
        organizationLocks.ensureRow(userId);
        organizationLocks.lockByUserId(userId);
        Set<Long> ids = new TreeSet<>(); ids.add(childId); if (parentId != null) ids.add(parentId);
        Map<Long, CardRow> locked = new HashMap<>();
        for (long cardId : ids) {
            CardRow row = cards.findOwnedForUpdate(userId, cardId);
            if (row == null) throw missing();
            locked.put(cardId, row);
        }
        CardRow child = locked.get(childId);
        if (!child.getVersion().equals(input.getVersion()))
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "卡片已被修改，请核对后重新编辑", child.getVersion());
        if (parentId != null) {
            Map<Long, Long> parents = new HashMap<>();
            for (CardRow row : cards.listOwnedParents(userId)) parents.put(row.getId(), row.getParentCardId());
            Set<Long> seen = new HashSet<>();
            for (Long ancestor = parentId; ancestor != null; ancestor = parents.get(ancestor)) {
                if (ancestor == childId || !seen.add(ancestor))
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PARENT_CYCLE", "父子层级不能形成循环");
                if (!parents.containsKey(ancestor)) throw missing();
            }
        }
        cards.updateParent(userId, childId, parentId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return cardService.get(userId, id);
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片或祖先卡片不存在"); }
}
