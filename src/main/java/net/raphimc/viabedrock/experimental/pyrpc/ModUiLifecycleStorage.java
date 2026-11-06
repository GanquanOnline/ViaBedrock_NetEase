package net.raphimc.viabedrock.experimental.pyrpc;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;

import java.util.HashMap;
import java.util.Map;

/**
 * 按连接保存的 ModUI 生命周期状态。改成按连接存放，是因为原先的静态标志
 * 会跨会话泄漏，导致首次之后的每次重连都跳过了握手。
 */
public class ModUiLifecycleStorage extends StoredObject {

    /** ScreenInfoEvent + RequestHudNodeDataEvent 已发出（成功发送或回退触发）。 */
    boolean bootstrapSent;
    /** 已收到过服务端主动推送的 ModEventS2C —— 回退定时器据此让路。 */
    boolean s2cSeen;
    /** 回退定时器已排定，避免 minecraft:register 与 CONFIRM 两条路径重复排定。 */
    boolean fallbackScheduled;
    /** CONFIRM 已发出；注册包可能分两次到达，重复回同一个空负载没有必要。 */
    boolean confirmSent;
    /** 通道门槛丢弃日志计数，只打前几次。 */
    int droppedLogs;
    /** 事件名 → 已见次数。用于「首次打 INFO、前三次附负载预览」。 */
    final Map<String, Integer> eventCounts = new HashMap<>();

    public ModUiLifecycleStorage(final UserConnection user) {
        super(user);
    }

    /**
     * @return 该事件名在本次调用之前的出现次数（0 表示首次）
     */
    int countEvent(final String key) {
        final Integer previous = this.eventCounts.get(key);
        final int count = previous == null ? 0 : previous;
        if (count < 1024) {
            this.eventCounts.put(key, count + 1);
        }
        return count;
    }

}

/**
 * 一次性/重试状态：使 {@code ClientLoadAddonsFinishedFromGac} 不在
 * PLAY_STATUS PlayerSpawn 重载时重复发送，但在首次生成通知时 Netty 通道
 * 尚未激活的情况下仍可重试。
 */
class NetEaseAddonsFinishedStorage extends StoredObject {

    static final int MAX_ATTEMPTS = 8;

    volatile boolean sent;
    volatile boolean scheduled;
    volatile int attempts;

    NetEaseAddonsFinishedStorage(final UserConnection user) {
        super(user);
    }

}
