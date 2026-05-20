package cat.complai.services.telegram;

import cat.complai.config.TelegramConfiguration;
import cat.complai.controllers.feedback.dto.FeedbackRequest;
import cat.complai.controllers.telegram.dto.*;
import cat.complai.dto.feedback.FeedbackResult;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.sqs.RedactSqsMessage;
import cat.complai.services.feedback.FeedbackPublisherService;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.telegram.TelegramSessionStore.TelegramMode;
import cat.complai.utilities.s3.S3PdfUploader;
import cat.complai.utilities.sqs.SqsComplaintPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates Telegram bot interactions.
 *
 * <p>Receives parsed updates from {@link cat.complai.controllers.telegram.TelegramController},
 * routes commands, manages per-chat sessions, and delegates to existing ComplAI services
 * (OpenRouter, feedback publisher, SQS complaint publisher).
 *
 * <p>Communicates with the Telegram Bot API via {@link java.net.http.HttpClient}.
 */
@Singleton
public class TelegramBotService {

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final String S3_KEY_PREFIX = "complaints/telegram/";

    // Deduplication cache for Telegram update IDs
    // TTL of 60 seconds covers Telegram's retry window; entries expire naturally.
    static final Cache<Long, Boolean> PROCESSED_UPDATE_IDS = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    private final TelegramConfiguration telegramConfig;
    private final TelegramSessionStore sessionStore;
    private final IOpenRouterService openRouterService;
    private final ConversationManagementService conversationService;
    private final SqsComplaintPublisher sqsPublisher;
    private final S3PdfUploader s3PdfUploader;
    private final FeedbackPublisherService feedbackPublisher;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Logger logger = Logger.getLogger(TelegramBotService.class.getName());

    @Inject
    public TelegramBotService(TelegramConfiguration telegramConfig,
                              TelegramSessionStore sessionStore,
                              IOpenRouterService openRouterService,
                              ConversationManagementService conversationService,
                              SqsComplaintPublisher sqsPublisher,
                              S3PdfUploader s3PdfUploader,
                              FeedbackPublisherService feedbackPublisher) {
        this.telegramConfig = telegramConfig;
        this.sessionStore = sessionStore;
        this.openRouterService = openRouterService;
        this.conversationService = conversationService;
        this.sqsPublisher = sqsPublisher;
        this.s3PdfUploader = s3PdfUploader;
        this.feedbackPublisher = feedbackPublisher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        this.mapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Process an incoming Telegram update for the given city.
     * Routes callback queries and text messages to their appropriate handlers.
     * <p>
     * Deduplicates webhook deliveries by {@code updateId}: if an update has
     * already been seen (Caffeine cache, 60 s TTL) it is silently skipped.
     * This prevents duplicate processing when Telegram retries the webhook
     * because our synchronous AI processing takes longer than Telegram's
     * webhook timeout window.
     */
    public void processUpdate(TelegramUpdate update, String cityId) {
        if (update == null) return;

        // Deduplication: skip if we already processed this updateId
        long updateId = update.updateId();
        if (PROCESSED_UPDATE_IDS.getIfPresent(updateId) != null) {
            logger.fine(() -> "Skipping duplicate updateId=" + updateId);
            return;
        }
        PROCESSED_UPDATE_IDS.put(updateId, Boolean.TRUE);

        if (update.callbackQuery() != null) {
            handleCallbackQuery(update.callbackQuery(), cityId);
        } else if (update.message() != null && update.message().text() != null) {
            handleMessage(update.message(), cityId);
        }
    }

    // -------------------------------------------------------------------------
    // Message routing
    // -------------------------------------------------------------------------

    private void handleMessage(TelegramMessage message, String cityId) {
        long chatId = message.chat().id();
        String text = message.text().trim();
        String lang = sessionStore.getLanguage(chatId);

        if (text.startsWith("/start")) {
            sessionStore.clearSession(chatId);
            sendWelcome(chatId, cityId, lang);
        } else if (text.startsWith("/mode")) {
            sendModeSelection(chatId, cityId, lang);
        } else if (text.startsWith("/help")) {
            sendHelp(chatId, cityId, lang);
        } else if (text.startsWith("/language")) {
            sendLanguageSelection(chatId, cityId);
        } else {
            handleTextByMode(chatId, text, cityId, lang);
        }
    }

    private void handleCallbackQuery(TelegramCallbackQuery callback, String cityId) {
        long chatId = callback.fromUser().id();
        String data = callback.data();
        if (data == null) return;

        String lang = sessionStore.getLanguage(chatId);

        if (data.startsWith("mode_")) {
            handleModeSelection(chatId, data.substring("mode_".length()), cityId, lang);
        } else if (data.startsWith("lang_")) {
            handleLanguageSelection(chatId, data.substring("lang_".length()), lang, cityId);
        }

        // Acknowledge the callback query
        answerCallbackQuery(callback.id(), cityId);
    }

    // -------------------------------------------------------------------------
    // Command handlers
    // -------------------------------------------------------------------------

    private void sendWelcome(long chatId, String cityId, String lang) {
        String cityDisplayName = resolveCityDisplayName(cityId);
        String message = switch (lang) {
            case "CA" -> "Benvingut/da a l'assistent municipal de " + cityDisplayName + "! 👋\n\n"
                    + "Puc ajudar-te amb:\n"
                    + "💬 Preguntes sobre tràmits, esdeveniments i serveis municipals\n"
                    + "📝 Redacció de queixes i reclamacions\n"
                    + "💡 Enviar suggeriments\n\n"
                    + "Selecciona una opció o escriu la teva consulta directament.";
            case "ES" -> "¡Bienvenido/a al asistente municipal de " + cityDisplayName + "! 👋\n\n"
                    + "Puedo ayudarte con:\n"
                    + "💬 Preguntas sobre trámites, eventos y servicios municipales\n"
                    + "📝 Redacción de quejas y reclamaciones\n"
                    + "💡 Enviar sugerencias\n\n"
                    + "Selecciona una opción o escribe tu consulta directamente.";
            default -> "Welcome to the municipal assistant of " + cityDisplayName + "! 👋\n\n"
                    + "I can help you with:\n"
                    + "💬 Questions about procedures, events and municipal services\n"
                    + "📝 Drafting complaints and claims\n"
                    + "💡 Sending feedback\n\n"
                    + "Select an option or type your query directly.";
        };
        sendMessage(chatId, message, buildMainKeyboard(lang), cityId);
    }

    private void sendModeSelection(long chatId, String cityId, String lang) {
        String message = switch (lang) {
            case "CA" -> "Selecciona una opció:";
            case "ES" -> "Selecciona una opción:";
            default -> "Select an option:";
        };
        sendMessage(chatId, message, buildMainKeyboard(lang), cityId);
    }

    private void sendHelp(long chatId, String cityId, String lang) {
        String message = switch (lang) {
            case "CA" -> "🤖 Assistent Municipal - Ajuda\n\n"
                    + "Comandes disponibles:\n"
                    + "/start - Iniciar conversa\n"
                    + "/mode - Canviar de mode\n"
                    + "/language - Canviar idioma\n"
                    + "/help - Mostrar aquesta ajuda\n\n"
                    + "💬 Mode Pregunta: Fes qualsevol consulta sobre tràmits, esdeveniments o serveis.\n"
                    + "📝 Mode Queixa: Redacta una reclamació formal.\n"
                    + "💡 Mode Suggeriment: Envia els teus suggeriments.";
            case "ES" -> "🤖 Asistente Municipal - Ayuda\n\n"
                    + "Comandos disponibles:\n"
                    + "/start - Iniciar conversación\n"
                    + "/mode - Cambiar de modo\n"
                    + "/language - Cambiar idioma\n"
                    + "/help - Mostrar esta ayuda\n\n"
                    + "💬 Modo Pregunta: Haz cualquier consulta sobre trámites, eventos o servicios.\n"
                    + "📝 Modo Queja: Redacta una reclamación formal.\n"
                    + "💡 Modo Sugerencia: Envía tus sugerencias.";
            default -> "🤖 Municipal Assistant - Help\n\n"
                    + "Available commands:\n"
                    + "/start - Start conversation\n"
                    + "/mode - Change mode\n"
                    + "/language - Change language\n"
                    + "/help - Show this help\n\n"
                    + "💬 Ask Mode: Ask questions about procedures, events or services.\n"
                    + "📝 Complaint Mode: Draft a formal complaint.\n"
                    + "💡 Feedback Mode: Send your suggestions.";
        };
        sendMessage(chatId, message, null, cityId);
    }

    private void sendLanguageSelection(long chatId, String cityId) {
        TelegramInlineKeyboardMarkup keyboard = new TelegramInlineKeyboardMarkup(List.of(
                List.of(
                        new TelegramInlineKeyboardButton("🇨🇦 Català", "lang_ca"),
                        new TelegramInlineKeyboardButton("🇪🇸 Español", "lang_es")
                ),
                List.of(
                        new TelegramInlineKeyboardButton("🇬🇧 English", "lang_en")
                )
        ));
        sendMessage(chatId, "Selecciona el teu idioma / Selecciona tu idioma / Select your language:",
                keyboard, cityId);
    }

    // -------------------------------------------------------------------------
    // Mode selection handlers
    // -------------------------------------------------------------------------

    private void handleModeSelection(long chatId, String mode, String cityId, String lang) {
        switch (mode) {
            case "ask" -> {
                sessionStore.setMode(chatId, TelegramMode.ASK);
                String msg = switch (lang) {
                    case "CA" -> "💬 Mode Pregunta activat. Escriu la teva consulta sobre tràmits, esdeveniments o serveis municipals.";
                    case "ES" -> "💬 Modo Pregunta activado. Escribe tu consulta sobre trámites, eventos o servicios municipales.";
                    default -> "💬 Ask Mode activated. Write your question about procedures, events or municipal services.";
                };
                sendMessage(chatId, msg, null, cityId);
            }
            case "redact" -> {
                sessionStore.setMode(chatId, TelegramMode.REDACT);
                String msg = switch (lang) {
                    case "CA" -> "📝 Mode Queixa activat. Descriu la teva queixa o reclamació en detall.";
                    case "ES" -> "📝 Modo Queja activado. Describe tu queja o reclamación en detalle.";
                    default -> "📝 Complaint Mode activated. Describe your complaint or claim in detail.";
                };
                sendMessage(chatId, msg, null, cityId);
            }
            case "feedback" -> {
                sessionStore.setMode(chatId, TelegramMode.FEEDBACK);
                String msg = switch (lang) {
                    case "CA" -> "💡 Mode Suggeriment activat. Explica'ns el teu suggeriment o opinió.";
                    case "ES" -> "💡 Modo Sugerencia activado. Cuéntanos tu sugerencia u opinión.";
                    default -> "💡 Feedback Mode activated. Tell us your suggestion or opinion.";
                };
                sendMessage(chatId, msg, null, cityId);
            }
            default -> sendModeSelection(chatId, cityId, lang);
        }
    }

    private void handleLanguageSelection(long chatId, String langCode, String currentLang, String cityId) {
        String effectiveCode = switch (langCode.toLowerCase()) {
            case "ca" -> "CA";
            case "es" -> "ES";
            case "en" -> "EN";
            default -> currentLang;
        };
        sessionStore.setLanguage(chatId, effectiveCode);

        String confirmMsg = switch (effectiveCode) {
            case "CA" -> "✅ Idioma canviat a Català. Com puc ajudar-te?";
            case "ES" -> "✅ Idioma cambiado a Español. ¿Cómo puedo ayudarte?";
            default -> "✅ Language changed to English. How can I help you?";
        };
        sendMessage(chatId, confirmMsg, buildMainKeyboard(effectiveCode), cityId);
    }

    // -------------------------------------------------------------------------
    // Text processing by mode
    // -------------------------------------------------------------------------

    private void handleTextByMode(long chatId, String text, String cityId, String lang) {
        // Send typing indicator and a brief "processing" message
        sendChatAction(chatId, "typing", cityId);
        sendProcessingMessage(chatId, lang, cityId);

        TelegramMode mode = sessionStore.getMode(chatId);
        String conversationId = sessionStore.getOrCreateConversationId(chatId);

        switch (mode) {
            case ASK -> withTypingRefresh(chatId, cityId, () -> {
                handleAsk(chatId, text, cityId, lang, conversationId);
                return null;
            });
            case REDACT -> handleRedact(chatId, text, cityId, lang, conversationId);
            case FEEDBACK -> handleFeedback(chatId, text, cityId, lang);
            default -> {
                // Default to ask mode for unmatched mode
                sessionStore.setMode(chatId, TelegramMode.ASK);
                withTypingRefresh(chatId, cityId, () -> {
                    handleAsk(chatId, text, cityId, lang, conversationId);
                    return null;
                });
            }
        }
    }

    /**
     * Sends a brief "processing" message to the user so they get immediate
     * feedback while the AI is computing the answer. The message is ephemeral
     * in nature — once the real answer arrives it supersedes this placeholder.
     */
    private void sendProcessingMessage(long chatId, String lang, String cityId) {
        String msg = switch (lang) {
            case "CA" -> "\uD83E\uDD16 Estic pensant...";
            case "ES" -> "\uD83E\uDD16 Estoy pensando...";
            default -> "\uD83E\uDD16 Thinking...";
        };
        sendMessage(chatId, msg, null, cityId);
    }

    /**
     * Executes a long-running task while periodically sending the Telegram
     * "typing" chat action every 4 seconds. This keeps the typing indicator
     * visible to the user even when AI processing takes 20-30+ seconds.
     * <p>
     * The refresher runs on a daemon thread and is always shut down in the
     * {@code finally} block, so it cannot outlive the request even if the
     * task throws.
     *
     * @param chatId  Telegram chat ID
     * @param cityId  city identifier (for token resolution)
     * @param task    the blocking work to execute
     * @param <T>     return type of the task
     * @return the task's result
     */
    private <T> T withTypingRefresh(long chatId, String cityId, Callable<T> task) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telegram-typing-" + chatId);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> sendChatAction(chatId, "typing", cityId),
                3, 4, TimeUnit.SECONDS);
        try {
            return task.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Unexpected error during typing-refreshed task", e);
        } finally {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleAsk(long chatId, String text, String cityId, String lang, String conversationId) {
        try {
            OpenRouterResponseDto response = openRouterService.ask(text, conversationId, cityId);
            String reply;
            if (response != null && response.getMessage() != null) {
                reply = response.getMessage();
            } else if (response != null && response.getErrorCode() == OpenRouterErrorCode.TIMEOUT) {
                // Specific timeout message — AI took too long
                reply = switch (lang) {
                    case "CA" -> "\u23F1\uFE0F La consulta est\u00E0 trigant m\u00E9s del compte. Si us plau, torna-ho a intentar en uns segons.";
                    case "ES" -> "\u23F1\uFE0F La consulta est\u00E1 tardando m\u00E1s de lo normal. Por favor, int\u00E9ntalo de nuevo en unos segundos.";
                    default -> "\u23F1\uFE0F The query is taking longer than expected. Please try again in a few seconds.";
                };
            } else {
                reply = switch (lang) {
                    case "CA" -> "No he pogut processar la teva consulta. Si us plau, torna-ho a intentar.";
                    case "ES" -> "No he podido procesar tu consulta. Por favor, int\u00E9ntalo de nuevo.";
                    default -> "I could not process your query. Please try again.";
                };
            }
            sendMessage(chatId, reply, buildMainKeyboard(lang), cityId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ask failed — chatId=" + chatId + " city=" + cityId, e);
            String errorMsg = switch (lang) {
                case "CA" -> "\u274C Error en processar la consulta. Si us plau, torna-ho a intentar m\u00E9s tard.";
                case "ES" -> "\u274C Error al procesar la consulta. Por favor, int\u00E9ntalo m\u00E1s tarde.";
                default -> "\u274C Error processing your query. Please try again later.";
            };
            sendMessage(chatId, errorMsg, null, cityId);
        }
    }

    private void handleRedact(long chatId, String text, String cityId, String lang, String conversationId) {
        // Check if there is a pending complaint text (identity collection phase)
        String pendingComplaint = sessionStore.getAndClearPendingComplaintText(chatId);

        if (pendingComplaint == null) {
            // First step: collect complaint text
            sessionStore.setPendingComplaintText(chatId, text);
            String msg = switch (lang) {
                case "CA" -> "Gràcies. Ara necessito la teva identificació per a la reclamació.\n\n"
                        + "Si us plau, escriu el teu nom, cognoms i NIF en una sola línia:\n"
                        + "Exemple: <b>Joan García Pérez, 12345678Z</b>";
                case "ES" -> "Gracias. Ahora necesito tu identificación para la reclamación.\n\n"
                        + "Por favor, escribe tu nombre, apellidos y NIF en una sola línea:\n"
                        + "Ejemplo: <b>Juan García Pérez, 12345678Z</b>";
                default -> "Thank you. Now I need your identity for the complaint.\n\n"
                        + "Please write your name, surname and ID number in one line:\n"
                        + "Example: <b>John Doe, 12345678Z</b>";
            };
            sendMessage(chatId, msg, null, cityId);
            return;
        }

        // Second step: parse identity from user text and queue the complaint
        ComplainantIdentity identity = parseIdentity(text);

        if (!identity.isComplete()) {
            // Re-store the complaint and ask again
            sessionStore.setPendingComplaintText(chatId, pendingComplaint);
            String missingFields = buildMissingFieldsMessage(identity, lang);
            sendMessage(chatId, missingFields, null, cityId);
            return;
        }

        // Identity is complete — queue to SQS
        try {
            String s3Key = S3_KEY_PREFIX + chatId + "/" + Instant.now().getEpochSecond() + "-complaint.pdf";
            String pdfUrl = s3PdfUploader.generatePresignedGetUrl(s3Key);

            RedactSqsMessage sqsMessage = new RedactSqsMessage(
                    pendingComplaint,
                    identity.name(),
                    identity.surname(),
                    identity.idNumber(),
                    s3Key,
                    conversationId,
                    cityId);
            sqsPublisher.publish(sqsMessage);

            String msg = switch (lang) {
                case "CA" -> "✅ La teva reclamació s'està generant.\n\n"
                        + "Estarà disponible en breu a: " + pdfUrl;
                case "ES" -> "✅ Tu reclamación se está generando.\n\n"
                        + "Estará disponible en breve en: " + pdfUrl;
                default -> "✅ Your complaint is being generated.\n\n"
                        + "It will be available shortly at: " + pdfUrl;
            };
            sendMessage(chatId, msg, buildMainKeyboard(lang), cityId);

            // Reset mode to ASK after successful redact
            sessionStore.setMode(chatId, TelegramMode.ASK);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Redact SQS publish failed — chatId=" + chatId + " city=" + cityId, e);
            String errorMsg = switch (lang) {
                case "CA" -> "❌ Error en generar la reclamació. Si us plau, torna-ho a intentar.";
                case "ES" -> "❌ Error al generar la reclamación. Por favor, inténtalo de nuevo.";
                default -> "❌ Error generating the complaint. Please try again.";
            };
            sendMessage(chatId, errorMsg, null, cityId);
        }
    }

    private void handleFeedback(long chatId, String text, String cityId, String lang) {
        try {
            String userName = "telegram:" + chatId;
            String idUser = String.valueOf(chatId);
            FeedbackRequest request = new FeedbackRequest(userName, idUser, text);
            FeedbackResult result = feedbackPublisher.publishFeedback(request, cityId);

            String msg;
            if (result instanceof FeedbackResult.Success) {
                msg = switch (lang) {
                    case "CA" -> "✅ Gràcies pel teu suggeriment! El teu feedback és molt valuós per millorar el servei.";
                    case "ES" -> "✅ ¡Gracias por tu sugerencia! Tu feedback es muy valioso para mejorar el servicio.";
                    default -> "✅ Thank you for your feedback! Your input is very valuable to improve the service.";
                };
            } else {
                msg = switch (lang) {
                    case "CA" -> "❌ No s'ha pogut processar el suggeriment. Si us plau, torna-ho a intentar.";
                    case "ES" -> "❌ No se ha podido procesar la sugerencia. Por favor, inténtalo de nuevo.";
                    default -> "❌ Could not process your feedback. Please try again.";
                };
            }
            sendMessage(chatId, msg, buildMainKeyboard(lang), cityId);

            // Reset mode
            sessionStore.setMode(chatId, TelegramMode.ASK);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Feedback publish failed — chatId=" + chatId + " city=" + cityId, e);
            String errorMsg = switch (lang) {
                case "CA" -> "❌ Error en processar el suggeriment. Si us plau, torna-ho a intentar.";
                case "ES" -> "❌ Error al procesar la sugerencia. Por favor, inténtalo de nuevo.";
                default -> "❌ Error processing feedback. Please try again.";
            };
            sendMessage(chatId, errorMsg, null, cityId);
        }
    }

    // -------------------------------------------------------------------------
    // Telegram Bot API calls
    // -------------------------------------------------------------------------

    private void sendMessage(long chatId, String text,
                             TelegramInlineKeyboardMarkup replyMarkup,
                             String cityId) {
        String token = resolveToken(cityId);
        try {
            TelegramSendMessageRequest payload = new TelegramSendMessageRequest(
                    chatId, text, "HTML", replyMarkup);
            String json = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API_BASE + "/bot" + token + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(HTTP_TIMEOUT)
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "sendMessage failed — chatId=" + chatId, e);
        }
    }

    private void answerCallbackQuery(String callbackQueryId, String cityId) {
        String token = resolveToken(cityId);
        try {
            TelegramAnswerCallbackQueryRequest payload =
                    new TelegramAnswerCallbackQueryRequest(callbackQueryId, null);
            String json = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API_BASE + "/bot" + token + "/answerCallbackQuery"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(HTTP_TIMEOUT)
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "answerCallbackQuery failed — id=" + callbackQueryId, e);
        }
    }

    private void sendChatAction(long chatId, String action, String cityId) {
        String token = resolveToken(cityId);
        try {
            TelegramSendChatActionRequest payload = new TelegramSendChatActionRequest(chatId, action);
            String json = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API_BASE + "/bot" + token + "/sendChatAction"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(HTTP_TIMEOUT)
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // ChatAction failures are non-critical — just log
            logger.log(Level.FINE, "sendChatAction failed — chatId=" + chatId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Keyboard builders
    // -------------------------------------------------------------------------

    private TelegramInlineKeyboardMarkup buildMainKeyboard(String lang) {
        String askLabel, redactLabel, feedbackLabel;
        switch (lang) {
            case "CA" -> {
                askLabel = "💬 Preguntar";
                redactLabel = "📝 Queixa";
                feedbackLabel = "💡 Suggeriment";
            }
            case "ES" -> {
                askLabel = "💬 Preguntar";
                redactLabel = "📝 Queja";
                feedbackLabel = "💡 Sugerencia";
            }
            default -> {
                askLabel = "💬 Ask";
                redactLabel = "📝 Complaint";
                feedbackLabel = "💡 Feedback";
            }
        }
        return new TelegramInlineKeyboardMarkup(List.of(
                List.of(
                        new TelegramInlineKeyboardButton(askLabel, "mode_ask"),
                        new TelegramInlineKeyboardButton(redactLabel, "mode_redact")
                ),
                List.of(
                        new TelegramInlineKeyboardButton(feedbackLabel, "mode_feedback"),
                        new TelegramInlineKeyboardButton("\u2699\ufe0f Idioma / Language", "lang_" + lang.toLowerCase())
                )
        ));
    }

    // -------------------------------------------------------------------------
    // Identity parsing helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a user-provided identity string into a {@link ComplainantIdentity}.
     * Accepts formats: "Name Surname, NIF" or "Name, Surname, NIF".
     */
    static ComplainantIdentity parseIdentity(String input) {
        if (input == null || input.isBlank()) {
            return new ComplainantIdentity(null, null, null);
        }

        // Try comma-separated: "Name, Surname, NIF"
        String[] parts = input.split(",");
        if (parts.length >= 3) {
            return new ComplainantIdentity(
                    parts[0].trim(),
                    parts[1].trim(),
                    parts[2].trim());
        }

        // Try "Name Surname, NIF" (2 parts)
        if (parts.length == 2) {
            String[] nameParts = parts[0].trim().split("\\s+", 2);
            if (nameParts.length >= 2) {
                return new ComplainantIdentity(
                        nameParts[0].trim(),
                        nameParts[1].trim(),
                        parts[1].trim());
            }
            return new ComplainantIdentity(parts[0].trim(), null, parts[1].trim());
        }

        // Single part — try to extract NIF-like pattern
        String trimmed = input.trim();
        String nif = extractNif(trimmed);
        String namePart = nif != null ? trimmed.substring(0, trimmed.indexOf(nif)).trim() : trimmed;

        String[] nameParts = namePart.split("\\s+", 2);
        if (nameParts.length >= 2) {
            return new ComplainantIdentity(nameParts[0].trim(), nameParts[1].trim(), nif);
        }
        return new ComplainantIdentity(nameParts[0].trim(), null, nif);
    }

    private static String extractNif(String text) {
        if (text == null) return null;
        // Match Spanish NIF/NIE patterns: 12345678Z, X1234567Z, etc.
        var matcher = java.util.regex.Pattern.compile("[XYZ]?\\d{7,8}[A-Z]").matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private String buildMissingFieldsMessage(ComplainantIdentity identity, String lang) {
        StringBuilder missing = new StringBuilder();
        if (!isPresent(identity.name())) missing.append("- ").append(
                switch (lang) { case "CA" -> "Nom"; case "ES" -> "Nombre"; default -> "Name"; }).append("\n");
        if (!isPresent(identity.surname())) missing.append("- ").append(
                switch (lang) { case "CA" -> "Cognoms"; case "ES" -> "Apellidos"; default -> "Surname"; }).append("\n");
        if (!isPresent(identity.idNumber())) missing.append("- ").append(
                switch (lang) { case "CA" -> "NIF"; case "ES" -> "NIF"; default -> "ID Number (NIF)"; }).append("\n");

        return switch (lang) {
            case "CA" -> "Falten les següents dades:\n" + missing + "\n"
                    + "Si us plau, escriu-les en el format: <b>Nom Cognoms, NIF</b>";
            case "ES" -> "Faltan los siguientes datos:\n" + missing + "\n"
                    + "Por favor, escríbelos en el formato: <b>Nombre Apellidos, NIF</b>";
            default -> "Missing the following information:\n" + missing + "\n"
                    + "Please provide them in the format: <b>Name Surname, NIF</b>";
        };
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private String resolveToken(String cityId) {
        if (cityId == null) return "";
        try {
            return telegramConfig.getToken(cityId);
        } catch (IllegalArgumentException e) {
            logger.warning(() -> "No Telegram token for city=" + cityId);
            return "";
        }
    }

    private String resolveCityDisplayName(String cityId) {
        if (cityId == null) return "";
        return switch (cityId.toLowerCase()) {
            case "elprat" -> "El Prat de Llobregat";
            case "testcity" -> "Test City";
            default -> cityId.substring(0, 1).toUpperCase() + cityId.substring(1);
        };
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
