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

package wtf.mlsac.menu;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import wtf.mlsac.Main;
import wtf.mlsac.util.ColorUtil;
import wtf.mlsac.util.SecurityUtil;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes the configurable suspect-menu actions for a click. Splitting this out keeps
 * {@link SuspectsMenu} focused on rendering, and the prefix-keyed registry means a new action type
 * is added by registering a handler instead of extending an if/else chain (OCP).
 */
final class MenuActionDispatcher {

    @FunctionalInterface
    interface Action {
        void run(String argument, MenuActionContext context);
    }

    private static final Map<ClickType, String> CLICK_ACTION_KEYS = createClickActionKeys();

    private final Main plugin;
    private final Player admin;
    // LinkedHashMap: prefixes are matched in insertion order; none is a prefix of another.
    private final Map<String, Action> prefixActions = new LinkedHashMap<>();

    MenuActionDispatcher(Main plugin, Player admin) {
        this.plugin = plugin;
        this.admin = admin;
        prefixActions.put("[message]", (argument, context) ->
                admin.sendMessage(ColorUtil.colorize(applyPlaceholders(argument, context))));
        prefixActions.put("[teleport]", (argument, context) -> admin.teleport(context.target()));
        prefixActions.put("[gamemode]", this::setGamemode);
        prefixActions.put("[console]", (argument, context) -> {
            if (blockUnsafeTargetName(context)) {
                return;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), applyPlaceholders(argument, context));
        });
        prefixActions.put("[player]", (argument, context) -> {
            if (blockUnsafeTargetName(context)) {
                return;
            }
            admin.performCommand(applyPlaceholders(argument, context));
        });
        prefixActions.put("[admin]", (argument, context) -> {
            if (blockUnsafeTargetName(context)) {
                return;
            }
            admin.performCommand(applyPlaceholders(argument, context));
        });
    }

    /**
     * Command actions substitute {PLAYER} into a command line; a target name with spaces or
     * non-standard characters could smuggle extra arguments and hit an unrelated player.
     */
    private boolean blockUnsafeTargetName(MenuActionContext context) {
        String targetName = context.target().getName();
        if (SecurityUtil.isSafeCommandName(targetName)) {
            return false;
        }
        plugin.getLogger().warning("[Menu] Blocked command action for '" + admin.getName()
                + "': target name '" + targetName + "' is unsafe for command substitution");
        admin.sendMessage(ColorUtil.colorize("&cAction blocked: target name contains unsafe characters."));
        return true;
    }

    /** Runs every action configured under {@code gui.actions.<click>} for the given click. */
    void runForClick(ClickType clickType, MenuActionContext context,
            org.bukkit.configuration.file.FileConfiguration config) {
        String key = CLICK_ACTION_KEYS.get(clickType);
        if (key == null) {
            return;
        }
        List<String> actions = config.getStringList("gui.actions." + key);
        for (String rawAction : actions) {
            execute(rawAction, context);
        }
    }

    void execute(String rawAction, MenuActionContext context) {
        if (rawAction == null || rawAction.trim().isEmpty()) {
            return;
        }
        String action = rawAction.trim();
        String lower = action.toLowerCase(Locale.ROOT);

        if (lower.equals("[close]") || lower.equals("close")) {
            admin.closeInventory();
            return;
        }
        for (Map.Entry<String, Action> entry : prefixActions.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                String argument = action.substring(entry.getKey().length()).trim();
                entry.getValue().run(argument, context);
                return;
            }
        }

        admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                .getMessage("suspects-invalid-action", "{ACTION}", action)));
    }

    private void setGamemode(String argument, MenuActionContext context) {
        try {
            admin.setGameMode(GameMode.valueOf(argument.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid suspects menu gamemode: " + argument);
        }
    }

    private String applyPlaceholders(String input, MenuActionContext context) {
        return input
                .replace("{PLAYER}", context.target().getName())
                .replace("{TARGET}", context.target().getName())
                .replace("{ADMIN}", admin.getName())
                .replace("{AVG_PROB}", String.format(Locale.ROOT, "%.2f", context.avgProbability()))
                .replace("{DETECTIONS}", context.detections());
    }

    private static Map<ClickType, String> createClickActionKeys() {
        Map<ClickType, String> keys = new EnumMap<>(ClickType.class);
        keys.put(ClickType.LEFT, "left-click");
        keys.put(ClickType.RIGHT, "right-click");
        keys.put(ClickType.SHIFT_LEFT, "shift-left-click");
        keys.put(ClickType.SHIFT_RIGHT, "shift-right-click");
        keys.put(ClickType.MIDDLE, "middle-click");
        return keys;
    }
}
