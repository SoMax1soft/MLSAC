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

package wtf.mlsac;

import org.bukkit.permissions.Permissible;

/**
 * Every permission node MLSAC checks.
 *
 * <p>One node per action, so a rank can be given exactly what it needs — seeing alerts does not
 * imply being able to punish, and being able to punish does not imply being able to reload the
 * config. The nodes are grouped by prefix: {@code mlsac.command.*} for subcommands,
 * {@code mlsac.notify.*} for who receives chat output, {@code mlsac.bypass*} for exemptions.
 *
 * <p>The pre-existing coarse nodes ({@code mlsac.admin}, {@code mlsac.alerts}, {@code mlsac.prob},
 * {@code mlsac.reload}, {@code mlsac.collect}) still work. plugin.yml declares them as parents of
 * the new nodes, and the helpers below check them directly as well, so a server that already has
 * its ranks configured keeps working untouched.
 */
public final class Permissions {

    private Permissions() {
    }

    // ── Subcommands ──────────────────────────────────────────────────────────────────────────

    public static final String CMD_ALERTS = "mlsac.command.alerts";
    public static final String CMD_MONITOR = "mlsac.command.monitor";
    public static final String CMD_SUSPECTS = "mlsac.command.suspects";
    public static final String CMD_VISION = "mlsac.command.vision";
    public static final String CMD_PROFILE = "mlsac.command.profile";
    public static final String CMD_PUNISH = "mlsac.command.punish";
    public static final String CMD_KICKLIST = "mlsac.command.kicklist";
    public static final String CMD_FALSE_POSITIVE = "mlsac.command.falsepositive";
    public static final String CMD_COLLECT = "mlsac.command.collect";
    public static final String CMD_STATUS = "mlsac.command.status";
    public static final String CMD_RELOAD = "mlsac.command.reload";
    public static final String CMD_REINSTALL = "mlsac.command.reinstall";
    public static final String CMD_ANIMATION = "mlsac.command.animation";

    // ── Notifications ────────────────────────────────────────────────────────────────────────

    /** Receives cheat alerts in chat, the alert hologram and the detection sound. */
    public static final String NOTIFY_ALERTS = "mlsac.notify.alerts";
    /** Receives player reports, and is not held to the report cooldown. */
    public static final String NOTIFY_REPORTS = "mlsac.notify.reports";

    // ── Exemptions ───────────────────────────────────────────────────────────────────────────

    /** Exempts a player from every MLSAC check, including the anti-ESP occlusion engine. */
    public static final String BYPASS = "mlsac.bypass";
    /** Exempts a player from the anti-ESP occlusion engine only. */
    public static final String ANTI_ESP_BYPASS = "mlsac.antiesp.bypass";

    // ── Legacy nodes, still honoured ─────────────────────────────────────────────────────────

    public static final String ADMIN = "mlsac.admin";
    public static final String ALERTS = "mlsac.alerts";
    public static final String PROB = "mlsac.prob";
    public static final String RELOAD = "mlsac.reload";
    public static final String COLLECT = "mlsac.collect";

    /**
     * True if any of the nodes is granted.
     *
     * <p>Call sites pass the current node plus whichever legacy nodes used to cover it. plugin.yml
     * already maps the old nodes onto the new ones as children, so this is belt and braces for
     * permission plugins that resolve child nodes loosely — and it keeps the mapping visible at the
     * place that depends on it.
     */
    public static boolean hasAny(Permissible who, String... nodes) {
        if (who == null) {
            return false;
        }
        for (String node : nodes) {
            if (who.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this player should receive cheat alerts. */
    public static boolean canSeeAlerts(Permissible who) {
        return hasAny(who, NOTIFY_ALERTS, ALERTS, ADMIN);
    }

    /** Whether this player is staff for the report system. */
    public static boolean canSeeReports(Permissible who) {
        return hasAny(who, NOTIFY_REPORTS, ALERTS, ADMIN);
    }
}
