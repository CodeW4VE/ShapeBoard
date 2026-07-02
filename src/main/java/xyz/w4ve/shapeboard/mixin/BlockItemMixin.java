package xyz.w4ve.shapeboard.mixin;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import xyz.w4ve.shapeboard.ShapeBoard;

/**
 * Fabric API no trae evento de "bloque colocado" en server, así que se
 * intercepta BlockItem#place cuando la colocación fue exitosa.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "place", at = @At("RETURN"))
	private void shapeboard$afterPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!cir.getReturnValue().consumesAction()) return;
		Level level = context.getLevel();
		if (level.isClientSide()) return;
		Player player = context.getPlayer();
		if (player == null) return;
		ShapeBoard.INSTANCE.count(level, context.getClickedPos(), player, false);
	}
}
