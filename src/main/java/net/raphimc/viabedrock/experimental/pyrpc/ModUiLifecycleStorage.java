package net.raphimc.viabedrock.experimental.pyrpc;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;

/**
 * 标记此连接的代理侧 ModUI 生命周期（ScreenInfoEvent + RequestHudNodeDataEvent）
 * 已启动。改成按连接存放，是因为原先的静态标志会跨会话泄漏，
 * 导致首次之后的每次重连都跳过了握手。
 */
public class ModUiLifecycleStorage extends StoredObject {

    public ModUiLifecycleStorage() {
        super(null);
    }

    public ModUiLifecycleStorage(final UserConnection user) {
        super(user);
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
