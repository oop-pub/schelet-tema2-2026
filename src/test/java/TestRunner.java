import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import main.App;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.IOException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

@ExtendWith(TestCaseWatcher.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TestRunner {
    private static final ObjectMapper objectMapper = new ObjectMapper(
            new JsonFactory().enable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
    );

    private static final List<DevmindResult> devmindResults = new ArrayList<>();

    public static final String PASSED = "PASSED";

    public static final String EXPORTS_OUT_DIR = "out/exports";
    public static final String EXPORTS_REF_DIR = "ref/exports";
    public static final String EXPORT_OUT_PREFIX = "out_";

    // tests run in this exact order
    public static Stream<Arguments> data() {
        return Stream.of(
                testCase("01", "signup", "Sign up", 1),
                testCase("02", "wallet", "Wallet", 2),
                testCase("03", "ingest_part_1", "Ingest part 1", 2),
                testCase("04", "ingest_part_2", "Ingest part 2", 2),
                testCase("05", "ingest_special", "Ingest special", 2),
                testCase("06", "test_pricing", "Pricing", 2),
                testCase("07", "test_invoice_per_unit_per_package", "Invoice per unit and per package", 3),
                testCase("08", "test_invoice_graduated_volume", "Invoice graduated and volume", 3),
                testCase("09", "test_invoice_retries_hard", "Invoice retries hard", 4),
                testCase("10", "test_invoice_hard", "Invoice hard", 4),
                testCase("11", "test_invoice_refund", "Invoice refund", 3),
                testCase("12", "invoice_export", "Invoice export", 3),
                testCase("13", "credits", "Credits", 2),
                testCase("14", "discounts", "Discounts", 2),
                testCase("15", "credits_discounts_hard", "Credits and discounts hard", 4),
                testCase("16", "notifications", "Notifications", 2),
                testCase("17", "notifications_hard", "Notifications hard", 3),
                testCase("18", "reports_customer_impact", "Customer impact report", 3),
                testCase("19", "reports_financial_exposure", "Financial exposure report", 3),
                testCase("20", "complex", "Complex", 9),
                testCase("21", "complex_edge", "Complex edge", 11)
        );
    }

    private static Arguments testCase(final String id, final String file, final String name, final int points) {
        String json = id + "_" + file + ".json";
        return Arguments.of(id + " - " + name, "input/in_" + json, "out/out_" + json, "ref/ref_" + json, points);
    }

    @Order(1)
    // TestCaseWatcher reads the name and the points back from this display name
    @ParameterizedTest(name = "{0} ({4} points)")
    @MethodSource("data")
    public void run(
            final String testName,
            final String inputPath,
            final String outputPath,
            final String refPath,
            final int points
    ) throws IOException {
        // the test id is the "NN" in input/in_NN_name.json
        String testId = new File(inputPath).getName().substring(3, 5);
        deleteOutputExports(testId);

        App.run(inputPath, outputPath);

        try {
            JsonNode outputJson = objectMapper.readTree(new File(outputPath));
            JsonNode refJson = objectMapper.readTree(new File(refPath));

            assertThatJson(outputJson).isEqualTo(refJson);
            assertExportsMatch(testId);
            devmindResults.add(new DevmindResult(
                testName,
                PASSED,
                points
            ));
        } catch (IOException | AssertionError e) {
            devmindResults.add(new DevmindErrorResult(
                    testName,
                    points,
                    e.getMessage()
            ));
            throw e;
        }

    }

    /**
     * Removes the exports left by a previous run of this test, so a stale file cannot pass.
     */
    private static void deleteOutputExports(final String testId) throws IOException {
        for (Path file : listExports(EXPORTS_OUT_DIR, EXPORT_OUT_PREFIX + testId + "_")) {
            Files.delete(file);
        }
    }

    /**
     * Every ref/exports/NN_name must exist as out/exports/out_NN_name with the same content,
     * and the test must not export any other file.
     */
    private static void assertExportsMatch(final String testId) throws IOException {
        List<String> expected = listExports(EXPORTS_REF_DIR, testId + "_").stream()
                .map(path -> path.getFileName().toString())
                .toList();
        List<String> actual = listExports(EXPORTS_OUT_DIR, EXPORT_OUT_PREFIX + testId + "_").stream()
                .map(path -> path.getFileName().toString().substring(EXPORT_OUT_PREFIX.length()))
                .toList();

        assertThat(actual)
                .as("exported files in %s (each named %s<file>)", EXPORTS_OUT_DIR, EXPORT_OUT_PREFIX)
                .containsExactlyElementsOf(expected);

        for (String name : expected) {
            File refFile = Paths.get(EXPORTS_REF_DIR, name).toFile();
            File outFile = Paths.get(EXPORTS_OUT_DIR, EXPORT_OUT_PREFIX + name).toFile();

            if (name.endsWith(".json")) {
                assertThatJson(objectMapper.readTree(outFile))
                        .as("export %s", outFile.getPath())
                        .isEqualTo(objectMapper.readTree(refFile));
            } else {
                assertCsvEquals(outFile, refFile);
            }
        }
    }

    /**
     * Compares two CSV files by value: same rows in the same order, same number of cells per row,
     * and each cell equal after trimming and unquoting (numeric cells are compared as numbers).
     */
    private static void assertCsvEquals(final File outFile, final File refFile) throws IOException {
        List<List<String>> outRows = readCsv(outFile);
        List<List<String>> refRows = readCsv(refFile);

        assertThat(outRows.size())
                .as("number of rows in export %s", outFile.getPath())
                .isEqualTo(refRows.size());

        for (int row = 0; row < refRows.size(); row++) {
            List<String> outCells = outRows.get(row);
            List<String> refCells = refRows.get(row);
            String where = "export %s, row %d: expected %s but was %s"
                    .formatted(outFile.getPath(), row + 1, refCells, outCells);

            assertThat(outCells.size()).as(where).isEqualTo(refCells.size());
            for (int col = 0; col < refCells.size(); col++) {
                assertThat(sameCsvValue(outCells.get(col), refCells.get(col))).as(where).isTrue();
            }
        }
    }

    private static boolean sameCsvValue(final String actual, final String expected) {
        try {
            return new BigDecimal(actual).compareTo(new BigDecimal(expected)) == 0;
        } catch (NumberFormatException e) {
            return actual.equals(expected);
        }
    }

    // blank lines are ignored; a cell may be wrapped in double quotes ("" inside quotes is a quote)
    private static List<List<String>> readCsv(final File file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file.toPath())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"' && quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    cells.add(cell.toString().strip());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            cells.add(cell.toString().strip());
            rows.add(cells);
        }
        return rows;
    }

    private static List<Path> listExports(final String dir, final String prefix) throws IOException {
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }

    @Order(2)
    @Test
    public void testCheckstyle() throws CheckstyleException, IOException {
        File configFile = new File("src/test/resources/checkstyle/checkstyle.xml");
        Configuration config = ConfigurationLoader.loadConfiguration(
                new InputSource(configFile.getAbsolutePath()),
                new PropertiesExpander(System.getProperties()),
                ConfigurationLoader.IgnoredModulesOptions.EXECUTE
        );

        CheckstyleAuditListener checkstyleAuditListener = new CheckstyleAuditListener();
        Checker checker = new Checker();
        checker.setModuleClassLoader(CheckerConstants.class.getClassLoader());
        checker.addListener(checkstyleAuditListener);
        checker.configure(config);

        List<File> files = SourceFileCollector.getJavaSourceFiles("src/main/java");
        int errorCount = checker.process(files);
        checker.destroy();

        try {
            assertThat(errorCount).isLessThanOrEqualTo(CheckerConstants.MAXIMUM_ERROR_CHECKSTYLE);
            devmindResults.add(new DevmindResult(
                    "checkstyle",
                    PASSED,
                    CheckerConstants.CHECKSTYLE_POINTS
            ));
            TestCaseWatcher.record("checkstyle", CheckerConstants.CHECKSTYLE_POINTS, true);
        }
        catch (AssertionError e) {
            devmindResults.add(new DevmindErrorResult(
                    "checkstyle",
                    CheckerConstants.CHECKSTYLE_POINTS,
                    e.getMessage()
            ));
            TestCaseWatcher.record("checkstyle", CheckerConstants.CHECKSTYLE_POINTS, false);
            throw new CheckstyleException(checkstyleAuditListener.toString());
        }
    }

    @Order(3)
    @Test
    public void testGitCommits() throws GitAPIException, IOException {
        File repoDirectory = new File("./");

        try (Git git = Git.open(repoDirectory)) {
            List<RevCommit> commits = StreamSupport.stream(git.log().call().spliterator(), false)
                    .filter(this::hasNonDeveloperAuthor)
                    .sorted(Comparator.comparing(RevCommit::getCommitTime))
                    .toList();

            assertThat(commits).isNotNull();
            assertThat(commits.size())
                    .isGreaterThanOrEqualTo(CheckerConstants.GIT_MINIMUM_COMMITS);

            devmindResults.add(new DevmindResult(
                    "git",
                    PASSED,
                    CheckerConstants.GIT_POINTS
            ));
            TestCaseWatcher.record("git", CheckerConstants.GIT_POINTS, true);
        } catch (IOException | AssertionError | GitAPIException e) {
            devmindResults.add(new DevmindErrorResult(
                    "git",
                    CheckerConstants.GIT_POINTS,
                    e.getMessage()
            ));
            TestCaseWatcher.record("git", CheckerConstants.GIT_POINTS, false);
            throw e;
        }
    }

    @AfterAll
    public static void afterAll() throws JsonProcessingException {
        boolean isDevmindEnvironment = Optional.ofNullable(System.getProperty("environment"))
                .map(value -> value.equals("devmind"))
                .orElse(false);

        if (isDevmindEnvironment) {
            printDevmindResults();
        } else {
            printLocalResults();
        }
    }

    private static void printLocalResults() {
        System.out.println(TestCaseWatcher.stringBuilder);
        System.out.println("Total: " + TestCaseWatcher.totalPoints + "/85");

        boolean allPassed = devmindResults.stream()
                .allMatch(result -> PASSED.equals(result.getStatus()));

        if (allPassed) {
            System.out.println("Well done. You're the GREATEST!");
            System.out.println("https://www.youtube.com/shorts/CZSpfzxga9g");
        }
    }

    private static void printDevmindResults() throws JsonProcessingException {
        System.out.println("BEGIN-DEVMIND-TEST-RESULTS");
        System.out.println(objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(devmindResults)
        );
        System.out.println("END-DEVMIND-TEST-RESULTS");
    }

    private boolean hasNonDeveloperAuthor(RevCommit commit) {
        List<String> exceptedAuthors = List.of(
                "gabrielvalentine738@gmail.com",
                "119312368+gabriel-2802@users.noreply.github.com"
        );

        String userEmail = commit.getAuthorIdent().getEmailAddress();
        return !exceptedAuthors.contains(userEmail);
    }
}
