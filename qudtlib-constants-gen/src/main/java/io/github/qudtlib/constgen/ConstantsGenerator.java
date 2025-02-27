package io.github.qudtlib.constgen;

import com.github.qudlib.common.RdfOps;
import freemarker.core.Environment;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

/**
 * Generates the Units, QuantityKinds, and Prefixes classes.
 *
 * @author Florian Kleedorfer
 * @since 1.0
 */
public class ConstantsGenerator {
    private final Path outputDir;
    // input data
    private static final String DATA_UNITS = "qudtlib/qudt-units.ttl";
    private static final String DATA_QUANTITYKINDS = "qudtlib/qudt-quantitykinds.ttl";
    private static final String DATA_PREFIXES = "qudtlib/qudt-prefixes.ttl";
    // queries
    private static final String QUERY_UNITS = "query/units.rq";
    private static final String QUERY_QUANTITYKINDS = "query/quantitykinds.rq";
    private static final String QUERY_PREFIXES = "query/prefixes.rq";
    // output
    private static final String DESTINATION_PACKAGE = "io.github.qudtlib.model";
    // template
    private static final String TEMPLATE_FILE = "template/constants.ftl";

    public ConstantsGenerator(Path outputDir) {
        this.outputDir = outputDir;
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException("missing argument");
            }
            if (args.length > 1) {
                throw new IllegalArgumentException(" too many arguments");
            }
            String outputDir = args[0];
            ConstantsGenerator generator = new ConstantsGenerator(Path.of(outputDir));
            generator.generate();
        } catch (Exception e) {
            System.err.println("\n\n\tusage: ConstantsGenerator [output-dir]\n\n");
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void generate() throws IOException, TemplateException {
        Configuration cfg = getFreemarkerConfiguration();
        generateUnitConstants(cfg);
        generateQuantityKindConstants(cfg);
        generatePrefixConstants(cfg);
    }

    private Configuration getFreemarkerConfiguration() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setClassLoaderForTemplateLoading(ConstantsGenerator.class.getClassLoader(), "/");
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        return cfg;
    }

    private void generatePrefixConstants(Configuration config)
            throws IOException, TemplateException {
        Map<String, Object> templateVars = getConstantNamesByQuery(QUERY_PREFIXES, DATA_PREFIXES);
        generateJavaFile(config, templateVars, "Prefix", "Prefixes");
    }

    private void generateQuantityKindConstants(Configuration config)
            throws TemplateException, IOException {
        Map<String, Object> templateVars =
                getConstantNamesByQuery(QUERY_QUANTITYKINDS, DATA_QUANTITYKINDS);
        generateJavaFile(config, templateVars, "QuantityKind", "QuantityKinds");
    }

    private void generateUnitConstants(Configuration config) throws TemplateException, IOException {
        Map<String, Object> templateVars = getConstantNamesByQuery(QUERY_UNITS, DATA_UNITS);
        generateJavaFile(config, templateVars, "Unit", "Units");
    }

    private Map<String, Object> getConstantNamesByQuery(String queryFile, String dataFile) {
        String query = RdfOps.loadQuery(queryFile);
        Repository repo = new SailRepository(new MemoryStore());
        Map<String, Object> templateVars = new HashMap<>();
        try (RepositoryConnection con = repo.getConnection()) {
            RdfOps.addStatementsFromFile(con, dataFile);
            try (TupleQueryResult result = con.prepareTupleQuery(query).evaluate()) {
                List<Constant> constants = new ArrayList<>();
                while (result.hasNext()) {
                    BindingSet bindings = result.next();
                    String constName = bindings.getValue("constName").stringValue();
                    String localName = bindings.getValue("localName").stringValue();
                    String label =
                            bindings.getValue("label") == null
                                    ? localName
                                    : bindings.getValue("label").stringValue();
                    Constant constant = new Constant(constName, localName, label);
                    constants.add(constant);
                }
                templateVars.put("constants", constants);
            }
        }
        return templateVars;
    }

    private void generateJavaFile(
            Configuration config, Map<String, Object> templateVars, String type, String typePlural)
            throws IOException, TemplateException {
        RdfOps.message("Generating " + typePlural + ".java");
        File packageFile =
                new File(outputDir + "/" + DESTINATION_PACKAGE.replaceAll(Pattern.quote("."), "/"));
        if (!packageFile.exists()) {
            if (!packageFile.mkdirs()) {
                throw new IOException(
                        "Could not create output dir " + packageFile.getAbsolutePath());
            }
        }
        RdfOps.message("output dir: " + packageFile.getAbsolutePath());
        templateVars.put("type", type);
        templateVars.put("typePlural", typePlural);
        templateVars.put("package", DESTINATION_PACKAGE);
        templateVars.put(
                "valueFactory",
                type.substring(0, 1).toLowerCase() + type.substring(1) + "FromLocalname");
        Template template = config.getTemplate(TEMPLATE_FILE);
        FileWriter out =
                new FileWriter(new File(packageFile, typePlural + ".java"), StandardCharsets.UTF_8);
        Environment env = template.createProcessingEnvironment(templateVars, out);
        env.setOutputEncoding(StandardCharsets.UTF_8.toString());
        env.process();
    }
}
