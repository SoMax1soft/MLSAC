package wtf.mlsac.listeners;

import wtf.mlsac.checks.AICheck;
import wtf.mlsac.compat.EventCompat;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ServerType;
import wtf.mlsac.session.ISessionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import wtf.mlsac.data.AIPlayerData;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TickListener {
    private final ISessionManager sessionManager;
    private final AICheck aiCheck;
    private final EventCompat.TickHandler tickHandler;
    private final Map<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();
    private final boolean usePerPlayerTasks;
    private HitListener hitListener;

    public TickListener(JavaPlugin plugin, ISessionManager sessionManager, AICheck aiCheck) {
        this.sessionManager = sessionManager;
        this.aiCheck = aiCheck;
        this.tickHandler = EventCompat.createTickHandler(plugin, this::onTick);
        this.usePerPlayerTasks = SchedulerManager.getServerType() == ServerType.FOLIA;
    }

    public void start() {
        tickHandler.start();
    }

    public void stop() {
        tickHandler.stop();
        for (ScheduledTask task : playerTasks.values()) {
            task.cancel();
        }
        playerTasks.clear();
    }

    public void setHitListener(HitListener hitListener) {
        this.hitListener = hitListener;
    }

    private void onTick() {
        int currentTick = tickHandler.getCurrentTick();
        if (hitListener != null) {
            hitListener.setCurrentTick(currentTick);
        }
        if (!usePerPlayerTasks) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                aiCheck.onTick(player);
            }
        }
    }

    public void startPlayerTask(Player player) {
        if (!usePerPlayerTasks) {
            return;
        }
        if (playerTasks.containsKey(player.getUniqueId())) {
            return;
        }

        AIPlayerData data = aiCheck.getPlayerData(player.getUniqueId());
        if (data != null && data.isBedrock()) return;

        try {
            ScheduledTask task = SchedulerManager.getAdapter().runEntitySyncRepeating(player, () -> {
                aiCheck.onTick(player);
            }, 1L, 1L);
            playerTasks.put(player.getUniqueId(), task);
        } catch (Exception ignored) {
        }
    }

    public void stopPlayerTask(Player player) {
        ScheduledTask task = playerTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public int getCurrentTick() {
        return tickHandler.getCurrentTick();
    }
}
