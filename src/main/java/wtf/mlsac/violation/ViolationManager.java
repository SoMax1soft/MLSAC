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

package wtf.mlsac.violation;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.alert.AlertManager;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.config.Config;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.penalty.ActionType;
import wtf.mlsac.penalty.PenaltyContext;
import wtf.mlsac.penalty.PenaltyExecutor;
import wtf.mlsac.penalty.PunishmentLadder;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.server.IAIClient;
import wtf.mlsac.util.SecurityUtil;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ViolationManager {
    private static final int MAX_KICK_HISTORY = 10;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long PUNISHMENT_COOLDOWN_MS = 5000;
    // Raw (unprefixed) ladder commands whose first token is one of these still count as
    // punishments for the cooldown and API reporting, e.g. the default "kick {PLAYER} ...".
    private static final Set<String> DESTRUCTIVE_COMMAND_TOKENS = Set.of(
            "kick", "ban", "tempban", "banip", "ban-ip", "ipban", "mute", "tempmute", "jail", "punish");
    private final Main plugin;
    private final AlertManager alertManager;
    private final Logger logger;
    private final Map<UUID, Integer> violationLevels;
    private final LinkedList<KickRecord> kickHistory;
    private final PenaltyExecutor penaltyExecutor;
    private final Map<UUID, Long> lastPunishmentTime;
    private Config config;
    private PunishmentLadder ladder;
    private AICheck aiCheck;
    private ScheduledTask decayTask;

    public static class KickRecord {
        private final String playerName;
        private final double probability;
        private final double buffer;
        private final int vl;
        private final LocalDateTime time;
        private final String command;

        public KickRecord(String playerName, double probability, double buffer, int vl, String command) {
            this.playerName = playerName;
            this.probability = probability;
            this.buffer = buffer;
            this.vl = vl;
            this.time = LocalDateTime.now();
            this.command = command;
        }

        public String getPlayerName() {
            return playerName;
        }

        public double getProbability() {
            return probability;
        }

        public double getBuffer() {
            return buffer;
        }

        public int getVl() {
            return vl;
        }

        public LocalDateTime getTime() {
            return time;
        }

        public String getCommand() {
            return command;
        }

        public String getFormattedTime() {
            return time.format(TIME_FORMATTER);
        }
    }

    public ViolationManager(Main plugin, Config config, AlertManager alertManager) {
        this.plugin = plugin;
        this.config = config;
        this.ladder = new PunishmentLadder(config.getPunishmentCommands());
        this.alertManager = alertManager;
        this.logger = plugin.getLogger();
        this.violationLevels = new ConcurrentHashMap<>();
        this.kickHistory = new LinkedList<>();
        this.penaltyExecutor = new PenaltyExecutor(plugin);
        this.lastPunishmentTime = new ConcurrentHashMap<>();
        updatePenaltyExecutorConfig();
        startDecayTask();
    }

    public void setAICheck(AICheck aiCheck) {
        this.aiCheck = aiCheck;
    }

    private void startDecayTask() {
        stopDecayTask();
        if (!config.isVlDecayEnabled()) {
            return;
        }
        int intervalTicks = config.getVlDecayIntervalSeconds() * 20;
        decayTask = SchedulerManager.getAdapter().runSyncRepeating(this::processDecay, intervalTicks, intervalTicks);
        plugin.debug("[VL] Decay task started with interval " + config.getVlDecayIntervalSeconds() + "s");
    }

    private void stopDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    private void processDecay() {
        if (aiCheck == null) {
            return;
        }
        int decayAmount = config.getVlDecayAmount();
        for (Map.Entry<UUID, Integer> entry : violationLevels.entrySet()) {
            UUID playerId = entry.getKey();
            AIPlayerData playerData = aiCheck.getPlayerData(playerId);
            if (playerData != null && playerData.isInCombat()) {
                continue;
            }
            int oldVl = entry.getValue();
            int newVl = oldVl - decayAmount;
            if (newVl <= 0) {
                violationLevels.remove(playerId);
                plugin.debug("[VL] Decay: removed VL for " + playerId + " (was " + oldVl + ")");
            } else {
                violationLevels.put(playerId, newVl);
                plugin.debug("[VL] Decay: " + playerId + " VL " + oldVl + " -> " + newVl);
            }
        }
    }

    private void updatePenaltyExecutorConfig() {
        penaltyExecutor.setAlertPrefix(plugin.getMessagesConfig().getPrefix());
        penaltyExecutor.setConsoleAlerts(config.isAiConsoleAlerts());
        penaltyExecutor.setAnimationEnabled(config.isAnimationEnabled());
    }

    public void setConfig(Config config) {
        this.config = config;
        this.ladder = new PunishmentLadder(config.getPunishmentCommands());
        updatePenaltyExecutorConfig();
        startDecayTask();
    }

    public void handleFlag(Player player, double probability, double buffer) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        int newVl = incrementViolationLevel(uuid);
        alertManager.sendAlert(player.getName(), probability, buffer, newVl);
        logger.info("[Penalty] AI flag for " + player.getName() + " | VL=" + newVl
                + " prob=" + String.format(Locale.ROOT, "%.2f", probability)
                + " buffer=" + String.format(Locale.ROOT, "%.1f", buffer));
        String command = getApplicablePunishmentCommand(newVl);
        if (command != null) {
            ActionType actionType = ActionType.fromCommand(command);
            if (isPunishingAction(command, actionType)) {
                Long previousTime = lastPunishmentTime.get(uuid);
                if (previousTime != null && (now - previousTime) < PUNISHMENT_COOLDOWN_MS) {
                    logger.info("[Penalty] " + player.getName() + " punishment on cooldown, skipping " + actionType);
                    return;
                }
                lastPunishmentTime.put(uuid, now);
            }
            executeCommand(command, player, probability, buffer, newVl);
        }
    }

    /**
     * Punishment classifier for the cooldown and API reporting: prefixed animation commands plus
     * raw commands whose first token is a known kick/ban/mute command (the default ladder uses an
     * unprefixed {@code kick ...}, which is RAW and would otherwise bypass the cooldown).
     */
    private boolean isPunishingAction(String command, ActionType actionType) {
        if (actionType.isPunishment()) {
            return true;
        }
        if (actionType != ActionType.RAW || command == null) {
            return false;
        }
        String stripped = command.trim().toLowerCase(Locale.ROOT);
        int space = stripped.indexOf(' ');
        String first = space == -1 ? stripped : stripped.substring(0, space);
        if (first.startsWith("/")) {
            first = first.substring(1);
        }
        int colon = first.indexOf(':');
        if (colon != -1) {
            first = first.substring(colon + 1);
        }
        return DESTRUCTIVE_COMMAND_TOKENS.contains(first);
    }

    public int incrementViolationLevel(UUID playerId) {
        return violationLevels.merge(playerId, 1, Integer::sum);
    }

    public int getViolationLevel(UUID playerId) {
        return violationLevels.getOrDefault(playerId, 0);
    }

    public void resetViolationLevel(UUID playerId) {
        violationLevels.remove(playerId);
    }

    public String getApplicablePunishmentCommand(int vl) {
        return ladder.commandFor(vl).orElse(null);
    }

    public void executeMaxPunishment(Player player) {
        // MAX punishment uses hardcoded prob=1.0 / buffer=100.0. It is normally reachable only via
        // /mlsac punish, so log the caller stack to reveal anything else triggering it.
        logger.warning("[Penalty] executeMaxPunishment() called for " + player.getName()
                + " (hardcoded prob=1.00 buffer=100.0). Caller stack trace:");
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(stack.length, 12); i++) {
            logger.warning("    at " + stack[i]);
        }
        ladder.maxThreshold().ifPresent(threshold ->
                ladder.maxCommand().ifPresent(command ->
                        executeCommand(command, player, 1.0, 100.0, threshold)));
    }

    public void executeCommand(String command, Player player, double probability, double buffer, int vl) {
        ActionType actionType = ActionType.fromCommand(command);
        logger.info("[Penalty] EXECUTE for " + player.getName()
                + " | VL=" + vl
                + " prob=" + String.format(Locale.ROOT, "%.2f", probability)
                + " buffer=" + String.format(Locale.ROOT, "%.1f", buffer)
                + " action=" + actionType
                + " command='" + command + "'");
        // Names with spaces or non-standard characters (Geyser/Floodgate prefixes, cracked-server
        // exotics) must never be substituted into a console command: "kick Steve Notch reason"
        // would punish a different player. Fall back to a direct API kick instead.
        if (actionType.isConsoleCommand() && !SecurityUtil.isSafeCommandName(player.getName())) {
            logger.warning("[Penalty] Player name '" + player.getName()
                    + "' is unsafe for command substitution - skipping '" + command
                    + "', kicking directly via API instead");
            addKickRecord(new KickRecord(player.getName(), probability, buffer, vl, "DIRECT_KICK (unsafe name)"));
            String kickReason = "MLSAC Detection (VL:" + vl + ")";
            SchedulerManager.getAdapter().runEntitySync(player, () -> {
                if (player.isOnline()) {
                    player.kickPlayer(kickReason);
                }
            });
            reportPunish(player, probability, buffer, vl, "kick", "DIRECT_KICK");
            return;
        }
        PenaltyContext context = PenaltyContext.builder()
                .playerName(player.getName())
                .violationLevel(vl)
                .probability(probability)
                .buffer(buffer)
                .build();
        addKickRecord(new KickRecord(player.getName(), probability, buffer, vl, command));
        penaltyExecutor.execute(command, context);
        if (isPunishingAction(command, actionType)) {
            reportPunish(player, probability, buffer, vl, actionType.name().toLowerCase(Locale.ROOT), command);
        }
    }

    private void reportPunish(Player player, double probability, double buffer, int vl,
            String action, String command) {
        if (plugin.getAiClientProvider() == null) {
            return;
        }
        IAIClient client = plugin.getAiClientProvider().get();
        if (client != null) {
            client.reportPunish(player.getUniqueId().toString(), player.getName(), "unknown",
                    probability, buffer, vl, action, command);
        }
    }

    private synchronized void addKickRecord(KickRecord record) {
        kickHistory.addFirst(record);
        while (kickHistory.size() > MAX_KICK_HISTORY) {
            kickHistory.removeLast();
        }
    }

    public synchronized List<KickRecord> getKickHistory() {
        return Collections.unmodifiableList(new ArrayList<>(kickHistory));
    }

    public PenaltyExecutor getPenaltyExecutor() {
        return penaltyExecutor;
    }

    public void handlePlayerQuit(Player player) {
        lastPunishmentTime.remove(player.getUniqueId());
    }

    public void decreaseViolationLevel(UUID playerId, int amount) {
        violationLevels.computeIfPresent(playerId, (k, v) -> {
            int newVl = v - amount;
            return newVl <= 0 ? null : newVl;
        });
    }

    public void clearAll() {
        violationLevels.clear();
        lastPunishmentTime.clear();
        synchronized (this) {
            kickHistory.clear();
        }
    }

    public void shutdown() {
        stopDecayTask();
        clearAll();
        penaltyExecutor.shutdown();
    }
}
