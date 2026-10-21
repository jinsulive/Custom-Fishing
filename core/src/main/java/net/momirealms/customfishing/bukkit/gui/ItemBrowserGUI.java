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

import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.context.Context;
import net.momirealms.customfishing.api.mechanic.context.ContextKeys;
import net.momirealms.customfishing.common.helper.AdventureHelper;
import net.momirealms.customfishing.common.item.Item;
import net.momirealms.sparrow.heart.SparrowHeart;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 物品图鉴 GUI.
 *
 * <p>布局(54 槽, 6 行):</p>
 * <pre>
 *   第 0 行:  分类标签(0-6) | 间隔(7) | 关闭(8)
 *   第 1-4 行: 物品展示区(9-44), 每页 36 个
 *   第 5 行:  上一页(45) | 装饰(46-48, 50-52) | 页码(49) | 下一页(53)
 * </pre>
 *
 * <p>分类标签点击切换分类, 展示区左键获取 1 个物品、右键获取一组(需权限),
 * 物品通过 {@code ItemManager#buildInternal} 实时构建, 保证与配置完全一致.</p>
 */
public class ItemBrowserGUI {

    /** 每页展示的物品数量 */
    public static final int ITEMS_PER_PAGE = 36;
    /** 展示区起始槽位 */
    public static final int CONTENT_START_SLOT = 9;
    /** 上一页按钮槽位 */
    public static final int PREV_PAGE_SLOT = 45;
    /** 页码指示槽位 */
    public static final int PAGE_INDICATOR_SLOT = 49;
    /** 下一页按钮槽位 */
    public static final int NEXT_PAGE_SLOT = 53;
    /** 关闭按钮槽位 */
    public static final int CLOSE_SLOT = 8;
    /** 分类标签起始槽位 */
    public static final int TAB_START_SLOT = 0;

    private final BukkitCustomFishingPlugin plugin;
    private final ItemBrowserHolder holder;
    private final Inventory inventory;
    private final Player viewer;
    private final boolean canGiveItems;

    private ItemBrowserCategory category = ItemBrowserCategory.ALL;
    private int page = 0;
    /** 当前分类下已排序的物品 ID 列表 */
    private List<String> cachedItemIds = List.of();

    public ItemBrowserGUI(@NotNull BukkitCustomFishingPlugin plugin, @NotNull Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.canGiveItems = viewer.hasPermission("customfishing.command.getitem");
        this.holder = new ItemBrowserHolder();
        this.inventory = Bukkit.createInventory(holder, 54, "CustomFishing Item Browser");
        this.holder.setInventory(this.inventory);
        refreshItemIds();
    }

    /**
     * 打开并渲染 GUI.
     */
    public void open() {
        render();
        viewer.openInventory(inventory);
        updateTitle();
    }

    /**
     * 重绘整个 GUI(标签行、展示区、导航行).
     */
    public void render() {
        refreshItemIds();
        renderTabs();
        renderContent();
        renderNavigation();
    }

    /**
     * 刷新当前分类下的物品 ID 列表(按字典序排列保证翻页稳定).
     */
    private void refreshItemIds() {
        TreeSet<String> ids = new TreeSet<>();
        for (String id : plugin.getItemManager().getItemIDs()) {
            if (category.contains(id)) {
                ids.add(id);
            }
        }
        this.cachedItemIds = List.copyOf(ids);
    }

    private void renderTabs() {
        ItemBrowserCategory[] values = ItemBrowserCategory.values();
        for (int i = 0; i < values.length; i++) {
            ItemBrowserCategory tab = values[i];
            ItemStack icon = new ItemStack(tab.icon());
            // 附魔光效与 hide flags 不含 Component 描述符, 仍可用 Bukkit 原生 API
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                decorateTab(meta, tab == category);
                icon.setItemMeta(meta);
            }
            // 名称与 Lore 必须走 Rtag 组件写入, 详见 named() 的注释
            List<String> loreJson = new ArrayList<>(2);
            loreJson.add(AdventureHelper.miniMessageToJson("<dark_gray>" + tab.id()));
            if (tab == category) {
                loreJson.add(AdventureHelper.miniMessageToJson("<yellow>当前分类"));
            }
            inventory.setItem(TAB_START_SLOT + i, named(icon,
                    tab == category ? "<gold><b>▶ " + tab.displayName() : "<gray>" + tab.displayName(),
                    loreJson));
        }
        // 间隔与关闭按钮
        inventory.setItem(7, decorative(Material.GRAY_STAINED_GLASS_PANE, " "));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "<red>关闭", List.of("<gray>点击关闭图鉴")));
    }

    private void renderContent() {
        // 清空展示区
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            inventory.setItem(CONTENT_START_SLOT + i, null);
        }

        int totalPages = totalPages();
        if (page >= totalPages) {
            page = Math.max(0, totalPages - 1);
        }

        if (cachedItemIds.isEmpty()) {
            inventory.setItem(CONTENT_START_SLOT + ITEMS_PER_PAGE / 2, button(
                    Material.BARRIER, "<yellow>空空如也", List.of("<gray>该分类下暂无物品")));
            return;
        }

        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && start + i < cachedItemIds.size(); i++) {
            String id = cachedItemIds.get(start + i);
            inventory.setItem(CONTENT_START_SLOT + i, buildDisplay(id));
        }
    }

    /**
     * 构建展示用的物品: 在原始物品基础上追加 ID / 分类 / 操作提示.
     *
     * @param id 物品 ID
     * @return 展示用物品
     */
    @NotNull
    private ItemStack buildDisplay(@NotNull String id) {
        try {
            ItemStack built = plugin.getItemManager().buildInternal(Context.player(viewer).arg(ContextKeys.ID, id), id);
            if (built == null) {
                // 理论上不会发生: ID 来自 getItemIDs()
                return failedPlaceholder(id, "物品不存在");
            }
            // 空栈没有可用的 NMS 句柄, 交给 Rtag 会抛
            // "DataComponentHolder.get(...) with null receiver", 直接跳过
            if (built.getType() == Material.AIR) {
                return failedPlaceholder(id, "材质为空");
            }
            Item<ItemStack> wrapped = plugin.getItemManager().wrap(built.clone());
            List<String> loreJson = new ArrayList<>(wrapped.lore().orElse(List.of()));
            loreJson.add(AdventureHelper.miniMessageToJson(""));
            loreJson.add(AdventureHelper.miniMessageToJson("<dark_gray>ID: <gray>" + id));
            loreJson.add(AdventureHelper.miniMessageToJson("<dark_gray>类别: <gray>" + category.displayName()));
            if (canGiveItems) {
                loreJson.add(AdventureHelper.miniMessageToJson(""));
                loreJson.add(AdventureHelper.miniMessageToJson("<aqua>左键 <white>获取 1 个"));
                loreJson.add(AdventureHelper.miniMessageToJson("<aqua>右键 <white>获取一组"));
            }
            wrapped.lore(loreJson);
            return wrapped.load();
        } catch (Exception | LinkageError e) {
            // 单个物品读不到组件不应该拖垮整页渲染: 翻页时整个 InventoryClickEvent 会因此失败
            plugin.getPluginLogger().warn("Failed to render item '" + id + "' in the item browser", e);
            return failedPlaceholder(id, "渲染失败");
        }
    }

    /**
     * 构建展示失败的占位物品.
     *
     * @param id     物品 ID
     * @param reason 失败原因
     * @return 占位物品
     */
    @NotNull
    private ItemStack failedPlaceholder(@NotNull String id, @NotNull String reason) {
        try {
            return button(Material.PAPER, "<white>" + id, List.of("<red>物品构建失败: <gray>" + reason));
        } catch (Exception | LinkageError e) {
            // 连占位物品都写不进去(组件通道整体不可用)时, 至少保证槽位有一个物品
            return new ItemStack(Material.PAPER);
        }
    }

    private void renderNavigation() {
        int totalPages = totalPages();
        // 装饰底纹
        for (int slot : new int[]{46, 47, 48, 50, 51, 52}) {
            inventory.setItem(slot, decorative(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        inventory.setItem(PREV_PAGE_SLOT, button(Material.ARROW, "<white>上一页",
                List.of(page > 0 ? "<gray>点击翻到第 " + page + " 页" : "<gray>已经是第一页了")));
        inventory.setItem(PAGE_INDICATOR_SLOT, button(Material.PAPER, "<gold>第 " + (page + 1) + " / " + totalPages + " 页",
                List.of("<gray>" + category.displayName() + " 共 " + cachedItemIds.size() + " 个物品")));
        inventory.setItem(NEXT_PAGE_SLOT, button(Material.ARROW, "<white>下一页",
                List.of(page < totalPages - 1 ? "<gray>点击翻到第 " + (page + 2) + " 页" : "<gray>已经是最后一页了")));
    }

    private void updateTitle() {
        // 标题走 SparrowHeart 的 NMS 通道, 版本不匹配时只影响标题文本, 不应让 GUI 整体不可用
        try {
            SparrowHeart.getInstance().updateInventoryTitle(viewer,
                    AdventureHelper.componentToJson(AdventureHelper.miniMessage(
                            "<gradient:#56ab2f:#a8e063><b>CustomFishing</b></gradient> <dark_gray>|</dark_gray> <white>物品图鉴</white> <gray>(" + category.displayName() + ")</gray>")));
        } catch (Exception | LinkageError e) {
            plugin.getPluginLogger().warn("Failed to update item browser title", e);
        }
    }

    /**
     * 总页数, 至少为 1.
     *
     * @return 总页数
     */
    public int totalPages() {
        return Math.max(1, (int) Math.ceil(cachedItemIds.size() / (double) ITEMS_PER_PAGE));
    }

    /**
     * 切换到指定分类并回到第一页.
     *
     * @param category 目标分类
     */
    public void switchCategory(@NotNull ItemBrowserCategory category) {
        if (this.category == category) return;
        this.category = category;
        this.page = 0;
        render();
        updateTitle();
    }

    /**
     * 切换到上一页, 已在第一页时返回 false.
     *
     * @return 是否成功切换
     */
    public boolean previousPage() {
        if (page <= 0) return false;
        page--;
        render();
        return true;
    }

    /**
     * 切换到下一页, 已在最后一页时返回 false.
     *
     * @return 是否成功切换
     */
    public boolean nextPage() {
        if (page >= totalPages() - 1) return false;
        page++;
        render();
        return true;
    }

    /**
     * 获取展示区槽位对应的物品 ID.
     *
     * @param slot 槽位
     * @return 物品 ID, 非展示区或为空时返回 null
     */
    @Nullable
    public String getItemIdAtSlot(int slot) {
        if (slot < CONTENT_START_SLOT || slot >= CONTENT_START_SLOT + ITEMS_PER_PAGE) return null;
        int start = page * ITEMS_PER_PAGE;
        int index = start + (slot - CONTENT_START_SLOT);
        if (index < 0 || index >= cachedItemIds.size()) return null;
        return cachedItemIds.get(index);
    }

    /**
     * 获取分类标签槽位对应的分类.
     *
     * @param slot 槽位
     * @return 分类, 非标签槽位时返回 null
     */
    @Nullable
    public ItemBrowserCategory getCategoryAtSlot(int slot) {
        ItemBrowserCategory[] values = ItemBrowserCategory.values();
        if (slot < TAB_START_SLOT || slot >= TAB_START_SLOT + values.length) return null;
        return values[slot - TAB_START_SLOT];
    }

    /**
     * 给分类标签加上选中光效并隐藏附魔提示(纯装饰, 失败不影响渲染).
     *
     * @param meta     物品 meta
     * @param selected 是否为当前选中的分类
     */
    @SuppressWarnings("deprecation")
    private static void decorateTab(@NotNull ItemMeta meta, boolean selected) {
        try {
            if (selected) {
                // 1.20.5+ 起 UNBREAKING 更名为 DURABILITY
                meta.addEnchant(Enchantment.DURABILITY, 1, true);
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } catch (Exception | LinkageError ignored) {
            // 服务端移除某个枚举/字段时忽略: 光效只是视觉提示
        }
    }

    /**
     * 构建装饰性物品(无交互).
     */
    @NotNull
    private ItemStack decorative(@NotNull Material material, @NotNull String name) {
        return button(material, name, List.of());
    }

    /**
     * 构建带名称与 Lore 的按钮物品.
     *
     * @param material 材质
     * @param name     MiniMessage 名称
     * @param lore     MiniMessage Lore 行
     * @return 按钮物品
     */
    @NotNull
    private ItemStack button(@NotNull Material material, @NotNull String name, @NotNull List<String> lore) {
        List<String> loreJson = new ArrayList<>(lore.size());
        for (String line : lore) {
            loreJson.add(AdventureHelper.miniMessageToJson(line));
        }
        return named(new ItemStack(material), name, loreJson);
    }

    /**
     * 给物品写入 MiniMessage 名称与已序列化为 JSON 的 Lore.
     *
     * <p><b>不要改用 {@code ItemMeta#displayName(Component)} / {@code ItemMeta#lore(List&lt;Component&gt;)}
     * 之类的 adventure 重载:</b> 构建产物由 shadowJar 把 {@code net.kyori} 重定位到
     * {@code net.momirealms.customfishing.libraries}, 方法描述符里的 Component 也被一起改写,
     * 而服务端的 {@code ItemMeta} 仍是原版签名, 运行时只会抛
     * {@code NoSuchMethodError: ItemMeta.displayName(net.momirealms...Component)}.
     * 因此统一走 {@link net.momirealms.customfishing.common.item.Item} 的 Rtag 组件写入通道,
     * 与插件配置化物品(display.name / display.lore)完全同源.</p>
     *
     * @param itemStack 目标物品
     * @param name      MiniMessage 名称
     * @param loreJson  已序列化为 JSON 的 Lore 行
     * @return 写入后的物品
     */
    @NotNull
    private ItemStack named(@NotNull ItemStack itemStack, @NotNull String name, @NotNull List<String> loreJson) {
        Item<ItemStack> wrapped = plugin.getItemManager().wrap(itemStack);
        wrapped.displayName(AdventureHelper.miniMessageToJson(name));
        wrapped.lore(loreJson);
        return wrapped.load();
    }
}
