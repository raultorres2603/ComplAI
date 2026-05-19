package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import java.util.List;

/**
 * Represents an inline keyboard that appears below a message.
 *
 * <p>Each row in the keyboard is a list of buttons. The keyboard is
 * serialised as a JSON array of button rows under the
 * {@code inline_keyboard} field.
 *
 * @param inlineKeyboard a list of button rows, where each row is a list
 *                       of {@link TelegramInlineKeyboardButton} instances
 */
@Introspected
public record TelegramInlineKeyboardMarkup(
        @JsonProperty("inline_keyboard") List<List<TelegramInlineKeyboardButton>> inlineKeyboard
) {}
