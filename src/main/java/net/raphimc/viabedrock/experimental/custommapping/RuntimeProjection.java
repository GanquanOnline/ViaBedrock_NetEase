/* 由 SnapshotProfile 与基岩 START_GAME 运行时 ID 派生出的不可变连接运行时投影。 */
package net.raphimc.viabedrock.experimental.custommapping;

import java.util.List;

public record RuntimeProjection(
        List<ProjectedBlockState> blockStates,
        List<SnapshotProfile.BlockEntityTypeMapping> blockEntityTypes,
        List<SnapshotProfile.ItemMapping> items) {

    public RuntimeProjection {
        blockStates = List.copyOf(blockStates);
        blockEntityTypes = List.copyOf(blockEntityTypes);
        items = List.copyOf(items);
    }

    public CustomMappingAccess toAccess() {
        return toAccess(false, false, false);
    }

    public CustomMappingAccess toAccess(final boolean blockStatesAreFinalOutput, final boolean blockEntityTypesAreFinalOutput) {
        return toAccess(blockStatesAreFinalOutput, blockEntityTypesAreFinalOutput, false);
    }

    public CustomMappingAccess toAccess(final boolean blockStatesAreFinalOutput, final boolean blockEntityTypesAreFinalOutput, final boolean itemsAreFinalOutput) {
        final CustomMappingAccess.Builder builder = new CustomMappingAccess.Builder();
        for (SnapshotProfile.BlockEntityTypeMapping type : this.blockEntityTypes) {
            builder.addBlockEntityType(type.bedrockIdentifier(), type.javaIdentifier(), blockEntityTypesAreFinalOutput ? type.targetJavaRawId() : type.sourceJavaRawId(), type.rule());
        }
        for (ProjectedBlockState state : this.blockStates) {
            builder.addBlockState(state.runtimeId(), state.bedrockIdentifier(), blockStatesAreFinalOutput ? state.targetJavaRawId() : state.sourceJavaRawId(), state.fallbackSourceJavaRawId(), state.emit(), state.filter(), state.secondsToDestroy(), state.rule());
        }
        for (SnapshotProfile.ItemMapping item : this.items) {
            builder.addItem(item.bedrockIdentifier(), itemsAreFinalOutput ? item.targetJavaRawId() : item.sourceJavaRawId(), item.maxStackSize());
        }
        return builder.build();
    }

    public boolean isEmpty() {
        return this.blockStates.isEmpty() && this.blockEntityTypes.isEmpty() && this.items.isEmpty();
    }

    public record ProjectedBlockState(
            int runtimeId,
            String bedrockIdentifier,
            int sourceJavaRawId,
            int targetJavaRawId,
            int fallbackSourceJavaRawId,
            int emit,
            int filter,
            float secondsToDestroy,
            CustomMappingAccess.BlockEntityRule rule) {
    }
}
