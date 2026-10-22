/*
 *  Copyright (C) <2024> <XiaoMoMi>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
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
import net.momirealms.customfishing.api.event.CompetitionEvent;
import net.momirealms.customfishing.api.mechanic.action.Action;
import net.momirealms.customfishing.api.mechanic.action.ActionManager;
import net.momirealms.customfishing.api.mechanic.competition.CompetitionConfig;
import net.momirealms.customfishing.api.mechanic.competition.CompetitionGoal;
import net.momirealms.customfishing.api.mechanic.competition.CompetitionPlayer;
import net.momirealms.customfishing.api.mechanic.competition.FishingCompetition;
import net.momirealms.customfishing.api.mechanic.competition.RankingProvider;
import net.momirealms.customfishing.api.mechanic.competition.info.BroadcastConfig;
import net.momirealms.customfishing.api.mechanic.config.ConfigManager;
import net.momirealms.customfishing.api.mechanic.context.Context;
import net.momirealms.customfishing.api.mechanic.context.ContextKeys;
import net.momirealms.customfishing.api.mechanic.misc.value.DynamicText;
import net.momirealms.customfishing.bukkit.competition.actionbar.ActionBarManager;
import net.momirealms.customfishing.bukkit.competition.bossbar.BossBarManager;
import net.momirealms.customfishing.bukkit.competition.ranking.LocalRankingProvider;
import net.momirealms.customfishing.bukkit.competition.ranking.RedisRankingProvider;
import net.momirealms.customfishing.common.helper.AdventureHelper;
import net.momirealms.customfishing.common.locale.MessageConstants;
import net.momirealms.customfishing.common.locale.TranslationManager;
import net.momirealms.customfishing.common.plugin.scheduler.SchedulerTask;
import net.momirealms.customfishing.common.sender.Sender;
import net.momirealms.customfishing.common.util.Pair;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class Competition implements FishingCompetition {
    private final BukkitCustomFishingPlugin plugin;
    private final CompetitionConfig config;
    private SchedulerTask competitionTimerTask;
    private final CompetitionGoal goal;
    private final Context<Player> publicContext;
    private final RankingProvider rankingProvider;
    private float progress;
    private int remainingTime;
    private long startTime;
    private BossBarManager bossBarManager;
    private ActionBarManager actionBarManager;
    /** 防止 end() 被重复调用时二次发奖（含重复写入离线补发队列）。 */
    private boolean prizesHandled;
    /** 排行榜快照落库间隔（秒）。重启最多丢这么多秒的积分。 */
    private static final int SNAPSHOT_INTERVAL_SECONDS = 10;
    /** 快照最多保存多少名（按名次截断，避免超长文本）。 */
    private static final int MAX_SNAPSHOT_ENTRIES = 200;
    /** 赛事已收尾（end / 主动停赛）后不再写快照，避免迟到的异步写入把记录复活。 */
    private boolean snapshotStopped;

    public Competition(BukkitCustomFishingPlugin plugin, CompetitionConfig config) {
        this(plugin, config, null);
    }

    /**
     * @param forcedGoal 非空时强制使用该目标（重启续赛要还原快照里抽中的目标，不能再随机一次）
     */
    public Competition(BukkitCustomFishingPlugin plugin, CompetitionConfig config,
                       @Nullable CompetitionGoal forcedGoal) {
        this.config = config;
        this.plugin = plugin;
        this.goal = forcedGoal != null ? forcedGoal
                : (config.goal() == CompetitionGoal.RANDOM ? CompetitionGoal.getRandom() : config.goal());
        if (ConfigManager.redisRanking()) this.rankingProvider = new RedisRankingProvider();
                              else this.rankingProvider = new LocalRankingProvider();
        this.publicContext = Context.player(null, true);
        this.publicContext.arg(ContextKeys.GOAL, goal);
    }

    @Override
    public void start(boolean triggerEvent) {
        this.progress = 1;
        this.remainingTime = this.config.durationInSeconds();
        this.startTime = Instant.now().getEpochSecond();

        this.arrangeTimerTask();
        if (this.config.bossBarConfig() != null && this.config.bossBarConfig().enabled()) {
            this.bossBarManager = new BossBarManager(this.config.bossBarConfig(), this);
            this.bossBarManager.load();
        }
        if (this.config.actionBarConfig() != null && this.config.actionBarConfig().enabled()) {
            this.actionBarManager = new ActionBarManager(this.config.actionBarConfig(), this);
            this.actionBarManager.load();
        }

        this.updatePublicPlaceholders();
        ActionManager.trigger(this.publicContext, this.config.startActions());
        this.rankingProvider.clear();
        this.saveSnapshot();
        if (triggerEvent) {
            this.plugin.getScheduler().async().execute(() -> {
                CompetitionEvent competitionStartEvent = new CompetitionEvent(CompetitionEvent.State.START, this);
                Bukkit.getPluginManager().callEvent(competitionStartEvent);
            });
        }
    }

    @Override
    public void stop(boolean triggerEvent) {
        if (this.competitionTimerTask != null)
            this.competitionTimerTask.cancel();
        if (this.bossBarManager != null)
            this.bossBarManager.unload();
        if (this.actionBarManager != null)
            this.actionBarManager.unload();
        if (triggerEvent) {
            // 主动停赛：清掉快照，重启后不再续赛
            this.snapshotStopped = true;
            deleteSnapshot();
        } else {
            // 插件停机 / 服务器关闭：同步落一次最新快照，等下次启动接着跑
            saveSnapshotNow();
        }
        this.rankingProvider.clear();
        this.remainingTime = 0;
        if (triggerEvent) {
            plugin.getScheduler().async().execute(() -> {
                CompetitionEvent competitionEvent = new CompetitionEvent(CompetitionEvent.State.STOP, this);
                Bukkit.getPluginManager().callEvent(competitionEvent);
            });
        }
    }

    @Override
    public void end(boolean triggerEvent) {
        // mark it as ended
        this.remainingTime = 0;

        // cancel some sub tasks
        if (competitionTimerTask != null)
            this.competitionTimerTask.cancel();
        if (this.bossBarManager != null)
            this.bossBarManager.unload();
        if (this.actionBarManager != null)
            this.actionBarManager.unload();

        // give prizes
        HashMap<String, Action<Player>[]> rewardsMap = config.rewards();
        if (!prizesHandled && rankingProvider.getSize() != 0 && rewardsMap != null) {
            prizesHandled = true;
            // 先快照名次：发奖要回主线程执行，而榜单可能已经被下一次 clear 清掉
            List<Pair<String, Double>> ranked = new ArrayList<>();
            Iterator<Pair<String, Double>> iterator = rankingProvider.getIterator();
            while (iterator.hasNext()) {
                ranked.add(iterator.next());
            }
            // 发奖涉及背包 / 经济操作，必须回主线程；离线玩家转入补发队列
            plugin.getScheduler().sync().run(() -> distributeRewards(ranked, rewardsMap), null);
        }

        // end actions
        ActionManager.trigger(publicContext, config.endActions());

        // call event
        if (triggerEvent) {
            plugin.getScheduler().async().execute(() -> {
                CompetitionEvent competitionEndEvent = new CompetitionEvent(CompetitionEvent.State.END, this);
                Bukkit.getPluginManager().callEvent(competitionEndEvent);
            });
        }

        // 赛事已结束，快照没有意义了
        this.snapshotStopped = true;
        deleteSnapshot();

        // 1 seconds delay for other servers to read the redis data
        plugin.getScheduler().asyncLater(this.rankingProvider::clear, 1, TimeUnit.SECONDS);
    }

    /**
     * 逐个名次发奖（主线程）。
     * <p>在线玩家当场发；离线玩家写入 {@link PendingRewardManager}，等其下次进服时补发。
     * 参与奖档位（第 4 名及以后）不进补发队列 —— 该档位在默认配置里是 {@code chance: 0} 的空动作。</p>
     */
    private void distributeRewards(List<Pair<String, Double>> ranked, HashMap<String, Action<Player>[]> rewardsMap) {
        PendingRewardManager pending = pendingRewardManager();
        int i = 1;
        for (Pair<String, Double> competitionPlayer : ranked) {
            String playerName = competitionPlayer.left();
            double score = goal.isReversed() ? -competitionPlayer.right() : competitionPlayer.right();
            this.publicContext.arg(ContextKeys.of(i + "_player", String.class), playerName);
            this.publicContext.arg(ContextKeys.of(i + "_score", String.class), String.format("%.2f", score));
            Action<Player>[] actions = i < rewardsMap.size()
                    ? rewardsMap.get(String.valueOf(i))
                    : rewardsMap.get("participation");
            if (actions != null) {
                Player player = Bukkit.getPlayerExact(playerName);
                if (player != null) {
                    ActionManager.trigger(Context.player(player).combine(this.publicContext), actions);
                } else if (pending != null && i < rewardsMap.size()) {
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
                    pending.add(offlinePlayer.getUniqueId(), playerName, this.config.id(), i, score, this.goal.key());
                }
            }
            i++;
        }
    }

    @Nullable
    private PendingRewardManager pendingRewardManager() {
        if (plugin.getCompetitionManager() instanceof BukkitCompetitionManager bukkitManager) {
            return bukkitManager.getPendingRewardManager();
        }
        return null;
    }

    /**
     * 从快照恢复一场被打断的比赛（服务器重启 / 崩溃后）。
     * 不重播开赛播报，也不重置 startTime，倒计时按原定结束时间继续走。
     *
     * @param startedAt        原开赛时间戳（秒）
     * @param remainingSeconds 剩余秒数
     * @param scoresCsv        {@code 名字:分数:时间戳} 以 {@code ;} 连接
     * @return 是否恢复成功
     */
    public boolean resume(long startedAt, int remainingSeconds, @Nullable String scoresCsv) {
        if (remainingSeconds <= 0) return false;
        this.startTime = startedAt;
        this.remainingTime = remainingSeconds;
        this.progress = Math.min(1f, (float) remainingSeconds / this.config.durationInSeconds());
        if (scoresCsv != null && !scoresCsv.isEmpty()) {
            for (String entry : scoresCsv.split(";")) {
                String[] parts = entry.split(":", 3);
                if (parts.length < 3) continue;
                try {
                    this.rankingProvider.addPlayer(new CompetitionPlayer(parts[0],
                            Double.parseDouble(parts[1]), Long.parseLong(parts[2])));
                } catch (NumberFormatException ignored) {
                    // 坏数据跳过即可，不影响其余名次
                }
            }
        }
        if (this.config.bossBarConfig() != null && this.config.bossBarConfig().enabled()) {
            this.bossBarManager = new BossBarManager(this.config.bossBarConfig(), this);
            this.bossBarManager.load();
        }
        if (this.config.actionBarConfig() != null && this.config.actionBarConfig().enabled()) {
            this.actionBarManager = new ActionBarManager(this.config.actionBarConfig(), this);
            this.actionBarManager.load();
        }
        this.arrangeTimerTask();
        this.updatePublicPlaceholders();
        this.plugin.getPluginLogger().info("Resumed competition `" + this.config.id()
                + "` (goal=" + this.goal.key() + ") with " + this.rankingProvider.getSize()
                + " entrie(s), " + remainingSeconds + "s left");
        return true;
    }

    /**
     * 把当前榜单写成快照（SQL 后端才生效）。
     */
    private void saveSnapshot() {
        saveSnapshot(false);
    }

    /**
     * 同步写快照：关服时连接池即将 dispose，异步写会来不及。
     */
    private void saveSnapshotNow() {
        saveSnapshot(true);
    }

    private void saveSnapshot(boolean blocking) {
        if (this.snapshotStopped) return;
        CompetitionPersistence persistence = persistence();
        if (persistence == null || !persistence.available()) return;
        // 跨服榜单以 Redis 为准，本地不再做快照
        if (ConfigManager.redisRanking()) return;
        StringBuilder builder = new StringBuilder();
        Iterator<Pair<String, Double>> iterator = this.rankingProvider.getIterator();
        int index = 0;
        while (iterator.hasNext() && index < MAX_SNAPSHOT_ENTRIES) {
            Pair<String, Double> pair = iterator.next();
            CompetitionPlayer player = this.rankingProvider.getCompetitionPlayer(pair.left());
            long time = player == null ? System.currentTimeMillis() : player.getTime();
            if (index > 0) builder.append(';');
            // 存 provider 内的原始分数（MIN_SIZE 为负数），恢复时原样塞回 TreeSet 才能保住同分顺序
            builder.append(pair.left()).append(':').append(pair.right()).append(':').append(time);
            index++;
        }
        CompetitionPersistence.SnapshotRow row = new CompetitionPersistence.SnapshotRow(
                this.config.id(), this.goal.key(), this.startTime,
                this.config.durationInSeconds(), builder.toString());
        if (blocking) {
            persistence.saveSnapshotNow(row);
        } else {
            persistence.saveSnapshotAsync(row);
        }
    }

    private void deleteSnapshot() {
        CompetitionPersistence persistence = persistence();
        if (persistence == null || !persistence.available()) return;
        persistence.deleteSnapshotAsync();
    }

    @Nullable
    private CompetitionPersistence persistence() {
        if (plugin.getCompetitionManager() instanceof BukkitCompetitionManager manager) {
            return manager.getPersistence();
        }
        return null;
    }

    private void arrangeTimerTask() {
        this.competitionTimerTask = this.plugin.getScheduler().asyncRepeating(() -> {
            if (decreaseTime()) {
                end(true);
                return;
            }
            updatePublicPlaceholders();
            if ((this.config.durationInSeconds() - this.remainingTime) % SNAPSHOT_INTERVAL_SECONDS == 0) {
                saveSnapshot();
            }
            BroadcastConfig broadcastConfig = config.broadcastConfig();
            if (broadcastConfig != null && broadcastConfig.enabled()) {
                int intervalInSeconds = broadcastConfig.interval() / 20;
                if ((this.config.durationInSeconds() - this.remainingTime) % intervalInSeconds == 0) {
                    this.broadcast(broadcastConfig);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void broadcast(BroadcastConfig config) {
        String[] texts = config.texts();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Sender audience = plugin.getSenderFactory().wrap(player);
            for (String s : texts) {
                DynamicText text = new DynamicText(player, s);
                text.update(this.publicContext.placeholderMap());
                audience.sendMessage(AdventureHelper.miniMessage(text.getLatestValue()));
            }
        }
    }

    private void updatePublicPlaceholders() {
        for (int i = 1; i < ConfigManager.placeholderLimit() + 1; i++) {
            Optional<String> player = Optional.ofNullable(this.rankingProvider.getPlayerAt(i));
            if (player.isPresent()) {
                this.publicContext.arg(ContextKeys.of(i + "_player", String.class), player.get());
                this.publicContext.arg(ContextKeys.of(i + "_score", String.class), String.format("%.2f", getScore(i)));
            } else {
                this.publicContext.arg(ContextKeys.of(i + "_player", String.class), TranslationManager.miniMessageTranslation(MessageConstants.COMPETITION_NO_PLAYER.build().key()));
                this.publicContext.arg(ContextKeys.of(i + "_score", String.class), TranslationManager.miniMessageTranslation(MessageConstants.COMPETITION_NO_SCORE.build().key()));
            }
        }
        this.publicContext.arg(ContextKeys.HOUR, remainingTime < 3600 ? "" : (remainingTime / 3600) + TranslationManager.miniMessageTranslation(MessageConstants.FORMAT_HOUR.build().key()));
        this.publicContext.arg(ContextKeys.MINUTE, remainingTime < 60 ? "" : (remainingTime % 3600) / 60 + TranslationManager.miniMessageTranslation(MessageConstants.FORMAT_MINUTE.build().key()));
        this.publicContext.arg(ContextKeys.SECOND, remainingTime == 0 ? "" : remainingTime % 60 + TranslationManager.miniMessageTranslation(MessageConstants.FORMAT_SECOND.build().key()));
        this.publicContext.arg(ContextKeys.SECONDS, remainingTime);
    }

    @ApiStatus.Internal
    public double getScore(String player) {
        double score = this.rankingProvider.getPlayerScore(player);
        return goal.isReversed() ? -score : score;
    }

    @ApiStatus.Internal
    public double getScore(int rank) {
        return goal.isReversed() ? -this.rankingProvider.getScoreAt(rank) : this.rankingProvider.getScoreAt(rank);
    }

    @Override
    public boolean isOnGoing() {
        return remainingTime > 0;
    }

    /**
     * Decreases the remaining time for the fishing competition and updates the progress.
     *
     * @return {@code true} if the remaining time becomes zero or less, indicating the competition has ended.
     */
    private boolean decreaseTime() {
        long current = Instant.now().getEpochSecond();
        int duration = config.durationInSeconds();
        remainingTime = (int) (duration - (current - startTime));
        progress = (float) remainingTime / duration;
        return remainingTime <= 0;
    }

    @Override
    public void refreshData(Player player, double score) {
        refreshScore(player, score);
    }

    @Override
    public void refreshScore(Player player, double score) {
        // if player join for the first time, trigger join actions
        if (!hasPlayerJoined(player)) {
            ActionManager.trigger(Context.player(player).combine(publicContext), config.joinActions());
        }

        // show competition info
        if (this.bossBarManager != null)
            this.bossBarManager.showBossBarTo(player);
        if (this.actionBarManager != null)
            this.actionBarManager.showActionBarTo(player);

        // refresh data
        this.goal.refreshScore(rankingProvider, player, score);
    }

    @Override
    public boolean hasPlayerJoined(OfflinePlayer player) {
        return rankingProvider.getPlayerRank(player.getName()) != -1;
    }

    @Override
    public float getProgress() {
        return progress;
    }

    @Override
    public long getRemainingTime() {
        return remainingTime;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    @NotNull
    @Override
    public CompetitionConfig getConfig() {
        return config;
    }

    @NotNull
    @Override
    public CompetitionGoal getGoal() {
        return goal;
    }

    @NotNull
    @Override
    public RankingProvider getRanking() {
        return rankingProvider;
    }

    @Override
    public Context<Player> getPublicContext() {
        return publicContext;
    }
}
