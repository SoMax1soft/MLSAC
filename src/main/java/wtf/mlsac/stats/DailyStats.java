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

package wtf.mlsac.stats;

import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Tracks per-day detection/request counters backed by SQLite.
 *
 * <p>Counters are lock-free {@link AtomicInteger}s because they are bumped from hot paths
 * (the async HTTP response thread and the netty packet thread). The database, however, is a single
 * {@link Connection} that is <em>not</em> thread-safe, so all DB access is serialised under
 * {@link #dbLock} and kept off the increment path: writes happen on a periodic async flush, on the
 * daily rollover, and on shutdown — never synchronously while handling a packet.
 */
public class DailyStats {
    private static final long FLUSH_INTERVAL_TICKS = 600L; // ~30s

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Object dbLock = new Object();
    private final AtomicInteger todayDetections = new AtomicInteger(0);
    private final AtomicInteger todayRequests = new AtomicInteger(0);
    private Connection connection;
    private volatile String currentDate;
    private ScheduledTask flushTask;

    public DailyStats(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.currentDate = LocalDate.now().toString();
    }

    public void initialize() {
        synchronized (dbLock) {
            try {
                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }

                String dbPath = new File(dataFolder, "stats.db").getAbsolutePath();
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

                createTables();
                loadTodayStats();

                logger.info("[Stats] Database initialized");
            } catch (SQLException e) {
                logger.severe("[Stats] Failed to initialize database: " + e.getMessage());
                return;
            }
        }

        flushTask = SchedulerManager.getAdapter()
                .runAsyncRepeating(this::flush, FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
    }

    private void createTables() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS daily_stats (" +
                     "date TEXT PRIMARY KEY," +
                     "detections INTEGER DEFAULT 0," +
                     "requests INTEGER DEFAULT 0" +
                     ")";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** Must be called while holding {@link #dbLock}. */
    private void loadTodayStats() {
        currentDate = LocalDate.now().toString();
        todayDetections.set(0);
        todayRequests.set(0);

        String sql = "SELECT detections, requests FROM daily_stats WHERE date = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, currentDate);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    todayDetections.set(rs.getInt("detections"));
                    todayRequests.set(rs.getInt("requests"));
                }
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to load today's stats: " + e.getMessage());
        }
    }

    public void incrementDetections() {
        rollOverIfNeeded();
        todayDetections.incrementAndGet();
    }

    public void incrementRequests() {
        rollOverIfNeeded();
        todayRequests.incrementAndGet();
    }

    /**
     * Resets the counters when the calendar day changes. The fast path is a lock-free compare on a
     * {@code volatile} field; the lock is only taken on the rare day boundary, where the finished
     * day is flushed before the counters reset.
     */
    private void rollOverIfNeeded() {
        String today = LocalDate.now().toString();
        if (today.equals(currentDate)) {
            return;
        }
        synchronized (dbLock) {
            if (!today.equals(currentDate)) {
                saveStatsLocked();
                currentDate = today;
                todayDetections.set(0);
                todayRequests.set(0);
            }
        }
    }

    private void flush() {
        synchronized (dbLock) {
            saveStatsLocked();
        }
    }

    /** Must be called while holding {@link #dbLock}. */
    private void saveStatsLocked() {
        if (connection == null) {
            return;
        }
        String sql = "INSERT OR REPLACE INTO daily_stats (date, detections, requests) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, currentDate);
            stmt.setInt(2, todayDetections.get());
            stmt.setInt(3, todayRequests.get());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to save stats: " + e.getMessage());
        }
    }

    public int getTodayDetections() {
        rollOverIfNeeded();
        return todayDetections.get();
    }

    public int getTodayRequests() {
        rollOverIfNeeded();
        return todayRequests.get();
    }

    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        synchronized (dbLock) {
            if (connection != null) {
                try {
                    saveStatsLocked();
                    connection.close();
                    logger.info("[Stats] Database closed");
                } catch (SQLException e) {
                    logger.warning("[Stats] Error closing database: " + e.getMessage());
                } finally {
                    connection = null;
                }
            }
        }
    }
}
