package co.wethinkcode.trafficflow;

import com.opencsv.CSVReader;
import io.javalin.Javalin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class IngestionServiceApp {


    private static  final Set<String> MISSING_TOKENS =
            Set.of("n/a", "na", "tbd", "unknown", "-", "", "nan");

    private static final Set<String> TRUE_TOKENS = Set.of("y", "yes", "true", "1");
    private static final Set<String> FALSE_TOKENS = Set.of("n", "no", "false", "0");

    public static void main(String[] args) throws Exception{
        List<IntersectionRecord> cleaned = dedup(loadAndClean());

        Javalin app = Javalin.create().start(7020);
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/intersections", ctx -> ctx.json(cleaned));

        System.out.println("ingestion-service ready on :7020 with " + cleaned.size() + " intersections");

        // TODO: read and clean src/main/resources/intersections-legacy.csv (intersections, districts, signal types data —
        // trim whitespace, fix casing, normalize dates/booleans) and expose the
        // cleaned records here for the other services to consume.
    }

    static List<IntersectionRecord> loadAndClean() throws Exception {
        List<IntersectionRecord> records = new ArrayList<>();
        int skipped = 0;

        try (InputStream in = IngestionServiceApp.class.getResourceAsStream("/intersections-legacy.csv");
             CSVReader reader = new CSVReader(new InputStreamReader(in))) {

            reader.skip(1);
            String[] row;
            while ((row = reader.readNext()) != null) {
                String id = cleanId(row[0]);
                if (id == null || id.isEmpty()) {
                    skipped++;
                    continue;
                }
                records.add(new IntersectionRecord(
                        id,
                        cleanOrNull(row[1], Case.TITLE),
                        cleanOrNull(row[2], Case.LOWER),
                        parseActive(row[3])
                ));
            }
        }
        System.out.println("Loaded " + records.size() + ", skiped " + skipped + " with no id");
        return records;
    }

    //Groups by ID (normalized)
    static List<IntersectionRecord> dedup(List<IntersectionRecord> records) {
        LinkedHashMap<String, List<IntersectionRecord>> groups = new LinkedHashMap<>();

        for (IntersectionRecord r : records) {
            groups.computeIfAbsent(r.id().toUpperCase(), k -> new ArrayList<>()).add(r);
        }

        List<IntersectionRecord> merged = new ArrayList<>();
        for (List<IntersectionRecord> group : groups.values()) {
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }

            System.out.println("Merging " + group.size() + " duplicate rows for ID " + group.get(0).id());

            String district = group.stream().map(IntersectionRecord::district)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            String signalType = group.stream().map(IntersectionRecord::signalType)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            Boolean active = group.stream().anyMatch(r -> Boolean.TRUE.equals(r.active()))
                    ? Boolean.TRUE
                    : group.stream().allMatch(r -> r.active() == null) ? null : Boolean.FALSE;

            merged.add(new IntersectionRecord(group.get(0).id().toUpperCase(),district,signalType,active));
        }
        return merged;
    }

    enum Case { TITLE , LOWER}

    static String cleanOrNull(String raw, Case c) {
        String clean = cleanText(raw);
        if (clean == null || MISSING_TOKENS.contains(clean.toLowerCase())) return null;
        return c == Case.TITLE ? titleCase(clean) : clean.toLowerCase();
    }

    static Boolean parseActive(String raw) {
        String clean = cleanText(raw);
        if (clean == null) return null;
        String lower = clean.toLowerCase();
        if (MISSING_TOKENS.contains(lower)) return null;
        if (TRUE_TOKENS.contains(lower)) return  true;
        if (FALSE_TOKENS.contains(lower)) return false;
        System.err.println("Unrecognized active value: \"" +  raw + "\"");
        return null;
    }

    static String cleanText (String s) {
        if (s == null) return null;
        return s.trim().replaceAll("\\s+" ," ");
    }

    static String cleanId(String s) {
        String c = cleanText(s);
        return c == null ? null : c.toUpperCase();
    }

    static String titleCase(String s) {
        if (s.isEmpty()) return s;
        String[] words = s.toLowerCase().split(" ");
        StringBuilder result = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) result.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        return result.toString().trim();
    }
}



















