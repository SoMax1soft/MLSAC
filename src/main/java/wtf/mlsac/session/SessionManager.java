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


package wtf.mlsac.session;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.config.Label;
import wtf.mlsac.data.DataSession;
import wtf.mlsac.util.AimProcessor;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
public class SessionManager implements ISessionManager {
    private final Map<UUID, DataSession> activeSessions;
    private final Map<UUID, AimProcessor> playerAimProcessors;
    private final Main plugin;
    private volatile String currentSessionFolder = null;
    public SessionManager(Main plugin) {
        this.activeSessions = new ConcurrentHashMap<>();
        this.playerAimProcessors = new ConcurrentHashMap<>();
        this.plugin = plugin;
    }
    public AimProcessor getOrCreateAimProcessor(UUID playerId) {
        return playerAimProcessors.computeIfAbsent(playerId, id -> new AimProcessor());
    }
    public void removeAimProcessor(UUID playerId) {
        playerAimProcessors.remove(playerId);
    }
    @Override
    public DataSession startSession(Player player, Label label, String comment) {
        UUID playerId = player.getUniqueId();
        if (hasActiveSession(player)) {
            stopSession(player);
        }
        AimProcessor aimProcessor = getOrCreateAimProcessor(playerId);
        DataSession session = new DataSession(
            playerId,
            player.getName(),
            label,
            comment,
            aimProcessor
        );
        activeSessions.put(playerId, session);
        plugin.getLogger().info("Started data collection for " + player.getName() + 
            " [" + label + "]");
        return session;
    }
    @Override
    public void stopSession(Player player) {
        stopSession(player.getUniqueId());
    }
    @Override
    public void stopSession(UUID playerId) {
        DataSession session = activeSessions.remove(playerId);
        if (session != null) {
            try {
                session.saveAndClose(plugin, currentSessionFolder);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, 
                    "Failed to save session for " + session.getPlayerName(), e);
            }
        }
    }
    @Override
    public void stopAllSessions() {
        for (UUID playerId : activeSessions.keySet().toArray(new UUID[0])) {
            stopSession(playerId);
        }
        this.currentSessionFolder = null;
    }
    @Override
    public boolean hasActiveSession(Player player) {
        return hasActiveSession(player.getUniqueId());
    }
    @Override
    public boolean hasActiveSession(UUID playerId) {
        return activeSessions.containsKey(playerId);
    }
    @Override
    public DataSession getSession(UUID playerId) {
        return activeSessions.get(playerId);
    }
    @Override
    public DataSession getSession(Player player) {
        return getSession(player.getUniqueId());
    }
    @Override
    public Collection<DataSession> getActiveSessions() {
        return Collections.unmodifiableCollection(activeSessions.values());
    }
    @Override
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
    @Override
    public String getCurrentSessionFolder() {
        return currentSessionFolder;
    }
    @Override
    public void onAttack(Player player) {
        DataSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.onAttack();
        }
    }
    @Override
    public void onTick(Player player, float yaw, float pitch) {
        DataSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.processTick(yaw, pitch);
        }
    }
}