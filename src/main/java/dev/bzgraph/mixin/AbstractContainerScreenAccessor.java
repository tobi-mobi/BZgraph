package dev.bzgraph.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("hoveredSlot")
	Slot bzgraph$getHoveredSlot();

	@Accessor("leftPos")
	int bzgraph$getLeftPos();

	@Accessor("topPos")
	int bzgraph$getTopPos();

	@Accessor("imageHeight")
	int bzgraph$getImageHeight();
}
