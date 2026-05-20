package ontology;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects to a GraphDB repository via the RDF4J HTTP Repository API,
 * clears it, then loads the OWL ontology and the generated instance data.
 *
 * Files are parsed locally and uploaded in batches to avoid GraphDB's
 * HTTP body size limit (which causes "Connection reset by peer" on large files).
 *
 * Prerequisites:
 *   1. GraphDB must be running at localhost:7200
 *   2. A repository named "MiniProject" must exist with OWL-Max reasoning enabled
 *      (GraphDB Workbench → Repositories → Create → Ruleset: OWL-Max)
 */
public class GraphDBConnector {

    private static final int    BATCH_SIZE = 5_000;
    private static final String BASE_IRI   = "http://www.semanticweb.org/videogame-ontology";

    private final String repositoryUrl;

    public GraphDBConnector(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public void load(String ontologyPath, String dataPath, String... extraPaths) throws Exception {
        HTTPRepository repo = new HTTPRepository(repositoryUrl);
        repo.init();

        try (RepositoryConnection conn = repo.getConnection()) {

            System.out.println("    Clearing existing statements...");
            conn.clear();

            System.out.println("    Loading ontology: " + ontologyPath);
            addFileInBatches(conn, ontologyPath);

            System.out.println("    Loading instance data: " + dataPath);
            addFileInBatches(conn, dataPath);

            for (String extra : extraPaths) {
                File f = new File(extra);
                if (f.exists()) {
                    System.out.println("    Loading extra: " + extra);
                    addFileInBatches(conn, extra);
                } else {
                    System.out.println("    Skipping (not found): " + extra);
                }
            }

            long count = conn.size();
            System.out.println("    Repository now contains " + count + " triples.");

        } finally {
            repo.shutDown();
        }
    }

    /** Parses a Turtle file locally and uploads to GraphDB in batches of BATCH_SIZE statements. */
    private void addFileInBatches(RepositoryConnection conn, String filePath) throws Exception {
        List<Statement> batch = new ArrayList<>(BATCH_SIZE);

        RDFParser parser = Rio.createParser(RDFFormat.TURTLE);
        parser.setRDFHandler(new AbstractRDFHandler() {
            @Override
            public void handleStatement(Statement st) {
                batch.add(st);
                if (batch.size() >= BATCH_SIZE) {
                    conn.add(batch);
                    batch.clear();
                }
            }
            @Override
            public void endRDF() {
                if (!batch.isEmpty()) {
                    conn.add(batch);
                    batch.clear();
                }
            }
        });

        try (InputStream is = new BufferedInputStream(new FileInputStream(new File(filePath)))) {
            parser.parse(is, BASE_IRI);
        }
    }
}
