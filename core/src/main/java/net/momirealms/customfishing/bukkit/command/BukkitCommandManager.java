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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.setting.ManagerSetting;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * 本次 enable 是否属于运行期重载(PlugManX unload -> load 之类的场景).
     */
    private final boolean runtimeReload;

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
        this.runtimeReload = consumeRuntimeReload(plugin.getDataDirectory());

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
     * 注册完成后主动把命令树下发给在线玩家.
     *
     * <p>Paper 只在启动阶段把命令树同步给客户端, 运行期重新注册的命令不会自动体现到客户端,
     * 表现为"服务端明明有命令, 客户端却提示未知命令".</p>
     *
     * <p>补发放在注册全部完成之后同步执行: 此时不会再有节点增删, 异步重建与结构修改不会交错.
     * 不能用调度器延后到下一 tick —— enable 阶段插件尚未标记为 enabled, Bukkit 调度器会拒收任务.</p>
     */
    @Override
    public void registerDefaultFeatures() {
        super.registerDefaultFeatures();
        resendCommandTree();
    }

    /**
     * 注销全部命令.
     *
     * <p>cloud 原实现是"逐个根节点 删除 + {@code player.updateCommands()}"。这有两个问题:
     * 一是 {@code updateCommands()} 会让 Paper 在异步线程重建整棵命令树, 而下一个根节点的删除
     * 紧接着发生, 两次操作并发就抛 {@code ConcurrentModificationException}; 二是卸载阶段根本
     * 不需要下发命令树(PlugManX 随后自己还会清理命令, 又是一次并发源)。</p>
     *
     * <p>所以这里一次性摘完所有根节点, <b>且不做任何 resend</b>; 命令树由下一次 enable 的
     * {@link #registerDefaultFeatures()} 统一补发。</p>
     */
    @Override
    public void unregisterFeatures() {
        final int removed = unregisterRootCommandsQuietly();
        if (removed < 0) {
            // 极少数情况下(拿不到 Brigadier 分派器)退回 cloud 原逻辑, 重新出现并发告警但不影响卸载
            plugin.getPluginLogger().warn("Failed to remove command nodes quietly, falling back to the default unregistration");
            super.unregisterFeatures();
            return;
        }
        plugin.getPluginLogger().info("Removed " + removed + " root command node(s) from the brigadier tree");
        this.registeredRootCommandComponents.clear();
        this.registeredFeatures.forEach(CommandFeature::unregisterRelatedFunctions);
        this.registeredFeatures.clear();
    }

    /**
     * 一次性摘除全部根命令节点, 过程中不触发任何命令树下发.
     *
     * @return 摘除的节点数; {@code -1} 表示应退回 cloud 的默认注销逻辑
     */
    private int unregisterRootCommandsQuietly() {
        final Object root = dispatcherRoot();
        if (root == null) return -1;
        final Set<String> labels = rootLabels();
        if (labels.isEmpty()) return 0;
        try {
            final Method removeCommand = removeCommandMethod();
            if (removeCommand != null) {
                for (final String label : labels) {
                    removeCommand.invoke(root, label);
                }
                return labels.size();
            }
            // 运行期 brigadier 没有 removeCommand 时, 直接操作 children(与它等价的删除方式)
            final Map<String, ?> children = childrenMapOf(root);
            if (children == null) return -1;
            labels.forEach(children::remove);
            return labels.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 收集需要摘除的根节点标签.
     *
     * <p>除了 cloud 注册时记下的别名(含 {@code FLATTEN_ALIASES} 展开出来的 label), 还要补上
     * 命令组件本身的名称, 避免别名表拿不到时漏删留下悬挂节点.</p>
     *
     * @return 标签集合
     */
    @NotNull
    private Set<String> rootLabels() {
        final Set<String> labels = new LinkedHashSet<>();
        try {
            final Object handler = this.getCommandManager().commandRegistrationHandler();
            final Field aliasesField = handler == null ? null : findField(handler.getClass(), "aliases");
            if (aliasesField != null && aliasesField.get(handler) instanceof Map<?, ?> aliases) {
                for (final Object value : aliases.values()) {
                    if (value instanceof Collection<?> aliasesOfLabel) {
                        for (final Object alias : aliasesOfLabel) {
                            if (alias instanceof String string) labels.add(string);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 拿不到别名表就只靠组件名, 下面的 removeCommand 是按 label 删除的, 组件名同样有效
        }
        for (final CommandComponent<CommandSender> component : this.registeredRootCommandComponents) {
            labels.add(component.name());
        }
        return labels;
    }

    /**
     * 取得 Brigadier 的根命令节点.
     *
     * <p>优先从 cloud 的现代通道 handler 里取 Paper 的 {@code Commands} 实例(Server 启动路径),
     * 拿不到时再从 {@code CraftServer#getServer()} 反查 {@code MinecraftServer#getCommands()},
     * 这样运行期重载走 CommandMap 通道时同样能拿到同一个分派器.</p>
     *
     * @return 根节点; 取不到返回 {@code null}
     */
    private Object dispatcherRoot() {
        try {
            final Object handler = this.getCommandManager().commandRegistrationHandler();
            final Field commandsField = handler == null ? null : findField(handler.getClass(), "commands");
            if (commandsField != null) {
                final Object commands = commandsField.get(handler);
                if (commands != null) {
                    final Object root = rootOf(commands);
                    if (root != null) return root;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            final Object server = Bukkit.getServer();
            final Object nmsServer = server.getClass().getMethod("getServer").invoke(server);
            final Object commands = nmsServer.getClass().getMethod("getCommands").invoke(nmsServer);
            return rootOf(commands);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 Paper 的 {@code Commands} 实例取 Brigadier 根节点.
     *
     * @param commands Paper 的 Commands 实例
     * @return 根节点; 反射失败返回 {@code null}
     */
    private static Object rootOf(@NotNull Object commands) {
        try {
            Object dispatcher;
            try {
                // 优先走接口方法, 实现类不公开时直接反射实现类会 IllegalAccessException
                dispatcher = Class.forName("io.papermc.paper.command.brigadier.Commands")
                        .getMethod("getDispatcher").invoke(commands);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                dispatcher = commands.getClass().getMethod("getDispatcher").invoke(commands);
            }
            return dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取得 Brigadier {@code CommandNode#removeCommand(String)}.
     *
     * <p>编译期依赖的 brigadier 版本没有该方法, 运行期(Paper)才有, 因此必须反射获取.</p>
     *
     * @return 方法句柄; 运行期没有该方法时返回 {@code null}
     */
    @NotNull
    private static Method removeCommandMethod() {
        try {
            final Method method = Class.forName("com.mojang.brigadier.tree.CommandNode")
                    .getMethod("removeCommand", String.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取 {@code CommandNode#children} 的实际 map.
     *
     * @param node 命令节点
     * @return 子节点 map; 取不到返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, ?> childrenMapOf(@NotNull Object node) {
        final Field field = findField(node.getClass(), "children");
        if (field == null) return null;
        try {
            return field.get(node) instanceof Map<?, ?> map ? (Map<String, ?>) map : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 沿继承链查找字段.
     *
     * @param type      起始类型
     * @param fieldName 字段名
     * @return 字段, 找不到返回 {@code null}
     */
    private static Field findField(@NotNull Class<?> type, @NotNull String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                final Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * 把最新的命令树下发给所有在线玩家(必须在主线程调用).
     */
    private void resendCommandTree() {
        try {
            final int players = Bukkit.getOnlinePlayers().size();
            for (final Player player : Bukkit.getOnlinePlayers()) {
                player.updateCommands();
            }
            if (this.runtimeReload && players > 0) {
                plugin.getPluginLogger().info("Command tree has been resent to " + players + " online player(s)");
            }
        } catch (Throwable ignored) {
            // 补发失败不影响命令本身可用, 玩家重新登录即可
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
