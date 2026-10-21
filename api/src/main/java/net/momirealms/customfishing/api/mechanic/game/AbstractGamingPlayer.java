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

package net.momirealms.customfishing.api.mechanic.game;

import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.context.ContextKeys;
import net.momirealms.customfishing.api.mechanic.fishing.CustomFishingHook;
import net.momirealms.customfishing.common.plugin.scheduler.SchedulerTask;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.TimeUnit;

/**
 * Represents an abstract gaming player.
 * Provides the basic structure and functionalities for a gaming player.
 */
public abstract class AbstractGamingPlayer implements GamingPlayer, Runnable {
    protected long deadline;
    protected boolean success;
    protected SchedulerTask task;
    protected GameSetting settings;
    protected CustomFishingHook hook;
    protected boolean isTimeOut;
    private volatile boolean valid = true;
    /**
     * 开局宽限期（毫秒）。开启小游戏的那一次点击会额外产生一个挥手包（Swing），
     * 它晚于 REEL_IN 到达，若不做拦截会被误判为游戏内的第一次左键。
     * 这里用时间窗口只丢弃这一段残留输入，不影响玩家后续的正常点击。
     */
    private static final long STARTUP_GRACE_MS = 120L;
    private final long startTime = System.currentTimeMillis();
    protected Boolean forcedGameResult;

    @Override
    public void setGameResult(Boolean forcedGameResult) {
        this.forcedGameResult = forcedGameResult;
    }

    /**
     * Constructs an AbstractGamingPlayer instance.
     *
     * @param hook the custom fishing hook.
     * @param settings the game settings.
     */
    public AbstractGamingPlayer(CustomFishingHook hook, GameSetting settings) {
        this.hook = hook;
        this.settings = settings;
        this.deadline = (long) (System.currentTimeMillis() + settings.time() * 1000L);
        this.arrangeTask();
    }

    @Override
    public GameSetting settings() {
        return settings;
    }

    /**
     * Arranges the task for the gaming player.
     */
    public void arrangeTask() {
        this.task = BukkitCustomFishingPlugin.getInstance().getScheduler().asyncRepeating(this, 50, 50, TimeUnit.MILLISECONDS);
    }

    /**
     * Destroys the gaming player, canceling any ongoing tasks.
     */
    @Override
    public void destroy() {
        valid = false;
        if (task != null) task.cancel();
    }

    /**
     * Cancels the gaming player, it defaults to {@link AbstractGamingPlayer#destroy()}
     */
    @Override
    public void cancel() {
        destroy();
    }

    /**
     * Checks if the gaming player has successfully completed the game.
     *
     * @return true if successful, false otherwise.
     */
    @Override
    public boolean isSuccessful() {
        if (forcedGameResult != null) return forcedGameResult;
        return success;
    }

    /**
     * Handles internal right-click actions.
     */
    @ApiStatus.Internal
    public void internalRightClick() {
        // 已结束/已销毁的对局不再响应，避免"僵尸窗口"内继续走到判定逻辑
        if (!isValid()) return;
        handleRightClick();
    }

    /**
     * Handles right-click actions.
     */
    @Override
    public void handleRightClick() {
        endGame();
    }

    /**
     * Handles internal left-click actions.
     *
     * @return true if cancel the event, false otherwise.
     */
    @ApiStatus.Internal
    public boolean internalLeftClick() {
        // 已结束/已销毁的对局不再响应（原实现会让 dance 越界并抛异常）
        if (!isValid()) return false;
        // 只丢弃开局瞬间的残留挥手包，之后的所有左键都正常处理
        if (System.currentTimeMillis() - startTime < STARTUP_GRACE_MS) return false;
        return handleLeftClick();
    }

    /**
     * Handles left-click actions.
     *
     * @return true if cancel the event, false otherwise.
     */
    @Override
    public boolean handleLeftClick() {
        return false;
    }

    /**
     * Handles chat input during the game.
     *
     * @param message the chat message.
     * @return true if cancel the event, false otherwise.
     */
    @Override
    public boolean handleChat(String message) {
        return false;
    }

    /**
     * Handles the swap hand action during the game.
     */
    @Override
    public void handleSwapHand() {
    }

    /**
     * Handles the jump action during the game.
     *
     * @return true if cancel the event, false otherwise.
     */
    @Override
    public boolean handleJump() {
        return false;
    }

    /**
     * Handles the sneak action during the game.
     *
     * @return true if cancel the event, false otherwise.
     */
    @Override
    public boolean handleSneak() {
        return false;
    }

    /**
     * Gets the player associated with the gaming player.
     *
     * @return the player.
     */
    @Override
    public Player getPlayer() {
        return hook.getContext().holder();
    }

    /**
     * Runs the gaming player's task.
     */
    @Override
    public void run() {
        if (timeOutCheck()) {
            return;
        }
        tick();
    }

    /**
     * Checks if the game is valid.
     *
     * @return true if valid, false otherwise.
     */
    @Override
    public boolean isValid() {
        return valid;
    }

    /**
     * Defines the tick behavior for the gaming player.
     */
    protected abstract void tick();

    /**
     * Ends the game for the gaming player.
     */
    @Override
    public void endGame() {
        if (!isValid()) return;
        destroy();
        boolean success = isSuccessful();
        BukkitCustomFishingPlugin.getInstance().getScheduler().sync().run(() -> {
            if (success) {
                hook.handleSuccessfulFishing();
            } else {
                hook.handleFailedFishing();
            }
            hook.destroy();
        }, hook.getHookEntity().getLocation());
    }

    /**
     * Sets the game result.
     *
     * @param success true if the game was successful, false otherwise.
     */
    protected void setGameResult(boolean success) {
        this.success = success;
    }

    /**
     * Checks if the game has timed out.
     *
     * @return true if the game has timed out, false otherwise.
     */
    protected boolean timeOutCheck() {
        long delta = deadline - System.currentTimeMillis();
        if (delta <= 0) {
            isTimeOut = true;
            endGame();
            return true;
        }
        hook.getContext().arg(ContextKeys.TIME_LEFT, String.format("%.1f", (double) delta / 1000));
        return false;
    }
}
