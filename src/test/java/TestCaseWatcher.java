import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestCaseWatcher implements TestWatcher {
    public record TestCaseData(String testName, int points) { }

    // display name of a test case, as set in TestRunner: "<name> (<points> points)"
    private static final Pattern DISPLAY_NAME = Pattern.compile("^(.*) \\((\\d+) points\\)$");

    public static final StringBuilder stringBuilder = new StringBuilder();

    public static int totalPoints = 0;

    @Override
    public void testSuccessful(ExtensionContext context) {
        try {
            TestCaseData testCaseData = getTestCaseData(context);
            record(testCaseData.testName(), testCaseData.points(), true);
        }
        catch (IllegalArgumentException e) {
            // Do nothing
        }
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        try {
            TestCaseData testCaseData = getTestCaseData(context);
            record(testCaseData.testName(), testCaseData.points(), false);
        }
        catch (IllegalArgumentException e) {
            // Do nothing
        }
    }

    // also used by TestRunner for the checks that are not test cases (checkstyle, git)
    public static void record(String name, int points, boolean passed) {
        if (passed) {
            stringBuilder.append("CASE - %s - %s/%s = PASSED\n".formatted(name, points, points));
            totalPoints += points;
        } else {
            stringBuilder.append("CASE - %s - 0/%s = FAILED\n".formatted(name, points));
        }
    }

    private TestCaseData getTestCaseData(ExtensionContext context) {
        Matcher matcher = DISPLAY_NAME.matcher(context.getDisplayName());

        if (!matcher.matches()) {
            throw new IllegalArgumentException();
        }

        return new TestCaseData(matcher.group(1), Integer.parseInt(matcher.group(2)));
    }
}
