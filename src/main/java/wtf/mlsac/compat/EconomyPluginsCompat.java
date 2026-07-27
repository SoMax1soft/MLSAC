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
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.compat;

import org.bukkit.Bukkit;

/**
 * Presence detection for the economy/social plugins MLS VISION can recognise: Vault,
 * PlayerPoints, EssentialsX and CMI.
 *
 * <p>Deliberately presence-only, not an API integration: none of these plugins are a compileOnly
 * dependency of this project (see plugin.yml softdepend — they're optional at runtime, not at
 * compile time), and their exact API surface varies enough across versions that calling into it
 * directly is not worth the fragility. {@link wtf.mlsac.vision.VisionListener} gets everything it
 * needs from command text (\`/pay\`, \`/points pay\`, \`/tpa\`, ...) via
 * {@code PlayerCommandPreprocessEvent}; this class only tells it which of those commands are
 * worth watching on this particular server, following the same
 * {@code Class.forName + getPlugin(name) != null} pattern as {@link WorldGuardCompat}.
 */
public final class EconomyPluginsCompat {
    private final boolean vaultPresent;
    private final boolean playerPointsPresent;
    private final boolean essentialsPresent;
    private final boolean cmiPresent;

    public EconomyPluginsCompat() {
        this.vaultPresent = isPluginPresent("net.milkbowl.vault.economy.Economy", "Vault");
        this.playerPointsPresent = isPluginPresent("org.black_ixx.playerpoints.PlayerPoints", "PlayerPoints");
        this.essentialsPresent = isPluginPresent("com.earth2me.essentials.Essentials", "Essentials");
        this.cmiPresent = isPluginPresent("com.Zrips.CMI.CMI", "CMI");
    }

    private static boolean isPluginPresent(String probeClassName, String pluginName) {
        try {
            Class.forName(probeClassName);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return false;
        }
        try {
            return Bukkit.getPluginManager().getPlugin(pluginName) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isVaultPresent() {
        return vaultPresent;
    }

    public boolean isPlayerPointsPresent() {
        return playerPointsPresent;
    }

    public boolean isEssentialsPresent() {
        return essentialsPresent;
    }

    public boolean isCmiPresent() {
        return cmiPresent;
    }

    /** True if any plugin that could explain a real-money/donation economy is on this server. */
    public boolean hasAnyEconomyProvider() {
        return vaultPresent || playerPointsPresent || essentialsPresent || cmiPresent;
    }

    /** Comma-separated list of detected plugins, for the startup log line. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (vaultPresent) sb.append("Vault");
        if (playerPointsPresent) sb.append(sb.length() > 0 ? ", " : "").append("PlayerPoints");
        if (essentialsPresent) sb.append(sb.length() > 0 ? ", " : "").append("Essentials");
        if (cmiPresent) sb.append(sb.length() > 0 ? ", " : "").append("CMI");
        return sb.length() > 0 ? sb.toString() : "no economy plugin detected";
    }
}
