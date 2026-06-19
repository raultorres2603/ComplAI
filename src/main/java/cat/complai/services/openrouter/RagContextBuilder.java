package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.Source;
import cat.complai.helpers.openrouter.CityInfoRagHelperRegistry;
import cat.complai.helpers.openrouter.EventRagHelperRegistry;
import cat.complai.helpers.openrouter.GencatProcedureRagHelperRegistry;
import cat.complai.helpers.openrouter.NewsRagHelperRegistry;
import cat.complai.helpers.openrouter.ProcedureRagHelperRegistry;
import cat.complai.helpers.openrouter.RagHelper;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds RAG context blocks for procedures, events, news, and city information
 * to inject into AI prompts. Provides both synchronous and asynchronous
 * retrieval for each domain, as well as source deduplication.
 *
 * <p>This class was extracted from {@code ProcedureContextService} during the
 * god-class split.</p>
 */
@Singleton
public class RagContextBuilder {

    private final ProcedureRagHelperRegistry ragRegistry;
    private final GencatProcedureRagHelperRegistry gencatRagRegistry;
    private final EventRagHelperRegistry eventRagRegistry;
    private final NewsRagHelperRegistry newsRagRegistry;
    private final CityInfoRagHelperRegistry cityInfoRagRegistry;
    private final RedactPromptBuilder promptBuilder;
    private final IntentDetector intentDetector;
    private final Logger logger = Logger.getLogger(RagContextBuilder.class.getName());

    @Inject
    public RagContextBuilder(ProcedureRagHelperRegistry ragRegistry,
                             GencatProcedureRagHelperRegistry gencatRagRegistry,
                             EventRagHelperRegistry eventRagRegistry,
                             NewsRagHelperRegistry newsRagRegistry,
                             CityInfoRagHelperRegistry cityInfoRagRegistry,
                             RedactPromptBuilder promptBuilder,
                             IntentDetector intentDetector) {
        this.ragRegistry = ragRegistry;
        this.gencatRagRegistry = gencatRagRegistry;
        this.eventRagRegistry = eventRagRegistry;
        this.newsRagRegistry = newsRagRegistry;
        this.cityInfoRagRegistry = cityInfoRagRegistry;
        this.promptBuilder = promptBuilder;
        this.intentDetector = intentDetector;
    }

    /**
     * Convenience constructor (used by tests).
     */
    public RagContextBuilder(ProcedureRagHelperRegistry ragRegistry,
                             GencatProcedureRagHelperRegistry gencatRagRegistry,
                             EventRagHelperRegistry eventRagRegistry,
                             NewsRagHelperRegistry newsRagRegistry,
                             CityInfoRagHelperRegistry cityInfoRagRegistry,
                             RedactPromptBuilder promptBuilder) {
        this(ragRegistry, gencatRagRegistry, eventRagRegistry, newsRagRegistry,
                cityInfoRagRegistry, promptBuilder, null);
    }

    /**
     * Convenience constructor (used by tests).
     */
    public RagContextBuilder(ProcedureRagHelperRegistry ragRegistry,
                             EventRagHelperRegistry eventRagRegistry,
                             NewsRagHelperRegistry newsRagRegistry,
                             CityInfoRagHelperRegistry cityInfoRagRegistry,
                             RedactPromptBuilder promptBuilder) {
        this(ragRegistry, new GencatProcedureRagHelperRegistry(), eventRagRegistry,
                newsRagRegistry, cityInfoRagRegistry, promptBuilder, null);
    }

    // -----------------------------------------------------------------------
    // Procedure context
    // -----------------------------------------------------------------------

    public ProcedureContextResult buildProcedureContextResult(String query, String cityId) {
        return buildProcedureContextResult(query, cityId, false);
    }

    public ProcedureContextResult buildProcedureContextResult(String query, String cityId, boolean forceGencat) {
        try {
            RagHelper<RagHelper.Procedure> helper;
            String effectiveCityId;
            boolean useGencat = forceGencat
                    || (intentDetector != null && intentDetector.isAskingGencatProcedures(query));

            if (useGencat) {
                helper = gencatRagRegistry.getForCity("gencat");
                effectiveCityId = "gencat";
                logger.info("Using gencat fallback for procedure query: " + query);
            } else {
                helper = ragRegistry.getForCity(cityId);
                effectiveCityId = cityId;
            }

            List<RagHelper.Procedure> matches = helper.search(query);

            if (matches.isEmpty() && !useGencat) {
                logger.info("No city procedure matches for query: " + query + ", trying gencat fallback");
                return buildProcedureContextResult(query, cityId, true);
            }

            if (matches.isEmpty()) {
                return new ProcedureContextResult(null, List.of());
            }

            List<Source> sources = matches.stream()
                    .map(p -> new Source(p.url(), p.title()))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();
            String contextBlock = promptBuilder.buildProcedureContextBlockFromMatches(matches, effectiveCityId);
            return new ProcedureContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build procedure context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new ProcedureContextResult(null, List.of());
        }
    }

    public ProcedureContextResult buildProcedureContextResultForId(String procedureId, String cityId) {
        try {
            RagHelper.Procedure procedure = ragRegistry.getForCity(cityId).getAll()
                    .stream()
                    .filter(p -> procedureId.equals(p.procedureId()))
                    .findFirst()
                    .orElse(null);
            if (procedure == null) {
                return new ProcedureContextResult(null, List.of());
            }
            String contextBlock = promptBuilder.buildProcedureContextBlockFromMatches(List.of(procedure), cityId);
            List<Source> sources = List.of(new Source(procedure.url(), procedure.title()));
            return new ProcedureContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "buildProcedureContextResultForId failed for procedureId=" + procedureId
                    + " city=" + cityId + ": " + e.getMessage(), e);
            return new ProcedureContextResult(null, List.of());
        }
    }

    // -----------------------------------------------------------------------
    // Event context
    // -----------------------------------------------------------------------

    public EventContextResult buildEventContextResult(String query, String cityId) {
        try {
            RagHelper<RagHelper.Event> helper = eventRagRegistry.getForCity(cityId);
            List<RagHelper.Event> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new EventContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(e -> new Source(e.url(), e.title()))
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

    // -----------------------------------------------------------------------
    // News context
    // -----------------------------------------------------------------------

    public NewsContextResult buildNewsContextResult(String query, String cityId) {
        try {
            RagHelper<RagHelper.News> helper = newsRagRegistry.getForCity(cityId);
            List<RagHelper.News> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new NewsContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(item -> new Source(item.url(), item.title()))
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

    // -----------------------------------------------------------------------
    // City-info context
    // -----------------------------------------------------------------------

    public CityInfoContextResult buildCityInfoContextResult(String query, String cityId) {
        try {
            RagHelper<RagHelper.CityInfo> helper = cityInfoRagRegistry.getForCity(cityId);
            List<RagHelper.CityInfo> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new CityInfoContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(item -> new Source(item.url(), item.title()))
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

    // -----------------------------------------------------------------------
    // Async variants
    // -----------------------------------------------------------------------

    public CompletableFuture<ProcedureContextResult> buildProcedureContextResultAsync(String query, String cityId,
                                                                                       Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildProcedureContextResult(query, cityId), executor);
    }

    public CompletableFuture<EventContextResult> buildEventContextResultAsync(String query, String cityId,
                                                                                Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildEventContextResult(query, cityId), executor);
    }

    public CompletableFuture<NewsContextResult> buildNewsContextResultAsync(String query, String cityId,
                                                                              Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildNewsContextResult(query, cityId), executor);
    }

    public CompletableFuture<CityInfoContextResult> buildCityInfoContextResultAsync(String query, String cityId,
                                                                                      Executor executor) {
        return CompletableFuture.supplyAsync(() -> buildCityInfoContextResult(query, cityId), executor);
    }

    // -----------------------------------------------------------------------
    // Source utilities
    // -----------------------------------------------------------------------

    /**
     * De-duplicates sources by URL and preserves order: first occurrence wins.
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

    /**
     * Validates and adds sources to the merged list, logging warnings for missing
     * URLs. Missing URLs do not prevent the source from being added — logging only.
     */
    public void validateAndAddSources(List<Source> mergedSources, List<Source> contextSources, String contextType) {
        if (contextSources == null || contextSources.isEmpty()) {
            return;
        }
        for (Source source : contextSources) {
            if (source.getUrl() == null || source.getUrl().isBlank()) {
                String title = source.getTitle() != null ? source.getTitle() : "<no title>";
                Logger.getLogger(RagContextBuilder.class.getName())
                        .warning("Missing URL for " + contextType + " item: " + title);
            }
            mergedSources.add(source);
        }
    }

    // -----------------------------------------------------------------------
    // Context block formatters (private)
    // -----------------------------------------------------------------------

    private String buildNewsContextBlockFromMatches(List<RagHelper.News> matches, String cityId) {
        if (matches.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CONTEXT FROM CITY NEWS IN ").append(cityId).append(":\n\n");
        for (int i = 0; i < matches.size(); i++) {
            RagHelper.News item = matches.get(i);
            sb.append(i + 1).append(". ").append(item.title()).append("\n");
            if (item.publishedAt() != null && !item.publishedAt().isBlank()) {
                sb.append("   Published: ").append(item.publishedAt()).append("\n");
            }
            if (item.categories() != null && !item.categories().isBlank()) {
                sb.append("   Categories: ").append(item.categories()).append("\n");
            }
            if (item.summary() != null && !item.summary().isBlank()) {
                sb.append("   Summary: ").append(item.summary()).append("\n");
            }
            if (item.body() != null && !item.body().isBlank()) {
                sb.append("   Details: ").append(item.body()).append("\n");
            }
            if (item.url() != null && !item.url().isBlank()) {
                sb.append("   Source URL: ").append(item.url()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("INSTRUCTIONS:\n");
        sb.append("- Base your answer on the news context above.\n");
        sb.append("- Do not invent news items or URLs.\n");
        sb.append("- MANDATORY: If you cite a news item and it has a Source URL, include that URL in your answer.\n");
        return sb.toString();
    }

    private String buildEventContextBlockFromMatches(List<RagHelper.Event> matches, String cityId) {
        if (matches.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Events in ").append(cityId).append(":\n\n");
        for (int i = 0; i < matches.size(); i++) {
            RagHelper.Event event = matches.get(i);
            sb.append(i + 1).append(". ").append(event.title()).append("\n");
            if (event.date() != null && !event.date().isBlank()) {
                sb.append("   Date: ").append(event.date()).append("\n");
            }
            if (event.time() != null && !event.time().isBlank()) {
                sb.append("   Time: ").append(event.time()).append("\n");
            }
            if (event.location() != null && !event.location().isBlank()) {
                sb.append("   Location: ").append(event.location()).append("\n");
            }
            if (event.eventType() != null && !event.eventType().isBlank()) {
                sb.append("   Type: ").append(event.eventType()).append("\n");
            }
            if (event.targetAudience() != null && !event.targetAudience().isBlank()) {
                sb.append("   Audience: ").append(event.targetAudience()).append("\n");
            }
            if (event.description() != null && !event.description().isBlank()) {
                sb.append("   Description: ").append(event.description()).append("\n");
            }
            if (event.theme() != null && !event.theme().isBlank()) {
                sb.append("   Theme: ").append(event.theme()).append("\n");
            }
            if (event.url() != null && !event.url().isBlank()) {
                sb.append("   Source URL: ").append(event.url()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("INSTRUCTIONS:\n");
        sb.append("- Base your answer on the events above.\n");
        sb.append("- MANDATORY: If you mention an event listed above and it has a Source URL, include that URL in your answer.\n");
        sb.append("- NEVER invent event URLs.\n");
        return sb.toString();
    }

    private String buildCityInfoContextBlockFromMatches(List<RagHelper.CityInfo> matches, String cityId) {
        if (matches.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CITY INFORMATION IN ").append(cityId).append(":\n\n");
        for (int i = 0; i < matches.size(); i++) {
            RagHelper.CityInfo item = matches.get(i);
            sb.append(i + 1).append(". ").append(item.title()).append("\n");
            if (item.theme() != null && !item.theme().isBlank()) {
                sb.append("   Theme: ").append(item.theme()).append("\n");
            }
            if (item.breadcrumbs() != null && !item.breadcrumbs().isBlank()) {
                sb.append("   Breadcrumbs: ").append(item.breadcrumbs()).append("\n");
            }
            if (item.summary() != null && !item.summary().isBlank()) {
                sb.append("   Summary: ").append(item.summary()).append("\n");
            }
            if (item.body() != null && !item.body().isBlank()) {
                sb.append("   Details: ").append(item.body()).append("\n");
            }
            if (item.url() != null && !item.url().isBlank()) {
                sb.append("   Source URL: ").append(item.url()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("INSTRUCTIONS:\n");
        sb.append("- Base your answer on the city-info context above.\n");
        sb.append("- Do not invent city-info items or URLs.\n");
        sb.append("- MANDATORY: If you cite an item and it has a Source URL, include that URL in your answer.\n");
        return sb.toString();
    }
}
