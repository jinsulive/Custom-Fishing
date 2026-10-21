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

import net.momirealms.customfishing.api.mechanic.MechanicType;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * 物品图鉴 GUI 的物品分类.
 * 与 {@link MechanicType} 中的机制类型一一对应, ALL 为不过滤的聚合视图.
 */
public enum ItemBrowserCategory {

    /** 全部物品 */
    ALL("all", "全部", Material.BOOK),
    /** 钓竿 */
    ROD("rod", "钓竿", Material.FISHING_ROD),
    /** 鱼饵 */
    BAIT("bait", "鱼饵", Material.WHEAT),
    /** 鱼钩 */
    HOOK("hook", "鱼钩", Material.TRIPWIRE_HOOK),
    /** 装备 */
    EQUIPMENT("equipment", "装备", Material.IRON_CHESTPLATE),
    /** 工具 */
    UTIL("util", "工具", Material.COMPASS),
    /** 物品(自定义鱼等战利品物品) */
    ITEM("item", "物品", Material.TROPICAL_FISH);

    private final String id;
    private final String displayName;
    private final Material icon;

    ItemBrowserCategory(String id, String displayName, Material icon) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
    }

    /**
     * 分类标识, 与配置目录/机制类型名一致.
     *
     * @return 分类标识
     */
    public String id() {
        return id;
    }

    /**
     * 分类显示名(中文).
     *
     * @return 显示名
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 分类标签图标材质.
     *
     * @return 图标材质
     */
    public Material icon() {
        return icon;
    }

    /**
     * 根据机制类型名称解析分类.
     *
     * @param mechanicType 机制类型, 可为 null
     * @return 匹配的分类, 无法匹配时回退为 {@link #ITEM}
     */
    @NotNull
    public static ItemBrowserCategory fromMechanicType(@Nullable MechanicType mechanicType) {
        if (mechanicType == null) return ITEM;
        return switch (mechanicType.getType().toLowerCase(Locale.ROOT)) {
            case "rod" -> ROD;
            case "bait" -> BAIT;
            case "hook" -> HOOK;
            case "equipment" -> EQUIPMENT;
            case "util" -> UTIL;
            case "loot" -> ITEM;
            default -> ITEM;
        };
    }

    /**
     * 判断给定物品 ID 是否属于该分类.
     * ALL 分类恒为 true.
     *
     * @param itemId 物品 ID
     * @return 是否属于该分类
     */
    public boolean contains(@NotNull String itemId) {
        if (this == ALL) return true;
        return fromMechanicType(resolveMechanicType(itemId)) == this;
    }

    /**
     * 解析物品 ID 对应的机制类型.
     * 同一个 ID 可能被多个配置注册, 取第一个注册的类型.
     *
     * @param itemId 物品 ID
     * @return 机制类型, 未注册时返回 null
     */
    @Nullable
    private static MechanicType resolveMechanicType(@NotNull String itemId) {
        List<MechanicType> types = MechanicType.getTypeByID(itemId);
        if (types == null || types.isEmpty()) return null;
        return types.get(0);
    }
}
