package com.lostglade.mixin;

import com.lostglade.server.CartelSecretRecipeBookSystem;
import com.lostglade.server.CopperManGogglesSystem;
import com.lostglade.server.ItRecipeBookSystem;
import com.lostglade.server.MarkShieldRecipeSystem;
import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Collection;
import java.util.List;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerCartelRecipeBookMixin {
	@ModifyVariable(method = "awardRecipes", at = @At("HEAD"), argsOnly = true)
	private Collection<RecipeHolder<?>> lg2$filterCartelSecretRecipeAwards(Collection<RecipeHolder<?>> recipes) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		recipes = CartelSecretRecipeBookSystem.filterAwardedRecipes(player, recipes);
		recipes = CopperManGogglesSystem.filterAwardedRecipes(player, recipes);
		recipes = MarkShieldRecipeSystem.filterAwardedRecipes(player, recipes);
		recipes = ItRecipeBookSystem.filterAwardedRecipes(player, recipes);
		return NecromancerStockSystem.filterAwardedRecipes(player, recipes);
	}

	@ModifyVariable(method = "awardRecipesByKey", at = @At("HEAD"), argsOnly = true)
	private List<ResourceKey<Recipe<?>>> lg2$filterCartelSecretRecipeAwardKeys(List<ResourceKey<Recipe<?>>> recipeKeys) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		recipeKeys = CartelSecretRecipeBookSystem.filterAwardedRecipeKeys(player, recipeKeys);
		recipeKeys = CopperManGogglesSystem.filterAwardedRecipeKeys(player, recipeKeys);
		recipeKeys = MarkShieldRecipeSystem.filterAwardedRecipeKeys(player, recipeKeys);
		recipeKeys = ItRecipeBookSystem.filterAwardedRecipeKeys(player, recipeKeys);
		return NecromancerStockSystem.filterAwardedRecipeKeys(player, recipeKeys);
	}
}
