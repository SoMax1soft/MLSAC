package wtf.mlsac.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityUtilTest {

    @Test
    void acceptsStandardJavaEditionNames() {
        assertTrue(SecurityUtil.isSafeCommandName("Steve"));
        assertTrue(SecurityUtil.isSafeCommandName("xX_Killer_2000"));
        assertTrue(SecurityUtil.isSafeCommandName("a"));
        assertTrue(SecurityUtil.isSafeCommandName("1234567890123456"));
    }

    @Test
    void rejectsNamesUnsafeForCommandSubstitution() {
        assertFalse(SecurityUtil.isSafeCommandName(null));
        assertFalse(SecurityUtil.isSafeCommandName(""));
        assertFalse(SecurityUtil.isSafeCommandName("Steve Notch"), "space smuggles an extra argument");
        assertFalse(SecurityUtil.isSafeCommandName(".BedrockGamer"), "Floodgate prefix");
        assertFalse(SecurityUtil.isSafeCommandName("Steve\nop Steve"));
        assertFalse(SecurityUtil.isSafeCommandName("12345678901234567"), "over 16 chars");
        assertFalse(SecurityUtil.isSafeCommandName("Steve;kill @a"));
    }

    @Test
    void validProbabilityIsUnitInterval() {
        assertTrue(SecurityUtil.isValidProbability(0.0));
        assertTrue(SecurityUtil.isValidProbability(0.87));
        assertTrue(SecurityUtil.isValidProbability(1.0));
        assertFalse(SecurityUtil.isValidProbability(-0.01));
        assertFalse(SecurityUtil.isValidProbability(1.01));
        assertFalse(SecurityUtil.isValidProbability(Double.NaN));
        assertFalse(SecurityUtil.isValidProbability(Double.POSITIVE_INFINITY));
        assertFalse(SecurityUtil.isValidProbability(Double.NEGATIVE_INFINITY));
        assertFalse(SecurityUtil.isValidProbability(1e308));
    }

    @Test
    void sanitizeFileNameBlocksTraversalAndSeparators() {
        assertEquals("Steve", SecurityUtil.sanitizeFileName("Steve"));
        assertFalse(SecurityUtil.sanitizeFileName("../../evil").contains(".."));
        assertFalse(SecurityUtil.sanitizeFileName("..\\..\\evil").contains(".."));
        assertFalse(SecurityUtil.sanitizeFileName("a/b\\c").contains("/"));
        assertFalse(SecurityUtil.sanitizeFileName("a/b\\c").contains("\\"));
        assertEquals("unknown", SecurityUtil.sanitizeFileName(null));
        assertEquals("unknown", SecurityUtil.sanitizeFileName(""));
        assertTrue(SecurityUtil.sanitizeFileName("x".repeat(100)).length() <= 32);
    }

    @Test
    void sanitizeChatTextStripsControlCharsAndTruncates() {
        assertEquals("hello", SecurityUtil.sanitizeChatText("hello", 32));
        assertEquals("ab", SecurityUtil.sanitizeChatText("a\nb\r\t", 32));
        assertEquals("abc", SecurityUtil.sanitizeChatText("abcdef", 3));
        assertEquals("", SecurityUtil.sanitizeChatText(null, 32));
    }
}
