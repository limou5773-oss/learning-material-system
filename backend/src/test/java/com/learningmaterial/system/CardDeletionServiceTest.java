package com.learningmaterial.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.entity.CardLayoutRow;
import com.learningmaterial.system.entity.CardRow;
import com.learningmaterial.system.mapper.*;
import com.learningmaterial.system.service.impl.CardServiceImpl;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardDeletionServiceTest {
    final CardMapper cards = mock(CardMapper.class);
    final CardLayoutMapper layouts = mock(CardLayoutMapper.class);
    final CardOrganizationLockMapper locks = mock(CardOrganizationLockMapper.class);
    final CardRelationMapper relations = mock(CardRelationMapper.class);
    final FavoriteMapper favorites = mock(FavoriteMapper.class);
    final ObjectMapper json = new ObjectMapper();
    final CardServiceImpl service = new CardServiceImpl(cards, layouts, locks, relations, favorites, json, Clock.systemUTC());

    @Test
    void deletionPromotesOnlyDirectChildrenAndRemovesVisibleReferences() throws Exception {
        var layout = new CardLayoutRow();
        layout.setId(1L); layout.setUserId(7L); layout.setLayoutKey("main"); layout.setVersion(3);
        layout.setItemsJson("[{\"cardId\":\"20\",\"x\":0,\"y\":0,\"orderNo\":0},{\"cardId\":\"10\",\"x\":1,\"y\":2,\"orderNo\":1}]");
        when(layouts.listOwnedForUpdate(7L)).thenReturn(List.of(layout));
        when(cards.listDirectChildIds(7L, 20L)).thenReturn(List.of(10L, 30L));
        for (long id : List.of(10L, 20L, 30L)) {
            var card = new CardRow(); card.setId(id); card.setVersion(2);
            when(cards.findOwnedForUpdate(7L, id)).thenReturn(card);
        }

        service.delete(7L, "20", 2);

        var order = inOrder(locks, layouts, cards);
        order.verify(locks).ensureRow(7L);
        order.verify(locks).lockByUserId(7L);
        order.verify(layouts).listOwnedForUpdate(7L);
        order.verify(cards).findOwnedForUpdate(7L, 10L);
        order.verify(cards).findOwnedForUpdate(7L, 20L);
        order.verify(cards).findOwnedForUpdate(7L, 30L);
        verify(cards).updateParent(eq(7L), eq(10L), isNull(), any());
        verify(cards).updateParent(eq(7L), eq(30L), isNull(), any());
        verify(cards).softDelete(eq(7L), eq(20L), any());
        verify(relations).deleteForCard(7L, 20L);
        verify(favorites).removeCard(7L, 20L);
        verify(layouts).update(layout);
        assertEquals(4, layout.getVersion());
        assertEquals("10", json.readTree(layout.getItemsJson()).get(0).path("cardId").asText());
        assertEquals(1, json.readTree(layout.getItemsJson()).size());
        verify(cards, times(2)).updateParent(anyLong(), anyLong(), isNull(), any());
    }

    @Test
    void staleVersionReportsCurrentVersionWithoutDeleting() {
        var card = new CardRow(); card.setVersion(5);
        when(cards.findOwnedForUpdate(7L, 20L)).thenReturn(card);
        var error = assertThrows(ApiException.class, () -> service.delete(7L, "20", 2));
        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals(5, error.currentVersion());
        verify(cards, never()).softDelete(anyLong(), anyLong(), any());
        verifyNoInteractions(relations, favorites);
    }

    @Test
    void invalidAndUnownedIdsAreNotFound() {
        for (String id : List.of("invalid", "0", "9223372036854775808", "20")) {
            assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.delete(7L, id, 1)).status());
        }
        verify(cards, never()).softDelete(anyLong(), anyLong(), any());
    }
}
