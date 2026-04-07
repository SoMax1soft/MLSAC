package wtf.mlsac.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.util.ColorUtil;
import wtf.mlsac.util.ProbabilityFormatUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class NametagManager implements Listener {
    private static final int BELOW_NAME_DISPLAY_SLOT = 2;
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final AICheck aiCheck;
    private final ConcurrentHashMap<UUID, String> lastSentText = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> lastSentScore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> viewersMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastKnownNames = new ConcurrentHashMap<>();
    private final Set<UUID> objectiveViewers = ConcurrentHashMap.newKeySet();
    private ScheduledTask task;
    private int cleanupCounter;

    public NametagManager(JavaPlugin plugin, AICheck aiCheck) {
        this.plugin = plugin;
        this.aiCheck = aiCheck;
    }

    public void start() {
        if (!getConfig().getBoolean("nametags.enabled", true)) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long interval = Math.max(1L, getConfig().getLong("nametags.update-interval-ticks",
                getConfig().getLong("nametags.update_interval_ticks", 10L)));
        task = SchedulerManager.getAdapter().runSyncRepeating(this::globalTick, interval, interval);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            removeViewerFromAll(viewer);
            removeViewerObjective(viewer);
        }
        lastSentText.clear();
        lastSentScore.clear();
        viewersMap.clear();
        lastKnownNames.clear();
        objectiveViewers.clear();
    }

    private void globalTick() {
        List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Player> staff = new ArrayList<>();
        for (Player player : allPlayers) {
            if (canView(player)) {
                staff.add(player);
            } else {
                removeViewerFromAll(player);
                removeViewerObjective(player);
            }
        }

        if (isBelowNameMode()) {
            for (Player viewer : staff) {
                ensureViewerObjective(viewer);
            }
        }

        for (Player target : allPlayers) {
            updateNametag(target, staff);
        }

        if (++cleanupCounter > 100) {
            cleanupCounter = 0;
            cleanupOfflineViewers();
        }
    }

    private boolean canView(Player player) {
        return player.hasPermission(Permissions.ADMIN) || player.hasPermission(Permissions.ALERTS);
    }

    private void cleanupOfflineViewers() {
        lastSentText.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        lastSentScore.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        lastKnownNames.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        objectiveViewers.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        for (Set<UUID> viewers : viewersMap.values()) {
            viewers.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        }
    }

    public void updateNametag(Player target, List<Player> staff) {
        AIPlayerData data = aiCheck.getPlayerData(target.getUniqueId());
        String renderedText = buildNametagText(data);
        int belowScore = buildBelowScore(data);
        UUID targetId = target.getUniqueId();
        lastKnownNames.put(targetId, target.getName());

        boolean textChanged = !renderedText.equals(lastSentText.get(targetId));
        boolean scoreChanged = belowScore != lastSentScore.getOrDefault(targetId, Integer.MIN_VALUE);

        for (Player viewer : staff) {
            if (viewer.getUniqueId().equals(targetId)) {
                continue;
            }
            if (!viewer.getWorld().equals(target.getWorld())
                    || viewer.getLocation().distanceSquared(target.getLocation()) > getViewDistanceSquared()) {
                removeViewer(targetId, viewer);
                continue;
            }
            updateFor(target, viewer, renderedText, belowScore, textChanged, scoreChanged);
        }

        lastSentText.put(targetId, renderedText);
        lastSentScore.put(targetId, belowScore);
    }

    private String buildNametagText(AIPlayerData data) {
        String fastFormat = getConfig().getString("nametags.fast-format", "F{RESULT}%");
        String proFormat = getConfig().getString("nametags.pro-format", "P{RESULT}%");
        String ultraFormat = getConfig().getString("nametags.ultra-format", "U{RESULT}%");
        int historyLimit = Math.max(1, getConfig().getInt("nametags.history-limit",
                getConfig().getInt("nametags.history_limit", 8)));
        Function<Double, String> coloredFormatter = this::formatColoredPercent;

        if (data == null) {
            return applyFallbackPlaceholders(getPrimaryFormat());
        }

        String history = ProbabilityFormatUtil.formatHistory(data, fastFormat, proFormat, ultraFormat, historyLimit,
                coloredFormatter);
        String format = getPrimaryFormat();
        return ProbabilityFormatUtil.applyModelPlaceholders(format, data, coloredFormatter)
                .replace("{HISTORY}", history)
                .replace("{BUFFER}", formatPlainBuffer(data.getBuffer()))
                .replace("{LAST}", coloredFormatter.apply(data.getLastProbability()));
    }

    private String getPrimaryFormat() {
        if (isBelowNameMode()) {
            return getConfig().getString("nametags.below-name-format",
                    getConfig().getString("nametags.below_name_format",
                            getConfig().getString("nametags.format", "&6▶AVG &f&l{AVG}% &7| {HISTORY}")));
        }
        return getConfig().getString("nametags.above-name-format",
                getConfig().getString("nametags.above_name_format",
                        getConfig().getString("nametags.format", "&6▶AVG &f&l{AVG}% &7| {HISTORY}")));
    }

    private String applyFallbackPlaceholders(String template) {
        String avg = getConfig().getString("nametags.fallback.avg", "--");
        String last = getConfig().getString("nametags.fallback.last", avg);
        String fast = getConfig().getString("nametags.fallback.fast", "--");
        String pro = getConfig().getString("nametags.fallback.pro", "--");
        String ultra = getConfig().getString("nametags.fallback.ultra", "--");
        String history = getConfig().getString("nametags.fallback.history", "-");
        String buffer = getConfig().getString("nametags.fallback.buffer", "--");
        return template
                .replace("{AVG}", avg)
                .replace("{LAST}", last)
                .replace("{LAST-FAST}", fast)
                .replace("{LAST-PRO}", pro)
                .replace("{LAST-ULTRA}", ultra)
                .replace("{AVG-FAST}", fast)
                .replace("{AVG-PRO}", pro)
                .replace("{AVG-ULTRA}", ultra)
                .replace("{HISTORY}", history)
                .replace("{BUFFER}", buffer);
    }

    private int buildBelowScore(AIPlayerData data) {
        if (data == null) {
            return 0;
        }
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, data.getLastProbability())) * 100.0D);
    }

    private void updateFor(Player target, Player viewer, String text, int score,
            boolean textChanged, boolean scoreChanged) {
        UUID targetId = target.getUniqueId();
        Set<UUID> viewers = viewersMap.computeIfAbsent(targetId, ignored -> ConcurrentHashMap.newKeySet());
        boolean isNew = viewers.add(viewer.getUniqueId());

        if (isBelowNameMode()) {
            ensureViewerObjective(viewer);
            if (isNew || textChanged || scoreChanged) {
                updateBelowName(viewer, target.getName(), text, score);
            }
            return;
        }

        String teamName = teamNameForTarget(targetId);
        if (isNew) {
            createTeam(viewer, teamName, target.getName(), text);
        } else if (textChanged) {
            updateTeam(viewer, teamName, text);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeViewerFromAll(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getWorld() != event.getTo().getWorld()
                || event.getFrom().distanceSquared(event.getTo()) > 16.0D) {
            removeViewerFromAll(event.getPlayer());
        }
    }

    public void handlePlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        removeTargetForAllViewers(playerId, player.getName());
        removeViewerFromAll(player);
        removeViewerObjective(player);
        lastSentText.remove(playerId);
        lastSentScore.remove(playerId);
        lastKnownNames.remove(playerId);
    }

    private void removeTargetForAllViewers(UUID targetId, String fallbackName) {
        Set<UUID> viewers = viewersMap.remove(targetId);
        if (viewers == null) {
            return;
        }
        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            if (isBelowNameMode()) {
                removeBelowName(viewer, fallbackName);
            } else {
                removeTeam(viewer, teamNameForTarget(targetId));
            }
        }
    }

    private void removeViewerFromAll(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        for (Map.Entry<UUID, Set<UUID>> entry : viewersMap.entrySet()) {
            UUID targetId = entry.getKey();
            Set<UUID> viewers = entry.getValue();
            if (!viewers.remove(viewerId)) {
                continue;
            }
            if (isBelowNameMode()) {
                String targetName = lastKnownNames.getOrDefault(targetId, "");
                if (!targetName.isEmpty()) {
                    removeBelowName(viewer, targetName);
                }
            } else {
                removeTeam(viewer, teamNameForTarget(targetId));
            }
        }
    }

    private void removeViewer(UUID targetId, Player viewer) {
        Set<UUID> viewers = viewersMap.get(targetId);
        if (viewers == null || !viewers.remove(viewer.getUniqueId())) {
            return;
        }
        if (isBelowNameMode()) {
            String targetName = lastKnownNames.getOrDefault(targetId, "");
            if (!targetName.isEmpty()) {
                removeBelowName(viewer, targetName);
            }
        } else {
            removeTeam(viewer, teamNameForTarget(targetId));
        }
    }

    private boolean isBelowNameMode() {
        String raw = getConfig().getString("nametags.position", "BELOW_NAME");
        return raw != null && (raw.equalsIgnoreCase("below_name") || raw.equalsIgnoreCase("below"));
    }

    private void ensureViewerObjective(Player viewer) {
        if (!objectiveViewers.add(viewer.getUniqueId())) {
            return;
        }
        String objectiveName = objectiveNameForViewer(viewer.getUniqueId());
        if (supportsFancyBelowName()) {
            WrapperPlayServerScoreboardObjective createObjective = new WrapperPlayServerScoreboardObjective(
                    objectiveName,
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                    legacyComponent(getConfig().getString("nametags.below-name-title",
                            getConfig().getString("nametags.below_name_title", ""))),
                    WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                    ScoreFormat.fixedScore(Component.empty()));
            sendPacket(viewer, createObjective);
        } else {
            WrapperPlayServerScoreboardObjective createObjective = new WrapperPlayServerScoreboardObjective(
                    objectiveName,
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                    legacyComponent(getConfig().getString("nametags.below-name-title",
                            getConfig().getString("nametags.below_name_title", "% AI"))),
                    WrapperPlayServerScoreboardObjective.RenderType.INTEGER);
            sendPacket(viewer, createObjective);
        }
        sendPacket(viewer, new WrapperPlayServerDisplayScoreboard(BELOW_NAME_DISPLAY_SLOT, objectiveName));
    }

    private void removeViewerObjective(Player viewer) {
        if (!objectiveViewers.remove(viewer.getUniqueId())) {
            return;
        }
        String objectiveName = objectiveNameForViewer(viewer.getUniqueId());
        WrapperPlayServerScoreboardObjective removeObjective = new WrapperPlayServerScoreboardObjective(
                objectiveName,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                Component.empty(),
                null);
        sendPacket(viewer, removeObjective);
    }

    private void updateBelowName(Player viewer, String targetName, String text, int score) {
        String objectiveName = objectiveNameForViewer(viewer.getUniqueId());
        PacketWrapper<?> packet;
        if (supportsFancyBelowName()) {
            packet = new WrapperPlayServerUpdateScore(
                    targetName,
                    WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                    objectiveName,
                    score,
                    null,
                    ScoreFormat.fixedScore(legacyComponent(text)));
        } else {
            packet = new WrapperPlayServerUpdateScore(
                    targetName,
                    WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                    objectiveName,
                    Optional.of(score));
        }
        sendPacket(viewer, packet);
    }

    private void removeBelowName(Player viewer, String targetName) {
        String objectiveName = objectiveNameForViewer(viewer.getUniqueId());
        PacketWrapper<?> packet;
        if (supportsFancyBelowName()) {
            packet = new WrapperPlayServerResetScore(targetName, objectiveName);
        } else {
            packet = new WrapperPlayServerUpdateScore(
                    targetName,
                    WrapperPlayServerUpdateScore.Action.REMOVE_ITEM,
                    objectiveName,
                    Optional.empty());
        }
        sendPacket(viewer, packet);
    }

    private void createTeam(Player viewer, String teamName, String playerName, String text) {
        WrapperPlayServerTeams wrapper = new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.CREATE,
                createTeamInfo(text),
                Collections.singletonList(playerName));
        sendPacket(viewer, wrapper);
    }

    private void updateTeam(Player viewer, String teamName, String text) {
        WrapperPlayServerTeams wrapper = new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.UPDATE,
                createTeamInfo(text),
                Collections.emptyList());
        sendPacket(viewer, wrapper);
    }

    private void removeTeam(Player viewer, String teamName) {
        WrapperPlayServerTeams wrapper = new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                Collections.emptyList());
        sendPacket(viewer, wrapper);
    }

    private WrapperPlayServerTeams.ScoreBoardTeamInfo createTeamInfo(String text) {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                legacyComponent(text),
                Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                NamedTextColor.WHITE,
                WrapperPlayServerTeams.OptionData.NONE);
    }

    private Component legacyComponent(String text) {
        return LEGACY_SERIALIZER.deserialize(ColorUtil.colorize(text == null ? "" : text));
    }

    private void sendPacket(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private String formatColoredPercent(double probability) {
        return getProbabilityColor(probability) + ProbabilityFormatUtil.formatPercent(probability);
    }

    private String formatPlainBuffer(double buffer) {
        if (Math.abs(buffer - Math.rint(buffer)) < 0.0001D) {
            return String.valueOf((int) Math.rint(buffer));
        }
        return String.format(Locale.US, "%.2f", buffer);
    }

    private String getProbabilityColor(double probability) {
        String path = "nametags.colors.";
        double mediumThreshold = getConfig().getDouble(path + "thresholds.medium", 0.5D);
        double highThreshold = getConfig().getDouble(path + "thresholds.high", 0.6D);
        double criticalThreshold = getConfig().getDouble(path + "thresholds.critical", 0.8D);
        double criticalBoldThreshold = getConfig().getDouble(path + "thresholds.critical-bold",
                getConfig().getDouble(path + "thresholds.critical_bold", 0.9D));

        if (probability >= criticalBoldThreshold) {
            return getConfig().getString(path + "critical_bold",
                    getConfig().getString(path + "critical-bold", "&4&l"));
        }
        if (probability >= criticalThreshold) {
            return getConfig().getString(path + "critical", "&4");
        }
        if (probability >= highThreshold) {
            return getConfig().getString(path + "high", "&c");
        }
        if (probability >= mediumThreshold) {
            return getConfig().getString(path + "medium", "&6");
        }
        return getConfig().getString(path + "low", "&a");
    }

    private boolean supportsFancyBelowName() {
        return PacketEvents.getAPI().getServerManager().getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_20_3);
    }

    private String teamNameForTarget(UUID targetId) {
        String compact = targetId.toString().replace("-", "");
        return "mls_" + compact.substring(0, 12);
    }

    private String objectiveNameForViewer(UUID viewerId) {
        String compact = viewerId.toString().replace("-", "");
        return "mlv_" + compact.substring(0, 12);
    }

    private FileConfiguration getConfig() {
        return ((Main) plugin).getHologramConfig().getConfig();
    }

    private double getViewDistanceSquared() {
        double viewDistance = getConfig().getDouble("nametags.view-distance", 64.0D);
        return viewDistance * viewDistance;
    }
}
