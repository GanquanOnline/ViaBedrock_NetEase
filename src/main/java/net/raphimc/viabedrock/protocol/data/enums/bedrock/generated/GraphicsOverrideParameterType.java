// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum GraphicsOverrideParameterType {

    /** 用于设置天空天顶颜色 */
    SkyZenithColor(0),
    /** 用于设置天空地平线颜色 */
    SkyHorizonColor(1),
    /** 用于设置地平线混合最小值 */
    HorizonBlendMin(2),
    /** 用于设置地平线混合最大值 */
    HorizonBlendMax(3),
    /** 用于设置地平线混合起点 */
    HorizonBlendStart(4),
    /** 用于设置地平线米氏混合起点 */
    HorizonBlendMieStart(5),
    /** 用于设置瑞利强度 */
    RayleighStrength(6),
    /** 用于设置太阳米氏强度 */
    SunMieStrength(7),
    /** 用于设置月亮米氏强度 */
    MoonMieStrength(8),
    /** 用于设置太阳眩光形状 */
    SunGlareShape(9),
    /** 用于设置水体叶绿素值 */
    Chlorophyll(10),
    /** 用于设置水体 CDOM 值 */
    CDOM(11),
    /** 用于设置水体悬浮沉积物值 */
    SuspendedSediment(12),
    /** 用于设置水波深度值 */
    WavesDepth(13),
    /** 用于设置水波频率值 */
    WavesFrequency(14),
    /** 用于设置水波频率缩放值 */
    WavesFrequencyScaling(15),
    /** 用于设置水波速度值 */
    WavesSpeed(16),
    /** 用于设置水波速度缩放值 */
    WavesSpeedScaling(17),
    /** 用于设置水波形状值 */
    WavesShape(18),
    /** 用于设置水波倍频值 */
    WavesOctaves(19),
    /** 用于设置水波混合值 */
    WavesMix(20),
    /** 用于设置水波牵引值 */
    WavesPull(21),
    /** 用于设置水波方向增量值 */
    WavesDirectionIncrement(22),
    /** 用于设置调色中间调对比度值 */
    MidtonesContrast(23),
    /** 用于设置调色高光对比度值 */
    HighlightsContrast(24),
    /** 用于设置调色阴影对比度值 */
    ShadowsContrast(25),
    /** 用于设置调色高光增益值 */
    HighlightsGain(26),
    /** 用于设置调色高光伽马值 */
    HighlightsGamma(27),
    /** 用于设置调色高光偏移值 */
    HighlightsOffset(28),
    /** 用于设置调色高光饱和度值 */
    HighlightsSaturation(29),
    /** 用于设置调色中间调增益值 */
    MidtonesGain(30),
    /** 用于设置调色中间调伽马值 */
    MidtonesGamma(31),
    /** 用于设置调色中间调偏移值 */
    MidtonesOffset(32),
    /** 用于设置调色中间调饱和度值 */
    MidtonesSaturation(33),
    /** 用于设置调色阴影增益值 */
    ShadowsGain(34),
    /** 用于设置调色阴影伽马值 */
    ShadowsGamma(35),
    /** 用于设置调色阴影偏移值 */
    ShadowsOffset(36),
    /** 用于设置调色阴影饱和度值 */
    ShadowsSaturation(37),
    /** 用于设置调色高光最小值 */
    HighlightsMin(38),
    /** 用于设置调色阴影最大值 */
    ShadowsMax(39),
    /** 用于设置调色色温值 */
    Temperature(40),
    /** 用于设置光照太阳颜色值 */
    SunColor(41),
    /** 用于设置光照太阳照度值 */
    SunIlluminance(42),
    /** 用于设置光照月亮颜色值 */
    MoonColor(43),
    /** 用于设置光照月亮照度值 */
    MoonIlluminance(44),
    /** 用于设置光照闪电颜色值 */
    FlashColor(45),
    /** 用于设置光照闪电照度值 */
    FlashIlluminance(46),
    /** 用于设置光照环境颜色值 */
    AmbientColor(47),
    /** 用于设置光照环境照度值 */
    AmbientIlluminance(48),
    /** 用于设置光照自发光去饱和度值 */
    EmissiveDesaturation(49),
    /** 用于设置光照天空强度值 */
    SkyIntensity(50),
    /** 用于设置光照轨道偏移角度值 */
    OrbitalOffsetDegrees(51),
    ;

    private static final Int2ObjectMap<GraphicsOverrideParameterType> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (GraphicsOverrideParameterType value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static GraphicsOverrideParameterType getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static GraphicsOverrideParameterType getByValue(final int value, final GraphicsOverrideParameterType fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static GraphicsOverrideParameterType getByName(final String name) {
        for (GraphicsOverrideParameterType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static GraphicsOverrideParameterType getByName(final String name, final GraphicsOverrideParameterType fallback) {
        for (GraphicsOverrideParameterType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    GraphicsOverrideParameterType(final GraphicsOverrideParameterType value) {
        this(value.value);
    }

    GraphicsOverrideParameterType(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
