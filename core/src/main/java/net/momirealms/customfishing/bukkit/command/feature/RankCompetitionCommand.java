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

package net.momirealms.customfishing.bukkit.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.competition.CompetitionGoal;
import net.momirealms.customfishing.api.mechanic.competition.FishingCompetition;
import net.momirealms.customfishing.api.mechanic.competition.RankingProvider;
import net.momirealms.customfishing.bukkit.command.BukkitCommandFeature;
import net.momirealms.customfishing.common.command.CustomFishingCommandManager;
import net.momirealms.customfishing.common.helper.AdventureHelper;
import net.momirealms.customfishing.common.locale.MessageConstants;
import net.momirealms.customfishing.common.locale.TranslationManager;
import net.momirealms.customfishing.common.util.Pair;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;

import java.util.Iterator;

/**
 * 供管理员查看当前进行中比赛的实时排行榜。
 * <pre>/cfishing competition rank [count]</pre>
 * count 为展示名次数量，默认 10，最大 100；不带 -s 时输出完整榜单。
 */
public class RankCompetitionCommand extends BukkitCommandFeature<CommandSender> {

    /** 默认展示前 10 名 */
    private static final int DEFAULT_RANK_SIZE = 10;
    /** 单次最多展示 100 名，防止聊天框刷屏 */
    private static final int MAX_RANK_SIZE = 100;

    public RankCompetitionCommand(CustomFishingCommandManager<CommandSender> commandManager) {
        super(commandManager);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .flag(manager.flagBuilder("silent").withAliases("s").build())
                .optional("count", IntegerParser.integerParser(1, MAX_RANK_SIZE))
                .handler(context -> {
                    FishingCompetition competition = BukkitCustomFishingPlugin.getInstance().getCompetitionManager().getOnGoingCompetition();
                    if (competition == null) {
                        handleFeedback(context, MessageConstants.COMMAND_COMPETITION_FAILURE_NO_COMPETITION);
                        return;
                    }
                    // 与其余竞赛指令一致：-s 静默（命令被脚本/其他指令间接触发时使用）
                    if (context.flags().hasFlag("silent")) return;
                    int count = context.getOrDefault("count", DEFAULT_RANK_SIZE);
                    sendRankList(context.sender(), competition, count);
                });
    }

    /**
     * 向发送者输出榜单：表头（赛事/条件/剩余时间/人数）+ 前 count 名明细。
     */
    private void sendRankList(CommandSender sender, FishingCompetition competition, int count) {
        CompetitionGoal goal = competition.getGoal();
        RankingProvider ranking = competition.getRanking();

        handleFeedback(sender, MessageConstants.COMMAND_COMPETITION_RANK_HEADER,
                Component.text(competition.getConfig().id()),
                Component.text(goal.toString()),
                Component.text(formatRemainingTime(competition.getRemainingTime())),
                Component.text(ranking.getSize())
        );

        if (ranking.getSize() == 0) {
            handleFeedback(sender, MessageConstants.COMMAND_COMPETITION_RANK_EMPTY);
            return;
        }

        // 按名次序遍历，只展示前 count 名
        String unit = scoreUnit(goal);
        Iterator<Pair<String, Double>> iterator = ranking.getIterator();
        int rank = 1;
        while (iterator.hasNext() && rank <= count) {
            Pair<String, Double> entry = iterator.next();
            // MIN_SIZE 的分数在排行里取负存储，展示时还原为正值
            double score = goal.isReversed() ? -entry.right() : entry.right();
            handleFeedback(sender, MessageConstants.COMMAND_COMPETITION_RANK_ENTRY,
                    medal(rank),
                    Component.text(entry.left()),
                    Component.text(String.format("%.2f", score) + unit)
            );
            rank++;
        }
    }

    /** 前三名配金银铜色，与颁奖文案的视觉语言保持一致 */
    private static Component medal(int rank) {
        return switch (rank) {
            case 1 -> AdventureHelper.miniMessage("<#FFD700>" + rank + "</#FFD700>");
            case 2 -> AdventureHelper.miniMessage("<#C0C0C0>" + rank + "</#C0C0C0>");
            case 3 -> AdventureHelper.miniMessage("<#CD853F>" + rank + "</#CD853F>");
            default -> Component.text(rank);
        };
    }

    /** 体长类目标（最长/最短/总长）的成绩单位为 cm，其余无单位 */
    private static String scoreUnit(CompetitionGoal goal) {
        return switch (goal.key()) {
            case "max_size", "min_size", "total_size" -> "cm";
            default -> "";
        };
    }

    /** 把剩余秒数格式化为「X 小时 Y 分钟 Z 秒」，单位取自 format.* 翻译 */
    private static String formatRemainingTime(long seconds) {
        if (seconds <= 0) return "0";
        StringBuilder builder = new StringBuilder();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            builder.append(hours).append(TranslationManager.miniMessageTranslation(MessageConstants.FORMAT_HOUR.build().key()));
        }
        if (minutes > 0) {
            builder.append(minutes).append(TranslationManager.miniMessageTranslation(MessageConstants.FORMAT_MINUTE.build().key()));
        }
        if (secs > 0 || builder.length() == 0) {
            builder.append(secs).append(TranslationManager.miniMessageTranslation(MessageConstants.FORMAT_SECOND.build().key()));
        }
        return builder.toString();
    }

    @Override
    public String getFeatureID() {
        return "rank_competition";
    }
}
