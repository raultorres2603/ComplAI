package cat.complai.services.telegram;

import cat.complai.config.TelegramConfiguration;
import cat.complai.controllers.feedback.dto.FeedbackRequest;
import cat.complai.controllers.telegram.dto.*;
import cat.complai.dto.feedback.FeedbackResult;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.sqs.AskSqsMessage;
import cat.complai.dto.sqs.RedactSqsMessage;
import cat.complai.services.feedback.FeedbackPublisherService;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.telegram.TelegramSessionStore.TelegramMode;
import cat.complai.utilities.s3.S3PdfUploader;
import cat.complai.utilities.sqs.SqsAskPublisher;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Per-chat rate limiting: at most N text messages per second per chat.
    // Key = "cityId:chatId", value = request counter in the current 1-second window.
    // Callback queries are NOT rate-limited (they are user-initiated button presses).
    static final Cache<String, AtomicInteger> CHAT_RATE_LIMIT = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .maximumSize(100_000)
            .build();

    /** Default maximum text messages per second from a single chat. */
    static final int DEFAULT_RATE_LIMIT_PER_SECOND = Integer.parseInt(
            System.getenv().getOrDefault("TELEGRAM_CHAT_RATE_LIMIT_PER_SECOND", "3"));

    // Global per-bot rate limiting: at most N text messages per second across all chats
    // for the same bot (city). Key = "cityId", value = request counter.
    // This is a second layer of defence on top of the per-chat limit.
    static final Cache<String, AtomicInteger> BOT_RATE_LIMIT = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .maximumSize(100)
            .build();

    /** Default maximum text messages per second from a single bot (across all chats). */
    static final int DEFAULT_BOT_RATE_LIMIT_PER_SECOND = Integer.parseInt(
            System.getenv().getOrDefault("TELEGRAM_BOT_RATE_LIMIT_PER_SECOND", "10"));

    /** Maximum allowed length for a non-command message (Telegram's own limit is 4096). */
    static final int MAX_MESSAGE_LENGTH = Integer.parseInt(
            System.getenv().getOrDefault("TELEGRAM_MAX_MESSAGE_LENGTH", "4096"));

    private final TelegramConfiguration telegramConfig;
    private final TelegramSessionStore sessionStore;
    private final SqsComplaintPublisher sqsPublisher;
    private final SqsAskPublisher askPublisher;
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
                              SqsAskPublisher askPublisher,
                              S3PdfUploader s3PdfUploader,
                              FeedbackPublisherService feedbackPublisher,
                              ObjectMapper mapper) {
        this.telegramConfig = telegramConfig;
        this.sessionStore = sessionStore;
        this.sqsPublisher = sqsPublisher;
        this.askPublisher = askPublisher;
        this.s3PdfUploader = s3PdfUploader;
        this.feedbackPublisher = feedbackPublisher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        this.mapper = mapper;
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
        PROCESSED_UPDATE_IDS.put(updateId, true);

        if (update.callbackQuery() != null) {
            handleCallbackQuery(update.callbackQuery(), cityId);
        } else if (update.message() != null && update.message().text() != null) {
            long chatId = update.message().chat().id();

            // Two layers of rate limiting before processing:
            // 1. Bot-wide — protects the entire bot from coordinated floods
            if (isBotRateLimited(cityId)) {
                return;
            }
            // 2. Per-chat — protects individual chat fairness
            if (isRateLimited(cityId, chatId)) {
                return;
            }

            handleMessage(update.message(), cityId);
        }
    }

    /**
     * Checks whether the given chat has exceeded the per-second rate limit.
     *
     * <p>Uses a sliding 1-second window tracked by the static
     * {@link #CHAT_RATE_LIMIT} cache. When the limit is exceeded the message
     * is silently dropped and a warning is logged (no SQS enqueue, no Telegram
     * error reply — returning 200 to Telegram is the fastest way to stop
     * Telegram from retrying).
     *
     * @param cityId the city identifier
     * @param chatId the Telegram chat ID
     * @return {@code true} if the message should be dropped
     */
    private boolean isRateLimited(String cityId, long chatId) {
        String key = cityId + ":" + chatId;
        AtomicInteger counter = CHAT_RATE_LIMIT.get(key, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count > DEFAULT_RATE_LIMIT_PER_SECOND) {
            logger.warning(() -> "Per-chat rate limit exceeded — chatId=" + chatId
                    + " city=" + cityId + " count=" + count);
            return true;
        }
        return false;
    }

    /**
     * Checks whether the total traffic for a bot (city) has exceeded the
     * per-second global rate limit.
     *
     * <p>This is a second layer of defence on top of the per-chat limit.
     * Even if individual chats stay under their limit, a coordinated flood
     * across many chats would be caught here. The limit is tracked in the
     * static {@link #BOT_RATE_LIMIT} cache.
     *
     * @param cityId the city identifier (one bot per city)
     * @return {@code true} if the message should be dropped
     */
    private boolean isBotRateLimited(String cityId) {
        AtomicInteger counter = BOT_RATE_LIMIT.get(cityId, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count > DEFAULT_BOT_RATE_LIMIT_PER_SECOND) {
            logger.warning(() -> "Bot-wide rate limit exceeded — city=" + cityId
                    + " count=" + count);
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Message routing
    // -------------------------------------------------------------------------

    private void handleMessage(TelegramMessage message, String cityId) {
        long chatId = message.chat().id();
        String text = message.text().trim();
        String lang = sessionStore.getLanguage(chatId);

        // Commands are exempt from length validation
        if (text.startsWith("/start")) {
            sessionStore.clearSession(chatId);
            sendWelcome(chatId, cityId, lang);
            return;
        }
        if (text.startsWith("/mode")) {
            sendModeSelection(chatId, cityId, lang);
            return;
        }
        if (text.startsWith("/help")) {
            sendHelp(chatId, cityId, lang);
            return;
        }
        if (text.startsWith("/language")) {
            sendLanguageSelection(chatId, cityId);
            return;
        }

        // Length validation for non-command messages
        if (text.length() > MAX_MESSAGE_LENGTH) {
            logger.warning(() -> "Message too long — chatId=" + chatId
                    + " city=" + cityId + " length=" + text.length());
            String errorMsg = switch (lang) {
                case "CA" -> "El missatge \u00E9s massa llarg. Si us plau, envia'l en menys de 4000 car\u00E0cters.";
                case "ES" -> "El mensaje es demasiado largo. Por favor, envíalo en menos de 4000 caracteres.";
                case "FR" -> "Le message est trop long. Veuillez l'envoyer en moins de 4000 caractères.";
                default -> "The message is too long. Please send it in under 4000 characters.";
            };
            sendMessage(chatId, errorMsg, null, cityId);
            return;
        }

        handleTextByMode(chatId, text, cityId, lang);
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
            case "FR" -> "Bienvenue à l'assistant municipal de " + cityDisplayName + "! 👋\n\n"
                    + "Je peux vous aider avec:\n"
                    + "💬 Des questions sur les procédures, événements et services municipaux\n"
                    + "📝 Rédaction de plaintes et réclamations\n"
                    + "💡 Envoyer des suggestions\n\n"
                    + "Sélectionnez une option ou écrivez votre question directement.";
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
            case "FR" -> "🤖 Assistant Municipal - Aide\n\n"
                    + "Commandes disponibles:\n"
                    + "/start - Démarrer la conversation\n"
                    + "/mode - Changer de mode\n"
                    + "/language - Changer de langue\n"
                    + "/help - Afficher cette aide\n\n"
                    + "💬 Mode Question: Posez des questions sur les procédures, événements ou services.\n"
                    + "📝 Mode Plainte: Rédiger une réclamation formelle.\n"
                    + "💡 Mode Suggestion: Envoyez vos suggestions.";
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
                        new TelegramInlineKeyboardButton("🇬🇧 English", "lang_en"),
                        new TelegramInlineKeyboardButton("🇫🇷 Français", "lang_fr")
                )
        ));
        sendMessage(chatId, "Selecciona el teu idioma / Selecciona tu idioma / Select your language / Choisissez votre langue:",
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
                    case "FR" -> "💬 Mode Question activé. Écrivez votre question sur les procédures, événements ou services municipaux.";
                    default -> "💬 Ask Mode activated. Write your question about procedures, events or municipal services.";
                };
                sendMessage(chatId, msg, null, cityId);
            }
            case "redact" -> {
                sessionStore.setMode(chatId, TelegramMode.REDACT);
                String msg = switch (lang) {
                    case "CA" -> "📝 Mode Queixa activat. Descriu la teva queixa o reclamació en detall.";
                    case "ES" -> "📝 Modo Queja activado. Describe tu queja o reclamación en detalle.";
                    case "FR" -> "📝 Mode Plainte activé. Décrivez votre plainte ou réclamation en détail.";
                    default -> "📝 Complaint Mode activated. Describe your complaint or claim in detail.";
                };
                sendMessage(chatId, msg, null, cityId);
            }
            case "feedback" -> {
                sessionStore.setMode(chatId, TelegramMode.FEEDBACK);
                String msg = switch (lang) {
                    case "CA" -> "💡 Mode Suggeriment activat. Explica'ns el teu suggeriment o opinió.";
                    case "ES" -> "💡 Modo Sugerencia activado. Cuéntanos tu sugerencia u opinión.";
                    case "FR" -> "💡 Mode Suggestion activé. Partagez votre suggestion ou opinion.";
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
            case "fr" -> "FR";
            default -> currentLang;
        };
        sessionStore.setLanguage(chatId, effectiveCode);

        String confirmMsg = switch (effectiveCode) {
            case "CA" -> "✅ Idioma canviat a Català. Com puc ajudar-te?";
            case "ES" -> "✅ Idioma cambiado a Español. ¿Cómo puedo ayudarte?";
            case "FR" -> "✅ Langue changée en Français. Comment puis-je vous aider ?";
            default -> "✅ Language changed to English. How can I help you?";
        };
        sendMessage(chatId, confirmMsg, buildMainKeyboard(effectiveCode), cityId);
    }

    // -------------------------------------------------------------------------
    // Text processing by mode
    // -------------------------------------------------------------------------

    private void handleTextByMode(long chatId, String text, String cityId, String lang) {
        // 1. Send typing indicator so the user sees the bot is active
        sendChatAction(chatId, "typing", cityId);
        TelegramMode mode = sessionStore.getMode(chatId);

        // 2. For potentially long operations (ASK mode), send a brief
        //    "processing" message with keyboard so the user gets immediate
        //    feedback and can still interact if the AI takes too long.
        if (mode == TelegramMode.ASK || mode == TelegramMode.NONE) {
            sendProcessingMessage(chatId, lang, cityId);
        }

        // 3. Execute the operation
        String conversationId = sessionStore.getOrCreateConversationId(chatId);
        switch (mode) {
            case ASK -> handleAsk(chatId, text, cityId, lang, conversationId);
            case REDACT -> handleRedact(chatId, text, cityId, lang, conversationId);
            case FEEDBACK -> handleFeedback(chatId, text, cityId, lang);
            default -> {
                // Default to ask mode for unmatched mode
                sessionStore.setMode(chatId, TelegramMode.ASK);
                handleAsk(chatId, text, cityId, lang, conversationId);
            }
        }
    }

    /**
     * Sends a brief "processing" message to the user so they get immediate
     * feedback while the AI is computing the answer.
     * <p>
     * The message is sent WITH the main inline keyboard so the user can
     * still interact even if the AI response is slow or times out. Without
     * the keyboard, a timeout would leave the user stranded with no way to
     * continue the conversation.
     */
    private void sendProcessingMessage(long chatId, String lang, String cityId) {
        String msg = switch (lang) {
            case "CA" -> "\uD83E\uDD16 Estic pensant...";
            case "ES" -> "\uD83E\uDD16 Estoy pensando...";
            case "FR" -> "\uD83E\uDD16 Je r\u00E9fl\u00E9chis...";
            default -> "\uD83E\uDD16 Thinking...";
        };
        sendMessage(chatId, msg, buildMainKeyboard(lang), cityId);
    }

    /**
     * Enqueues the user's question to SQS for async processing by the AskWorker Lambda.
     *
     * <p>The "Estic pensant..." message was already sent by {@link #handleTextByMode}
     * before this method was called. The worker Lambda will consume the SQS message,
     * call the AI, and send the answer back to the user via the Telegram Bot API —
     * all outside the 60-second Lambda timeout window.
     */
    private void handleAsk(long chatId, String text, String cityId, String lang, String conversationId) {
        try {
            AskSqsMessage sqsMessage = new AskSqsMessage(chatId, text, cityId, lang, conversationId);
            askPublisher.publish(sqsMessage);
            logger.info(() -> "Ask enqueued — chatId=" + chatId + " city=" + cityId
                    + " conversationId=" + conversationId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ask enqueue failed — chatId=" + chatId + " city=" + cityId, e);
            String errorMsg = switch (lang) {
                case "CA" -> "\u274C Error en processar la consulta. Si us plau, torna-ho a intentar m\u00E9s tard.";
                case "ES" -> "\u274C Error al procesar la consulta. Por favor, int\u00E9ntalo m\u00E1s tarde.";
                case "FR" -> "\u274C Erreur lors du traitement de la demande. Veuillez r\u00E9essayer plus tard.";
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
                case "FR" -> "Merci. J\u2019ai besoin de votre identit\u00E9 pour la r\u00E9clamation.\n\n"
                        + "Veuillez \u00E9crire votre nom, pr\u00E9nom et NIF sur une seule ligne :\n"
                        + "Exemple : <b>Jean Dupont, 12345678Z</b>";
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
                case "FR" -> "✅ Votre r\u00E9clamation est en cours de g\u00E9n\u00E9ration.\n\n"
                        + "Elle sera bient\u00F4t disponible \u00E0 : " + pdfUrl;
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
                case "FR" -> "❌ Erreur lors de la g\u00E9n\u00E9ration de la r\u00E9clamation. Veuillez r\u00E9essayer.";
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
                    case "FR" -> "✅ Merci pour votre suggestion ! Votre avis est tr\u00E8s pr\u00E9cieux pour am\u00E9liorer le service.";
                    default -> "✅ Thank you for your feedback! Your input is very valuable to improve the service.";
                };
            } else {
                msg = switch (lang) {
                    case "CA" -> "❌ No s'ha pogut processar el suggeriment. Si us plau, torna-ho a intentar.";
                    case "ES" -> "❌ No se ha podido procesar la sugerencia. Por favor, inténtalo de nuevo.";
                    case "FR" -> "❌ Impossible de traiter votre suggestion. Veuillez r\u00E9essayer.";
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
                case "FR" -> "❌ Erreur lors du traitement de la suggestion. Veuillez r\u00E9essayer.";
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
            case "FR" -> {
                askLabel = "💬 Demander";
                redactLabel = "📝 Plainte";
                feedbackLabel = "💡 Suggestion";
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
                switch (lang) { case "CA" -> "Nom"; case "ES" -> "Nombre"; case "FR" -> "Nom"; default -> "Name"; }).append("\n");
        if (!isPresent(identity.surname())) missing.append("- ").append(
                switch (lang) { case "CA" -> "Cognoms"; case "ES" -> "Apellidos"; case "FR" -> "Pr\u00E9nom"; default -> "Surname"; }).append("\n");
        if (!isPresent(identity.idNumber())) missing.append("- ").append(
                switch (lang) { case "CA" -> "NIF"; case "ES" -> "NIF"; case "FR" -> "NIF"; default -> "ID Number (NIF)"; }).append("\n");

        return switch (lang) {
            case "CA" -> "Falten les següents dades:\n" + missing + "\n"
                    + "Si us plau, escriu-les en el format: <b>Nom Cognoms, NIF</b>";
            case "ES" -> "Faltan los siguientes datos:\n" + missing + "\n"
                    + "Por favor, escríbelos en el formato: <b>Nombre Apellidos, NIF</b>";
            case "FR" -> "Donn\u00E9es manquantes :\n" + missing + "\n"
                    + "Veuillez les fournir au format : <b>Nom Pr\u00E9nom, NIF</b>";
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
