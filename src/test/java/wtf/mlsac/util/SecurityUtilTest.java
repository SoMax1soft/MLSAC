/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This file is based on GPLv3 licensed work and includes modifications.
 * Derived from:
 *   - Shard (© 2025 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 *   - Client-side project (GPLv3: https://github.com/MLSAC/client-side)
 *
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

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
        // The wildcard is the one operators hit in practice: a nick like this reaching
        // "ban * reason" would target every player instead of one.
        assertFalse(SecurityUtil.isSafeCommandName("*"));
        assertFalse(SecurityUtil.isSafeCommandName("Steve*"));
        assertFalse(SecurityUtil.isSafeCommandName("*Steve*"));
        assertFalse(SecurityUtil.isSafeCommandName("@e[type=player]"));
        assertFalse(SecurityUtil.isSafeCommandName("Steve\"Notch"));
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
