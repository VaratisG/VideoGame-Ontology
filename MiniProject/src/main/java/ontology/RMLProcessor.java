package ontology;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Programmatic implementation of the RML mapping rules defined in mapping.rml.ttl.
 *
 * Reads from Dataset/processed/ CSV files and writes Turtle triples to an output
 * file using RDF4J's streaming Rio writer — no full in-memory model is built.
 */
public class RMLProcessor {

    // Namespace constants — must match mapping.rml.ttl and videogame-ontology.ttl
    static final String BASE      = "http://www.semanticweb.org/videogame-ontology#";
    static final String SCHEMA    = "https://schema.org/";
    static final String XSD       = "http://www.w3.org/2001/XMLSchema#";
    static final String RDF_NS    = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String OWL_NS      = "http://www.w3.org/2002/07/owl#";
    static final String ONT_IRI     = "http://www.semanticweb.org/videogame-ontology-instances";
    static final String IMPORT_IRI  = "http://www.semanticweb.org/videogame-ontology";
    static final String SAMEAS_IRI  = "http://www.semanticweb.org/videogame-ontology-sameas";

    private final Path processedDir;
    private final int  gameLimit;
    private final ValueFactory vf = SimpleValueFactory.getInstance();

    // ── Class IRIs ────────────────────────────────────────────────────────
    private final IRI C_VIDEO_GAME      = base("VideoGame");
    private final IRI C_GENRE           = base("Genre");
    private final IRI C_PLATFORM        = base("Platform");
    private final IRI C_PARENT_PLATFORM = base("ParentPlatform");
    private final IRI C_STORE           = base("Store");
    private final IRI C_TAG             = base("Tag");
    private final IRI C_CREATOR_ROLE    = base("CreatorRole");
    private final IRI C_DEVELOPER       = base("Developer");
    private final IRI C_PUBLISHER       = base("Publisher");
    private final IRI C_CREATOR         = base("Creator");
    private final IRI C_AGG_RATING      = base("AggregateRating");
    private final IRI C_ESRB_RATING     = base("EsrbRating");

    // ── Property IRIs ─────────────────────────────────────────────────────
    private final IRI RDF_TYPE         = vf.createIRI(RDF_NS + "type");
    private final IRI P_NAME           = vf.createIRI(SCHEMA + "name");
    private final IRI P_DATE_PUBLISHED = vf.createIRI(SCHEMA + "datePublished");
    private final IRI P_RATING_VALUE   = vf.createIRI(SCHEMA + "ratingValue");
    private final IRI P_SLUG           = base("slug");
    private final IRI P_GAMES_COUNT    = base("gamesCount");
    private final IRI P_YEAR_START     = base("yearStart");
    private final IRI P_YEAR_END       = base("yearEnd");
    private final IRI P_STORE_DOMAIN   = base("storeDomain");
    private final IRI P_LANGUAGE       = base("language");
    private final IRI P_METACRITIC     = base("metacriticScore");
    private final IRI P_PLAYTIME       = base("playtime");
    private final IRI P_RATINGS_COUNT  = base("ratingsCount");
    private final IRI P_HAS_GENRE      = base("hasGenre");
    private final IRI P_AVAILABLE_ON   = base("availableOn");
    private final IRI P_ON_PARENT_PLAT = base("onParentPlatform");   // Platform → ParentPlatform
    private final IRI P_HAS_PARENT_PLAT= base("hasParentPlatform");  // VideoGame → ParentPlatform
    private final IRI P_SOLD_AT        = base("soldAt");
    private final IRI P_HAS_TAG        = base("hasTag");
    private final IRI P_HAS_RATING     = base("hasRating");
    private final IRI P_HAS_ESRB       = base("hasEsrbRating");
    private final IRI P_DEVELOPS       = base("develops");
    private final IRI P_PUBLISHES      = base("publishes");
    private final IRI P_CREATED        = base("created");
    private final IRI P_HAS_ROLE       = base("hasRole");

    // ── XSD datatype IRIs ─────────────────────────────────────────────────
    private final IRI XSD_STRING  = vf.createIRI(XSD + "string");
    private final IRI XSD_INTEGER = vf.createIRI(XSD + "integer");
    private final IRI XSD_FLOAT   = vf.createIRI(XSD + "float");
    private final IRI XSD_DATE    = vf.createIRI(XSD + "date");

    public RMLProcessor(Path processedDir, int gameLimit) {
        this.processedDir = processedDir;
        this.gameLimit    = gameLimit;
    }

    // ── IRI helpers ───────────────────────────────────────────────────────

    private IRI base(String local) {
        return vf.createIRI(BASE + local);
    }

    /** IRI for a typed entity, e.g. entityIRI("game", "3498") → :game_3498 */
    private IRI entityIRI(String type, String id) {
        return vf.createIRI(BASE + type + "_" + id);
    }

    private Statement s(Resource subj, IRI pred, Value obj) {
        return vf.createStatement(subj, pred, obj);
    }

    private Literal lit(String val, IRI datatype) {
        return vf.createLiteral(val, datatype);
    }

    // ── CSV helpers ───────────────────────────────────────────────────────

    private CSVParser parse(String filename) throws IOException {
        File f = processedDir.resolve(filename).toFile();
        return CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .withIgnoreEmptyLines()
                .withTrim()
                .parse(new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)));
    }

    /** Write an xsd:integer triple only when the value cell is non-empty. */
    private void writeInt(RDFWriter w, IRI subj, IRI pred, String raw) throws IOException {
        if (raw == null || raw.isEmpty()) return;
        try {
            int v = (int) Double.parseDouble(raw); // handles "2020.0" → 2020
            w.handleStatement(s(subj, pred, lit(String.valueOf(v), XSD_INTEGER)));
        } catch (NumberFormatException ignored) { }
    }

    // ── Main entry point ──────────────────────────────────────────────────

    /**
     * Reads the processed CSV files and writes all RDF triples to outputPath
     * in Turtle format.  Implements every TriplesMap defined in mapping.rml.ttl.
     */
    public void process(String outputPath, String sameAsPath) throws IOException {

        System.out.println("    Collecting game IDs (limit=" + gameLimit + ")...");
        Set<String> gameIds = collectGameIds();

        System.out.println("    Resolving related entity IDs...");
        Set<String> devIds     = relatedIds("developer_games.csv", "developer_id", "game_id", gameIds);
        Set<String> pubIds     = relatedIds("publisher_games.csv", "publisher_id", "game_id", gameIds);
        Set<String> creatorIds = relatedIds("creator_games.csv",   "creator_id",   "game_id", gameIds);

        System.out.println("    Writing Turtle to: " + outputPath);
        try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(outputPath), 1 << 20);
             Writer writer    = new OutputStreamWriter(raw, StandardCharsets.UTF_8)) {

            RDFWriter w = Rio.createWriter(RDFFormat.TURTLE, writer);
            w.startRDF();
            w.handleNamespace("",       BASE);
            w.handleNamespace("schema", SCHEMA);
            w.handleNamespace("xsd",    XSD);
            w.handleNamespace("owl",    OWL_NS);

            // Ontology declaration so Protégé loads this file and imports the schema
            IRI ontIRI    = vf.createIRI(ONT_IRI);
            IRI owlOnt    = vf.createIRI(OWL_NS + "Ontology");
            IRI owlImport = vf.createIRI(OWL_NS + "imports");
            w.handleStatement(s(ontIRI, RDF_TYPE,   owlOnt));
            w.handleStatement(s(ontIRI, owlImport,  vf.createIRI(IMPORT_IRI)));
            // Import sameAs links if the file exists (generated by src/link_to_wikidata.py)
            if (sameAsPath != null && new java.io.File(sameAsPath).exists()) {
                w.handleStatement(s(ontIRI, owlImport, vf.createIRI(SAMEAS_IRI)));
            }

            // ── Entity tables (small — write all rows) ────────────────────
            writeGenres(w);
            writePlatforms(w);
            writeParentPlatforms(w);
            writeStores(w);
            writeTags(w);
            writeCreatorRoles(w);

            // ── Filtered entity tables ────────────────────────────────────
            writeDevelopers(w, devIds);
            writePublishers(w, pubIds);
            writeCreators(w, creatorIds);

            // ── Games and all their relationships ─────────────────────────
            writeGames(w, gameIds);
            writeJoin(w, "game_genres.csv",          "game_id", gameIds, "game_id", "game", P_HAS_GENRE,       "genre_id",          "genre");
            writeJoin(w, "game_platforms.csv",        "game_id", gameIds, "game_id", "game", P_AVAILABLE_ON,    "platform_id",       "platform");
            writeJoin(w, "game_parent_platforms.csv", "game_id", gameIds, "game_id", "game", P_HAS_PARENT_PLAT, "parent_platform_id","parentPlatform");
            writeJoin(w, "game_stores.csv",           "game_id", gameIds, "game_id", "game", P_SOLD_AT,         "store_id",          "store");
            writeJoin(w, "game_tags.csv",             "game_id", gameIds, "game_id", "game", P_HAS_TAG,         "tag_id",            "tag");
            writeGameRatings(w, gameIds);
            writeGameEsrb(w, gameIds);

            // ── Reverse relationships ─────────────────────────────────────
            writeJoin(w, "developer_games.csv", "game_id", gameIds, "developer_id", "developer", P_DEVELOPS,  "game_id", "game");
            writeJoin(w, "publisher_games.csv", "game_id", gameIds, "publisher_id", "publisher", P_PUBLISHES, "game_id", "game");
            writeJoin(w, "creator_games.csv",   "game_id", gameIds, "creator_id",   "creator",   P_CREATED,   "game_id", "game");

            // Creator roles (filter by creators we're actually writing)
            writeJoin(w, "creator_positions.csv", "creator_id", creatorIds, "creator_id", "creator", P_HAS_ROLE, "role_id", "role");

            // Platform → ParentPlatform (all 51 rows, no filtering needed)
            writePlatformParent(w);

            w.endRDF();
        }

        System.out.println("    Done.");
    }

    // ── Collect helpers ───────────────────────────────────────────────────

    private Set<String> collectGameIds() throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        try (CSVParser p = parse("games_flat.csv")) {
            for (CSVRecord r : p) {
                if (ids.size() >= gameLimit) break;
                ids.add(r.get("id"));
            }
        }
        return ids;
    }

    /** Return the set of values in idCol for rows where filterCol ∈ filterSet. */
    private Set<String> relatedIds(String csv, String idCol,
                                   String filterCol, Set<String> filterSet) throws IOException {
        Set<String> ids = new HashSet<>();
        try (CSVParser p = parse(csv)) {
            for (CSVRecord r : p) {
                if (filterSet.contains(r.get(filterCol)))
                    ids.add(r.get(idCol));
            }
        }
        return ids;
    }

    // ── Entity writers ────────────────────────────────────────────────────

    private void writeGenres(RDFWriter w) throws IOException {
        try (CSVParser p = parse("genres_flat.csv")) {
            for (CSVRecord r : p) {
                IRI s = entityIRI("genre", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_GENRE));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
                writeInt(w, s, P_GAMES_COUNT, r.get("games_count"));
            }
        }
    }

    private void writePlatforms(RDFWriter w) throws IOException {
        try (CSVParser p = parse("platforms_flat.csv")) {
            for (CSVRecord r : p) {
                IRI s = entityIRI("platform", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_PLATFORM));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
                writeInt(w, s, P_YEAR_START, r.get("year_start"));
                writeInt(w, s, P_YEAR_END,   r.get("year_end"));
            }
        }
    }

    private void writeParentPlatforms(RDFWriter w) throws IOException {
        try (CSVParser p = parse("parent_platforms_flat.csv")) {
            for (CSVRecord r : p) {
                IRI s = entityIRI("parentPlatform", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_PARENT_PLATFORM));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
            }
        }
    }

    private void writeStores(RDFWriter w) throws IOException {
        try (CSVParser p = parse("stores_flat.csv")) {
            for (CSVRecord r : p) {
                IRI s = entityIRI("store", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE,       C_STORE));
                w.handleStatement(s(s, P_NAME,         lit(r.get("name"),   XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,         lit(r.get("slug"),   XSD_STRING)));
                w.handleStatement(s(s, P_STORE_DOMAIN, lit(r.get("domain"), XSD_STRING)));
            }
        }
    }

    private void writeTags(RDFWriter w) throws IOException {
        try (CSVParser p = parse("tags_flat.csv")) {
            for (CSVRecord r : p) {
                IRI s = entityIRI("tag", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_TAG));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
                if (!r.get("language").isEmpty())
                    w.handleStatement(s(s, P_LANGUAGE, lit(r.get("language"), XSD_STRING)));
            }
        }
    }

    private void writeCreatorRoles(RDFWriter w) throws IOException {
        try (CSVParser p = parse("creator_roles_flat.csv")) {
            for (CSVRecord r : p) {
                IRI s = entityIRI("role", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_CREATOR_ROLE));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
            }
        }
    }

    private void writeDevelopers(RDFWriter w, Set<String> allowed) throws IOException {
        try (CSVParser p = parse("developers_flat.csv")) {
            for (CSVRecord r : p) {
                if (!allowed.contains(r.get("id"))) continue;
                IRI s = entityIRI("developer", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_DEVELOPER));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
                writeInt(w, s, P_GAMES_COUNT, r.get("games_count"));
            }
        }
    }

    private void writePublishers(RDFWriter w, Set<String> allowed) throws IOException {
        try (CSVParser p = parse("publishers_flat.csv")) {
            for (CSVRecord r : p) {
                if (!allowed.contains(r.get("id"))) continue;
                IRI s = entityIRI("publisher", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_PUBLISHER));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
                writeInt(w, s, P_GAMES_COUNT, r.get("games_count"));
            }
        }
    }

    private void writeCreators(RDFWriter w, Set<String> allowed) throws IOException {
        try (CSVParser p = parse("creators_flat.csv")) {
            for (CSVRecord r : p) {
                if (!allowed.contains(r.get("id"))) continue;
                IRI s = entityIRI("creator", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_CREATOR));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
            }
        }
    }

    private void writeGames(RDFWriter w, Set<String> gameIds) throws IOException {
        try (CSVParser p = parse("games_flat.csv")) {
            for (CSVRecord r : p) {
                if (!gameIds.contains(r.get("id"))) continue;
                IRI s = entityIRI("game", r.get("id"));
                w.handleStatement(s(s, RDF_TYPE, C_VIDEO_GAME));
                w.handleStatement(s(s, P_NAME,   lit(r.get("name"), XSD_STRING)));
                w.handleStatement(s(s, P_SLUG,   lit(r.get("slug"), XSD_STRING)));
                if (!r.get("released").isEmpty())
                    w.handleStatement(s(s, P_DATE_PUBLISHED, lit(r.get("released"), XSD_DATE)));
                writeInt(w, s, P_METACRITIC,    r.get("metacritic"));
                writeInt(w, s, P_PLAYTIME,      r.get("playtime"));
                writeInt(w, s, P_RATINGS_COUNT, r.get("ratings_count"));
            }
        }
    }

    private void writeGameRatings(RDFWriter w, Set<String> gameIds) throws IOException {
        try (CSVParser p = parse("game_ratings.csv")) {
            for (CSVRecord r : p) {
                if (!gameIds.contains(r.get("game_id"))) continue;
                IRI game   = entityIRI("game",   r.get("game_id"));
                IRI rating = entityIRI("rating", r.get("game_id"));
                w.handleStatement(s(rating, RDF_TYPE,       C_AGG_RATING));
                w.handleStatement(s(rating, P_RATING_VALUE, lit(r.get("rating_value"), XSD_FLOAT)));
                w.handleStatement(s(game,   P_HAS_RATING,   rating));
            }
        }
    }

    private void writeGameEsrb(RDFWriter w, Set<String> gameIds) throws IOException {
        try (CSVParser p = parse("game_esrb.csv")) {
            for (CSVRecord r : p) {
                if (!gameIds.contains(r.get("game_id"))) continue;
                IRI game = entityIRI("game", r.get("game_id"));
                IRI esrb = entityIRI("esrb", r.get("esrb_id"));
                w.handleStatement(s(esrb, RDF_TYPE, C_ESRB_RATING));
                w.handleStatement(s(esrb, P_NAME,   lit(r.get("esrb_name"), XSD_STRING)));
                w.handleStatement(s(esrb, P_SLUG,   lit(r.get("esrb_slug"), XSD_STRING)));
                w.handleStatement(s(game, P_HAS_ESRB, esrb));
            }
        }
    }

    private void writePlatformParent(RDFWriter w) throws IOException {
        try (CSVParser p = parse("platform_parent_platform.csv")) {
            for (CSVRecord r : p) {
                IRI platform = entityIRI("platform",       r.get("platform_id"));
                IRI parent   = entityIRI("parentPlatform", r.get("parent_platform_id"));
                w.handleStatement(s(platform, P_ON_PARENT_PLAT, parent));
            }
        }
    }

    // ── Generic join-table writer ─────────────────────────────────────────

    /**
     * Reads a join-table CSV, filters rows where filterCol ∈ filterSet,
     * then emits:  entityIRI(subjectPrefix, subjectCol)  predicate  entityIRI(objectPrefix, objectCol)
     */
    private void writeJoin(RDFWriter w,
                           String csv, String filterCol, Set<String> filterSet,
                           String subjectCol, String subjectPrefix,
                           IRI predicate,
                           String objectCol,  String objectPrefix) throws IOException {
        try (CSVParser p = parse(csv)) {
            for (CSVRecord r : p) {
                if (!filterSet.contains(r.get(filterCol))) continue;
                IRI subj = entityIRI(subjectPrefix, r.get(subjectCol));
                IRI obj  = entityIRI(objectPrefix,  r.get(objectCol));
                w.handleStatement(s(subj, predicate, obj));
            }
        }
    }
}
