package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Represents a Telegram chat (private, group, supergroup, or channel).
 *
 * @param id        unique identifier for this chat
 * @param type      type of chat: "private", "group", "supergroup", or
 *                  "channel"
 * @param firstName the first name of the other party in a private chat,
 *                  or {@code null}
 */
@Introspected
public record TelegramChat(
        long id,
        String type,
        @JsonProperty("first_name") @JsonAlias("first_name") @Nullable String firstName
) {}
