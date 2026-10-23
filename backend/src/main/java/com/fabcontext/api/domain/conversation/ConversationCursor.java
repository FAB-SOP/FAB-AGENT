package com.fabcontext.api.domain.conversation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Json;

/** 시각·종류·영속 ID 기준 keyset. 새 기록이 늘어도 offset으로 과거 페이지를 밀지 않는다. */
final class ConversationCursor {
    private ConversationCursor() {}
    record Key(Instant at, int rank, String id) {}
    private record Token(String incidentId, Instant at, String type, String id) {}
    static final Comparator<Key> ORDER = Comparator.comparing(Key::at).thenComparingInt(Key::rank).thenComparing(Key::id);
    static Key key(ConversationDtos.Entry entry) { return new Key(entry.occurredAt(), entry.type().ordinal(), entry.entryId()); }
    static String encode(String incidentId, Key key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(new Token(incidentId, key.at(), ConversationDtos.Kind.values()[key.rank()].name(), key.id())).getBytes(StandardCharsets.UTF_8));
    }
    static Key decode(String cursor, String incidentId) {
        if (cursor == null) return null;
        try {
            if (cursor.isBlank() || cursor.length() > 2048) throw new IllegalArgumentException();
            Token token = Json.read(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8), new tools.jackson.core.type.TypeReference<Token>() {});
            return validate(token, incidentId);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("INVALID_CURSOR", "이 사건의 conversation 응답에서 받은 cursor를 사용하세요.");
        }
    }
    private static Key validate(Token token, String incidentId) {
        if (!incidentId.equals(token.incidentId()) || token.at() == null || token.id() == null || token.id().isBlank()
                || token.type() == null) throw new IllegalArgumentException();
        return new Key(token.at(), ConversationDtos.Kind.valueOf(token.type()).ordinal(), token.id());
    }
}
