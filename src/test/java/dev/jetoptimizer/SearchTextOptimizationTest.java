package dev.jetoptimizer;

import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchTextOptimizationTest {
    private static void equivalent(String text) {
        assertEquals(ChatFormatting.stripFormatting(text), SearchTextOptimization.stripFormatting(text));
        Set<String> expected = new LinkedHashSet<>(List.of("existing"));
        Arrays.stream(text.trim().split("\\s+")).filter(s -> !s.isEmpty()).forEach(expected::add);
        Set<String> actual = new LinkedHashSet<>(List.of("existing"));
        SearchTextOptimization.splitOnWhitespace(actual, text);
        assertEquals(new ArrayList<>(expected), new ArrayList<>(actual), () -> "UTF-16 input: " + text.chars().boxed().toList());
    }
    @Test void playerTooltipFormattingAndWhitespaceEdgeCasesMatchMinecraft() {
        List.of("", " \t\r\n", "§aCopper §Lwire§r", "§§a§z§", "§x§1§2§3§4§5§6",
                "\u0000iron\u0001 dust\u001f", "copper\u0000wire", "iron\u00a0dust\u2003wire",
                "same same\tsame", "日本語 😀 §R金", "\ud800§a\udfff").forEach(SearchTextOptimizationTest::equivalent);
        for (int c = 0; c <= Character.MAX_VALUE; c++) equivalent("§" + (char)c);
    }
    @Test void generatedTooltipLinesMatchTheNativeOperations() {
        Random random = new Random(704981);
        String alphabet = "ab§0123456789aAfFkKoOrRzZ \t\n\r\f\u000b\u0000\u001f\u00a0\u2003日本\ud83d\ude00";
        for (int trial = 0; trial < 10_000; trial++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0, n = random.nextInt(100); i < n; i++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            equivalent(text.toString());
        }
    }
    @Test void unformattedTextDoesNotAllocateAReplacement() {
        String text = new String("Copper §z wire §");
        assertSame(text, SearchTextOptimization.stripFormatting(text));
    }
}
