/*
 * Copyright (C) 2026 lavinhoque33
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-06
 */

package app.morphe.extension.youtube.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

public class AboutInfoTest {
    private static final String FORMAT = "RVX Patches: %1$s\nApp: %2$s %3$s\nPatched: %4$s\n"
            + "Android: %5$s (SDK %6$d)\nDevice: %7$s %8$s";

    @Test
    public void githubOpensForkRepository() {
        assertEquals("https://github.com/lavinhoque33/revanced-patches", AboutInfo.url(AboutInfo.Link.GITHUB));
    }

    @Test
    public void bugReportUsesExistingTemplate() {
        assertEquals("https://github.com/lavinhoque33/revanced-patches/issues/new?template=bug-report.yml",
                AboutInfo.url(AboutInfo.Link.REPORT_ISSUE));
    }

    @Test
    public void featureRequestUsesExistingTemplate() {
        assertEquals("https://github.com/lavinhoque33/revanced-patches/issues/new?template=feature-request.yml",
                AboutInfo.url(AboutInfo.Link.REQUEST_FEATURE));
    }

    @Test
    public void allTargetsAreAbsoluteGithubHttpsUrls() {
        for (AboutInfo.Link link : AboutInfo.Link.values()) {
            URI uri = URI.create(AboutInfo.url(link));
            assertEquals("https", uri.getScheme());
            assertEquals("github.com", uri.getHost());
        }
    }

    @Test
    public void debugInfoContainsOnlyExplicitMetadata() {
        assertEquals("RVX Patches: 1.31.0\nApp: YouTube 21.39.525\nPatched: Nov 6, 2026\n"
                        + "Android: 14 (SDK 34)\nDevice: motorola edge+ 2023",
                AboutInfo.buildDebugInfo(FORMAT, "1.31.0", "YouTube", "21.39.525", "Nov 6, 2026",
                        "14", 34, "motorola", "edge+ 2023"));
    }

    @Test
    public void debugInfoTreatsBrandingAndDeviceTextAsData() {
        String text = AboutInfo.buildDebugInfo(FORMAT, "1.31.0", "My %s app", "20.05.46", "2026-11-06",
                "14", 34, "Example", "Model %1$s");
        assertEquals("RVX Patches: 1.31.0\nApp: My %s app 20.05.46\nPatched: 2026-11-06\n"
                + "Android: 14 (SDK 34)\nDevice: Example Model %1$s", text);
        assertFalse(text.endsWith("\n"));
    }

    @Test
    public void callerCanLocalizeLabelsWithoutChangingMetadata() {
        assertEquals("Patches 1.31.0 | YouTube 21.13.164 | 2026-11-06 | 14 / 34 | motorola edge+",
                AboutInfo.buildDebugInfo("Patches %1$s | %2$s %3$s | %4$s | %5$s / %6$d | %7$s %8$s",
                        "1.31.0", "YouTube", "21.13.164", "2026-11-06", "14", 34, "motorola", "edge+"));
    }

    @Test
    public void hostDebugFormatHasNoLayoutIndentation() throws Exception {
        Path hostStrings = Path.of("../../patches/src/main/resources/youtube/settings/host/values/strings.xml");
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(hostStrings.toFile());
        String format = XPathFactory.newInstance().newXPath().evaluate(
                "/resources/string[@name='revanced_about_debug_format']/text()", document);

        assertEquals(FORMAT, format.replace("\\n", "\n"));
    }

    @Test
    public void sdkFormattingDoesNotDependOnDeviceLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"));
            assertEquals("34", AboutInfo.buildDebugInfo("%6$d", "", "", "", "", "", 34, "", ""));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
