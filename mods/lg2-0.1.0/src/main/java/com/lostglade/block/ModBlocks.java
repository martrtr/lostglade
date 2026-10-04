package com.lostglade.block;

import com.lostglade.Lg2;
import com.lostglade.mixin.BlockEntityTypeAccessor;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

public final class ModBlocks {
	private static final Identifier BITCOIN_ORE_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "bitcoin_ore");
	private static final Identifier DEEPSLATE_BITCOIN_ORE_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "deepslate_bitcoin_ore");
	private static final Identifier BACKROOMS_BLOCK_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms_block");
	private static final Identifier BACKROOMS_LIGHTBLOCK_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms_lightblock");
	private static final Identifier BACKROOMS_DOOR_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms_door");
	private static final Identifier DICTATOR_IRON_DOOR_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "dictator_iron_door");
	private static final Identifier DICTATOR_IRON_TRAPDOOR_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "dictator_iron_trapdoor");
	private static final Identifier EXIT_SIGN_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "exit_sign");
	private static final Identifier EXIT_WALL_SIGN_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "exit_wall_sign");
	private static final Identifier STARTUP_VOID_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "startup_void");
	private static final Identifier SERVER_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "server");
	private static final Identifier SPEAKER_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "speaker");
	private static final Identifier MICROPHONE_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "microphone");
	private static final Identifier CAMERA_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "camera");
	private static final Identifier MILK_POCKET_PHANTOM_FLOOR_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "milk_pocket_phantom_floor");
	private static final Identifier RAINBOW_WOOL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rainbow_wool");
	private static final Identifier RAINBOW_CARPET_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rainbow_carpet");
	private static final Identifier RAINBOW_BED_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rainbow_bed");

	private static final ResourceKey<Block> BITCOIN_ORE_KEY = ResourceKey.create(Registries.BLOCK, BITCOIN_ORE_ID);
	private static final ResourceKey<Block> DEEPSLATE_BITCOIN_ORE_KEY = ResourceKey.create(Registries.BLOCK, DEEPSLATE_BITCOIN_ORE_ID);
	private static final ResourceKey<Block> BACKROOMS_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, BACKROOMS_BLOCK_ID);
	private static final ResourceKey<Block> BACKROOMS_LIGHTBLOCK_KEY = ResourceKey.create(Registries.BLOCK, BACKROOMS_LIGHTBLOCK_ID);
	private static final ResourceKey<Block> BACKROOMS_DOOR_KEY = ResourceKey.create(Registries.BLOCK, BACKROOMS_DOOR_ID);
	private static final ResourceKey<Block> DICTATOR_IRON_DOOR_KEY = ResourceKey.create(Registries.BLOCK, DICTATOR_IRON_DOOR_ID);
	private static final ResourceKey<Block> DICTATOR_IRON_TRAPDOOR_KEY = ResourceKey.create(Registries.BLOCK, DICTATOR_IRON_TRAPDOOR_ID);
	private static final ResourceKey<Block> EXIT_SIGN_KEY = ResourceKey.create(Registries.BLOCK, EXIT_SIGN_ID);
	private static final ResourceKey<Block> EXIT_WALL_SIGN_KEY = ResourceKey.create(Registries.BLOCK, EXIT_WALL_SIGN_ID);
	private static final ResourceKey<Block> STARTUP_VOID_KEY = ResourceKey.create(Registries.BLOCK, STARTUP_VOID_ID);
	private static final ResourceKey<Block> SERVER_KEY = ResourceKey.create(Registries.BLOCK, SERVER_ID);
	private static final ResourceKey<Block> SPEAKER_KEY = ResourceKey.create(Registries.BLOCK, SPEAKER_ID);
	private static final ResourceKey<Block> MICROPHONE_KEY = ResourceKey.create(Registries.BLOCK, MICROPHONE_ID);
	private static final ResourceKey<Block> CAMERA_KEY = ResourceKey.create(Registries.BLOCK, CAMERA_ID);
	private static final ResourceKey<Block> MILK_POCKET_PHANTOM_FLOOR_KEY = ResourceKey.create(Registries.BLOCK, MILK_POCKET_PHANTOM_FLOOR_ID);
	private static final ResourceKey<Block> RAINBOW_WOOL_KEY = ResourceKey.create(Registries.BLOCK, RAINBOW_WOOL_ID);
	private static final ResourceKey<Block> RAINBOW_CARPET_KEY = ResourceKey.create(Registries.BLOCK, RAINBOW_CARPET_ID);
	private static final ResourceKey<Block> RAINBOW_BED_KEY = ResourceKey.create(Registries.BLOCK, RAINBOW_BED_ID);
	private static final ResourceKey<Item> BITCOIN_ORE_ITEM_KEY = ResourceKey.create(Registries.ITEM, BITCOIN_ORE_ID);
	private static final ResourceKey<Item> DEEPSLATE_BITCOIN_ORE_ITEM_KEY = ResourceKey.create(Registries.ITEM, DEEPSLATE_BITCOIN_ORE_ID);
	private static final ResourceKey<Item> BACKROOMS_BLOCK_ITEM_KEY = ResourceKey.create(Registries.ITEM, BACKROOMS_BLOCK_ID);
	private static final ResourceKey<Item> BACKROOMS_LIGHTBLOCK_ITEM_KEY = ResourceKey.create(Registries.ITEM, BACKROOMS_LIGHTBLOCK_ID);
	private static final ResourceKey<Item> BACKROOMS_DOOR_ITEM_KEY = ResourceKey.create(Registries.ITEM, BACKROOMS_DOOR_ID);
	private static final ResourceKey<Item> EXIT_SIGN_ITEM_KEY = ResourceKey.create(Registries.ITEM, EXIT_SIGN_ID);
	private static final ResourceKey<Item> SERVER_ITEM_KEY = ResourceKey.create(Registries.ITEM, SERVER_ID);
	private static final ResourceKey<Item> SPEAKER_ITEM_KEY = ResourceKey.create(Registries.ITEM, SPEAKER_ID);
	private static final ResourceKey<Item> MICROPHONE_ITEM_KEY = ResourceKey.create(Registries.ITEM, MICROPHONE_ID);
	private static final ResourceKey<Item> RAINBOW_WOOL_ITEM_KEY = ResourceKey.create(Registries.ITEM, RAINBOW_WOOL_ID);
	private static final ResourceKey<Item> RAINBOW_CARPET_ITEM_KEY = ResourceKey.create(Registries.ITEM, RAINBOW_CARPET_ID);
	private static final ResourceKey<Item> RAINBOW_BED_ITEM_KEY = ResourceKey.create(Registries.ITEM, RAINBOW_BED_ID);

	private static final ResourceKey<CreativeModeTab> NATURAL_BLOCKS_TAB = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			Identifier.fromNamespaceAndPath("minecraft", "natural_blocks")
	);
	private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS_TAB = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			Identifier.fromNamespaceAndPath("minecraft", "functional_blocks")
	);

	public static final Block BITCOIN_ORE = Registry.register(
			BuiltInRegistries.BLOCK,
			BITCOIN_ORE_ID,
			new BitcoinOreBlock(
					createNormalOreProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/bitcoin_ore"),
					net.minecraft.world.level.block.Blocks.INFESTED_STONE
			)
	);

	public static final Block DEEPSLATE_BITCOIN_ORE = Registry.register(
			BuiltInRegistries.BLOCK,
			DEEPSLATE_BITCOIN_ORE_ID,
			new BitcoinOreBlock(
					createDeepslateOreProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/deepslate_bitcoin_ore"),
					net.minecraft.world.level.block.Blocks.INFESTED_DEEPSLATE
			)
	);

	/**
	 * Technical shell for the season-start scene. It deliberately has no
	 * BlockItem, so it cannot be obtained or selected as a furnace glitch drop.
	 */
	public static final Block STARTUP_VOID = Registry.register(
			BuiltInRegistries.BLOCK,
			STARTUP_VOID_ID,
			new BackroomsBlock(
					createStartupVoidProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/startup_void"),
					Blocks.BLACK_CONCRETE,
					Blocks.BLACK_CONCRETE
			)
	);

	public static final Block SERVER = Registry.register(
			BuiltInRegistries.BLOCK,
			SERVER_ID,
			new ServerBlock(createServerProperties())
	);

	public static final Block SPEAKER = Registry.register(
			BuiltInRegistries.BLOCK,
			SPEAKER_ID,
			new SpeakerBlock(createSpeakerProperties())
	);

	public static final Block MICROPHONE = Registry.register(
			BuiltInRegistries.BLOCK,
			MICROPHONE_ID,
			new MicrophoneBlock(createMicrophoneProperties())
	);

	public static final Block CAMERA = Registry.register(
			BuiltInRegistries.BLOCK,
			CAMERA_ID,
			new CameraBlock(createCameraProperties())
	);

	public static final Block MILK_POCKET_PHANTOM_FLOOR = Registry.register(
			BuiltInRegistries.BLOCK,
			MILK_POCKET_PHANTOM_FLOOR_ID,
			new MilkPocketPhantomFloorBlock(createMilkPocketPhantomFloorProperties())
	);

	public static final Block RAINBOW_WOOL = Registry.register(
			BuiltInRegistries.BLOCK,
			RAINBOW_WOOL_ID,
			new RainbowWoolBlock(
					createRainbowWoolProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/rainbow_wool")
			)
	);

	public static final Block RAINBOW_CARPET = Registry.register(
			BuiltInRegistries.BLOCK,
			RAINBOW_CARPET_ID,
			new RainbowCarpetBlock(
					createRainbowCarpetProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/rainbow_carpet")
			)
	);

	public static final Block RAINBOW_BED = Registry.register(
			BuiltInRegistries.BLOCK,
			RAINBOW_BED_ID,
			new RainbowBedBlock(createRainbowBedProperties())
	);

	public static final Block BACKROOMS_BLOCK = Registry.register(
			BuiltInRegistries.BLOCK,
			BACKROOMS_BLOCK_ID,
			new RandomizedBackroomsBlock(
					createBackroomsBlockProperties(),
					new Identifier[] {
							Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/backrooms_block"),
							Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/backrooms_block_1"),
							Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/backrooms_block_2"),
							Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/backrooms_block_3"),
							Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/backrooms_block_4")
					},
					Blocks.STRIPPED_BIRCH_LOG,
					Blocks.STRIPPED_BIRCH_LOG
			)
	);

	public static final Block BACKROOMS_LIGHTBLOCK = Registry.register(
			BuiltInRegistries.BLOCK,
			BACKROOMS_LIGHTBLOCK_ID,
			new BackroomsLightBlock(
					createBackroomsLightBlockProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/backrooms_lightblock"),
					Blocks.SEA_LANTERN,
					Blocks.SEA_LANTERN
			)
	);

	public static final Block BACKROOMS_DOOR = Registry.register(
			BuiltInRegistries.BLOCK,
			BACKROOMS_DOOR_ID,
			new BackroomsDoorBlock(
					net.minecraft.world.level.block.state.properties.BlockSetType.BIRCH,
					createBackroomsDoorProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/backrooms_door"),
					Blocks.WARPED_DOOR
			)
	);
	public static final Block DICTATOR_IRON_DOOR = Registry.register(
			BuiltInRegistries.BLOCK,
			DICTATOR_IRON_DOOR_ID,
			new DictatorIronDoorBlock(
					createDictatorIronDoorProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/dictator_iron_door"),
					Blocks.IRON_DOOR
			)
	);
	public static final Block DICTATOR_IRON_TRAPDOOR = Registry.register(
			BuiltInRegistries.BLOCK,
			DICTATOR_IRON_TRAPDOOR_ID,
			new DictatorIronTrapdoorBlock(
					createDictatorIronTrapdoorProperties(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "block/dictator_iron_trapdoor"),
					Blocks.IRON_TRAPDOOR
			)
	);
	public static final Block EXIT_SIGN = Registry.register(
			BuiltInRegistries.BLOCK,
			EXIT_SIGN_ID,
			new ExitSignBlock(createExitSignProperties())
	);

	public static final Block EXIT_WALL_SIGN = Registry.register(
			BuiltInRegistries.BLOCK,
			EXIT_WALL_SIGN_ID,
			new ExitWallSignBlock(createExitWallSignProperties())
	);

	public static final Item BITCOIN_ORE_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			BITCOIN_ORE_ID,
			new BitcoinOreBlockItem(
					BITCOIN_ORE,
					new Item.Properties().setId(BITCOIN_ORE_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.RAW_GOLD_BLOCK,
					true
			)
	);

	public static final Item DEEPSLATE_BITCOIN_ORE_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			DEEPSLATE_BITCOIN_ORE_ID,
			new BitcoinOreBlockItem(
					DEEPSLATE_BITCOIN_ORE,
					new Item.Properties().setId(DEEPSLATE_BITCOIN_ORE_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.RAW_GOLD_BLOCK,
					true
			)
	);

	public static final Item BACKROOMS_BLOCK_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			BACKROOMS_BLOCK_ID,
			new BackroomsBlockItem(
					BACKROOMS_BLOCK,
					new Item.Properties().setId(BACKROOMS_BLOCK_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.STRIPPED_BIRCH_LOG,
					true,
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms_block"),
					"Backrooms Block",
					"\u0411\u043b\u043e\u043a \u0437\u0430\u043a\u0443\u043b\u0438\u0441\u044c\u044f",
					"\u041a\u043e\u043d\u0441\u0442\u0440\u0443\u043a\u0446i\u044f \u0412\u0463\u0447\u043d\u0430\u0433\u043e \u041a\u043e\u0440\u0438\u0434\u043e\u0440\u0430",
					"\u0411\u043b\u043e\u043a \u0437\u0430\u043a\u0443\u043b\u0456\u0441\u0441\u044f",
					"\u30d0\u30c3\u30af\u30eb\u30fc\u30e0\u30ba\u30d6\u30ed\u30c3\u30af"
			)
	);

	public static final Item BACKROOMS_LIGHTBLOCK_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			BACKROOMS_LIGHTBLOCK_ID,
			new BackroomsBlockItem(
					BACKROOMS_LIGHTBLOCK,
					new Item.Properties().setId(BACKROOMS_LIGHTBLOCK_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.SEA_LANTERN,
					true,
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms_lightblock"),
					"Backrooms Light",
					"\u0421\u0432\u0435\u0442 \u0437\u0430\u043a\u0443\u043b\u0438\u0441\u044c\u044f",
					"\u0421\u0432\u0463\u0442\u044a \u0412\u0463\u0447\u043d\u0430\u0433\u043e \u041a\u043e\u0440\u0438\u0434\u043e\u0440\u0430",
					"\u0421\u0432\u0456\u0442\u043b\u043e \u0437\u0430\u043a\u0443\u043b\u0456\u0441\u0441\u044f",
					"\u30d0\u30c3\u30af\u30eb\u30fc\u30e0\u30ba\u30e9\u30a4\u30c8"
			)
	);

	public static final Item BACKROOMS_DOOR_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			BACKROOMS_DOOR_ID,
			new BackroomsBlockItem(
					BACKROOMS_DOOR,
					new Item.Properties().setId(BACKROOMS_DOOR_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.WARPED_DOOR,
					true,
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms_door"),
					"Backrooms Door",
					"\u0414\u0432\u0435\u0440\u044c \u0438\u0437 \u0437\u0430\u043a\u0443\u043b\u0438\u0441\u044c\u044f",
					"\u0414\u0432\u0463\u0440\u044c \u0412\u0463\u0447\u043d\u0430\u0433\u043e \u041a\u043e\u0440\u0438\u0434\u043e\u0440\u0430",
					"\u0414\u0432\u0435\u0440\u0456 \u0437\u0430\u043a\u0443\u043b\u0456\u0441\u0441\u044f",
					"\u30d0\u30c3\u30af\u30eb\u30fc\u30e0\u30ba\u306e\u6249"
			)
	);

	public static final Item EXIT_SIGN_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			EXIT_SIGN_ID,
			new ExitSignItem(
					EXIT_SIGN,
					EXIT_WALL_SIGN,
					new Item.Properties().setId(EXIT_SIGN_ITEM_KEY).useBlockDescriptionPrefix().stacksTo(16),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "exit_sign"),
					Items.PALE_OAK_SIGN,
					"Exit Sign",
					"\u0422\u0430\u0431\u043b\u0438\u0447\u043a\u0430 \u0432\u044b\u0445\u043e\u0434\u0430",
					"\u0418\u0441\u0445\u043e\u0434\u044a \u043e\u0442\u0441\u044e\u0434\u043e\u0432\u0430",
					"\u0422\u0430\u0431\u043b\u0438\u0447\u043a\u0430 \u0432\u0438\u0445\u043e\u0434\u0443",
					"EXIT\u6a19\u8b58"
			)
	);

	public static final Item SERVER_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			SERVER_ID,
			new ServerBlockItem(
					SERVER,
					new Item.Properties().setId(SERVER_ITEM_KEY).useBlockDescriptionPrefix().fireResistant(),
					Items.COMMAND_BLOCK,
					true
			)
	);

	public static final Item SPEAKER_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			SPEAKER_ID,
			new SpeakerBlockItem(
					(SpeakerBlock) SPEAKER,
					new Item.Properties().setId(SPEAKER_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.NOTE_BLOCK,
					true
			)
	);

	public static final Item MICROPHONE_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			MICROPHONE_ID,
			new MicrophoneBlockItem(
					(MicrophoneBlock) MICROPHONE,
					new Item.Properties().setId(MICROPHONE_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.STICK,
					true
			)
	);

	public static final Item RAINBOW_WOOL_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			RAINBOW_WOOL_ID,
			new BackroomsBlockItem(
					RAINBOW_WOOL,
					new Item.Properties().setId(RAINBOW_WOOL_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.WHITE_WOOL,
					true,
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rainbow_wool"),
					"Rainbow Wool",
					"Разноцветная шерсть",
					"Радужная шерсть",
					"Різнокольорова вовна",
					"レインボーウール"
			)
	);

	public static final Item RAINBOW_CARPET_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			RAINBOW_CARPET_ID,
			new BackroomsBlockItem(
					RAINBOW_CARPET,
					new Item.Properties().setId(RAINBOW_CARPET_ITEM_KEY).useBlockDescriptionPrefix(),
					Items.WHITE_CARPET,
					true,
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rainbow_carpet"),
					"Rainbow Carpet",
					"Разноцветный ковёр",
					"Радужный ковёр",
					"Різнокольоровий килим",
					"レインボーカーペット"
			)
	);

	public static final Item RAINBOW_BED_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			RAINBOW_BED_ID,
			new RainbowBedItem(
					RAINBOW_BED,
					new Item.Properties().setId(RAINBOW_BED_ITEM_KEY).useBlockDescriptionPrefix(),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rainbow_bed"),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rainbow_bed_display"),
					Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "brown_bed_display"),
					"Rainbow Bed",
					"Разноцветная кровать",
					"Радужная кровать",
					"Різнокольорове ліжко",
					"レインボーベッド"
			)
	);

	private ModBlocks() {
	}

	public static void register() {
		((BlockEntityTypeAccessor) (Object) BlockEntityType.SIGN).lg2$getValidBlocks().add(EXIT_SIGN);
		((BlockEntityTypeAccessor) (Object) BlockEntityType.SIGN).lg2$getValidBlocks().add(EXIT_WALL_SIGN);
		((BlockEntityTypeAccessor) (Object) BlockEntityType.BED).lg2$getValidBlocks().add(RAINBOW_BED);
		FlammableBlockRegistry.getDefaultInstance().add(RAINBOW_WOOL, 30, 60);
		FlammableBlockRegistry.getDefaultInstance().add(RAINBOW_CARPET, 60, 20);
		FlammableBlockRegistry.getDefaultInstance().add(RAINBOW_BED, 5, 20);

		ItemGroupEvents.modifyEntriesEvent(NATURAL_BLOCKS_TAB).register(entries -> {
			entries.prepend(RAINBOW_CARPET_ITEM);
			entries.prepend(RAINBOW_WOOL_ITEM);
			entries.prepend(BACKROOMS_LIGHTBLOCK_ITEM);
			entries.prepend(BACKROOMS_BLOCK_ITEM);
			entries.prepend(DEEPSLATE_BITCOIN_ORE_ITEM);
			entries.prepend(BITCOIN_ORE_ITEM);
		});
		ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS_TAB).register(entries -> {
			entries.prepend(RAINBOW_BED_ITEM);
			entries.prepend(MICROPHONE_ITEM);
			entries.prepend(SPEAKER_ITEM);
			entries.prepend(EXIT_SIGN_ITEM);
			entries.prepend(BACKROOMS_DOOR_ITEM);
			entries.prepend(SERVER_ITEM);
		});
	}

	private static BlockBehaviour.Properties createNormalOreProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.STONE)
				.strength(3.0f, 3.0f)
				.sound(SoundType.STONE)
				.noLootTable()
				.setId(BITCOIN_ORE_KEY);
	}

	private static BlockBehaviour.Properties createDeepslateOreProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.DEEPSLATE)
				.strength(4.5f, 3.0f)
				.sound(SoundType.DEEPSLATE)
				.noLootTable()
				.setId(DEEPSLATE_BITCOIN_ORE_KEY);
	}

	private static BlockBehaviour.Properties createServerProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.NONE)
				.strength(-1.0f, 3600000.0f)
				.noLootTable()
				.noOcclusion()
				.setId(SERVER_KEY);
	}

	private static BlockBehaviour.Properties createStartupVoidProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE)
				.mapColor(MapColor.COLOR_BLACK)
				.strength(-1.0F, 3600000.0F)
				.noLootTable()
				.setId(STARTUP_VOID_KEY);
	}

	private static BlockBehaviour.Properties createSpeakerProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.COLOR_BLACK)
				.strength(1.2F, 6.0F)
				.sound(SoundType.METAL)
				.noLootTable()
				.setId(SPEAKER_KEY);
	}

	private static BlockBehaviour.Properties createMicrophoneProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.METAL)
				.strength(1.2F, 3.0F)
				.sound(SoundType.STONE)
				.noLootTable()
				.noOcclusion()
				.setId(MICROPHONE_KEY);
	}

	private static BlockBehaviour.Properties createCameraProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.COLOR_BLACK)
				.strength(1.2F, 2.0F)
				.sound(SoundType.METAL)
				.noOcclusion()
				.setId(CAMERA_KEY);
	}

	private static BlockBehaviour.Properties createMilkPocketPhantomFloorProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.NONE)
				.strength(-1.0F, 3600000.0F)
				.sound(SoundType.EMPTY)
				.noLootTable()
				.noOcclusion()
				.setId(MILK_POCKET_PHANTOM_FLOOR_KEY);
	}

	private static BlockBehaviour.Properties createRainbowWoolProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.WHITE_WOOL)
				.sound(SoundType.WOOL)
				.setId(RAINBOW_WOOL_KEY);
	}

	private static BlockBehaviour.Properties createRainbowCarpetProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.WHITE_CARPET)
				.sound(SoundType.WOOL)
				.setId(RAINBOW_CARPET_KEY);
	}

	private static BlockBehaviour.Properties createRainbowBedProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.BROWN_BED)
				.setId(RAINBOW_BED_KEY);
	}

	public static BlockState getRandomizedBackroomsBlockState(long seed) {
		return ((RandomizedBackroomsBlock) BACKROOMS_BLOCK).getRandomizedState(seed);
	}

	private static BlockBehaviour.Properties createBackroomsBlockProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.SAND)
				.strength(30.0f, 1200.0f)
				.sound(SoundType.WOOD)
				.requiresCorrectToolForDrops()
				.noLootTable()
				.setId(BACKROOMS_BLOCK_KEY);
	}

	private static BlockBehaviour.Properties createBackroomsLightBlockProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.SAND)
				.strength(30.0f, 1200.0f)
				.sound(SoundType.GLASS)
				.lightLevel(state -> 15)
				.requiresCorrectToolForDrops()
				.noLootTable()
				.setId(BACKROOMS_LIGHTBLOCK_KEY);
	}

	private static BlockBehaviour.Properties createBackroomsDoorProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.WOOD)
				.strength(30.0f, 1200.0f)
				.sound(SoundType.WOOD)
				.noOcclusion()
				.requiresCorrectToolForDrops()
				.ignitedByLava()
				.setId(BACKROOMS_DOOR_KEY);
	}
	private static BlockBehaviour.Properties createDictatorIronDoorProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_DOOR)
				.setId(DICTATOR_IRON_DOOR_KEY);
	}
	private static BlockBehaviour.Properties createDictatorIronTrapdoorProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_TRAPDOOR)
				.setId(DICTATOR_IRON_TRAPDOOR_KEY);
	}
	private static BlockBehaviour.Properties createExitSignProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.PALE_OAK_SIGN)
				.strength(30.0f, 1200.0f)
				.sound(SoundType.EMPTY)
				.requiresCorrectToolForDrops()
				.noLootTable()
				.setId(EXIT_SIGN_KEY);
	}

	private static BlockBehaviour.Properties createExitWallSignProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.PALE_OAK_WALL_SIGN)
				.strength(30.0f, 1200.0f)
				.sound(SoundType.EMPTY)
				.requiresCorrectToolForDrops()
				.noLootTable()
				.setId(EXIT_WALL_SIGN_KEY);
	}
}
