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
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.setting.ManagerSetting;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BukkitCommandManager extends AbstractCommandManager<CommandSender> {

    /**
     * 运行期卸载(PlugManX unload -> load)的落盘标记.
     *
     * <p>Paper 的 {@code LifecycleEvents.COMMANDS} 只在服务器启动与资源重载时触发,
     * 运行期重新 enable 的插件不会再收到该事件, 于是 cloud 的 ModernPaperBrigadier
     * 里 {@code commands} 字段始终为 null, 命令注册会被静默跳过, 表现为重载后指令全部失效.
     * 因此重载后的这一次 enable 改走 LegacyPaperBrigadier(命令本体注册到 Bukkit
     * CommandMap), 每次 enable 都会重新注册一遍.</p>
     *
     * <p>标记必须落盘: PlugManX 卸载插件时可能连 ClassLoader 一起丢弃, 静态字段会随之丢失.</p>
     */
    private static final String RELOAD_FLAG_FILE = ".runtime-command-reload";

    /**
     * 同一 ClassLoader 下的快速标记, 避免每次都要读盘.
     */
    private static volatile boolean reloadedOnce = false;

    /**
     * 标记插件即将因运行期卸载而关闭, 下一次 enable 使用 legacy 命令通道.
     *
     * @param dataDirectory 插件数据目录
     */
    public static void markRuntimeReload(@NotNull Path dataDirectory) {
        reloadedOnce = true;
        try {
            Files.writeString(dataDirectory.resolve(RELOAD_FLAG_FILE), Long.toString(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 写不进去时只靠静态标记, 同 ClassLoader 的重载仍然有效
        }
    }

    /**
     * 消费运行期重载标记(读盘并清除).
     *
     * @param dataDirectory 插件数据目录
     * @return 本次 enable 是否属于运行期重载
     */
    private static boolean consumeRuntimeReload(@NotNull Path dataDirectory) {
        boolean reloaded = reloadedOnce;
        try {
            reloaded |= Files.deleteIfExists(dataDirectory.resolve(RELOAD_FLAG_FILE));
        } catch (IOException ignored) {
        }
        return reloaded;
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
            new RankCompetitionCommand(this),
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
        final String pluginName = plugin.getBootstrap().getName();
        // 上一次运行期卸载可能把命令留在 Bukkit 的 knownCommands 里(现代 Brigadier 通道的
        // unregister 只摘 Brigadier 节点, 不清 CommandMap), 残留会让本次 commandMap.register
        // 因为 label 已占用而返回 false, 表现同样是"重载后指令消失"
        purgeStaleCommands(pluginName);
        final boolean runtimeReload = consumeRuntimeReload(plugin.getDataDirectory());

        final LegacyPaperCommandManager<CommandSender> manager = (LegacyPaperCommandManager<CommandSender>) getCommandManager();
        manager.settings().set(ManagerSetting.ALLOW_UNSAFE_REGISTRATION, true);
        // 允许覆盖同名 label, 否则残留项会静默吞掉注册
        manager.settings().set(ManagerSetting.OVERRIDE_EXISTING_COMMANDS, true);
        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            try {
                if (runtimeReload) {
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

    /**
     * 清掉 Bukkit CommandMap 里本插件的遗留命令(含 {@code <插件名>:<label>} 形式).
     *
     * @param pluginName 插件名
     */
    private static void purgeStaleCommands(@NotNull String pluginName) {
        try {
            final CommandMap commandMap = Bukkit.getServer().getCommandMap();
            if (!(commandMap instanceof SimpleCommandMap simpleCommandMap)) return;
            final Map<String, Command> knownCommands = simpleCommandMap.getKnownCommands();
            final String owner = pluginName.toLowerCase(Locale.ROOT);
            final List<String> stale = new ArrayList<>();
            for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).startsWith(owner + ":")) {
                    stale.add(entry.getKey());
                    continue;
                }
                final Command command = entry.getValue();
                if (!(command instanceof PluginIdentifiableCommand identifiable)) continue;
                try {
                    final Plugin owningPlugin = identifiable.getPlugin();
                    // 插件卸载后 getPlugin() 可能为 null, 这类悬挂命令同样要清掉
                    if (owningPlugin == null || owningPlugin.getName().equalsIgnoreCase(pluginName)) {
                        stale.add(entry.getKey());
                    }
                } catch (Throwable ignored) {
                    stale.add(entry.getKey());
                }
            }
            stale.forEach(knownCommands::remove);
        } catch (Throwable ignored) {
            // 拿不到 knownCommands(非 SimpleCommandMap / 反射受限)时交给 OVERRIDE_EXISTING_COMMANDS 兜底
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
