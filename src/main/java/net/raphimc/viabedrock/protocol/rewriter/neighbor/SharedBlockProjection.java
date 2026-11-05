package net.raphimc.viabedrock.protocol.rewriter.neighbor;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.easecation.blockstates.SharedBlockStates;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 协议适配只绑定当前 JE 注册表；连接决策在共用核心，不反查 JE 邻居身份。 */
final class SharedBlockProjection {
    private final SharedBlockStates data = SharedBlockStates.instance();
    private final Map<SharedBlockStates.JavaState, Integer> ids = new HashMap<>();
    private final Map<Integer, SharedBlockStates.JavaState> states = new HashMap<>();
    private final Set<Integer> handled = new HashSet<>();

    SharedBlockProjection(Map<BlockState, Integer> registry) {
        // 显示名可能是 26.1-26.1.2；按注册的协议身份比较，不能与数据里的单版本字符串直接比较。
        final ProtocolVersion dataVersion = ProtocolVersion.getClosest(data.targetVersion());
        if (!ProtocolConstants.JAVA_VERSION.equals(dataVersion)) {
            throw new IllegalStateException("Shared block-state target " + data.targetVersion()
                    + " (protocol " + (dataVersion == null ? "unknown" : dataVersion.getVersion())
                    + ") does not match ViaBedrock target " + ProtocolConstants.JAVA_VERSION.getName()
                    + " (protocol " + ProtocolConstants.JAVA_VERSION.getVersion() + ")");
        }
        registry.forEach((state, id) -> {
            SharedBlockStates.JavaState key = new SharedBlockStates.JavaState(state.namespacedIdentifier(), state.properties());
            ids.put(key, id); states.put(id, key);
            if (data.handlesJavaName(state.namespacedIdentifier())) handled.add(id);
        });
    }
    boolean handles(int id) { return handled.contains(id); }
    int resolve(BlockNeighborView view, BlockPosition pos, int current) {
        SharedBlockStates.State source = view.getBedrockState(pos);
        if (!data.handles(source)) return current;
        SharedBlockStates.JavaState before = states.get(current);
        SharedBlockStates.JavaState base = data.base(source);
        // 自定义投影/替代材质拥有不同目标时保持其所有权，不能被原版绑定覆盖。
        if (before == null || base == null || !before.name().equals(base.name())) return current;
        SharedBlockStates.Resolution result = data.resolve(source,
                (dx, dy, dz) -> view.getBedrockState(new BlockPosition(pos.x() + dx, pos.y() + dy, pos.z() + dz)));
        if (!result.known() || result.javaState() == null) return current;
        SharedBlockStates.JavaState target = result.javaState();
        if (before.properties().containsKey("waterlogged")) target = target.with("waterlogged", before.properties().get("waterlogged"));
        Integer id = ids.get(target);
        if (id == null) throw new IllegalStateException("Unregistered shared parameter target: " + target);
        return id;
    }
}
