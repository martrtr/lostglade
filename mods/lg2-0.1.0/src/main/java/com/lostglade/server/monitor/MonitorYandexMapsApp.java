package com.lostglade.server.monitor;

public final class MonitorYandexMapsApp implements MonitorApp {
	@Override
	public String id() {
		return "yandexmaps";
	}

	@Override
	public String title() {
		return "Я.КАРТЫ";
	}

	@Override
	public String iconResourcePath() {
		return "/monitor/yandex_maps_app.png";
	}

	@Override
	public int accentStartRgb() {
		return 0xFF3B30;
	}

	@Override
	public int accentEndRgb() {
		return 0xF7D64A;
	}

	@Override
	public int panelRgb() {
		return 0x101418;
	}

	@Override
	public String screenTitle() {
		return "Яндекс Карты";
	}

	@Override
	public String screenHint() {
		return "Ванильная карта мира сверху";
	}
}
