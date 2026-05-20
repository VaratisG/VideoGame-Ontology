package ontology;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    // ── Paths ─────────────────────────────────────────────────────────────
    // Resolution order:
    //   1. -DPROJECT_DIR=... JVM system property
    //   2. ONTOLOGY_PROJECT_DIR environment variable
    //   3. parent of the directory containing the running JAR/classes
    static final String PROJECT_DIR;
    static {
        String prop = System.getProperty("PROJECT_DIR");
        if (prop != null && !prop.isBlank()) {
            PROJECT_DIR = prop;
        } else {
            String env = System.getenv("ONTOLOGY_PROJECT_DIR");
            if (env != null && !env.isBlank()) {
                PROJECT_DIR = env;
            } else {
                // Resolves to the project root regardless of working directory
                Path candidate;
                try {
                    Path codeLocation = Paths.get(
                        Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()
                    );
                    candidate = codeLocation.toAbsolutePath();
                    while (candidate != null && !candidate.resolve("videogame-ontology.ttl").toFile().exists()) {
                        candidate = candidate.getParent();
                    }
                } catch (URISyntaxException e) {
                    candidate = null;
                }
                PROJECT_DIR = (candidate != null ? candidate : Paths.get(".")).toString();
            }
        }
    }

    static final String DATASET_DIR  = PROJECT_DIR + "/Dataset/processed";
    static final String ONTOLOGY     = PROJECT_DIR + "/videogame-ontology.ttl";
    static final String OUTPUT_TTL   = PROJECT_DIR + "/output-data.ttl";
    static final String SAME_AS_TTL  = PROJECT_DIR + "/sameAs_wikidata.ttl";
    static final String GRAPHDB_URL  = "http://localhost:7200/repositories/MiniProject";

    /** Number of games to include. Increase for more coverage. */
    static final int GAME_LIMIT = 10;
    // ─────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("=== Video Game Ontology Pipeline ===\n");

        // Step 1 — Transform processed CSVs → RDF (implements mapping.rml.ttl rules)
        System.out.println("[1] Applying RML mapping rules via RDF4J...");
        RMLProcessor processor = new RMLProcessor(Paths.get(DATASET_DIR), GAME_LIMIT);
        processor.process(OUTPUT_TTL, SAME_AS_TTL);
        System.out.println("    Output written: " + OUTPUT_TTL);

        // Step 2 — Load ontology + instance data into GraphDB MiniProject repository
        System.out.println("\n[2] Loading data into GraphDB...");
        GraphDBConnector connector = new GraphDBConnector(GRAPHDB_URL);
        connector.load(ONTOLOGY, OUTPUT_TTL, SAME_AS_TTL);

        // Step 3 — Execute SPARQL queries and print results
        System.out.println("\n[3] Running SPARQL queries...");
        SPARQLRunner runner = new SPARQLRunner(GRAPHDB_URL);
        runner.runAll();

        System.out.println("\n=== Pipeline complete ===");
    }
}
