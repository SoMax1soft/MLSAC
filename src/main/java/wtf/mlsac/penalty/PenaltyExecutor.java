/*
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


package wtf.mlsac.penalty;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.penalty.handlers.AlertHandler;
import wtf.mlsac.penalty.handlers.BanHandler;
import wtf.mlsac.penalty.handlers.KickHandler;
import wtf.mlsac.penalty.handlers.RawHandler;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
public class PenaltyExecutor {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final ActionParser parser;
    private final PlaceholderProcessor placeholders;
    private final Map<ActionType, ActionHandler> handlers;
    private final AlertHandler alertHandler;
    public PenaltyExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.parser = new ActionParser();
        this.placeholders = new PlaceholderProcessor();
        this.handlers = new EnumMap<>(ActionType.class);
        this.alertHandler = new AlertHandler(plugin);
        
        // ANIMATION handler (also handles legacy {BAN} and {KICK})
        BanHandler animationHandler = new BanHandler(plugin);
        handlers.put(ActionType.ANIMATION, animationHandler);
        handlers.put(ActionType.BAN, animationHandler);  // Legacy support
        handlers.put(ActionType.KICK, animationHandler); // Legacy support
        
        handlers.put(ActionType.CUSTOM_ALERT, alertHandler);
        handlers.put(ActionType.RAW, new RawHandler(plugin));
    }
    public void execute(String rawCommand, PenaltyContext context) {
        if (rawCommand == null || rawCommand.isEmpty()) {
            return;
        }
        ParsedAction action = parser.parse(rawCommand);
        String processedCommand = placeholders.process(action.getCommand(), context);
        ActionHandler handler = handlers.get(action.getType());
        if (handler != null) {
            handler.handle(processedCommand, context);
        } else {
            logger.warning("No handler found for action type: " + action.getType());
        }
    }
    public void setAlertPrefix(String prefix) {
        alertHandler.setAlertPrefix(prefix);
    }
    public void setAlertRecipients(Set<UUID> recipients) {
        alertHandler.setAlertRecipients(recipients);
    }
    public void setConsoleAlerts(boolean enabled) {
        alertHandler.setConsoleAlerts(enabled);
    }
    public void setAnimationEnabled(boolean enabled) {
        ActionHandler animationHandler = handlers.get(ActionType.ANIMATION);
        if (animationHandler instanceof BanHandler) {
            ((BanHandler) animationHandler).setAnimationEnabled(enabled);
        }
    }
    public ActionParser getParser() {
        return parser;
    }
    public PlaceholderProcessor getPlaceholderProcessor() {
        return placeholders;
    }
    public void shutdown() {
        ActionHandler animationHandler = handlers.get(ActionType.ANIMATION);
        if (animationHandler instanceof BanHandler) {
            ((BanHandler) animationHandler).shutdown();
        }
    }
}