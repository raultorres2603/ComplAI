package cat.complai.openrouter.services.procedure;

import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.helpers.CityInfoRagHelper;
import cat.complai.openrouter.helpers.CityInfoRagHelperRegistry;
import cat.complai.openrouter.helpers.EventRagHelper;
import cat.complai.openrouter.helpers.EventRagHelperRegistry;
import cat.complai.openrouter.helpers.NewsRagHelper;
import cat.complai.openrouter.helpers.NewsRagHelperRegistry;
import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.helpers.TransparencyRagHelper;
import cat.complai.openrouter.helpers.TransparencyRagHelperRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Retrieves RAG context blocks for procedures, events, news, and city
 * information
 * to inject into AI prompts.
 *
 * <p>
 * Each domain (procedures, events, news, city-info) is served by its own helper
 * registry
 * that lazily builds an in-memory lexical index per city on first access. This
 * service
 * orchestrates keyword-based intent detection and context extraction, then
 * returns typed
 * result objects that carry both the context text and the source URLs for
 * attribution.
 */
@Singleton
public class ProcedureContextService {

    private static final Set<String> TITLE_STOP_WORDS = Set.of(
            "a", "an", "and", "d", "de", "del", "el", "els", "en", "for", "i", "la", "las",
            "les", "lo", "los", "of", "the", "y");

    private static final List<String> PROCEDURE_KEYWORDS = List.of(
            "how to", "how do i", "what is the process", "procedure", "tramit", "tràmit",
            "requirement", "requirements", "document", "documents", "apply", "application",
            "form", "forms", "permit", "license", "request", "complaint", "claim",
            "where can i", "how can i", "steps", "step by step", "process",
            "recycling", "waste", "garbage", "trash", "center", "collection");

    private static final List<String> MUNICIPAL_SERVICE_KEYWORDS = List.of(
            "ajuntament", "city hall", "municipal", "council");

    private static final List<String> EVENT_KEYWORDS = List.of(
            "event", "events", "evento", "eventos", "esdeveniment", "esdeveniments",
            "agenda", "activity", "activities", "activitat", "activitats",
            "festival", "concert", "exhibition", "exposició", "theater", "teatre",
            "cinema", "sports", "esports", "culture", "cultura", "celebration", "celebració",
            "what's on", "what's happening", "què passa", "agenda cultural", "program",
            "this weekend", "upcoming",
            "capsa", "artesa", "l'artesa", "la capsa", "teatre artesa");

    private static final List<String> EVENT_INTENT_GUARD_KEYWORDS = List.of(
            "event", "events", "evento", "eventos", "esdeveniment", "esdeveniments",
            "agenda", "activity", "activities", "activitat", "activitats",
            "what's on", "what is on", "what's happening", "que passa", "què passa");

    private static final List<String> DATE_WINDOW_KEYWORDS = List.of(
            "today", "tomorrow", "tonight", "this weekend", "next weekend", "weekend",
            "this week", "next week", "this month", "next month",
            "avui", "dema", "demà", "aquesta setmana", "setmana vinent", "cap de setmana", "aquest mes",
            "mes vinent",
            "hoy", "manana", "mañana", "esta semana", "proxima semana", "próxima semana", "fin de semana",
            "este mes", "proximo mes", "próximo mes");

    private static final List<String> MONTH_KEYWORDS = List.of(
            "january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december",
            "gener", "febrer", "marc", "març", "abril", "maig", "juny", "juliol", "agost",
            "setembre", "octubre", "novembre", "desembre",
            "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
            "septiembre", "octubre", "noviembre", "diciembre");

    private static final List<String> RANGE_CONNECTOR_KEYWORDS = List.of(
            "from", "to", "between", "until", "through", "del", "al", "desde", "hasta", "entre");

    private static final Pattern NUMERIC_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b");

    private static final List<String> NEWS_KEYWORDS = List.of(
            "news", "latest news", "recent news", "recent happenings", "current affairs",
            "actuality", "actualitat", "actualidad", "noticies", "notícies", "noticias",
            "ultimes", "últimes", "ultimas", "últimas", "latest", "recent");

    private static final List<String> CITY_INFO_KEYWORDS = List.of(
            "city", "municipal", "ajuntament", "city hall", "serveis", "serveis municipals",
            "services", "public service", "public services", "equipaments", "equipamientos",
            "tourism", "turisme", "turismo", "visit", "visitar", "visita", "informacio",
            "informació", "informacion", "información");

    private static final List<String> TRANSPARENCY_KEYWORDS = List.of(
            "transparència", "transparencia", "transparency",
            "contractes", "subvencions", "pressupost",
            "licitació", "licitacion", "convenis", "factures");

    private static final List<String> CONVERSATIONAL_SHORT_CIRCUITS = List.of(
            "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
            "hola", "bon dia", "bona tarda", "how are you", "what is the weather", "weather",
            "tell me a joke", "who are you", "what's your name", "thank you", "thanks");

    private final ProcedureRagHelperRegistry ragRegistry;
    private final EventRagHelperRegistry eventRagRegistry;
    private final NewsRagHelperRegistry newsRagRegistry;
    private final CityInfoRagHelperRegistry cityInfoRagRegistry;
    private final TransparencyRagHelperRegistry transparencyRagRegistry;
    private final RedactPromptBuilder promptBuilder;
    private final Logger logger = Logger.getLogger(ProcedureContextService.class.getName());
    private final ConcurrentHashMap<String, CityDetectionIndex> detectionIndexByCity = new ConcurrentHashMap<>();

    /**
     * Constructs the service with all RAG helper registries and the prompt builder.
     *
     * @param ragRegistry         registry for procedure RAG helpers
     * @param eventRagRegistry    registry for event RAG helpers
     * @param newsRagRegistry     registry for news RAG helpers
     * @param cityInfoRagRegistry registry for city-info RAG helpers
     * @param promptBuilder       builder used to resolve procedure keywords per
     *                            city
     */
    @Inject
    public ProcedureContextService(ProcedureRagHelperRegistry ragRegistry, EventRagHelperRegistry eventRagRegistry,
            NewsRagHelperRegistry newsRagRegistry,
            CityInfoRagHelperRegistry cityInfoRagRegistry,
            TransparencyRagHelperRegistry transparencyRagRegistry,
            RedactPromptBuilder promptBuilder) {
        this.ragRegistry = ragRegistry;
        this.eventRagRegistry = eventRagRegistry;
        this.newsRagRegistry = newsRagRegistry;
        this.cityInfoRagRegistry = cityInfoRagRegistry;
        this.transparencyRagRegistry = transparencyRagRegistry;
        this.promptBuilder = promptBuilder;
    }

    public ProcedureContextService(ProcedureRagHelperRegistry ragRegistry, EventRagHelperRegistry eventRagRegistry,
            RedactPromptBuilder promptBuilder) {
        this(ragRegistry, eventRagRegistry, new NewsRagHelperRegistry(), new CityInfoRagHelperRegistry(),
                new TransparencyRagHelperRegistry(), promptBuilder);
    }

    /**
     * Result type for procedure context extraction: context block string and the
     * list of source URLs.
     */
    public static class ProcedureContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        public ProcedureContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public String getContextBlock() {
            return contextBlock;
        }

        public List<Source> getSources() {
            return sources;
        }
    }

    /**
     * Result type for event context extraction: context block string and the list
     * of source URLs.
     */
    public static class EventContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        public EventContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public String getContextBlock() {
            return contextBlock;
        }

        public List<Source> getSources() {
            return sources;
        }
    }

    /**
     * Result type for news context extraction.
     */
    public static class NewsContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        /**
         * Constructs a news context result.
         *
         * @param contextBlock the RAG context text to inject into the AI prompt
         * @param sources      the source documents used to build the context
         */
        public NewsContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        /**
         * Returns the RAG context text snippet for the AI prompt.
         *
         * @return context block text
         */
        public String getContextBlock() {
            return contextBlock;
        }

        /**
         * Returns the unmodifiable list of source documents.
         *
         * @return list of sources
         */
        public List<Source> getSources() {
            return sources;
        }
    }

    /**
     * Result type for city-information context extraction.
     */
    public static class CityInfoContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        /**
         * Constructs a city-info context result.
         *
         * @param contextBlock the RAG context text to inject into the AI prompt
         * @param sources      the source documents used to build the context
         */
        public CityInfoContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        /**
         * Returns the RAG context text snippet for the AI prompt.
         *
         * @return context block text
         */
        public String getContextBlock() {
            return contextBlock;
        }

        /**
         * Returns the unmodifiable list of source documents.
         *
         * @return list of sources
         */
        public List<Source> getSources() {
            return sources;
        }
    }

    public static class TransparencyContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        public TransparencyContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public String getContextBlock() {
            return contextBlock;
        }

        public List<Source> getSources() {
            return sources;
        }
    }

    /**
     * Captures which RAG context domains are required for a given query.
     */
    public static final class ContextRequirements {
        private static final ContextRequirements NONE = new ContextRequirements(false, false, false, false, false);

        private final boolean needsProcedureContext;
        private final boolean needsEventContext;
        private final boolean needsNewsContext;
        private final boolean needsCityInfoContext;
        private final boolean needsTransparencyContext;

        /**
         * Constructs a requirements descriptor.
         *
         * @param needsProcedureContext    {@code true} if procedure context should be
         *                                 fetched
         * @param needsEventContext        {@code true} if event context should be
         *                                 fetched
         * @param needsNewsContext         {@code true} if news context should be
         *                                 fetched
         * @param needsCityInfoContext     {@code true} if city-info context should be
         *                                 fetched
         * @param needsTransparencyContext {@code true} if transparency context should
         *                                 be
         *                                 fetched
         */
        public ContextRequirements(boolean needsProcedureContext, boolean needsEventContext, boolean needsNewsContext,
                boolean needsCityInfoContext, boolean needsTransparencyContext) {
            this.needsProcedureContext = needsProcedureContext;
            this.needsEventContext = needsEventContext;
            this.needsNewsContext = needsNewsContext;
            this.needsCityInfoContext = needsCityInfoContext;
            this.needsTransparencyContext = needsTransparencyContext;
        }

        /**
         * Returns an instance that requires no context at all.
         *
         * @return shared NONE instance
         */
        public static ContextRequirements none() {
            return NONE;
        }

        /**
         * Returns {@code true} if procedure context should be fetched.
         *
         * @return needs-procedure flag
         */
        public boolean needsProcedureContext() {
            return needsProcedureContext;
        }

        /**
         * Returns {@code true} if event context should be fetched.
         *
         * @return needs-event flag
         */
        public boolean needsEventContext() {
            return needsEventContext;
        }

        /**
         * Returns {@code true} if news context should be fetched.
         *
         * @return needs-news flag
         */
        public boolean needsNewsContext() {
            return needsNewsContext;
        }

        /**
         * Returns {@code true} if city-info context should be fetched.
         *
         * @return needs-city-info flag
         */
        public boolean needsCityInfoContext() {
            return needsCityInfoContext;
        }

        public boolean needsTransparencyContext() {
            return needsTransparencyContext;
        }
    }

    private record CityDetectionIndex(TitleMatcher procedureMatcher, TitleMatcher eventMatcher,
            TitleMatcher newsMatcher, TitleMatcher cityInfoMatcher, TitleMatcher transparencyMatcher,
            int procedureTitleCount, int eventTitleCount, int newsTitleCount, int cityInfoTitleCount,
            int transparencyTitleCount) {
    }

    private static final class TitleMatcher {
        private final Map<String, List<String>> titlesByToken;
        private final List<String> fallbackTitles;

        private TitleMatcher(Map<String, List<String>> titlesByToken, List<String> fallbackTitles) {
            this.titlesByToken = titlesByToken;
            this.fallbackTitles = fallbackTitles;
        }

        private static TitleMatcher fromTitles(List<String> normalizedTitles) {
            Map<String, LinkedHashSet<String>> byToken = new LinkedHashMap<>();
            List<String> fallbackTitles = new ArrayList<>();

            for (String normalizedTitle : normalizedTitles) {
                Set<String> tokens = tokenize(normalizedTitle);
                if (tokens.isEmpty()) {
                    fallbackTitles.add(normalizedTitle);
                    continue;
                }
                for (String token : tokens) {
                    byToken.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(normalizedTitle);
                }
            }

            Map<String, List<String>> immutableIndex = new LinkedHashMap<>();
            byToken.forEach((token, titles) -> immutableIndex.put(token, List.copyOf(titles)));
            return new TitleMatcher(Map.copyOf(immutableIndex), List.copyOf(fallbackTitles));
        }

        private boolean matches(String normalizedQuestion) {
            if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
                return false;
            }

            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            for (String token : tokenize(normalizedQuestion)) {
                List<String> titles = titlesByToken.get(token);
                if (titles != null) {
                    candidates.addAll(titles);
                }
            }

            if (candidates.isEmpty()) {
                candidates.addAll(fallbackTitles);
            }

            for (String candidate : candidates) {
                if (normalizedQuestion.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Determines if a question likely needs procedure/municipal information.
     * This avoids expensive RAG searches for conversational queries.
     * Checks against actual procedure titles for more accurate detection.
     */
    public boolean questionNeedsProcedureContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsProcedureContext();
    }

    public ContextRequirements detectContextRequirements(String question, String cityId) {
        if (question == null || question.isBlank()) {
            return ContextRequirements.none();
        }

        String normalizedQuestion = normalize(question);
        long startNanos = System.nanoTime();

        boolean needsProcedure = containsAny(normalizedQuestion, PROCEDURE_KEYWORDS)
                || containsAny(normalizedQuestion, MUNICIPAL_SERVICE_KEYWORDS);
        boolean needsEvent = containsAny(normalizedQuestion, EVENT_KEYWORDS);
        boolean needsNews = containsAny(normalizedQuestion, NEWS_KEYWORDS);
        boolean needsTransparency = containsAny(normalizedQuestion, TRANSPARENCY_KEYWORDS);

        if (needsTransparency) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from transparency keyword path - city=" + cityId
                    + " durationMs=" + durationMs);
            return new ContextRequirements(false, false, false, false, true);
        }

        if (needsNews) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from news keyword path - city=" + cityId
                    + " procedure=false event=false news=true durationMs=" + durationMs);
            return new ContextRequirements(false, false, true, false, false);
        }

        if (!needsProcedure && !needsEvent && isClearlyConversational(normalizedQuestion)) {
            logger.fine(() -> "Context detection short-circuited conversational query — city=" + cityId);
            return ContextRequirements.none();
        }

        if (needsProcedure ^ needsEvent) {
            boolean finalNeedsProcedure = needsProcedure;
            boolean finalNeedsEvent = needsEvent;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection short-circuited keyword query — city=" + cityId
                    + " procedure=" + finalNeedsProcedure
                    + " event=" + finalNeedsEvent
                    + " news=false"
                    + " durationMs=" + durationMs);
            return new ContextRequirements(finalNeedsProcedure, finalNeedsEvent, false, false, false);
        }

        if (needsProcedure && needsEvent) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from keyword path — city=" + cityId
                    + " procedure=true event=true news=false durationMs=" + durationMs);
            return new ContextRequirements(true, true, false, false, false);
        }

        CityDetectionIndex detectionIndex = getOrCreateDetectionIndex(cityId);
        needsProcedure = detectionIndex.procedureMatcher().matches(normalizedQuestion);
        needsEvent = detectionIndex.eventMatcher().matches(normalizedQuestion);
        needsNews = detectionIndex.newsMatcher().matches(normalizedQuestion);

        if (needsNews) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from title path - city=" + cityId
                    + " procedure=false event=false news=true durationMs=" + durationMs
                    + " newsTitles=" + detectionIndex.newsTitleCount());
            return new ContextRequirements(false, false, true, false, false);
        }

        boolean needsTransparencyByTitle = detectionIndex.transparencyMatcher().matches(normalizedQuestion);
        if (needsTransparencyByTitle) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from transparency title path - city=" + cityId
                    + " durationMs=" + durationMs);
            return new ContextRequirements(false, false, false, false, true);
        }

        boolean needsCityInfo = false;
        if (!needsProcedure && !needsEvent && !needsNews) {
            needsCityInfo = containsAny(normalizedQuestion, CITY_INFO_KEYWORDS)
                    || detectionIndex.cityInfoMatcher().matches(normalizedQuestion);
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        boolean finalNeedsProcedure = needsProcedure;
        boolean finalNeedsEvent = needsEvent;
        boolean finalNeedsCityInfo = needsCityInfo;
        logger.fine(() -> "Context detection completed — city=" + cityId
                + " procedure=" + finalNeedsProcedure
                + " event=" + finalNeedsEvent
                + " news=false"
                + " cityInfo=" + finalNeedsCityInfo
                + " durationMs=" + durationMs
                + " procedureTitles=" + detectionIndex.procedureTitleCount()
                + " eventTitles=" + detectionIndex.eventTitleCount()
                + " cityInfoTitles=" + detectionIndex.cityInfoTitleCount());
        return new ContextRequirements(finalNeedsProcedure, finalNeedsEvent, false, finalNeedsCityInfo, false);
    }

    public ProcedureContextResult buildProcedureContextResult(String query, String cityId) {
        try {
            ProcedureRagHelper helper = ragRegistry.getForCity(cityId);
            List<ProcedureRagHelper.Procedure> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new ProcedureContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(p -> new Source(p.url, p.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();
            String contextBlock = promptBuilder.buildProcedureContextBlockFromMatches(matches, cityId);
            return new ProcedureContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build procedure context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new ProcedureContextResult(null, List.of());
        }
    }

    /**
     * Determines if a question likely needs event information.
     * This avoids expensive RAG searches for conversational queries.
     * Checks against actual event titles for more accurate detection.
     */
    public boolean questionNeedsEventContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsEventContext();
    }

    public EventContextResult buildEventContextResult(String query, String cityId) {
        try {
            EventRagHelper helper = eventRagRegistry.getForCity(cityId);
            List<EventRagHelper.Event> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new EventContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(e -> new Source(e.url, e.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();
            String contextBlock = buildEventContextBlockFromMatches(matches, cityId);
            return new EventContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build event context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new EventContextResult(null, List.of());
        }
    }

    public boolean questionNeedsNewsContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsNewsContext();
    }

    public boolean questionNeedsCityInfoContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsCityInfoContext();
    }

    public boolean requiresEventDateWindowClarification(String question, String cityId) {
        if (question == null || question.isBlank()) {
            return false;
        }

        ContextRequirements requirements = detectContextRequirements(question, cityId);
        if (!requirements.needsEventContext() || requirements.needsNewsContext()) {
            return false;
        }

        String normalizedQuestion = normalize(question);
        if (!containsAny(normalizedQuestion, EVENT_INTENT_GUARD_KEYWORDS)) {
            return false;
        }

        return !hasDateWindow(normalizedQuestion, question);
    }

    public NewsContextResult buildNewsContextResult(String query, String cityId) {
        try {
            NewsRagHelper helper = newsRagRegistry.getForCity(cityId);
            List<NewsRagHelper.News> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new NewsContextResult(null, List.of());
            }

            List<Source> sources = matches.stream()
                    .map(item -> new Source(item.url, item.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();

            String contextBlock = buildNewsContextBlockFromMatches(matches, cityId);
            return new NewsContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build news context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new NewsContextResult(null, List.of());
        }
    }

    public CityInfoContextResult buildCityInfoContextResult(String query, String cityId) {
        try {
            CityInfoRagHelper helper = cityInfoRagRegistry.getForCity(cityId);
            List<CityInfoRagHelper.CityInfo> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new CityInfoContextResult(null, List.of());
            }

            List<Source> sources = matches.stream()
                    .map(item -> new Source(item.url, item.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();

            String contextBlock = buildCityInfoContextBlockFromMatches(matches, cityId);
            return new CityInfoContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build city-info context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new CityInfoContextResult(null, List.of());
        }
    }

    private String buildNewsContextBlockFromMatches(List<NewsRagHelper.News> matches, String cityId) {
        if (matches.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CONTEXT FROM CITY NEWS IN ").append(cityId).append(":\n\n");

        for (int i = 0; i < matches.size(); i++) {
            NewsRagHelper.News item = matches.get(i);
            sb.append(i + 1).append(". ").append(item.title).append("\n");
            if (item.publishedAt != null && !item.publishedAt.isBlank()) {
                sb.append("   Published: ").append(item.publishedAt).append("\n");
            }
            if (item.categories != null && !item.categories.isBlank()) {
                sb.append("   Categories: ").append(item.categories).append("\n");
            }
            if (item.summary != null && !item.summary.isBlank()) {
                sb.append("   Summary: ").append(item.summary).append("\n");
            }
            if (item.body != null && !item.body.isBlank()) {
                sb.append("   Details: ").append(item.body).append("\n");
            }
            if (item.url != null && !item.url.isBlank()) {
                sb.append("   Source URL: ").append(item.url).append("\n");
            }
            sb.append("\n");
        }

        sb.append("INSTRUCTIONS:\n");
        sb.append("- Base your answer on the news context above.\n");
        sb.append("- Do not invent news items or URLs.\n");
        sb.append("- MANDATORY: If you cite a news item and it has a Source URL, include that URL in your answer.\n");
        return sb.toString();
    }

    private String buildEventContextBlockFromMatches(List<EventRagHelper.Event> matches, String cityId) {
        if (matches.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Events in ").append(cityId).append(":\n\n");

        for (int i = 0; i < matches.size(); i++) {
            EventRagHelper.Event event = matches.get(i);
            sb.append(i + 1).append(". ").append(event.title).append("\n");

            if (event.date != null && !event.date.isBlank()) {
                sb.append("   Date: ").append(event.date).append("\n");
            }
            if (event.time != null && !event.time.isBlank()) {
                sb.append("   Time: ").append(event.time).append("\n");
            }
            if (event.location != null && !event.location.isBlank()) {
                sb.append("   Location: ").append(event.location).append("\n");
            }
            if (event.eventType != null && !event.eventType.isBlank()) {
                sb.append("   Type: ").append(event.eventType).append("\n");
            }
            if (event.targetAudience != null && !event.targetAudience.isBlank()) {
                sb.append("   Audience: ").append(event.targetAudience).append("\n");
            }
            if (event.description != null && !event.description.isBlank()) {
                sb.append("   Description: ").append(event.description).append("\n");
            }
            if (event.theme != null && !event.theme.isBlank()) {
                sb.append("   Theme: ").append(event.theme).append("\n");
            }
            if (event.url != null && !event.url.isBlank()) {
                sb.append("   Source URL: ").append(event.url).append("\n");
            }
            sb.append("\n");
        }

        sb.append("INSTRUCTIONS:\n");
        sb.append("- Base your answer on the events above.\n");
        sb.append(
                "- MANDATORY: If you mention an event listed above and it has a Source URL, include that URL in your answer.\n");
        sb.append("- NEVER invent event URLs.\n");

        return sb.toString();
    }

    private String buildCityInfoContextBlockFromMatches(List<CityInfoRagHelper.CityInfo> matches, String cityId) {
        if (matches.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CITY INFORMATION IN ").append(cityId).append(":\n\n");

        for (int i = 0; i < matches.size(); i++) {
            CityInfoRagHelper.CityInfo item = matches.get(i);
            sb.append(i + 1).append(". ").append(item.title).append("\n");
            if (item.theme != null && !item.theme.isBlank()) {
                sb.append("   Theme: ").append(item.theme).append("\n");
            }
            if (item.breadcrumbs != null && !item.breadcrumbs.isBlank()) {
                sb.append("   Breadcrumbs: ").append(item.breadcrumbs).append("\n");
            }
            if (item.summary != null && !item.summary.isBlank()) {
                sb.append("   Summary: ").append(item.summary).append("\n");
            }
            if (item.body != null && !item.body.isBlank()) {
                sb.append("   Details: ").append(item.body).append("\n");
            }
            if (item.url != null && !item.url.isBlank()) {
                sb.append("   Source URL: ").append(item.url).append("\n");
            }
            sb.append("\n");
        }

        sb.append("INSTRUCTIONS:\n");
        sb.append("- Base your answer on the city-info context above.\n");
        sb.append("- Do not invent city-info items or URLs.\n");
        sb.append("- MANDATORY: If you cite an item and it has a Source URL, include that URL in your answer.\n");
        return sb.toString();
    }

    /**
     * Async variant of buildProcedureContextResult.
     * Uses the supplied executor when bounded parallelism is required.
     */
    public CompletableFuture<ProcedureContextResult> buildProcedureContextResultAsync(String query, String cityId,
            Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildProcedureContextResult(query, cityId), executor);
    }

    public CompletableFuture<ProcedureContextResult> buildProcedureContextResultAsync(String query, String cityId) {
        return CompletableFuture.completedFuture(buildProcedureContextResult(query, cityId));
    }

    /**
     * Async variant of buildEventContextResult.
     * Uses the supplied executor when bounded parallelism is required.
     */
    public CompletableFuture<EventContextResult> buildEventContextResultAsync(String query, String cityId,
            Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildEventContextResult(query, cityId), executor);
    }

    public CompletableFuture<EventContextResult> buildEventContextResultAsync(String query, String cityId) {
        return CompletableFuture.completedFuture(buildEventContextResult(query, cityId));
    }

    public CompletableFuture<NewsContextResult> buildNewsContextResultAsync(String query, String cityId,
            Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildNewsContextResult(query, cityId), executor);
    }

    public CompletableFuture<NewsContextResult> buildNewsContextResultAsync(String query, String cityId) {
        return CompletableFuture.completedFuture(buildNewsContextResult(query, cityId));
    }

    public CompletableFuture<CityInfoContextResult> buildCityInfoContextResultAsync(String query, String cityId,
            Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildCityInfoContextResult(query, cityId), executor);
    }

    public CompletableFuture<CityInfoContextResult> buildCityInfoContextResultAsync(String query, String cityId) {
        return CompletableFuture.completedFuture(buildCityInfoContextResult(query, cityId));
    }

    /**
     * De-duplicates sources by URL and preserves order: first occurrence wins,
     * stable ordering.
     */
    public List<Source> deDuplicateAndOrderSources(List<Source> sources) {
        LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
        List<Source> deduped = new ArrayList<>();
        for (Source source : sources) {
            if (seenUrls.add(source.getUrl())) {
                deduped.add(source);
            }
        }
        return List.copyOf(deduped);
    }

    private CityDetectionIndex getOrCreateDetectionIndex(String cityId) {
        return detectionIndexByCity.computeIfAbsent(cityId, this::buildDetectionIndex);
    }

    private CityDetectionIndex buildDetectionIndex(String cityId) {
        List<String> normalizedProcedureTitles = loadNormalizedProcedureTitles(cityId);
        List<String> normalizedEventTitles = loadNormalizedEventTitles(cityId);
        List<String> normalizedNewsTitles = loadNormalizedNewsTitles(cityId);
        List<String> normalizedCityInfoTitles = loadNormalizedCityInfoTitles(cityId);
        List<String> normalizedTransparencyTitles = loadNormalizedTransparencyTitles(cityId);

        logger.info(() -> "Context detection index initialized — city=" + cityId
                + " procedureTitles=" + normalizedProcedureTitles.size()
                + " eventTitles=" + normalizedEventTitles.size()
                + " newsTitles=" + normalizedNewsTitles.size()
                + " cityInfoTitles=" + normalizedCityInfoTitles.size()
                + " transparencyTitles=" + normalizedTransparencyTitles.size());

        return new CityDetectionIndex(
                TitleMatcher.fromTitles(normalizedProcedureTitles),
                TitleMatcher.fromTitles(normalizedEventTitles),
                TitleMatcher.fromTitles(normalizedNewsTitles),
                TitleMatcher.fromTitles(normalizedCityInfoTitles),
                TitleMatcher.fromTitles(normalizedTransparencyTitles),
                normalizedProcedureTitles.size(),
                normalizedEventTitles.size(),
                normalizedNewsTitles.size(),
                normalizedCityInfoTitles.size(),
                normalizedTransparencyTitles.size());
    }

    private List<String> loadNormalizedProcedureTitles(String cityId) {
        try {
            ProcedureRagHelper helper = ragRegistry.getForCity(cityId);
            return helper.getAllProcedures().stream()
                    .map(procedure -> normalize(procedure.title))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize procedure detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    private List<String> loadNormalizedEventTitles(String cityId) {
        try {
            EventRagHelper helper = eventRagRegistry.getForCity(cityId);
            return helper.getAllEvents().stream()
                    .map(event -> normalize(event.title))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize event detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    private List<String> loadNormalizedNewsTitles(String cityId) {
        try {
            NewsRagHelper helper = newsRagRegistry.getForCity(cityId);
            return helper.getAllNews().stream()
                    .map(item -> normalize(item.title))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize news detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    private List<String> loadNormalizedCityInfoTitles(String cityId) {
        try {
            CityInfoRagHelper helper = cityInfoRagRegistry.getForCity(cityId);
            return helper.getAllCityInfo().stream()
                    .map(item -> normalize(item.title))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize city-info detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    private List<String> loadNormalizedTransparencyTitles(String cityId) {
        try {
            TransparencyRagHelper helper = transparencyRagRegistry.getForCity(cityId);
            return helper.getAllTransparencyItems().stream()
                    .map(item -> normalize(item.title))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize transparency detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    public boolean questionNeedsTransparencyContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsTransparencyContext();
    }

    public TransparencyContextResult buildTransparencyContextResult(String query, String cityId) {
        try {
            TransparencyRagHelper helper = transparencyRagRegistry.getForCity(cityId);
            List<TransparencyRagHelper.TransparencyItem> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new TransparencyContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(item -> new Source(item.url, item.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();
            String contextBlock = buildTransparencyContextBlockFromMatches(matches, cityId);
            return new TransparencyContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build transparency context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new TransparencyContextResult(null, List.of());
        }
    }

    private String buildTransparencyContextBlockFromMatches(
            List<TransparencyRagHelper.TransparencyItem> matches, String cityId) {
        if (matches.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        sb.append("TRANSPARENCY INFORMATION IN ").append(cityId).append(":\n\n");
        for (int i = 0; i < matches.size(); i++) {
            TransparencyRagHelper.TransparencyItem item = matches.get(i);
            sb.append(i + 1).append(". ").append(item.title).append("\n");
            if (item.section != null && !item.section.isBlank())
                sb.append("   Section: ").append(item.section).append("\n");
            if (item.body != null && !item.body.isBlank())
                sb.append("   Details: ").append(item.body).append("\n");
            if (item.url != null && !item.url.isBlank())
                sb.append("   Source URL: ").append(item.url).append("\n");
            sb.append("\n");
        }
        sb.append("INSTRUCTIONS:\n");
        sb.append("- Base your answer on the transparency information above.\n");
        sb.append("- Do not invent transparency items or URLs.\n");
        sb.append("- MANDATORY: If you cite an item and it has a Source URL, include that URL in your answer.\n");
        return sb.toString();
    }

    public CompletableFuture<TransparencyContextResult> buildTransparencyContextResultAsync(
            String query, String cityId, Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildTransparencyContextResult(query, cityId), executor);
    }

    public CompletableFuture<TransparencyContextResult> buildTransparencyContextResultAsync(
            String query, String cityId) {
        return CompletableFuture.completedFuture(buildTransparencyContextResult(query, cityId));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String normalizedQuestion, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalizedQuestion.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClearlyConversational(String normalizedQuestion) {
        return containsAny(normalizedQuestion, CONVERSATIONAL_SHORT_CIRCUITS);
    }

    private static boolean hasDateWindow(String normalizedQuestion, String originalQuestion) {
        if (containsAny(normalizedQuestion, DATE_WINDOW_KEYWORDS)) {
            return true;
        }

        if (containsAny(normalizedQuestion, MONTH_KEYWORDS)) {
            return true;
        }

        if (NUMERIC_DATE_PATTERN.matcher(originalQuestion).find()) {
            return true;
        }

        if (ISO_DATE_PATTERN.matcher(originalQuestion).find()) {
            return true;
        }

        return hasRangeExpressionWithDateTokens(normalizedQuestion, originalQuestion);
    }

    private static boolean hasRangeExpressionWithDateTokens(String normalizedQuestion, String originalQuestion) {
        if (!containsAny(normalizedQuestion, RANGE_CONNECTOR_KEYWORDS)) {
            return false;
        }

        return NUMERIC_DATE_PATTERN.matcher(originalQuestion).find()
                || ISO_DATE_PATTERN.matcher(originalQuestion).find()
                || containsAny(normalizedQuestion, MONTH_KEYWORDS)
                || containsAny(normalizedQuestion, DATE_WINDOW_KEYWORDS);
    }

    private static Set<String> tokenize(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String[] rawTokens = normalizedValue.replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim().split("\\s+");
        for (String token : rawTokens) {
            if (token.length() < 3 || TITLE_STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}
