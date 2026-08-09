var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var IntInsnNode = Java.type('org.objectweb.asm.tree.IntInsnNode');
var LdcInsnNode = Java.type('org.objectweb.asm.tree.LdcInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var TypeInsnNode = Java.type('org.objectweb.asm.tree.TypeInsnNode');

function appendResourceLocation(instructions, namespace, path) {
    instructions.add(new TypeInsnNode(Opcodes.NEW, 'net/minecraft/resources/ResourceLocation'));
    instructions.add(new InsnNode(Opcodes.DUP));
    instructions.add(new LdcInsnNode(namespace));
    instructions.add(new LdcInsnNode(path));
    instructions.add(new MethodInsnNode(
        Opcodes.INVOKESPECIAL,
        'net/minecraft/resources/ResourceLocation',
        '<init>',
        '(Ljava/lang/String;Ljava/lang/String;)V',
        false
    ));
}

function appendItem(instructions, methodName, itemId, amount) {
    instructions.add(new LdcInsnNode(itemId));
    instructions.add(new IntInsnNode(Opcodes.SIPUSH, amount));
    instructions.add(new MethodInsnNode(
        Opcodes.INVOKEVIRTUAL,
        'com/gtolib/api/recipe/RecipeBuilder',
        methodName,
        '(Ljava/lang/String;I)Lcom/gtolib/api/recipe/RecipeBuilder;',
        false
    ));
}

function appendCircuit(instructions, circuit) {
    instructions.add(new IntInsnNode(Opcodes.SIPUSH, circuit));
    instructions.add(new MethodInsnNode(
        Opcodes.INVOKEVIRTUAL,
        'com/gtolib/api/recipe/RecipeBuilder',
        'circuitMeta',
        '(I)Lcom/gtolib/api/recipe/RecipeBuilder;',
        false
    ));
}

function appendPowerAndDuration(instructions, eut, duration) {
    instructions.add(new IntInsnNode(Opcodes.SIPUSH, eut));
    instructions.add(new InsnNode(Opcodes.I2L));
    instructions.add(new MethodInsnNode(
        Opcodes.INVOKEVIRTUAL,
        'com/gtolib/api/recipe/RecipeBuilder',
        'EUt',
        '(J)Lcom/gtolib/api/recipe/RecipeBuilder;',
        false
    ));
    instructions.add(new IntInsnNode(Opcodes.SIPUSH, duration));
    instructions.add(new MethodInsnNode(
        Opcodes.INVOKEVIRTUAL,
        'com/gtolib/api/recipe/RecipeBuilder',
        'duration',
        '(I)Lcom/gtolib/api/recipe/RecipeBuilder;',
        false
    ));
}

function buildRecipe(recipe) {
    var instructions = new InsnList();
    instructions.add(new FieldInsnNode(
        Opcodes.GETSTATIC,
        (recipe.typeOwner || 'com/gtocore/common/data/GTORecipeTypes'),
        recipe.typeField,
        'Lcom/gtolib/api/recipe/RecipeType;'
    ));
    appendResourceLocation(instructions, recipe.namespace, recipe.name);
    instructions.add(new MethodInsnNode(
        Opcodes.INVOKEVIRTUAL,
        'com/gtolib/api/recipe/RecipeType',
        'recipeBuilder',
        '(Lnet/minecraft/resources/ResourceLocation;)Lcom/gtolib/api/recipe/RecipeBuilder;',
        false
    ));
    (recipe.inputs || []).forEach(function(input) { appendItem(instructions, 'inputItems', input[0], input[1]); });
    (recipe.outputs || []).forEach(function(output) { appendItem(instructions, 'outputItems', output[0], output[1]); });
    if (recipe.circuit) appendCircuit(instructions, recipe.circuit);
    appendPowerAndDuration(instructions, recipe.eut, recipe.duration);
    instructions.add(new MethodInsnNode(
        Opcodes.INVOKEVIRTUAL,
        'com/gtolib/api/recipe/RecipeBuilder',
        'save',
        '()Lcom/gregtechceu/gtceu/api/recipe/GTRecipeDefinition;',
        false
    ));
    instructions.add(new InsnNode(Opcodes.POP));
    return instructions;
}
var RECIPES = [{"name": "kirin_rocket_t1", "typeField": "ASSEMBLER_RECIPES", "inputs": [["ad_astra:rocket_nose_cone", 1], ["ad_astra:rocket_fin", 4], ["ad_astra:steel_plate", 16]], "outputs": [["ad_astra:tier_1_rocket", 1]], "eut": 480, "duration": 600, "namespace": "gtocutcorners"}, {"name": "pgm_all_in_one", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:cooperite_dust", 6]], "outputs": [["gtceu:platinum_dust", 12], ["gtceu:palladium_dust", 12], ["gtceu:iridium_dust", 12], ["gtceu:rhodium_dust", 12], ["gtceu:ruthenium_dust", 12], ["gtceu:osmium_dust", 12]], "eut": 480, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_lanthanum", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:lanthanum_dust", 12]], "circuit": 1, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_cerium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:cerium_dust", 12]], "circuit": 2, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_neodymium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:neodymium_dust", 12]], "circuit": 3, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_samarium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:samarium_dust", 12]], "circuit": 4, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_europium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:europium_dust", 12]], "circuit": 5, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_praseodymium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:praseodymium_dust", 12]], "circuit": 6, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_gadolinium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:gadolinium_dust", 12]], "circuit": 7, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_terbium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:terbium_dust", 12]], "circuit": 8, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_dysprosium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:dysprosium_dust", 12]], "circuit": 9, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_holmium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:holmium_dust", 12]], "circuit": 10, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_erbium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:erbium_dust", 12]], "circuit": 11, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_thulium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:thulium_dust", 12]], "circuit": 12, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_ytterbium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:ytterbium_dust", 12]], "circuit": 13, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_scandium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:scandium_dust", 12]], "circuit": 14, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_lutetium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:lutetium_dust", 12]], "circuit": 15, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_yttrium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:yttrium_dust", 12]], "circuit": 16, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "re_promethium", "typeField": "CENTRIFUGE_RECIPES", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:promethium_dust", 12]], "circuit": 17, "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "easy_box_pgm", "typeOwner": "com/gtocutcorners/data/GTOCRecipeTypes", "typeField": "EASY_BOX", "inputs": [["gtceu:cooperite_dust", 1]], "outputs": [["gtceu:platinum_dust", 12], ["gtceu:palladium_dust", 12], ["gtceu:iridium_dust", 12], ["gtceu:rhodium_dust", 12], ["gtceu:ruthenium_dust", 12], ["gtceu:osmium_dust", 12]], "eut": 480, "duration": 1, "namespace": "gtocutcorners"}, {"name": "easy_box_rare_earth", "typeOwner": "com/gtocutcorners/data/GTOCRecipeTypes", "typeField": "EASY_BOX", "inputs": [["gtceu:monazite_dust", 1]], "outputs": [["gtceu:lanthanum_dust", 12], ["gtceu:cerium_dust", 12], ["gtceu:neodymium_dust", 12], ["gtceu:samarium_dust", 12], ["gtceu:europium_dust", 12], ["gtceu:praseodymium_dust", 12], ["gtceu:gadolinium_dust", 12], ["gtceu:terbium_dust", 12], ["gtceu:dysprosium_dust", 12], ["gtceu:holmium_dust", 12], ["gtceu:erbium_dust", 12], ["gtceu:thulium_dust", 12], ["gtceu:ytterbium_dust", 12], ["gtceu:scandium_dust", 12], ["gtceu:lutetium_dust", 12], ["gtceu:yttrium_dust", 12], ["gtceu:promethium_dust", 12]], "eut": 1920, "duration": 1, "namespace": "gtocutcorners"}, {"name": "easy_raws_2", "typeOwner": "com/gtocutcorners/data/GTOCRecipeTypes", "typeField": "EASY_BOX", "circuit": 2, "duration": 400, "eut": 32, "outputs": [["minecraft:raw_iron", 1], ["minecraft:raw_copper", 1], ["minecraft:raw_gold", 1], ["gtceu:raw_titanium", 1], ["gtceu:raw_tungsten", 1], ["gtceu:raw_aluminium", 1], ["gtceu:raw_beryllium", 1], ["gtceu:raw_cobalt", 1], ["gtceu:raw_lead", 1], ["gtceu:raw_lithium", 1], ["gtceu:raw_molybdenum", 1], ["gtceu:raw_neodymium", 1], ["gtceu:raw_nickel", 1], ["gtceu:raw_palladium", 1], ["gtceu:raw_platinum", 1], ["gtceu:raw_plutonium", 1], ["gtceu:raw_silver", 1], ["gtceu:raw_sulfur", 1], ["gtceu:raw_thorium", 1], ["gtceu:raw_tin", 1], ["gtceu:raw_naquadah", 1], ["gtceu:raw_chalcopyrite", 1], ["gtceu:raw_chromite", 1], ["gtceu:raw_cinnabar", 1], ["gtceu:raw_galena", 1], ["gtceu:raw_ilmenite", 1], ["gtceu:raw_bauxite", 1], ["gtceu:raw_magnetite", 1], ["gtceu:raw_molybdenite", 1], ["gtceu:raw_scheelite", 1], ["gtceu:raw_tantalite", 1], ["gtceu:raw_spessartine", 1], ["gtceu:raw_sphalerite", 1], ["gtceu:raw_stibnite", 1], ["gtceu:raw_tetrahedrite", 1], ["gtceu:raw_tungstate", 1], ["gtceu:raw_uraninite", 1], ["gtceu:raw_wulfenite", 1], ["gtceu:raw_yellow_limonite", 1], ["gtceu:raw_nether_quartz", 1], ["gtceu:raw_certus_quartz", 1], ["gtceu:raw_graphite", 1], ["gtceu:raw_bornite", 1], ["gtceu:raw_chalcocite", 1], ["gtceu:raw_realgar", 1], ["gtceu:raw_pentlandite", 1], ["gtceu:raw_spodumene", 1], ["gtceu:raw_lepidolite", 1], ["gtceu:raw_glauconite_sand", 1], ["gtceu:raw_malachite", 1], ["gtceu:raw_barite", 1], ["gtceu:raw_kyanite", 1], ["gtceu:raw_pyrochlore", 1], ["gtceu:raw_olivine", 1], ["gtceu:raw_apatite", 1], ["gtceu:raw_red_garnet", 1], ["gtceu:raw_yellow_garnet", 1], ["gtceu:raw_vanadium_magnetite", 1], ["gtceu:raw_monazite", 1], ["gtceu:raw_trona", 1], ["gtceu:raw_gypsum", 1], ["gtceu:raw_zeolite", 1], ["gtceu:raw_diatomite", 1], ["gtceu:raw_granitic_mineral_sand", 1], ["gtceu:raw_garnet_sand", 1], ["gtceu:raw_basaltic_mineral_sand", 1], ["gtceu:raw_quartzite", 1], ["gtceu:raw_bastnasite", 1], ["minecraft:ancient_debris", 1], ["minecraft:clay_ball", 1], ["minecraft:glowstone_dust", 1]], "namespace": "gtocutcorners"}, {"name": "easy_raws_3", "typeOwner": "com/gtocutcorners/data/GTOCRecipeTypes", "typeField": "EASY_BOX", "circuit": 3, "duration": 400, "eut": 32, "outputs": [["gtceu:raw_almandine", 1], ["gtceu:raw_asbestos", 1], ["gtceu:raw_hematite", 1], ["gtceu:raw_blue_topaz", 1], ["gtceu:raw_goethite", 1], ["gtceu:raw_calcite", 1], ["gtceu:raw_cassiterite", 1], ["gtceu:raw_cassiterite_sand", 1], ["gtceu:raw_coal", 1], ["gtceu:raw_cobaltite", 1], ["gtceu:raw_cooperite", 1], ["gtceu:raw_diamond", 1], ["gtceu:raw_emerald", 1], ["gtceu:raw_garnierite", 1], ["gtceu:raw_green_sapphire", 1], ["gtceu:raw_grossular", 1], ["gtceu:raw_lazurite", 1], ["gtceu:raw_magnesite", 1], ["gtceu:raw_powellite", 1], ["gtceu:raw_pyrite", 1], ["gtceu:raw_pyrolusite", 1], ["gtceu:raw_pyrope", 1], ["gtceu:raw_rock_salt", 1], ["gtceu:raw_ruby", 1], ["gtceu:raw_salt", 1], ["gtceu:raw_saltpeter", 1], ["gtceu:raw_sapphire", 1], ["gtceu:raw_sodalite", 1], ["gtceu:raw_topaz", 1], ["gtceu:raw_opal", 1], ["gtceu:raw_amethyst", 1], ["gtceu:raw_lapis", 1], ["gtceu:raw_tricalcium_phosphate", 1], ["gtceu:raw_pollucite", 1], ["gtceu:raw_bentonite", 1], ["gtceu:raw_fullers_earth", 1], ["gtceu:raw_pitchblende", 1], ["gtceu:raw_oilsands", 1], ["gtceu:raw_mica", 1], ["gtceu:raw_alunite", 1], ["gtceu:raw_talc", 1], ["gtceu:raw_soapstone", 1], ["gtceu:raw_redstone", 1], ["gtceu:raw_electrotine", 1]], "namespace": "gtocutcorners"}, {"name": "easy_raws_4", "typeOwner": "com/gtocutcorners/data/GTOCRecipeTypes", "typeField": "EASY_BOX", "circuit": 4, "duration": 400, "eut": 32, "outputs": [["gtceu:raw_enriched_naquadah", 1], ["gtceu:raw_indium", 1], ["gtceu:raw_tellurium", 1], ["gtceu:raw_titanium", 1], ["gtceu:raw_tungsten", 1], ["gtceu:raw_borax", 1], ["gtceu:raw_certus_quartz", 1], ["gtceu:raw_crystal_chip", 1], ["ae2:certus_quartz_crystal", 1]], "namespace": "gtocutcorners"}, {"name": "not_hard_box_from_furnaces", "typeField": "COMPRESSOR_RECIPES", "inputs": [["minecraft:furnace", 64]], "outputs": [["gtocore:not_hard_box", 1]], "eut": 30, "duration": 400, "namespace": "gtocutcorners"}];

function initializeCoreMod() {
    return {
        'gtocutcorners_after_gto_machines_clinit': {
            'target': {
                'type': 'METHOD',                'class': "com/gtocore/common/data/GTOMachines",                'methodName': "<clinit>",                'methodDesc': "()V"
            },
            'transformer': function(method) {
                var nodes = method.instructions.toArray();
                var injected = 0;
                for (var i = 0; i < nodes.length; i++) {
                    if (nodes[i].getOpcode() === Opcodes.RETURN) {
                        method.instructions.insertBefore(nodes[i], ASMAPI.buildMethodCall(
                            'com/gtocutcorners/multiblock/GTOCMultiblocks',
                            'registerFromGtoWindow',
                            '()V',
                            ASMAPI.MethodType.STATIC
                        ));
                        injected++;
                    }
                }
                if (injected === 0) {
                    ASMAPI.log('WARN', 'GTOCutCorners could not find RETURN in com/gtocore/common/data/GTOMachines.<clinit>()V');
                    return method;
                }
                ASMAPI.log('INFO', 'GTOCutCorners injected machine registration into com/gtocore/common/data/GTOMachines.<clinit>');
                return method;
            }
        },
        'gtocutcorners_recipe_types': {
            'target': {
                'type': 'METHOD',
                'class': 'com/gregtechceu/gtceu/common/data/GTRecipeTypes',
                'methodName': 'init',
                'methodDesc': '()V'
            },
            'transformer': function(method) {
                var nodes = method.instructions.toArray();
                if (nodes.length === 0) {
                    ASMAPI.log('WARN', 'GTOCutCorners: empty GTRecipeTypes.init(); recipe types missing');
                    return method;
                }
                method.instructions.insertBefore(nodes[0], ASMAPI.buildMethodCall(
                    'com/gtocutcorners/data/GTOCRecipeTypes',
                    'register',
                    '()V',
                    ASMAPI.MethodType.STATIC
                ));
                if (method.maxStack < 1) {
                    method.maxStack = 1;
                }
                ASMAPI.log('INFO', 'GTOCutCorners injected recipe-type registration into GTRecipeTypes.init()');
                return method;
            }
        },
        'gtocutcorners_inline_recipes_after_recipe_filter': {
            'target': {
                'type': 'METHOD',                'class': "com/gtocore/data/Data",                'methodName': "commonInit",                'methodDesc': "()V"
            },
            'transformer': function(method) {
                var nodes = method.instructions.toArray();
                var anchor = null;
                var injected = 0;
                for (var i = 0; i < nodes.length; i++) {
                    var node = nodes[i];
                    if (node.getOpcode() === Opcodes.INVOKESTATIC &&
                        node.owner === "com/gtocore/data/recipe/RecipeFilter" &&
                        node.name === "init" &&
                        node.desc === "()V") {
                        anchor = node;
                        injected++;
                    }
                }
                if (injected !== 1 || anchor === null) {
                    ASMAPI.log('WARN', 'GTOCutCorners expected one RecipeFilter.init() call, found ' + injected + '; recipes fall back to CommonSetup');
                    return method;
                }
                for (var r = 0; r < RECIPES.length; r++) {
                    method.instructions.insert(anchor, buildRecipe(RECIPES[r]));
                }
                ASMAPI.log('INFO', 'GTOCutCorners inlined ' + RECIPES.length + ' recipes after RecipeFilter.init()');
                return method;
            }
        }
    };
}
