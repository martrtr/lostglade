package com.lostglade.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds Lostglade's settings page to the optional Mod Menu integration. */
public final class LostgladeModMenuApi implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return LostgladeSettingsScreen::new;
	}
}
