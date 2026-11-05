package net.easecation.blockstates;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** 共用的纯数据读取代码，由使用方按各自工程结构接入。 */
public final class SharedBlockStates {
    public static final String RESOURCE = "/reference/shared-block-states.json.gz";
    private static final class Holder { private static final SharedBlockStates INSTANCE = load(); }
    public static SharedBlockStates instance() { return Holder.INSTANCE; }

    public record State(String name, List<Object> values, int profile) {
        public State { values = List.copyOf(values); }
    }
    public record JavaState(String name, Map<String, String> properties) {
        public JavaState { properties = Map.copyOf(properties); }
        public JavaState with(String property, String value) {
            Map<String, String> next = new HashMap<>(properties);
            next.put(property, value);
            return new JavaState(name, next);
        }
    }
    /** null 邻居表示未知，不是空气，也不能归入 dynamic fallback。 */
    @FunctionalInterface public interface Neighbors { State at(int dx, int dy, int dz); }
    public record Resolution(boolean known, int bedrockShape, JavaState javaState) {}
    private record Definition(List<String> keys, Map<List<Object>, State> states) {}
    private record Profile(int defaultShape, String connection, int[] connections, String domain, String cases, boolean needsMask) {}
    private record Direction(int x, int y, int z) {}
    private record Domain(List<Direction> directions, int radix, int fallback, Map<State, Integer> groups) {}
    private record Acceptor(Map<String, Integer> stable, Map<State, Integer> exceptions, Set<String> stateNames) {}
    private final Map<String, Definition> blocks = new HashMap<>();
    private final Map<State, JavaState> baseStates = new HashMap<>();
    private final Map<State, JavaState[]> connections = new HashMap<>();
    private final Map<State, Map<Integer, JavaState>> dynamic = new HashMap<>();
    private final Map<String, Domain> domains = new HashMap<>();
    private final Map<String, int[]> cases = new HashMap<>();
    private final Map<String, Acceptor> acceptors = new HashMap<>();
    private final Map<JavaState, double[][]> javaShapes = new HashMap<>();
    private final Map<JavaState, JavaState> clientStates = new HashMap<>();
    private final Set<String> bindings = new HashSet<>();
    private final Set<String> targetNames = new HashSet<>();
    private final List<Profile> profiles = new ArrayList<>();
    private final String generatorVersion, targetVersion, geometryVersion;
    private static final int[][] OFFSETS = {{0,0,-1}, {0,0,1}, {-1,0,0}, {1,0,0}};
    private static final int[] FACES = {8, 4, 32, 16};

    public String generatorVersion() { return generatorVersion; }
    public String targetVersion() { return targetVersion; }
    public String geometryVersion() { return geometryVersion; }
    public List<String> stateKeys(String name) { Definition d = blocks.get(name); return d == null ? null : d.keys; }
    public boolean handles(State state) { return state != null && bindings.contains(state.name); }
    public boolean handlesJavaName(String name) { return targetNames.contains(name); }
    public Collection<State> states() { return blocks.values().stream().flatMap(d -> d.states.values().stream()).toList(); }
    public JavaState base(State state) { return baseStates.get(state); }
    /** 有 BE 动态邻居，但尚未提供 JE 结果参数时，不能把基础状态当最终碰撞。 */
    public boolean hasUnresolvedJavaNeighbors(State state) {
        return state != null && profiles.get(state.profile).domain != null && !dynamic.containsKey(state);
    }
    public State state(String name, List<Object> tuple) {
        Definition d = blocks.get(name);
        return d == null ? null : d.states.get(tuple);
    }
    /** ViaBedrock 的 NBT 属性已经字符串化，按原始 typed 定义恢复，而非无条件猜 bool/int。 */
    public State stateFromStrings(String name, Map<String, String> properties) {
        Definition d = blocks.get(name);
        if (d == null || properties.size() != d.keys.size()) return null;
        List<Object> sample = d.states.keySet().iterator().next(), values = new ArrayList<>();
        for (int i = 0; i < d.keys.size(); i++) {
            String text = properties.get(d.keys.get(i));
            if (text == null) return null;
            Object expected = sample.get(i);
            if (expected instanceof Boolean) {
                if (!text.equals("0") && !text.equals("1")) return null;
                values.add(text.equals("1"));
            } else if (expected instanceof Integer) {
                try { values.add(Integer.valueOf(text)); } catch (NumberFormatException ex) { return null; }
            } else values.add(text);
        }
        return d.states.get(values);
    }
    /** 翻译链内部状态对应的固定 1.21.8 客户端状态；未知映射返回 null。 */
    public JavaState clientState(JavaState protocolState) { return clientStates.get(protocolState); }
    /** 返回客户端实际替代方块的局部 min/max 数组，调用者不得修改。 */
    public double[][] javaShape(JavaState protocolState) { return javaShapes.get(clientState(protocolState)); }

    public Resolution resolve(State state, Neighbors neighbors) {
        if (state == null) return new Resolution(false, -1, null);
        Profile p = profiles.get(state.profile);
        int shape = p.defaultShape, mask = 0;
        JavaState target = baseStates.get(state);
        if (p.connection != null) {
            // 墙的16项常相同，其碰撞/参数来自自身状态，不强迫读取无关邻居。
            boolean needsMask = connections.containsKey(state) || p.needsMask;
            if (needsMask) {
                Acceptor acceptor = acceptors.get(p.connection);
                for (int i = 0; i < 4; i++) {
                    int[] offset = OFFSETS[i];
                    State neighbor = neighbors.at(offset[0], offset[1], offset[2]);
                    if (neighbor == null) return new Resolution(false, -1, target);
                    Integer faces = acceptor.stateNames.contains(neighbor.name)
                            ? acceptor.exceptions.get(neighbor) : acceptor.stable.getOrDefault(neighbor.name, 0);
                    if (faces == null) return new Resolution(false, -1, target);
                    if ((faces & FACES[i]) != 0) mask |= 1 << i;
                }
                shape = p.connections[mask];
                JavaState[] targets = connections.get(state);
                if (targets != null) target = targets[mask];
            }
        } else if (p.domain != null) {
            Domain domain = domains.get(p.domain);
            int encoded = 0, multiplier = 1;
            for (Direction direction : domain.directions) {
                State neighbor = neighbors.at(direction.x, direction.y, direction.z);
                if (neighbor == null) return new Resolution(false, -1, target);
                encoded += domain.groups.getOrDefault(neighbor, domain.fallback) * multiplier;
                multiplier *= domain.radix;
            }
            int override = cases.get(p.cases)[encoded];
            if (override >= 0) shape = override;
            Map<Integer, JavaState> targets = dynamic.get(state);
            if (targets != null) target = targets.get(shape);
        }
        return new Resolution(true, shape, target);
    }

    public static InputStream openResource() throws IOException {
        InputStream raw = SharedBlockStates.class.getResourceAsStream(RESOURCE);
        if (raw == null) throw new IllegalStateException("Missing " + RESOURCE);
        try { return new GZIPInputStream(raw); }
        catch (IOException ex) { raw.close(); throw ex; }
    }
    public static JsonObject readRoot() {
        try (InputStream in = openResource()) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException ex) { throw new IllegalStateException("Cannot read shared blocks", ex); }
    }
    private static SharedBlockStates load() {
        return new SharedBlockStates(readRoot());
    }
    private SharedBlockStates(JsonObject root) {
        JsonObject format = root.getAsJsonObject("shared_format");
        check(format.get("version").getAsInt() == 1 && format.get("name").getAsString().equals("nyaac-viabedrock-block-states"), "Unsupported shared format");
        check(root.get("format_version").getAsInt() == 8, "Unsupported BE format");
        generatorVersion = root.getAsJsonObject("generation").get("generator_version").getAsString();
        JsonObject projection = root.getAsJsonObject("java_state_projection");
        targetVersion = projection.get("target_version").getAsString();
        JsonObject geometry = root.getAsJsonObject("java_collision_geometry");
        geometryVersion = geometry.get("version").getAsString();
        check(geometry.get("coordinate_space").getAsString().equals("block_local_min_max"), "Unsupported geometry coordinates");
        for (JsonElement element : root.getAsJsonArray("collision_profiles")) {
            JsonObject p = element.getAsJsonObject();
            int defaultShape = p.get("default_shape").getAsInt();
            int[] variants = ints(p.getAsJsonArray("connection_shapes"));
            String connection = nullable(p.get("connection_id"));
            check(variants.length == (connection == null ? 0 : 16), "Wrong connection table size");
            check(defaultShape >= 0 && defaultShape < root.getAsJsonArray("shapes").size(), "Invalid default shape");
            for (int shape : variants) check(shape >= 0 && shape < root.getAsJsonArray("shapes").size(), "Invalid connection shape");
            profiles.add(new Profile(defaultShape, connection, variants, nullable(p.get("dynamic_domain")),
                    nullable(p.get("dynamic_case_set")), Arrays.stream(variants).anyMatch(id -> id != defaultShape)));
        }
        for (var entry : root.getAsJsonObject("blocks").entrySet()) {
            JsonObject block = entry.getValue().getAsJsonObject();
            List<String> keys = strings(block.getAsJsonArray("state_keys"));
            Map<List<Object>, State> values = new HashMap<>();
            for (JsonElement element : block.getAsJsonArray("groups")) {
                JsonObject group = element.getAsJsonObject();
                int profile = group.get("profile").getAsInt();
                check(profile >= 0 && profile < profiles.size(), "Invalid profile");
                for (JsonElement row : group.getAsJsonArray("states")) {
                    List<Object> tuple = tuple(row.getAsJsonArray());
                    check(tuple.size() == keys.size() && values.putIfAbsent(tuple, new State(entry.getKey(), tuple, profile)) == null, "Duplicate/incomplete BE state");
                }
            }
            check(!values.isEmpty(), "Empty BE block");
            blocks.put(entry.getKey(), new Definition(keys, Map.copyOf(values)));
        }
        List<JavaState> targets = new ArrayList<>();
        for (JsonElement element : projection.getAsJsonArray("states")) targets.add(javaState(element.getAsJsonObject()));
        JsonObject registry = root.getAsJsonObject("java_registry");
        check(geometryVersion.equals("1.21.8") && geometryVersion.equals(registry.get("version").getAsString())
                && geometryVersion.equals(projection.get("client_version").getAsString())
                && registry.get("protocol_version").getAsInt() == 772, "Unsupported Java client catalog");
        List<JavaState> registered = new ArrayList<>();
        for (JsonElement element : registry.getAsJsonArray("blockstates")) {
            String text = element.getAsString();
            int bracket = text.indexOf('[');
            String name = bracket < 0 ? text : text.substring(0, bracket);
            Map<String, String> props = new HashMap<>();
            if (bracket >= 0) {
                check(text.endsWith("]"), "Malformed Java registry state");
                for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
                    int separator = pair.indexOf('=');
                    check(separator > 0 && separator < pair.length() - 1, "Malformed Java registry property");
                    check(props.putIfAbsent(pair.substring(0, separator), pair.substring(separator + 1)) == null,
                            "Duplicate Java registry property");
                }
            }
            registered.add(new JavaState(name, props));
        }
        JsonArray clientIds = projection.getAsJsonArray("client_state_ids");
        check(clientIds.size() == targets.size(), "Incomplete client state projection");
        for (int i = 0; i < targets.size(); i++) {
            if (clientIds.get(i).isJsonNull()) continue;
            int index = clientIds.get(i).getAsInt();
            check(index >= 0 && index < registered.size(), "Invalid client state reference");
            check(clientStates.putIfAbsent(targets.get(i), registered.get(index)) == null, "Duplicate protocol state");
        }
        for (var entry : projection.getAsJsonObject("base_states").entrySet()) {
            Definition definition = blocks.get(entry.getKey());
            check(definition != null, "Unknown base mapping block");
            JsonObject block = entry.getValue().getAsJsonObject();
            check(definition.keys.equals(strings(block.getAsJsonArray("state_keys"))), "Inconsistent base keys");
            for (JsonElement element : block.getAsJsonArray("groups")) {
                JsonObject group = element.getAsJsonObject();
                if (group.get("target_state").isJsonNull()) continue;
                JavaState target = targets.get(group.get("target_state").getAsInt());
                for (JsonElement row : group.getAsJsonArray("states")) {
                    State state = definition.states.get(tuple(row.getAsJsonArray()));
                    check(state != null && baseStates.putIfAbsent(state, target) == null, "Invalid base state");
                }
            }
        }
        for (var entry : root.getAsJsonObject("dynamic_case_sets").entrySet()) {
            JsonObject data = entry.getValue().getAsJsonObject();
            int[] table = new int[data.get("combination_count").getAsInt()];
            Arrays.fill(table, -1);
            for (var shapes : data.getAsJsonObject("shape_combinations").entrySet()) {
                int shape = Integer.parseInt(shapes.getKey());
                for (JsonElement index : shapes.getValue().getAsJsonArray()) {
                    int i = index.getAsInt();
                    check(i >= 0 && i < table.length && table[i] < 0, "Invalid dynamic index");
                    table[i] = shape;
                }
            }
            cases.put(entry.getKey(), table);
        }
        for (var entry : root.getAsJsonObject("dynamic_domains").entrySet()) {
            JsonObject data = entry.getValue().getAsJsonObject();
            List<Direction> directions = new ArrayList<>();
            for (JsonElement element : data.getAsJsonArray("directions")) {
                int[] offset = ints(element.getAsJsonObject().getAsJsonArray("offset"));
                directions.add(new Direction(offset[0], offset[1], offset[2]));
            }
            JsonArray refs = data.getAsJsonArray("value_groups");
            int fallback = -1;
            Map<State, Integer> groups = new HashMap<>();
            for (int i = 0; i < refs.size(); i++) {
                JsonObject ref = refs.get(i).getAsJsonObject();
                if (ref.has("fallback") && ref.get("fallback").getAsBoolean()) { check(fallback < 0, "Duplicate fallback"); fallback = i; }
                else for (JsonElement value : root.getAsJsonObject("dynamic_value_groups").getAsJsonArray(ref.get("group").getAsString())) {
                    JsonObject member = value.getAsJsonObject();
                    State state = typedState(member.get("name").getAsString(), member.getAsJsonObject("states"));
                    check(state != null && groups.putIfAbsent(state, i) == null, "Unknown/overlapping group member");
                }
            }
            check(fallback >= 0, "Missing fallback");
            domains.put(entry.getKey(), new Domain(List.copyOf(directions), refs.size(), fallback, Map.copyOf(groups)));
        }
        JsonObject connectionData = root.getAsJsonObject("connections");
        check(connectionData.get("format_version").getAsInt() == 5
                && connectionData.getAsJsonObject("provider_state_exceptions").size() == 0, "Unsupported connection format");
        JsonObject faces = connectionData.getAsJsonObject("face_bits");
        check(faces.get("north").getAsInt() == 4 && faces.get("south").getAsInt() == 8
                && faces.get("west").getAsInt() == 16 && faces.get("east").getAsInt() == 32, "Unexpected face convention");
        Map<String, Acceptor> expanded = new HashMap<>();
        for (var entry : connectionData.getAsJsonObject("connection_ids").entrySet()) {
            String id = entry.getValue().getAsJsonObject().get("acceptor_profile").getAsString();
            acceptors.put(entry.getKey(), expand(id, connectionData.getAsJsonObject("acceptor_profiles"), expanded, new HashSet<>()));
        }
        for (Profile p : profiles) {
            check(p.connection == null || acceptors.containsKey(p.connection), "Unknown connection profile");
            check((p.domain == null) == (p.cases == null), "Unpaired domain/case");
            if (p.domain != null) {
                Domain d = domains.get(p.domain);
                check(d != null && cases.containsKey(p.cases), "Missing domain/case");
                int count = 1;
                for (int i = 0; i < d.directions.size(); i++) count = Math.multiplyExact(count, d.radix);
                check(cases.get(p.cases).length == count, "Domain/case size mismatch");
            }
        }
        compileBindings(projection);
        List<double[][]> shapes = new ArrayList<>();
        for (JsonElement element : geometry.getAsJsonArray("shapes")) shapes.add(boxes(element.getAsJsonArray()));
        for (JsonElement element : geometry.getAsJsonArray("states")) {
            JsonObject state = element.getAsJsonObject();
            check(javaShapes.putIfAbsent(javaState(state), shapes.get(state.get("shape").getAsInt())) == null, "Duplicate Java geometry");
        }
    }

    private void compileBindings(JsonObject projection) {
        for (var entry : projection.getAsJsonObject("bindings").entrySet()) {
            String name = entry.getKey();
            Definition definition = blocks.get(name);
            check(definition != null, "Unknown binding block");
            bindings.add(name);
            JsonObject binding = entry.getValue().getAsJsonObject();
            String targetName = binding.get("target_name").getAsString();
            targetNames.add(targetName);
            Map<String, String> defaults = stringMap(binding.getAsJsonObject("target_defaults"));
            JsonObject rule = projection.getAsJsonObject("rules").getAsJsonObject(binding.get("rule").getAsString());
            String mode = rule.get("mode").getAsString();
            for (State state : definition.states.values()) {
                if (!baseStates.containsKey(state)) continue;
                Map<String, String> properties = new HashMap<>(defaults);
                if (rule.has("properties")) for (JsonElement element : rule.getAsJsonArray("properties")) {
                    JsonObject property = element.getAsJsonObject();
                    int index = definition.keys.indexOf(property.get("source").getAsString());
                    check(index >= 0, "Missing property source");
                    String mapped = null;
                    for (JsonElement value : property.getAsJsonArray("values")) {
                        JsonObject pair = value.getAsJsonObject();
                        if (typed(pair.get("source")).equals(state.values.get(index))) mapped = pair.get("target").getAsString();
                    }
                    check(mapped != null, "Unmapped typed property value");
                    properties.put(property.get("target").getAsString(), mapped);
                }
                if (mode.equals("connection_mask")) {
                    JavaState[] variants = new JavaState[16];
                    for (int mask = 0; mask < 16; mask++) {
                        Map<String, String> next = new HashMap<>(properties);
                        for (JsonElement element : rule.getAsJsonArray("sides")) {
                            JsonObject side = element.getAsJsonObject();
                            int bit = side.get("bit").getAsInt();
                            next.put(side.get("target").getAsString(), rule.get((mask & bit) == 0 ? "false_value" : "true_value").getAsString());
                        }
                        variants[mask] = new JavaState(targetName, next);
                    }
                    connections.put(state, variants);
                } else if (mode.equals("intrinsic_dynamic")) {
                    String id = projection.getAsJsonObject("dynamic_values_by_profile").get(Integer.toString(state.profile)).getAsString();
                    Map<Integer, JavaState> variants = new HashMap<>();
                    for (var value : projection.getAsJsonObject("dynamic_result_values").getAsJsonObject(id).entrySet()) {
                        Map<String, String> next = new HashMap<>(properties);
                        next.put(rule.get("dynamic_target").getAsString(), value.getValue().getAsString());
                        variants.put(Integer.parseInt(value.getKey()), new JavaState(targetName, next));
                    }
                    dynamic.put(state, Map.copyOf(variants));
                } else {
                    check(mode.equals("intrinsic"), "Unsupported projection mode");
                    check(baseStates.get(state).equals(new JavaState(targetName, properties)), "Intrinsic projection disagrees with base");
                }
            }
        }
    }
    private Acceptor expand(String id, JsonObject raw, Map<String, Acceptor> cache, Set<String> visiting) {
        if (cache.containsKey(id)) return cache.get(id);
        check(visiting.add(id) && raw.has(id), "Missing/cyclic acceptor " + id);
        JsonObject data = raw.getAsJsonObject(id);
        Map<String, Integer> stable = new HashMap<>();
        Map<State, Integer> exceptions = new HashMap<>();
        Set<String> stateNames = new HashSet<>();
        for (JsonElement parent : data.getAsJsonArray("parents")) {
            Acceptor inherited = expand(parent.getAsString(), raw, cache, visiting);
            merge(stable, inherited.stable); merge(exceptions, inherited.exceptions); stateNames.addAll(inherited.stateNames);
        }
        for (JsonElement element : data.getAsJsonArray("face_groups")) {
            JsonObject group = element.getAsJsonObject();
            int mask = group.get("face_mask").getAsInt();
            check((mask & ~63) == 0, "Invalid face mask");
            for (JsonElement name : group.getAsJsonArray("blocks")) merge(stable, Map.of(name.getAsString(), mask));
        }
        for (var entry : data.getAsJsonObject("state_exceptions").entrySet()) {
            stateNames.add(entry.getKey());
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                JsonObject group = element.getAsJsonObject();
                int mask = group.get("face_mask").getAsInt();
                check((mask & ~63) == 0, "Invalid exception face mask");
                for (JsonElement member : group.getAsJsonArray("states")) {
                    State state = typedState(entry.getKey(), member.getAsJsonObject());
                    check(state != null, "Unknown acceptor state");
                    merge(exceptions, Map.of(state, mask));
                }
            }
        }
        check(Collections.disjoint(stable.keySet(), stateNames), "Overlapping acceptors");
        Acceptor result = new Acceptor(Map.copyOf(stable), Map.copyOf(exceptions), Set.copyOf(stateNames));
        visiting.remove(id); cache.put(id, result); return result;
    }
    private State typedState(String name, JsonObject props) {
        Definition definition = blocks.get(name);
        if (definition == null || props.size() != definition.keys.size()) return null;
        List<Object> values = new ArrayList<>();
        for (String key : definition.keys) { if (!props.has(key)) return null; values.add(typed(props.get(key))); }
        return definition.states.get(values);
    }
    private static <K, V> void merge(Map<K, V> target, Map<K, V> values) {
        values.forEach((k, v) -> { V old = target.putIfAbsent(k, v); check(old == null || old.equals(v), "Conflicting inherited data"); });
    }
    private static JavaState javaState(JsonObject value) { return new JavaState(value.get("name").getAsString(), stringMap(value.getAsJsonObject("properties"))); }
    private static Map<String, String> stringMap(JsonObject values) {
        Map<String, String> result = new HashMap<>(); values.entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsString())); return result;
    }
    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>(); values.forEach(v -> result.add(v.getAsString())); return List.copyOf(result);
    }
    private static int[] ints(JsonArray values) { int[] result = new int[values.size()]; for (int i = 0; i < result.length; i++) result[i] = values.get(i).getAsInt(); return result; }
    private static String nullable(JsonElement value) { return value == null || value.isJsonNull() ? null : value.getAsString(); }
    private static List<Object> tuple(JsonArray values) { List<Object> result = new ArrayList<>(); values.forEach(v -> result.add(typed(v))); return List.copyOf(result); }
    private static Object typed(JsonElement value) {
        check(value != null && value.isJsonPrimitive(), "Expected typed primitive");
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) {
            int i = primitive.getAsInt(); check(primitive.getAsDouble() == i, "Expected integer state"); return i;
        }
        return primitive.getAsString();
    }
    private static double[][] boxes(JsonArray values) {
        double[][] result = new double[values.size()][6];
        for (int i = 0; i < result.length; i++) {
            JsonArray box = values.get(i).getAsJsonArray(); check(box.size() == 6, "Invalid AABB");
            for (int j = 0; j < 6; j++) { result[i][j] = box.get(j).getAsDouble(); check(Double.isFinite(result[i][j]), "Nonfinite AABB"); }
        }
        return result;
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
