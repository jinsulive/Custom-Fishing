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

package net.momirealms.customfishing.bukkit.gui;

import net.kyori.adventure.text.Component;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.context.Context;
import net.momirealms.customfishing.api.mechanic.context.ContextKeys;
import net.momirealms.customfishing.api.util.PlayerUtils;
import net.momirealms.customfishing.common.locale.MessageConstants;
import net.momirealms.customfishing.common.locale.TranslationManager;
import net.momirealms.customfishing.common.sender.Sender;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.UUID;

/**
 * 物品图鉴 GUI 管理器.
 *
 * <p>负责打开/关闭 GUI、处理库存交互(全GUI内点击默认取消, 仅放开展示区的获取行为)、
 * 维护玩家与 GUI 实例的映射. 物品列表实时读取 {@code ItemManager}, 重载配置后
 * 重新打开即为最新数据, 无需缓存失效逻辑.</p>
 */
public class BukkitItemBrowserManager implements Listener {

    /** 获取物品所需权限, 与 /customfishing items get 保持一致 */
    public static final String GIVE_PERMISSION = "customfishing.command.getitem";

    private final BukkitCustomFishingPlugin plugin;
    private final HashMap<UUID, ItemBrowserGUI> guiCache = new HashMap<>();

    public BukkitItemBrowserManager(@NotNull BukkitCustomFishingPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin.getBootstrap());
    }

    /**
     * 注销事件监听并清空缓存.
     */
    public void unload() {
        HandlerList.unregisterAll(this);
        this.guiCache.clear();
    }

    /**
     * 为玩家打开物品图鉴 GUI.
     * 若该玩家已有打开的图鉴, 先关闭旧实例避免缓存泄漏.
     *
     * @param player 目标玩家
     * @return 是否成功打开
     */
    public boolean openGUI(@NotNull Player player) {
        ItemBrowserGUI previous = guiCache.remove(player.getUniqueId());
        if (previous != null) {
            player.closeInventory();
        }
        ItemBrowserGUI gui = new ItemBrowserGUI(plugin, player);
        guiCache.put(player.getUniqueId(), gui);
        gui.open();
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof ItemBrowserHolder))
            return;
        // 图鉴是纯展示界面, 默认取消一切交互(包括玩家背包的 Shift 点击)
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        ItemBrowserGUI gui = guiCache.get(player.getUniqueId());
        if (gui == null) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize())
            return;

        // 分类标签
        ItemBrowserCategory tab = gui.getCategoryAtSlot(slot);
        if (tab != null) {
            gui.switchCategory(tab);
            clickSound(player);
            return;
        }
        // 导航按钮
        if (slot == ItemBrowserGUI.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == ItemBrowserGUI.PREV_PAGE_SLOT) {
            if (gui.previousPage()) clickSound(player);
            return;
        }
        if (slot == ItemBrowserGUI.NEXT_PAGE_SLOT) {
            if (gui.nextPage()) clickSound(player);
            return;
        }
        // 展示区物品: 有权限时左键给 1 个, 右键给一组
        String id = gui.getItemIdAtSlot(slot);
        if (id != null && player.hasPermission(GIVE_PERMISSION)) {
            giveItem(player, id, event.getClick().isRightClick());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof ItemBrowserHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player))
            return;
        if (event.getInventory().getHolder(false) instanceof ItemBrowserHolder) {
            guiCache.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        guiCache.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 给予玩家指定 ID 的自定义物品, 背包已满时掉落在脚下.
     *
     * @param player   目标玩家
     * @param id       物品 ID
     * @param fullStack 是否给予一组(否则为 1 个)
     */
    private void giveItem(@NotNull Player player, @NotNull String id, boolean fullStack) {
        ItemStack built = plugin.getItemManager().buildInternal(Context.player(player).arg(ContextKeys.ID, id), id);
        if (built == null) {
            return;
        }
        int amount = fullStack ? built.getMaxStackSize() : 1;
        ItemStack toGive = built.clone();
        toGive.setAmount(amount);
        int leftOver = PlayerUtils.putItemsToInventory(player.getInventory(), toGive, amount);
        if (leftOver > 0) {
            ItemStack overflow = toGive.clone();
            overflow.setAmount(leftOver);
            player.getWorld().dropItem(player.getLocation(), overflow);
        }
        Sender sender = plugin.getSenderFactory().wrap(player);
        sender.sendMessage(TranslationManager.render(
                MessageConstants.COMMAND_ITEM_GET_SUCCESS
                        .arguments(Component.text(amount), Component.text(id))
                        .build()), true);
    }

    private void clickSound(@NotNull Player player) {
        player.playSound(player, "minecraft:ui.button.click", SoundCategory.MASTER, 0.3f, 1f);
    }
}
