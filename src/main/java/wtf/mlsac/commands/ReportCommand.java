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

package wtf.mlsac.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Handles the public {@code /report <nick> <reason>} command. */
public class ReportCommand implements CommandExecutor, TabCompleter {
    private static final int MAX_REASON_LENGTH = 256;

    private final Main plugin;

    public ReportCommand(Main plugin) {
        this.plugin = plugin;
    }

    private String prefix() {
        return ColorUtil.colorize(plugin.getMessagesConfig().getPrefix());
    }

    private String msg(String key, String... replacements) {
        return ColorUtil.colorize(plugin.getMessagesConfig().getMessage(key, replacements));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(prefix() + msg("players-only"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + msg("report-usage"));
            return true;
        }

        String targetName = args[0];
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        if (reason.isEmpty()) {
            sender.sendMessage(prefix() + msg("report-usage"));
            return true;
        }

        plugin.getReportManager().submitReport((Player) sender, targetName, reason);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefixArg = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefixArg))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
