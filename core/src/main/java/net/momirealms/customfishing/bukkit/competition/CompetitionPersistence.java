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
import net.momirealms.customfishing.api.storage.DataStorageProvider;
import net.momirealms.customfishing.bukkit.storage.method.database.sql.AbstractSQLDatabase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 赛事相关数据的持久化层：离线奖励补发队列 + 进行中赛事的排行榜快照。
 *
 * <p>只在存储后端是 SQL（H2 / SQLite / MySQL / MariaDB）时启用，直接复用
 * {@link AbstractSQLDatabase#getConnection()} 的连接池；JSON / YAML / MongoDB / Redis
 * 后端返回 {@link #available()} == false，调用方自动退回本地 YAML 文件。</p>
 *
 * <p>建表用 {@code CREATE TABLE IF NOT EXISTS} 在运行时执行（不依赖 schema/*.sql），
 * 所有标识符不带引号、不使用自增主键，保证四种方言都能跑。</p>
 */
public class CompetitionPersistence {

    private static final String PENDING_TABLE = "pending_rewards";
    private static final String SNAPSHOT_TABLE = "competition_snapshot";

    private final BukkitCustomFishingPlugin plugin;
    @Nullable
    private final AbstractSQLDatabase sql;

    public CompetitionPersistence(@NotNull BukkitCustomFishingPlugin plugin) {
        this.plugin = plugin;
        DataStorageProvider provider = plugin.getStorageManager() == null ? null
                : plugin.getStorageManager().getDataSource();
        this.sql = provider instanceof AbstractSQLDatabase database ? database : null;
    }

    /**
     * 当前存储后端是否支持落库。
     */
    public boolean available() {
        return sql != null;
    }

    /** 插件停机过程中调度器可能拒绝新任务，这里兜住，避免关服时刷异常。 */
    private void runAsync(Runnable task) {
        try {
            plugin.getScheduler().async().execute(task);
        } catch (Throwable throwable) {
            plugin.getPluginLogger().warn("Failed to schedule competition persistence task", throwable);
        }
    }

    private String pendingTable() {
        return sql.getTablePrefix() + "_" + PENDING_TABLE;
    }

    private String snapshotTable() {
        return sql.getTablePrefix() + "_" + SNAPSHOT_TABLE;
    }

    /**
     * 建表（幂等）。只在 {@link #available()} 为 true 时调用。
     */
    public void createTables() {
        if (sql == null) return;
        String pending = "CREATE TABLE IF NOT EXISTS " + pendingTable() + " ("
                + "uuid CHAR(36) NOT NULL,"
                + "player VARCHAR(32) NOT NULL,"
                + "competition VARCHAR(64) NOT NULL,"
                + "place INT NOT NULL,"
                + "score DOUBLE NOT NULL,"
                + "goal VARCHAR(32) NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "PRIMARY KEY (uuid, competition, place, created_at)"
                + ")";
        String snapshot = "CREATE TABLE IF NOT EXISTS " + snapshotTable() + " ("
                + "name VARCHAR(64) NOT NULL,"
                + "competition VARCHAR(64) NOT NULL,"
                + "goal VARCHAR(32) NOT NULL,"
                + "started_at BIGINT NOT NULL,"
                + "duration INT NOT NULL,"
                + "scores VARCHAR(8192) NOT NULL,"
                + "PRIMARY KEY (name)"
                + ")";
        try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(pending);
            statement.execute(snapshot);
        } catch (SQLException e) {
            plugin.getPluginLogger().warn("Failed to create competition tables, falling back to file storage", e);
        }
    }

    // ------------------------------------------------------------------
    // 离线奖励补发队列
    // ------------------------------------------------------------------

    /**
     * 一条待补发奖励的库内记录。
     */
    public record PendingRow(UUID uuid, String player, String competition, int place,
                             double score, String goal, long createdAt) {
    }

    public void insertPendingAsync(PendingRow row) {
        if (sql == null) return;
        runAsync(() -> {
            String sqlText = "INSERT INTO " + pendingTable()
                    + " (uuid, player, competition, place, score, goal, created_at) VALUES (?,?,?,?,?,?,?)";
            try (Connection connection = sql.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sqlText)) {
                statement.setString(1, row.uuid().toString());
                statement.setString(2, row.player());
                statement.setString(3, row.competition());
                statement.setInt(4, row.place());
                statement.setDouble(5, row.score());
                statement.setString(6, row.goal());
                statement.setLong(7, row.createdAt());
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getPluginLogger().warn("Failed to persist pending competition reward", e);
            }
        });
    }

    /**
     * 同步删除一条待补发记录。补发前先删，保证「至多一次」：
     * 即使发奖过程中崩溃，这条也不会在下次进服时重发。
     */
    public void deletePendingNow(@NotNull PendingRow row) {
        if (sql == null) return;
        String sqlText = "DELETE FROM " + pendingTable()
                + " WHERE uuid=? AND competition=? AND place=? AND created_at=?";
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(sqlText)) {
            statement.setString(1, row.uuid().toString());
            statement.setString(2, row.competition());
            statement.setInt(3, row.place());
            statement.setLong(4, row.createdAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getPluginLogger().warn("Failed to delete pending competition reward", e);
        }
    }

    public void purgePendingAsync(long before) {
        if (sql == null) return;
        runAsync(() -> {
            String sqlText = "DELETE FROM " + pendingTable() + " WHERE created_at < ?";
            try (Connection connection = sql.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sqlText)) {
                statement.setLong(1, before);
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getPluginLogger().warn("Failed to purge expired pending competition rewards", e);
            }
        });
    }

    /**
     * 读取全部待补发记录（阻塞调用，仅用于启动/重载）。
     */
    public List<PendingRow> loadPending() {
        List<PendingRow> rows = new ArrayList<>();
        if (sql == null) return rows;
        String sqlText = "SELECT uuid, player, competition, place, score, goal, created_at FROM " + pendingTable();
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(sqlText);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(resultSet.getString(1));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                rows.add(new PendingRow(
                        uuid,
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getInt(4),
                        resultSet.getDouble(5),
                        resultSet.getString(6),
                        resultSet.getLong(7)
                ));
            }
        } catch (SQLException e) {
            plugin.getPluginLogger().warn("Failed to load pending competition rewards from database", e);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 进行中赛事快照
    // ------------------------------------------------------------------

    /**
     * 赛事快照。{@code scores} 为 {@code 名字:分数:时间戳} 以 {@code ;} 连接。
     */
    public record SnapshotRow(String competition, String goal, long startedAt, int duration, String scores) {
    }

    public void saveSnapshotAsync(@NotNull SnapshotRow row) {
        if (sql == null) return;
        runAsync(() -> saveSnapshotNow(row));
    }

    /**
     * 同步写快照。关服时用（连接池马上要被 dispose，异步写会来不及）。
     */
    public void saveSnapshotNow(@NotNull SnapshotRow row) {
        if (sql == null) return;
        String update = "UPDATE " + snapshotTable()
                + " SET competition=?, goal=?, started_at=?, duration=?, scores=? WHERE name=?";
        try (Connection connection = sql.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, row.competition());
                statement.setString(2, row.goal());
                statement.setLong(3, row.startedAt());
                statement.setInt(4, row.duration());
                statement.setString(5, row.scores());
                statement.setString(6, "active");
                if (statement.executeUpdate() > 0) return;
            }
            String insert = "INSERT INTO " + snapshotTable()
                    + " (name, competition, goal, started_at, duration, scores) VALUES (?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, "active");
                statement.setString(2, row.competition());
                statement.setString(3, row.goal());
                statement.setLong(4, row.startedAt());
                statement.setInt(5, row.duration());
                statement.setString(6, row.scores());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getPluginLogger().warn("Failed to save competition snapshot", e);
        }
    }

    @Nullable
    public SnapshotRow loadSnapshot() {
        if (sql == null) return null;
        String sqlText = "SELECT competition, goal, started_at, duration, scores FROM "
                + snapshotTable() + " WHERE name=?";
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(sqlText)) {
            statement.setString(1, "active");
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                return new SnapshotRow(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getLong(3),
                        resultSet.getInt(4),
                        resultSet.getString(5)
                );
            }
        } catch (SQLException e) {
            plugin.getPluginLogger().warn("Failed to load competition snapshot", e);
            return null;
        }
    }

    public void deleteSnapshotAsync() {
        if (sql == null) return;
        runAsync(() -> {
            String sqlText = "DELETE FROM " + snapshotTable() + " WHERE name=?";
            try (Connection connection = sql.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sqlText)) {
                statement.setString(1, "active");
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getPluginLogger().warn("Failed to delete competition snapshot", e);
            }
        });
    }
}
