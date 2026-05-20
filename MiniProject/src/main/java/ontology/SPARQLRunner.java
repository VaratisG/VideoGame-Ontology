package ontology;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads each SPARQL query from src/main/resources/queries/ and executes it
 * against the GraphDB MiniProject repository, printing results to stdout.
 */
public class SPARQLRunner {

    private final String repositoryUrl;

    private static final String[] QUERIES = {
        "q1_console_games",
        "q2_indie_games",
        "q3_top_games_dev_pub",
        "q4_steam_metacritic",
        "q5_creator_directors",
        "q6_developer_develops",
        "q7_games_per_genre"
    };

    public SPARQLRunner(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public void runAll() throws Exception {
        HTTPRepository repo = new HTTPRepository(repositoryUrl);
        repo.init();

        try (RepositoryConnection conn = repo.getConnection()) {
            for (String name : QUERIES) {
                String sparql = loadQuery(name + ".sparql");
                System.out.println("\n" + "=".repeat(60));
                System.out.println("Query: " + name);
                System.out.println("=".repeat(60));
                runQuery(conn, sparql);
            }
        } finally {
            repo.shutDown();
        }
    }

    private String loadQuery(String filename) throws IOException {
        InputStream is = getClass().getResourceAsStream("/queries/" + filename);
        if (is == null)
            throw new FileNotFoundException("SPARQL file not found in resources: " + filename);
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private void runQuery(RepositoryConnection conn, String sparql) {
        try {
            TupleQuery q = conn.prepareTupleQuery(sparql);
            try (TupleQueryResult result = q.evaluate()) {
                List<String> cols = result.getBindingNames();
                // Print header
                System.out.println(String.join(" | ", cols));
                System.out.println("-".repeat(70));
                int rows = 0;
                while (result.hasNext()) {
                    BindingSet bs = result.next();
                    StringBuilder line = new StringBuilder();
                    for (String col : cols) {
                        if (line.length() > 0) line.append(" | ");
                        var val = bs.getValue(col);
                        line.append(val == null ? "-" : val.stringValue());
                    }
                    System.out.println(line);
                    if (++rows >= 25) {
                        if (result.hasNext()) System.out.println("... (showing first 25 rows)");
                        break;
                    }
                }
                if (rows == 0) System.out.println("(no results — check OWL-Max reasoning is enabled)");
            }
        } catch (Exception e) {
            System.err.println("Query error: " + e.getMessage());
        }
    }
}
