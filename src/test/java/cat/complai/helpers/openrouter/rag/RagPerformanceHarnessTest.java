package cat.complai.helpers.openrouter.rag;

import cat.complai.helpers.openrouter.EventRagHelper;
import cat.complai.helpers.openrouter.ProcedureRagHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPerformanceHarnessTest {

    @Test
    void reportsProcedureHelperLatency() throws IOException {
        Metrics javaEngine = runProcedureMetrics();

        System.out.println("PERF procedure java initMs=" + javaEngine.initMs + " p50Ms=" + javaEngine.p50Ms + " p95Ms="
                + javaEngine.p95Ms);
        System.out.println("CALIB procedure absoluteFloor=" + RagJavaCalibration.procedure().absoluteFloor()
                + " relativeFloor=" + RagJavaCalibration.procedure().relativeFloor()
                + " titleBoost=" + RagJavaCalibration.procedure().titleBoost()
                + " descriptionBoost=" + RagJavaCalibration.procedure().descriptionBoost()
                + " expansionEnabled=" + RagJavaCalibration.procedure().expansionEnabled());

        assertTrue(javaEngine.initMs >= 0);
        assertTrue(javaEngine.p95Ms >= 0);
    }

    @Test
    void reportsEventHelperLatency() throws IOException {
        Metrics javaEngine = runEventMetrics();

        System.out.println("PERF event java initMs=" + javaEngine.initMs + " p50Ms=" + javaEngine.p50Ms + " p95Ms="
                + javaEngine.p95Ms);
        System.out.println("CALIB event absoluteFloor=" + RagJavaCalibration.event().absoluteFloor()
                + " relativeFloor=" + RagJavaCalibration.event().relativeFloor()
                + " titleBoost=" + RagJavaCalibration.event().titleBoost()
                + " descriptionBoost=" + RagJavaCalibration.event().descriptionBoost()
                + " expansionEnabled=" + RagJavaCalibration.event().expansionEnabled());
        System.out.println("CALIB scorer k1=" + RagJavaCalibration.scorer().k1()
                + " b=" + RagJavaCalibration.scorer().b()
                + " idfSmoothing=" + RagJavaCalibration.scorer().idfSmoothing());

        assertTrue(javaEngine.initMs >= 0);
        assertTrue(javaEngine.p95Ms >= 0);
    }

    private Metrics runProcedureMetrics() throws IOException {
        long initStart = System.nanoTime();
        ProcedureRagHelper helper = new ProcedureRagHelper("testcity");
        long initMs = nanosToMs(System.nanoTime() - initStart);

        List<String> queries = List.of("recycling", "waste", "requirements", "steps", "municipal procedure");
        List<Long> latencies = new ArrayList<>();

        for (int warmup = 0; warmup < 10; warmup++) {
            helper.search(queries.get(warmup % queries.size()));
        }
        for (int i = 0; i < 50; i++) {
            String query = queries.get(i % queries.size());
            long start = System.nanoTime();
            helper.search(query);
            latencies.add(nanosToMs(System.nanoTime() - start));
        }

        return toMetrics(initMs, latencies);
    }

    private Metrics runEventMetrics() throws IOException {
        long initStart = System.nanoTime();
        EventRagHelper helper = new EventRagHelper("testcity");
        long initMs = nanosToMs(System.nanoTime() - initStart);

        List<String> queries = List.of("festival", "cinema", "concert", "cultural", "kids");
        List<Long> latencies = new ArrayList<>();

        for (int warmup = 0; warmup < 10; warmup++) {
            helper.search(queries.get(warmup % queries.size()));
        }
        for (int i = 0; i < 50; i++) {
            String query = queries.get(i % queries.size());
            long start = System.nanoTime();
            helper.search(query);
            latencies.add(nanosToMs(System.nanoTime() - start));
        }

        return toMetrics(initMs, latencies);
    }

    private Metrics toMetrics(long initMs, List<Long> latenciesMs) {
        List<Long> sorted = latenciesMs.stream().sorted(Comparator.naturalOrder()).toList();
        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        return new Metrics(initMs, p50, p95);
    }

    private long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil((percentile / 100.0d) * sorted.size()) - 1;
        int boundedIndex = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(boundedIndex);
    }

    private long nanosToMs(long nanos) {
        return nanos / 1_000_000;
    }

    @Test
    void reportsBenchmarkGateMetrics() throws IOException {
        List<BenchmarkEntry> all = loadBenchmarkManifest();
        List<BenchmarkEntry> procAll = all.stream().filter(e -> "procedure".equals(e.domain())).toList();
        List<BenchmarkEntry> evAll = all.stream().filter(e -> "event".equals(e.domain())).toList();
        List<BenchmarkEntry> procTune = procAll.stream().filter(e -> "tune".equals(e.split())).toList();
        List<BenchmarkEntry> procHoldout = procAll.stream().filter(e -> "holdout".equals(e.split())).toList();
        List<BenchmarkEntry> evTune = evAll.stream().filter(e -> "tune".equals(e.split())).toList();
        List<BenchmarkEntry> evHoldout = evAll.stream().filter(e -> "holdout".equals(e.split())).toList();

        ProcedureRagHelper procJava = new ProcedureRagHelper("testcity");
        EventRagHelper evJava = new EventRagHelper("testcity");

        LabeledMetrics procLabeledTune = computeLabeledMetrics(procTune,
                q -> procJava.search(q).stream().map(r -> r.procedureId).limit(3).toList());
        LabeledMetrics procLabeledHoldout = computeLabeledMetrics(procHoldout,
                q -> procJava.search(q).stream().map(r -> r.procedureId).limit(3).toList());
        LabeledMetrics evLabeledTune = computeLabeledMetrics(evTune,
                q -> evJava.search(q).stream().map(r -> r.eventId).limit(3).toList());
        LabeledMetrics evLabeledHoldout = computeLabeledMetrics(evHoldout,
                q -> evJava.search(q).stream().map(r -> r.eventId).limit(3).toList());

        System.out.println("SCORECARD_LAYER_B procedure tune"
                + " labeled_recall_at_3=" + procLabeledTune.recallAt3()
                + " labeled_mrr_at_3=" + procLabeledTune.mrrAt3()
                + " queries=" + procLabeledTune.queryCount());
        System.out.println("SCORECARD_LAYER_B procedure holdout"
                + " labeled_recall_at_3=" + procLabeledHoldout.recallAt3()
                + " labeled_mrr_at_3=" + procLabeledHoldout.mrrAt3()
                + " queries=" + procLabeledHoldout.queryCount());
        System.out.println("SCORECARD_LAYER_B event tune"
                + " labeled_recall_at_3=" + evLabeledTune.recallAt3()
                + " labeled_mrr_at_3=" + evLabeledTune.mrrAt3()
                + " queries=" + evLabeledTune.queryCount());
        System.out.println("SCORECARD_LAYER_B event holdout"
                + " labeled_recall_at_3=" + evLabeledHoldout.recallAt3()
                + " labeled_mrr_at_3=" + evLabeledHoldout.mrrAt3()
                + " queries=" + evLabeledHoldout.queryCount());

        assertTrue(procLabeledTune.recallAt3() >= 0.0 && procLabeledTune.recallAt3() <= 1.0);
        assertTrue(procLabeledHoldout.recallAt3() >= 0.0 && procLabeledHoldout.recallAt3() <= 1.0);
        assertTrue(evLabeledTune.recallAt3() >= 0.0 && evLabeledTune.recallAt3() <= 1.0);
        assertTrue(evLabeledHoldout.recallAt3() >= 0.0 && evLabeledHoldout.recallAt3() <= 1.0);
        assertTrue(procLabeledTune.queryCount() == 9, "Expected 9 procedure tune queries");
        assertTrue(procLabeledHoldout.queryCount() == 3, "Expected 3 procedure holdout queries");
        assertTrue(evLabeledTune.queryCount() == 9, "Expected 9 event tune queries");
        assertTrue(evLabeledHoldout.queryCount() == 3, "Expected 3 event holdout queries");
        assertTrue(procLabeledHoldout.recallAt3() >= 0.75, "Procedure holdout recall@3 must be >= 0.75");
        assertTrue(evLabeledHoldout.recallAt3() >= 0.75, "Event holdout recall@3 must be >= 0.75");
        assertTrue(procLabeledHoldout.mrrAt3() >= 0.70, "Procedure holdout MRR@3 must be >= 0.70");
        assertTrue(evLabeledHoldout.mrrAt3() >= 0.70, "Event holdout MRR@3 must be >= 0.70");
    }

    private LabeledMetrics computeLabeledMetrics(
            List<BenchmarkEntry> entries,
            java.util.function.Function<String, List<String>> search) {
        if (entries.isEmpty()) {
            return new LabeledMetrics(0.0, 0.0, 0);
        }

        double recallAccumulator = 0.0;
        double mrrAccumulator = 0.0;

        for (BenchmarkEntry entry : entries) {
            List<String> results = search.apply(entry.query());
            Set<String> relevant = new HashSet<>(entry.relevantIds());

            // recall@3: retrieved relevant / min(3, |relevant|)
            long retrieved = results.stream().filter(relevant::contains).count();
            double recall = retrieved / (double) Math.min(3, relevant.size());
            recallAccumulator += recall;

            // MRR@3: 1 / rank of first relevant, 0 if none in top-3
            double mrr = 0.0;
            for (int i = 0; i < results.size(); i++) {
                if (relevant.contains(results.get(i))) {
                    mrr = 1.0 / (i + 1);
                    break;
                }
            }
            mrrAccumulator += mrr;

            System.out.println("LABELED_QUERY id=" + entry.id() + " split=" + entry.split()
                    + " query='" + entry.query() + "' results=" + results
                    + " relevant=" + relevant + " recall=" + recall + " mrr=" + mrr);
        }

        return new LabeledMetrics(
                recallAccumulator / entries.size(),
                mrrAccumulator / entries.size(),
                entries.size());
    }

    // =========================================================================
    // Benchmark manifest loader
    // =========================================================================

    private List<BenchmarkEntry> loadBenchmarkManifest() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/benchmark-manifest.json")) {
            if (is == null) {
                throw new IOException("benchmark-manifest.json not found on classpath");
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            List<BenchmarkEntry> result = new ArrayList<>();
            for (JsonNode node : root.path("queries")) {
                String id = node.path("id").asText();
                String domain = node.path("domain").asText();
                String query = node.path("query").asText();
                List<String> relevantIds = new ArrayList<>();
                for (JsonNode rid : node.path("relevantIds")) {
                    relevantIds.add(rid.asText());
                }
                String split = node.path("split").asText();
                result.add(new BenchmarkEntry(id, domain, query, List.copyOf(relevantIds), split));
            }
            return List.copyOf(result);
        }
    }

    // =========================================================================
    // Records
    // =========================================================================

    private record Metrics(long initMs, long p50Ms, long p95Ms) {
    }

    private record LabeledMetrics(double recallAt3, double mrrAt3, int queryCount) {
    }

    private record BenchmarkEntry(String id, String domain, String query, List<String> relevantIds, String split) {
    }
}
