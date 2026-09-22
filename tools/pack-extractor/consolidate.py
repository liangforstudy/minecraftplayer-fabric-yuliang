import json, os, re
P='/Users/tan/Documents/Vibecodes/minecraftplayer-fabric-yuliang/survival-data'

NS={'farmersdelight':'farmersdelight','cobblemon':'cobblemon','bakery':'bakery','brewery':'brewery',
 'candlelight':'candlelight','farm_and_charm':'farm_and_charm','herbalbrews':'herbalbrews',
 'vinery':'vinery','ubesdelight':'ubesdelight','zymlabs':'zymlabs','civfabric':'civfabric',
 'supplementaries':'supplementaries'}

# ---------- 1. food values ----------
foods={}
for k,v in json.load(open('food_vanilla.json')).items():
    foods['minecraft:'+k]=dict(v, source='vanilla')
for mod,items in json.load(open('food_raw.json')).items():
    for k,v in items.items():
        if k in ('components',): continue
        foods[f'{NS.get(mod,mod)}:{k}']=dict(v, source=mod)
for mod,items in json.load(open('food_config.json')).items():
    for k,v in items.items():
        iid=f'{NS.get(mod,mod)}:{k}'
        foods.setdefault(iid,{}).update(dict(v, source=mod+' (server config)'))
# zymlabs server foods read from bytecode literals (nutrition, TOTAL saturation)
for k,(n,tot) in {'oyakodon':(12,19.2),'katsudon':(14,21.0),'raw_katsu':(3,1.8),'tonkatsu':(9,14.4)}.items():
    foods[f'zymlabs-rules:{k}']={'nutrition':n,'saturation':round(tot/(2*n),3),'source':'zymlabs-rules (server)'}
for iid,f in foods.items():
    n,s=f.get('nutrition'),f.get('saturation')
    if n is not None and s is not None: f['total_saturation']=round(min(n*s*2, n),3)
json.dump(dict(sorted(foods.items())), open(f'{P}/food_values.json','w'), indent=1)

# ---------- 2. forage ----------
forage=json.load(open('forage_features.json'))
json.dump(forage, open(f'{P}/wild_forage.json','w'), indent=1)

# ---------- 3. cobblemon ----------
mobs=json.load(open('cobblemon_food_mobs.json'))
json.dump(mobs, open(f'{P}/cobblemon_food_drops.json','w'), indent=1)

# ---------- 4. loot ----------
json.dump(json.load(open('loot_food.json')), open(f'{P}/structure_loot_food.json','w'), indent=1)

# ---------- 5. server rules ----------
rules={
 "spice_of_fabric":{
   "active":True,
   "hunger_formula":"hungerValue * 0.7^timesEaten",
   "saturation_formula":"saturationValue (unchanged)",
   "consume_duration_formula":"consumeDuration * 1.3^timesEaten",
   "history_length":11,
   "meaning":"timesEaten = how many of your last 11 eaten items were this same food. Rotate >=11 distinct foods to always eat at full value.",
   "decay_table":{str(i):round(0.7**i,4) for i in range(0,8)},
   "carrot_mode":False,
   "respawn_hunger":"max(8, hunger at death)",
   "reset_history_on_death":False},
 "realistic_plant_growth":{
   "active":True,
   "min_natural_light":15,
   "note":"Player-planted crops need natural skylight 15. Torch-lit indoor farms do NOT grow.",
   "only_player_planted":True,
   "bonemeal_limit":3,
   "bonemeal_respects_biome":True,
   "soil_rotation_days":5,
   "destroy_farmland_player":False,
   "grow_in_dark":["RED_MUSHROOM","BROWN_MUSHROOM","COCOA","CAVE_VINES","GLOW_LICHEN","KELP",
                   "CHORUS_FLOWER","WEEPING_VINES","TWISTING_VINES","NETHER_WART",
                   "WARPED_FUNGUS","CRIMSON_FUNGUS"],
   "biome_groups":{
     "Arid":["BADLANDS","DESERT","ERODED_BADLANDS","WOODED_BADLANDS"],
     "Savanna":["SAVANNA","SAVANNA_PLATEAU"],
     "Frozen":["SNOWY_BEACH","DEEP_FROZEN_OCEAN","FROZEN_OCEAN","FROZEN_RIVER","FROZEN_PEAKS","GROVE","ICE_SPIKES","JAGGED_PEAKS","SNOWY_PLAINS","SNOWY_SLOPES","SNOWY_TAIGA"],
     "Chilly":["COLD_OCEAN","DEEP_COLD_OCEAN","OLD_GROWTH_PINE_TAIGA","OLD_GROWTH_SPRUCE_TAIGA","TAIGA"],
     "Temperate":["OCEAN","DEEP_OCEAN","LUKEWARM_OCEAN","DEEP_LUKEWARM_OCEAN","WARM_OCEAN","BIRCH_FOREST","CHERRY_GROVE","DARK_FOREST","FLOWER_FOREST","FOREST","MEADOW","MUSHROOM_FIELDS","OLD_GROWTH_BIRCH_FOREST","PLAINS","SUNFLOWER_PLAINS"],
     "Tropical":["JUNGLE","BAMBOO_JUNGLE","SPARSE_JUNGLE","MANGROVE_SWAMP","SWAMP"],
     "RiverAndCoasts":["RIVER","BEACH","STONY_SHORE"],
     "Caves":["DEEP_DARK","DRIPSTONE_CAVES","LUSH_CAVES"],
     "Windy":["WINDSWEPT_FOREST","WINDSWEPT_GRAVELLY_HILLS","WINDSWEPT_HILLS","WINDSWEPT_SAVANNA"]},
   "crop_growth_rates":{
     "WHEAT":{"best":["PLAINS","SUNFLOWER_PLAINS","MEADOW"],"rate_best":100,"death_best":0.0,
              "groups":{"Savanna":[50,0.73],"Tropical":[50,1.49]},"elsewhere":"will not grow"},
     "CARROTS":{"best":["BIRCH_FOREST","DARK_FOREST","FLOWER_FOREST","FOREST","OLD_GROWTH_BIRCH_FOREST"],
                "rate_best":100,"death_best":0.73,"groups":{"Chilly":[50,2.29]},"elsewhere":"will not grow"},
     "POTATOES":{"best":["STONY_PEAKS","JAGGED_PEAKS","STONY_SHORE"],"rate_best":100,"death_best":0.73,
                 "groups":{"Windy":[100,0.73],"Arid":[100,0.73]},"elsewhere":"will not grow"},
     "BEETROOTS":{"best":["CHERRY_GROVE"],"rate_best":75,"death_best":3.45,
                  "groups":{"Chilly":[100,1.7],"Frozen":[75,3.45]},"elsewhere":"will not grow"},
     "SWEET_BERRY_BUSH":{"best":["WINDSWEPT_FOREST","WINDSWEPT_GRAVELLY_HILLS","WINDSWEPT_HILLS"],
                         "rate_best":25,"death_best":1.7,
                         "groups":{"Chilly":[100,0.67],"Frozen":[80,0.67],"Temperate":[60,1.7]}},
     "MELON_STEM":{"groups":{"Tropical":[100,0.73],"Temperate":[25,2.29]},"elsewhere":"will not grow"},
     "PUMPKIN_STEM":{"groups":{"Chilly":[100,0.73],"Temperate":[85,0.73],"Frozen":[70,5.97]},"elsewhere":"will not grow"},
     "BROWN_MUSHROOM":{"best":["DARK_FOREST","MUSHROOM_FIELDS"],"rate_best":100,"death_best":5,
                       "groups":{"Chilly":[100,5],"Tropical":[100,5],"Caves":[100,5]}},
     "SUGAR_CANE":{"groups":{"Tropical":[100,None],"Arid":[None,None],"RiverAndCoasts":[None,None]}},
     "COCOA":{"best":["JUNGLE","BAMBOO_JUNGLE","SPARSE_JUNGLE"],"rate_best":100,"death_best":2.53},
     "CACTUS":{"groups":{"Arid":[100,0.34]}}},
   "caveat":"GrowthRate is % chance a growth tick applies; NaturalDeathChance is % the plant dies on that tick. A crop with no matching biome group and an empty Default biome list does not grow at all."},
 "pacifist":{"active":True,"kill_cooldown_minutes":30,"engagement_grace_seconds":15,
   "hit_cooldown_seconds":20,"villages_suspend_pacifism_at_night":True,
   "pacifism_suspended_during":["enhancedcelestials:blood_moon","enhancedcelestials:super_blood_moon"],
   "note":"PvP-facing rule set; does not stop you harvesting animals or Pokemon."},
 "villager_trades":{
   "farmersdelight":{"enabled":True,"farmer_buys":["farmersdelight:cabbage","farmersdelight:onion","farmersdelight:rice","farmersdelight:tomato"],
                     "wandering_trader_sells":["farmersdelight:cabbage_seeds","farmersdelight:tomato_seeds"]},
   "vinery":{"level1":["farmer BUYS vinery:red_grape 15x1","farmer BUYS vinery:white_grape 15x1",
                       "farmer SELLS vinery:red_grape_seeds 2 emeralds","farmer SELLS vinery:white_grape_seeds 2 emeralds"],
             "level2":["SELLS vinery:wine_bottle","BUYS vinery:cherry x12","SELLS vinery:apple_mash"]},
   "ubesdelight":{"farmers_buy_crops":True,"wandering_trader_sells_items":True},
   "note":"Vanilla farmer/butcher/fisherman trades are unchanged and remain the fastest emerald->food conversion."},
 "world_generation":{
   "farmersdelight":{"generateFDChestLoot":True,"generateVillageCompostHeaps":True,"generateFDCropsOnVillageFarms":True},
   "ubesdelight":{"generateWildUbe":True,"generateWildGarlic":True,"generateWildGinger":True,
                  "generateWildLemongrass":True,"generateUDChestLoot":True},
   "chefsdelight_village_houses":{"plains_chef_house":2,"desert_chef_house":5,"savanna_chef_house":3,
                                  "snowy_chef_house":3,"taiga_chef_house":2}}
}
json.dump(rules, open(f'{P}/server_rules.json','w'), indent=1)
print('food_values',len(foods))
print('wild_forage',len(forage))
print('cobblemon',len(mobs))
