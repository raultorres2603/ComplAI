package cat.complai.services.worker;

import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.sqs.AskSqsMessage;
import cat.complai.services.openrouter.IOpenRouterService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone (non-Micronaut) class that processes a single Telegram ask job.
 *
 * <p>It is extracted from {@link AskWorkerHandler} so that unit tests can instantiate and
 * exercise the core logic directly — without triggering the {@code MicronautRequestHandler}
 * constructor which initialises an {@code ApplicationContext} and attempts to bind an HTTP port.
 *
 * <p>{@link AskWorkerHandler} constructs this class from its DI-managed fields and delegates
 * all record processing to it. The separation keeps DI wiring in the handler and business logic
 * here.
 */
class AskProcessor {

    private static final Logger logger = Logger.getLogger(AskProcessor.class.getName());
    private static final String TELEGRAM_API_BASE = "https://api.telegram.org";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final IOpenRouterService openRouterService;
    private final String telegramToken;
    private final TelegramSender telegramSender;
    private final ObjectMapper mapper;

    /**
     * Production constructor — uses a real {@link HttpClient} for Telegram API calls.
     */
    AskProcessor(IOpenRouterService openRouterService, String telegramToken) {
        this(openRouterService, telegramToken, new RealTelegramSender());
    }

    /**
     * Test constructor — accepts a custom {@link TelegramSender} for mocking.
     */
    AskProcessor(IOpenRouterService openRouterService, String telegramToken, TelegramSender telegramSender) {
        this.openRouterService = openRouterService;
        this.telegramToken = telegramToken;
        this.telegramSender = telegramSender;
        this.mapper = new ObjectMapper();
    }

    /**
     * Calls the AI service and sends the answer back to the user via Telegram.
     *
     * @throws Exception on any failure — the caller reports this as a batch item failure so SQS
     *                   retries the message
     */
    void process(AskSqsMessage message) throws Exception {
        long start = System.currentTimeMillis();
        logger.info(() -> "AskProcessor — starting chatId=" + message.chatId()
                + " cityId=" + message.cityId() + " lang=" + message.lang()
                + " conversationId=" + message.conversationId()
                + " questionLength=" + (message.question() != null ? message.question().length() : 0));

        // cityId is required — the API Lambda must set it when publishing the SQS message.
        String cityId = message.cityId();
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("AskSqsMessage is missing cityId — cannot determine procedures context");
        }

        // Telegram token is required to send the answer back.
        if (telegramToken == null || telegramToken.isBlank()) {
            throw new IllegalStateException("Telegram token is not configured for cityId=" + cityId
                    + " — cannot send answer back to user");
        }

        String conversationId = message.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "telegram-" + message.chatId();
        }

        // Call the AI service
        OpenRouterResponseDto response = openRouterService.ask(
                message.question(), conversationId, cityId);

        logger.info(() -> "AskProcessor — AI responded chatId=" + message.chatId()
                + " success=" + (response != null && response.isSuccess())
                + " hasMessage=" + (response != null && response.getMessage() != null));

        // Build the reply text
        String reply = buildReply(response, message.lang());

        // Send the answer back to the user via Telegram Bot API
        telegramSender.sendMessage(message.chatId(), reply, telegramToken);

        long latencyMs = System.currentTimeMillis() - start;
        logger.info(() -> "AskProcessor — completed successfully chatId=" + message.chatId()
                + " replyLength=" + reply.length() + " totalLatencyMs=" + latencyMs);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    String buildReply(OpenRouterResponseDto response, String lang) {
        if (response != null && response.getMessage() != null && !response.getMessage().isBlank()) {
            return response.getMessage();
        }
        // Fallback error messages
        return switch (lang != null ? lang : "CA") {
            case "ES" -> "\u274C No he podido procesar tu consulta. Por favor, int\u00E9ntalo de nuevo.";
            case "FR" -> "\u274C Je n\u2019ai pas pu traiter votre demande. Veuillez r\u00E9essayer.";
            case "EN" -> "\u274C I could not process your query. Please try again.";
            default -> "\u274C No he pogut processar la teva consulta. Si us plau, torna-ho a intentar.";
        };
    }

    /**
     * Functional interface for sending Telegram messages.
     * Extracted to enable unit testing without real HTTP calls.
     */
    @FunctionalInterface
    interface TelegramSender {
        void sendMessage(long chatId, String text, String token) throws Exception;
    }

    /**
     * Real implementation using {@link HttpClient}.
     */
    private static class RealTelegramSender implements TelegramSender {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public void sendMessage(long chatId, String text, String token) throws Exception {
            String url = TELEGRAM_API_BASE + "/bot" + token + "/sendMessage";
            String json = mapper.writeValueAsString(new TelegramSendMessageRequest(chatId, text, "HTML"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(HTTP_TIMEOUT)
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("Telegram API returned status " + httpResponse.statusCode()
                        + " for chatId=" + chatId + ": " + httpResponse.body());
            }
        }
    }

    /**
     * Minimal request payload for Telegram sendMessage API.
     * Kept as a private inner class to avoid coupling to the controller DTO layer.
     */
    private record TelegramSendMessageRequest(long chat_id, String text, String parse_mode) {}
}
