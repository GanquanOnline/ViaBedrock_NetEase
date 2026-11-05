package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementPredictionTest {

    // 用户报告的场景：方块 (0,99,0) 顶面垂直上方一格，玩家脚底 (-0.30, 100.00, -0.30)。
    private static final BlockPosition ABOVE_SUPPORT = new BlockPosition(0, 100, 0);

    @Test
    void playerCenteredExactlyOnBlockEdgeIsNotObstructed() {
        // 玩家中心距方块负向外缘正好 0.30，水平交集宽度为 0，原版允许放置。
        assertFalse(PlacementPrediction.intersectsPlayer(ABOVE_SUPPORT, -0.30, 100.0, -0.30, false));
        // 正向外缘同样是 0.30：中心在 x/z = 1.30 处。
        assertFalse(PlacementPrediction.intersectsPlayer(ABOVE_SUPPORT, 1.30, 100.0, 1.30, false));
    }

    @Test
    void playerInsideBlockIsObstructed() {
        assertTrue(PlacementPrediction.intersectsPlayer(ABOVE_SUPPORT, 0.5, 100.0, 0.5, false));
        assertTrue(PlacementPrediction.intersectsPlayer(ABOVE_SUPPORT, 0.0, 100.0, 0.0, false));
    }

    @Test
    void slightlyCrossingTheEdgeIsObstructed() {
        // 越过负向外缘 0.00001，超过 1e-7 容差即构成真实重叠。
        assertTrue(PlacementPrediction.intersectsPlayer(ABOVE_SUPPORT, -0.29999, 100.0, 0.5, false));
        assertTrue(PlacementPrediction.intersectsPlayer(ABOVE_SUPPORT, 0.5, 100.0, -0.29999, false));
    }

    @Test
    void floatRoundingOfTheReportedPositionIsBelowTheTolerance() {
        // ViaBedrock 内部使用 float 观测坐标；0.30f 与 0.30 的差异远小于容差，不能翻转结论。
        final double asFloat = (double) (float) 0.30;
        assertFalse(PlacementPrediction.intersectsPlayer(ABOVE_SUPPORT, -asFloat, 100.0, -asFloat, false));
    }

    @Test
    void verticalExtentDecidesWhetherTheBlockOverlapsThePlayer() {
        // 方块紧贴脚底：交集宽度 0，不重叠。
        assertFalse(PlacementPrediction.intersectsPlayer(new BlockPosition(0, 99, 0), 0.5, 100.0, 0.5, false));
        // 方块位于玩家头部高度范围内。
        assertTrue(PlacementPrediction.intersectsPlayer(new BlockPosition(0, 101, 0), 0.5, 100.0, 0.5, false));
    }

    @Test
    void crouchingUsesTheShorterPlayerBox() {
        final BlockPosition head = new BlockPosition(0, 102, 0);
        // 脚底 100.3，站立顶 102.1 与方块 102..103 相交；潜行顶 101.8 不相交。
        assertTrue(PlacementPrediction.intersectsPlayer(head, 0.5, 100.3, 0.5, false));
        assertFalse(PlacementPrediction.intersectsPlayer(head, 0.5, 100.3, 0.5, true));
    }
}
