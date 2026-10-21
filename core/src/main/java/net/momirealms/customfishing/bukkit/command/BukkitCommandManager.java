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

package net.momirealms.customfishing.bukkit.command;

import net.kyori.adventure.util.Index;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.bukkit.command.feature.*;
import net.momirealms.customfishing.common.command.AbstractCommandManager;
import net.momirealms.customfishing.common.command.CommandFeature;
import net.momirealms.customfishing.common.sender.Sender;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.setting.ManagerSetting;

import java.util.List;

public class BukkitCommandManager extends AbstractCommandManager<CommandSender> {

    /**
     * 是否已经经历过一次运行期卸载(PlugManX 之类的 unload -> load).
     *
     * <p>Paper 的 {@code LifecycleEvents.COMMANDS} 只在服务器启动与资源重载时触发,
     * 运行期重新 enable 的插件不会再收到该事件, 于是 cloud 的 ModernPaperBrigadier
     * 里 {@code commands} 字段始终为 null, 命令注册会被静默跳过, 表现为重载后指令全部失效.
     * 因此重载后的这一次 enable 改走 LegacyPaperBrigadier(命令本体注册到 Bukkit
     * CommandMap), 每次 enable 都会重新注册一遍.</p>
     */
    private static volatile boolean reloadedOnce = false;

    /**
     * 标记插件即将因运行期卸载而关闭, 下一次 enable 使用 legacy 命令通道.
     */
    public static void markRuntimeReload() {
        reloadedOnce = true;
    }

    private final List<CommandFeature<CommandSender>> FEATURES = List.of(
            new ReloadCommand(this),
            new SellFishCommand(this),
            new GetItemCommand(this),
            new GiveItemCommand(this),
            new GiveItemByUUIDCommand(this),
            new ImportItemCommand(this),
            new EndCompetitionCommand(this),
            new StopCompetitionCommand(this),
            new StartCompetitionCommand(this),
            new OpenMarketCommand(this),
            new OpenBagCommand(this),
            new OpenItemBrowserCommand(this),
            new FishingBagCommand(this),
            new EditOnlineBagCommand(this),
            new EditOfflineBagCommand(this),
            new UnlockDataCommand(this),
            new ImportDataCommand(this),
            new ExportDataCommand(this),
            new AddStatisticsCommand(this),
            new SetStatisticsCommand(this),
            new ResetStatisticsCommand(this),
            new QueryStatisticsCommand(this),
            new DebugLootCommand(this),
            new DebugBiomeCommand(this),
            new DebugSNBTCommand(this)
    );

    private final Index<String, CommandFeature<CommandSender>> INDEX = Index.create(CommandFeature::getFeatureID, FEATURES);

    public BukkitCommandManager(BukkitCustomFishingPlugin plugin) {
        super(plugin, new LegacyPaperCommandManager<>(
                plugin.getBootstrap(),
                ExecutionCoordinator.simpleCoordinator(),
                SenderMapper.identity()
        ));
        final LegacyPaperCommandManager<CommandSender> manager = (LegacyPaperCommandManager<CommandSender>) getCommandManager();
        manager.settings().set(ManagerSetting.ALLOW_UNSAFE_REGISTRATION, true);
        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            try {
                if (reloadedOnce) {
                    // 运行时重载: Paper 不会再派发 COMMANDS 生命周期事件, 原生 Brigadier 注册必然失效,
                    // 退回到 CommandMap 通道, 补全仍然可用(经由 LegacyPaperBrigadier 的事件监听)
                    manager.registerLegacyPaperBrigadier();
                    plugin.getPluginLogger().info("Runtime reload detected, commands are registered through the Bukkit command map");
                } else {
                    manager.registerBrigadier();
                }
                manager.brigadierManager().setNativeNumberSuggestions(true);
            } catch (Exception | LinkageError e) {
                // 注册失败时 cloud 仍保留默认的 CommandMap 注册处理器, 命令依旧可用(只是没有原生补全)
                plugin.getPluginLogger().warn("Failed to hook into Brigadier, falling back to the Bukkit command map", e);
            }
        } else if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        }
    }

    @Override
    protected Sender wrapSender(CommandSender sender) {
        return ((BukkitCustomFishingPlugin) plugin).getSenderFactory().wrap(sender);
    }

    @Override
    public Index<String, CommandFeature<CommandSender>> getFeatures() {
        return INDEX;
    }
}
