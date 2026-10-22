/*
 *  Copyright (C) <2024> <XiaoMoMi>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.momirealms.customfishing.bukkit.competition;

import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.action.Action;
import net.momirealms.customfishing.api.mechanic.action.ActionManager;
import net.momirealms.customfishing.api.mechanic.competition.CompetitionConfig;
import net.momirealms.customfishing.api.mechanic.competition.CompetitionGoal;
import net.momirealms.customfishing.api.mechanic.context.Context;
import net.momirealms.customfishing.api.mechanic.context.ContextKeys;
import net.momirealms.customfishing.bukkit.competition.CompetitionPersistence.PendingRow;
import net.momirealms.customfishing.common.helper.AdventureHelper;
import net.momirealms.customfishing.common.sender.Sender;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 大赛奖励补发队列。
 *
 * <p>结算时只给在线玩家发奖，离线玩家的名次进队列，等玩家下次进服时按当前赛事配置补发一次
 * （同一档位的所有动作全部执行）。补发的是「档位动作」而不是固定物品：奖励在此期间被改过，
 * 补发以**当前配置**为准；赛事配置被整个删掉时该条记录作废并记一条警告。</p>
 *
 * <p><b>语义</b>：每次进服**只尝试补发一次**，先删记录再发奖，失败只记 warn 不重试
 * ——「至多一次」，宁可少发也不多发。</p>
 *
 * <p><b>持久化</b>：存储后端是 SQL（H2 / SQLite / MySQL / MariaDB）时写入
 * {@code <prefix>_pending_rewards} 表（见 {@link CompetitionPersistence}），重启不丢；
 * JSON / YAML / MongoDB / Redis 后端退回 {@code pending-competition-rewards.yml} 文件。</p>
 */
public class PendingRewardManager implements Listener {

    private static final String FILE_NAME = "pending-competition-rewards.yml";
    /** 超过这个时间的未领取记录自动作废，避免表 / 文件无限膨胀。 */
    private static final long EXPIRE_MILLIS = TimeUnit.DAYS.toMillis(30);

    private final BukkitCustomFishingPlugin plugin;
    private final File file;
    private final LinkedHashMap<UUID, List<PendingReward>> pending = new LinkedHashMap<>();
    @Nullable
    private CompetitionPersistence persistence;
    private boolean databaseBacked;

    /**
     * 一条待补发的奖励记录。
     *
     * @param uuid          玩家 UUID（补发与删除都按它定位）
     * @param playerName    结算时的玩家名（用于占位符与日志）
     * @param competitionId 赛事配置 ID
     * @param rank          名次（1 起；0 表示参与奖档位）
     * @param score         结算时展示的成绩（已按目标反向规则处理）
     * @param goal          本场抽中的目标 key
     * @param time          写入时间戳
     */
    public record PendingReward(UUID uuid, String playerName, String competitionId, int rank,
                                double score, String goal, long time) {
    }

    public PendingRewardManager(@NotNull BukkitCustomFishingPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        Bukkit.getPluginManager().registerEvents(this, plugin.getBootstrap());
    }

    /**
     * 存储初始化。必须在存储管理器就绪之后调用（{@code BukkitCompetitionManager#load()}），
     * 可重复调用（重载时重新读一遍）。
     */
    public synchronized void initStorage(@Nullable CompetitionPersistence shared) {
        this.pending.clear();
        this.persistence = shared;
        this.databaseBacked = persistence != null && persistence.available();
        if (databaseBacked) {
            loadFromDatabase();
        } else {
            loadFromFile();
        }
    }

    public boolean isDatabaseBacked() {
        return databaseBacked;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    public synchronized void add(@NotNull UUID uuid, @NotNull String playerName, @NotNull String competitionId,
                                int rank, double score, @NotNull String goalKey) {
        PendingReward reward = new PendingReward(uuid, playerName, competitionId, rank, score, goalKey,
                System.currentTimeMillis());
        pending.computeIfAbsent(uuid, k -> new ArrayList<>()).add(reward);
        if (databaseBacked && persistence != null) {
            persistence.insertPendingAsync(toRow(reward));
        } else {
            saveFile();
        }
        plugin.debug("Queued offline competition reward: " + playerName + " #" + rank + " (" + competitionId + ")");
    }

    public synchronized boolean hasPending(@NotNull UUID uuid) {
        List<PendingReward> list = pending.get(uuid);
        return list != null && !list.isEmpty();
    }

    public synchronized int pendingCount() {
        int count = 0;
        for (List<PendingReward> list : pending.values()) count += list.size();
        return count;
    }

    // ------------------------------------------------------------------
    // 进服补发
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!hasPending(player.getUniqueId())) return;
        // 延迟 1 秒（20 tick）：等其它插件的进服流程（经济账户加载、数据同步）跑完再发奖
        plugin.getScheduler().sync().runLater(() -> deliver(player), 20L, player.getLocation());
    }

    /**
     * 把该玩家所有待补发奖励发出去，**只尝试一次**。
     *
     * <p>顺序是「先摘除、再发放」：内存里删掉 + 库里删掉之后才开始发奖，
     * 即使发奖过程中抛异常或服务器崩溃，这条也不会在下次进服时重发。
     * 即「至多一次」——宁可少发，不多发。</p>
     */
    public void deliver(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getScheduler().sync().run(() -> deliver(player), player.getLocation());
            return;
        }
        List<PendingReward> rewards;
        synchronized (this) {
            rewards = pending.remove(player.getUniqueId());
        }
        if (rewards == null || rewards.isEmpty()) return;

        // 先落库删除：之后无论发放结果如何都不再补发
        if (databaseBacked && persistence != null) {
            for (PendingReward reward : rewards) {
                persistence.deletePendingNow(toRow(reward));
            }
        } else {
            saveFile();
        }

        int delivered = 0;
        for (PendingReward reward : rewards) {
            if (give(player, reward)) {
                delivered++;
            }
        }

        if (delivered > 0) {
            Sender sender = plugin.getSenderFactory().wrap(player);
            sender.sendMessage(AdventureHelper.miniMessage(
                    "<#7FFFD4>[钓鱼大赛]</#7FFFD4> <gray>你离线期间结算的赛事奖励已补发，请查收背包。</gray>"));
            plugin.debug("Delivered " + delivered + " pending competition reward(s) to " + player.getName()
                    + " (single attempt, no retry)");
        }
    }

    /**
     * 发一条奖励，失败只记日志，不重试。
     *
     * @return 是否成功发放
     */
    private boolean give(Player player, PendingReward reward) {
        CompetitionConfig config = competitionConfig(reward.competitionId());
        if (config == null) {
            plugin.getPluginLogger().warn("Pending competition reward dropped: competition `"
                    + reward.competitionId() + "` no longer exists (player " + reward.playerName() + ")");
            return false;
        }
        Action<Player>[] actions = RewardTiers.of(config).actionsFor(reward.rank());
        if (actions == null) {
            plugin.getPluginLogger().warn("Pending competition reward dropped: no actions for rank "
                    + reward.rank() + " of `" + reward.competitionId() + "`");
            return false;
        }

        Context<Player> context = Context.player(player);
        context.arg(ContextKeys.of(reward.rank() + "_player", String.class), reward.playerName());
        context.arg(ContextKeys.of(reward.rank() + "_score", String.class), String.format("%.2f", reward.score()));
        CompetitionGoal goal = CompetitionGoal.index().value(reward.goal());
        if (goal != null) {
            context.arg(ContextKeys.GOAL, goal);
        }
        try {
            ActionManager.trigger(context, actions);
            return true;
        } catch (Throwable throwable) {
            plugin.getPluginLogger().warn("Failed to deliver pending competition reward to "
                    + reward.playerName() + " #" + reward.rank() + ", dropped (no retry)", throwable);
            return false;
        }
    }

    private static PendingRow toRow(PendingReward reward) {
        return new PendingRow(reward.uuid(), reward.playerName(), reward.competitionId(),
                reward.rank(), reward.score(), reward.goal(), reward.time());
    }

    private CompetitionConfig competitionConfig(String id) {
        if (plugin.getCompetitionManager() instanceof BukkitCompetitionManager manager) {
            return manager.getCompetition(id);
        }
        return null;
    }

    /** 名次 -> 动作数组；名次超出名次奖档位时回落到 participation。 */
    private record RewardTiers(CompetitionConfig config) {
        static RewardTiers of(CompetitionConfig config) {
            return new RewardTiers(config);
        }

        Action<Player>[] actionsFor(int rank) {
            Map<String, Action<Player>[]> rewards = config.rewards();
            if (rewards == null) return null;
            if (rank > 0 && rank < rewards.size()) {
                Action<Player>[] actions = rewards.get(String.valueOf(rank));
                if (actions != null) return actions;
            }
            return rewards.get("participation");
        }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private void loadFromDatabase() {
        if (persistence == null) return;
        long expireBefore = System.currentTimeMillis() - EXPIRE_MILLIS;
        persistence.purgePendingAsync(expireBefore);
        List<PendingRow> rows = persistence.loadPending();
        int expired = 0;
        synchronized (this) {
            for (PendingRow row : rows) {
                if (row.createdAt() < expireBefore) {
                    expired++;
                    continue;
                }
                pending.computeIfAbsent(row.uuid(), k -> new ArrayList<>())
                        .add(new PendingReward(row.uuid(), row.player(), row.competition(), row.place(),
                                row.score(), row.goal(), row.createdAt()));
            }
        }
        if (expired > 0) {
            plugin.getPluginLogger().info("Dropped " + expired + " expired pending competition reward(s)");
        }
        if (!pending.isEmpty()) {
            plugin.getPluginLogger().info("Loaded " + pendingCount()
                    + " pending competition reward(s) from database");
        }
    }

    private synchronized void loadFromFile() {
        if (!file.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            plugin.getPluginLogger().warn("Failed to read " + FILE_NAME + ", starting with an empty queue", e);
            return;
        }
        long now = System.currentTimeMillis();
        int expired = 0;
        for (String key : yaml.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<PendingReward> list = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList(key)) {
                long time = asLong(raw.get("time"), now);
                if (now - time > EXPIRE_MILLIS) {
                    expired++;
                    continue;
                }
                list.add(new PendingReward(uuid, asString(raw.get("player")), asString(raw.get("competition")),
                        (int) asLong(raw.get("rank"), 0), asDouble(raw.get("score")),
                        asString(raw.get("goal")), time));
            }
            if (!list.isEmpty()) pending.put(uuid, list);
        }
        if (expired > 0) {
            plugin.getPluginLogger().info("Dropped " + expired + " expired pending competition reward(s)");
            saveFile();
        }
        if (!pending.isEmpty()) {
            plugin.getPluginLogger().info("Loaded " + pendingCount()
                    + " pending competition reward(s) from " + FILE_NAME);
        }
    }

    private synchronized void saveFile() {
        if (databaseBacked) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<PendingReward>> entry : pending.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            List<Map<String, Object>> list = new ArrayList<>();
            for (PendingReward reward : entry.getValue()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("player", reward.playerName());
                map.put("competition", reward.competitionId());
                map.put("rank", reward.rank());
                map.put("score", reward.score());
                map.put("goal", reward.goal());
                map.put("time", reward.time());
                list.add(map);
            }
            yaml.set(entry.getKey().toString(), list);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getPluginLogger().warn("Failed to create data folder for " + FILE_NAME);
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getPluginLogger().warn("Failed to save " + FILE_NAME, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long asLong(Object o, long def) {
        if (o instanceof Number number) return number.longValue();
        try {
            return o == null ? def : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double asDouble(Object o) {
        if (o instanceof Number number) return number.doubleValue();
        try {
            return o == null ? 0d : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
