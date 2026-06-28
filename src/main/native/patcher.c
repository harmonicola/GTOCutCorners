#include <jni.h>
#include <jvmti.h>
#include <string.h>
#include <stdio.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#include <unistd.h>
#endif

static FILE* lf=NULL;
static void L(const char*m){printf("%s\n",m);if(lf){fprintf(lf,"%s\n",m);fflush(lf);}}

static JavaVM* g_jvm = NULL;
static volatile int g_watchdog_running = 0;
static int init_jvmti(JavaVM* vm);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *r) {
    g_jvm = vm;
    /* Log to stdout first, then try file */
    printf("[NATIVE] JNI_OnLoad: vm=%p\n", (void*)vm);
    lf = fopen("gtocutcorners_watchdog.log", "a");
    L("[NATIVE] ======== JNI_OnLoad: vm saved, g_jvm set ========");
    init_jvmti(vm);
    fclose(lf);
    return JNI_VERSION_1_8;
}

/* Forward declarations */
static int isExcludedType(const char* name);
static jobject collectAllViaTypeFields(JNIEnv *e);

/*
 * Helper: find the first available RecipeType static field in GTORecipeTypes.
 * Tries 20+ known field names across 0.5.0 / 0.5.1, then brute-force scans
 * all static fields via reflection if none match.
 */
static jobject findFirstRecipeType(JNIEnv *e) {
    jclass rtc = (*e)->FindClass(e, "com/gtocore/common/data/GTORecipeTypes");
    if (!rtc) {
        (*e)->ExceptionClear(e);
        L("[NATIVE] GTORecipeTypes class not found");
        return NULL;
    }

    const char* candidates[] = {
        "GAS_COMPRESSOR_RECIPES",
        "MACERATOR_RECIPES",
        "COMPRESSOR_RECIPES",
        "ALLOY_SMELTER_RECIPES",
        "ARC_FURNACE_RECIPES",
        "ASSEMBLER_RECIPES",
        "ELECTRIC_FURNACE_RECIPES",
        "EXTRACTOR_RECIPES",
        "MIXER_RECIPES",
        "CENTRIFUGE_RECIPES",
        "ELECTROLYZER_RECIPES",
        "CHEMICAL_REACTOR_RECIPES",
        "DISTILLERY_RECIPES",
        "FORMING_PRESS_RECIPES",
        "FORGE_HAMMER_RECIPES",
        "FURNACE_RECIPES",
        "PACKER_RECIPES",
        "LATHE_RECIPES",
        "CUTTER_RECIPES",
        "SLICER_RECIPES",
        "WIREMILL_RECIPES",
        "BENDER_RECIPES",
        NULL
    };

    for (int i = 0; candidates[i] != NULL; i++) {
        jfieldID fld = (*e)->GetStaticFieldID(e, rtc, candidates[i],
            "Lcom/gtolib/api/recipe/RecipeType;");
        if (fld == NULL) {
            (*e)->ExceptionClear(e);
            continue;
        }
        jobject rt = (*e)->GetStaticObjectField(e, rtc, fld);
        if (rt != NULL) {
            char buf[256];
            snprintf(buf, sizeof(buf), "[NATIVE] found RecipeType via field: %s", candidates[i]);
            L(buf);
            return rt;
        }
    }

    /* Fallback: scan all static fields via Java reflection */
    L("[NATIVE] named candidates exhausted, brute-force scanning static fields...");
    jclass clsC = (*e)->FindClass(e, "java/lang/Class");
    jmethodID getDeclaredFields = (*e)->GetMethodID(e, clsC, "getDeclaredFields",
        "()[Ljava/lang/reflect/Field;");
    jobjectArray fieldArr = (jobjectArray)(*e)->CallObjectMethod(e, rtc, getDeclaredFields);
    if (fieldArr == NULL) { L("[NATIVE] getDeclaredFields returned null"); return NULL; }

    jint len = (*e)->GetArrayLength(e, fieldArr);
    jclass fCls = (*e)->FindClass(e, "java/lang/reflect/Field");
    jmethodID getName = (*e)->GetMethodID(e, fCls, "getName", "()Ljava/lang/String;");
    jmethodID getType = (*e)->GetMethodID(e, fCls, "getType", "()Ljava/lang/Class;");
    jmethodID setAccessible = (*e)->GetMethodID(e, fCls, "setAccessible", "(Z)V");

    jclass recipeTypeCls = (*e)->FindClass(e, "com/gtolib/api/recipe/RecipeType");
    if (recipeTypeCls == NULL) {
        (*e)->ExceptionClear(e);
        L("[NATIVE] RecipeType class not found");
        return NULL;
    }
    jmethodID isAssignableFrom = (*e)->GetMethodID(e, clsC, "isAssignableFrom",
        "(Ljava/lang/Class;)Z");

    for (jint i = 0; i < len; i++) {
        jobject fObj = (*e)->GetObjectArrayElement(e, fieldArr, i);
        if (fObj == NULL) continue;

        jmethodID getModifiers = (*e)->GetMethodID(e, fCls, "getModifiers", "()I");
        jint mods = (*e)->CallIntMethod(e, fObj, getModifiers);
        if (!(mods & 0x0008)) continue; /* 0x0008 = STATIC */

        jobject typeObj = (*e)->CallObjectMethod(e, fObj, getType);
        if (typeObj == NULL) continue;
        jboolean assignable = (*e)->CallBooleanMethod(e, recipeTypeCls, isAssignableFrom,
            (*e)->GetObjectClass(e, typeObj));
        if (!assignable) continue;

        jstring nameStr = (jstring)(*e)->CallObjectMethod(e, fObj, getName);
        const char* fn = (*e)->GetStringUTFChars(e, nameStr, NULL);
        (*e)->CallVoidMethod(e, fObj, setAccessible, JNI_TRUE);
        jobject val = (*e)->CallObjectMethod(e, fObj,
            (*e)->GetMethodID(e, fCls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;"), NULL);

        if (val != NULL) {
            char buf[256];
            snprintf(buf, sizeof(buf), "[NATIVE] brute-force found RecipeType: %s", fn);
            L(buf);
            (*e)->ReleaseStringUTFChars(e, nameStr, fn);
            return val;
        }
        (*e)->ReleaseStringUTFChars(e, nameStr, fn);
    }

    L("[NATIVE] all strategies exhausted, no RecipeType found");
    return NULL;
}

/*
 * Collect ALL GTRecipeDefinition from GTRegistries.RECIPE_TYPES.
 * Iterates every recipe type, collects recipes.values() into one ArrayList.
 * This uses the stable public registry API -- no fragile RecipeBuilder tricks.
 */
static jobject collectAllRecipes(JNIEnv *e) {
    jclass grCls = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/registry/GTRegistries");
    if (!grCls) { (*e)->ExceptionClear(e); L("[NATIVE] GTRegistries class not found"); return NULL; }

    jfieldID rtf = (*e)->GetStaticFieldID(e, grCls, "RECIPE_TYPES",
        "Lcom/gregtechceu/gtceu/api/registry/GTRegistry;");
    if (!rtf) { (*e)->ExceptionClear(e); L("[NATIVE] RECIPE_TYPES field not found"); return NULL; }

    jobject registry = (*e)->GetStaticObjectField(e, grCls, rtf);
    if (!registry) { L("[NATIVE] RECIPE_TYPES registry is null"); return NULL; }

    jclass regC = (*e)->GetObjectClass(e, registry);
    jobject typesColl = (*e)->CallObjectMethod(e, registry,
        (*e)->GetMethodID(e, regC, "values", "()Ljava/util/Collection;"));
    if (!typesColl) { L("[NATIVE] registry.values() returned null"); return NULL; }

    jclass collC = (*e)->GetObjectClass(e, typesColl);
    jint typeCount = (*e)->CallIntMethod(e, typesColl,
        (*e)->GetMethodID(e, collC, "size", "()I"));
    char buf[256];
    snprintf(buf, sizeof(buf), "[NATIVE] found %d recipe types", typeCount);
    L(buf);

    /* Create result ArrayList */
    jclass alC = (*e)->FindClass(e, "java/util/ArrayList");
    jmethodID alInit = (*e)->GetMethodID(e, alC, "<init>", "()V");
    jobject allRecipes = (*e)->NewObject(e, alC, alInit);
    jmethodID alAddAll = (*e)->GetMethodID(e, alC, "addAll", "(Ljava/util/Collection;)Z");

    /* Access recipes from each RecipeType: try field, then methods */
    jclass rtC = (*e)->FindClass(e, "com/gtolib/api/recipe/RecipeType");
    jclass mapC = (*e)->FindClass(e, "java/util/Map");
    jclass collC2 = (*e)->FindClass(e, "java/util/Collection");

    /* Methods to try on RecipeType (in priority order) */
    const char* recipeMethods[] = {
        "getRecipes",        /* ()Ljava/util/Collection; */
        "getRecipeMap",      /* ()Ljava/util/Map; */
        "recipeMap",         /* ()Ljava/util/Map; (getter style) */
        NULL
    };
    const char* recipeMethodSigs[] = {
        "()Ljava/util/Collection;",
        "()Ljava/util/Map;",
        "()Ljava/util/Map;",
        NULL
    };

    jobjectArray typesArr = (jobjectArray)(*e)->CallObjectMethod(e, typesColl,
        (*e)->GetMethodID(e, collC, "toArray", "()[Ljava/lang/Object;"));
    jint len = (*e)->GetArrayLength(e, typesArr);

    jfieldID recipesFld = (*e)->GetFieldID(e, rtC, "recipes", "Ljava/util/Map;");
    if (!recipesFld) (*e)->ExceptionClear(e); /* field may not exist, that's ok */

    int totalDefs = 0, fieldHits = 0, methodHits = 0;
    for (jint i = 0; i < len; i++) {
        jobject rt = (*e)->GetObjectArrayElement(e, typesArr, i);
        if (!rt) continue;

        /* Check exclusion by type name (0.5.0 path) */
        jstring tnStr = (jstring)(*e)->CallObjectMethod(e, rt,
            (*e)->GetMethodID(e, rtC, "toString", "()Ljava/lang/String;"));
        if (tnStr) {
            const char* tn = (*e)->GetStringUTFChars(e, tnStr, NULL);
            int skip = isExcludedType(tn);
            if (skip) {
                char xb[256];
                snprintf(xb, sizeof(xb), "[NATIVE] EXCLUDED type: %s", tn);
                L(xb);
            }
            (*e)->ReleaseStringUTFChars(e, tnStr, tn);
            if (skip) continue;
        }
        jobject values = NULL;

        /* Strategy 1: access recipes field directly */
        if (recipesFld) {
            jobject map = (*e)->GetObjectField(e, rt, recipesFld);
            if (map) {
                values = (*e)->CallObjectMethod(e, map,
                    (*e)->GetMethodID(e, mapC, "values", "()Ljava/util/Collection;"));
                if (values) fieldHits++;
            }
        }

        /* Strategy 2: try getter methods on RecipeType */
        if (!values) {
            for (int m = 0; recipeMethods[m] != NULL; m++) {
                jmethodID mid = (*e)->GetMethodID(e, rtC, recipeMethods[m], recipeMethodSigs[m]);
                if (!mid) { (*e)->ExceptionClear(e); continue; }
                jobject result = (*e)->CallObjectMethod(e, rt, mid);
                if (!result) continue;
                /* If it returns a Map, get values(); if Collection, use directly */
                jclass rc = (*e)->GetObjectClass(e, result);
                if ((*e)->IsInstanceOf(e, result, mapC)) {
                    values = (*e)->CallObjectMethod(e, result,
                        (*e)->GetMethodID(e, mapC, "values", "()Ljava/util/Collection;"));
                } else if ((*e)->IsInstanceOf(e, result, collC2)) {
                    values = result;
                }
                if (values) { methodHits++; break; }
            }
        }

        if (values) {
            (*e)->CallBooleanMethod(e, allRecipes, alAddAll, values);
            jint sz = (*e)->CallIntMethod(e, values,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, values), "size", "()I"));
            totalDefs += sz;
        }
    }

    char buf2[256];
    snprintf(buf2, sizeof(buf2), "[NATIVE] collected %d recipes (field=%d method=%d types)",
        totalDefs, fieldHits, methodHits);
    L(buf2);
    return allRecipes;
}

/*
 * Fallback: use the old RecipeBuilder trick to get recipes.
 * Only called if collectAllRecipes fails or returns too few results.
 * Tries both "values" and other candidate method names.
 */
static jobject fallbackCollectRecipes(JNIEnv *e) {
    L("[NATIVE] trying fallback: RecipeBuilder approach...");
    jobject rt = findFirstRecipeType(e);
    if (rt == NULL) { L("[NATIVE] fallback: no RecipeType"); return NULL; }

    jclass rtC = (*e)->GetObjectClass(e, rt);
    jobject bld = (*e)->CallObjectMethod(e, rt,
        (*e)->GetMethodID(e, rtC, "recipeBuilder",
            "(Ljava/lang/String;)Lcom/gtolib/api/recipe/RecipeBuilder;"),
        (*e)->NewStringUTF(e, "x"));
    if (bld == NULL) { L("[NATIVE] fallback: recipeBuilder null"); return NULL; }

    jclass bC = (*e)->GetObjectClass(e, bld);
    jclass clsC = (*e)->FindClass(e, "java/lang/Class");

    /* Try multiple method names */
    const char* methodNames[] = {"values", "getValues", "getRecipes", "getAll", NULL};
    jobject vm = NULL;
    for (int i = 0; methodNames[i] != NULL; i++) {
        vm = (*e)->CallObjectMethod(e, bC,
            (*e)->GetMethodID(e, clsC, "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"),
            (*e)->NewStringUTF(e, methodNames[i]), NULL);
        if (vm != NULL) {
            char buf[128];
            snprintf(buf, sizeof(buf), "[NATIVE] fallback: found method '%s'", methodNames[i]);
            L(buf);
            break;
        }
        (*e)->ExceptionClear(e);
    }
    if (vm == NULL) { L("[NATIVE] fallback: no suitable method found"); return NULL; }

    jclass mCls = (*e)->FindClass(e, "java/lang/reflect/Method");
    jobject coll = (*e)->CallObjectMethod(e, vm,
        (*e)->GetMethodID(e, mCls, "invoke",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
        bld, NULL);
    if (coll == NULL) { L("[NATIVE] fallback: invoke returned null"); return NULL; }

    jclass colC = (*e)->GetObjectClass(e, coll);
    jint sz = (*e)->CallIntMethod(e, coll, (*e)->GetMethodID(e, colC, "size", "()I"));
    char buf[128];
    snprintf(buf, sizeof(buf), "[NATIVE] fallback: %d recipes", sz);
    L(buf);
    return coll;
}

/*
 * Check if a recipe type name should be excluded from 1-tick patching.
 * Generator fuels and space elevator recipes must keep original durations.
 */
static int isExcludedType(const char* rid) {
    if (!rid) return 0;
    /* DEBUG: log all boiler-related inputs */
    if (strstr(rid, "boiler") || strstr(rid, "BOILER")) {
        char dbg[256];
        snprintf(dbg, sizeof(dbg), "[NATIVE] isExcludedType INPUT: '%s'", rid);
        L(dbg);
    }
    /* Extract recipe type from ID: "namespace:recipe_type/recipe_name"
     * e.g. "gtceu:combustion_generator/high_octane_diesel" -> "combustion_generator"
     * Also handles colon-less field names like "LARGE_BOILER_RECIPES" via case-insensitive match */
    const char* colon = strchr(rid, ':');
    char typeName[128];
    if (colon) {
        const char* typeStart = colon + 1;
        const char* slash = strchr(typeStart, '/');
        if (slash) {
            size_t len = slash - typeStart;
            if (len >= sizeof(typeName)) len = sizeof(typeName) - 1;
            memcpy(typeName, typeStart, len);
            typeName[len] = '\0';
        } else {
            strncpy(typeName, typeStart, sizeof(typeName) - 1);
            typeName[sizeof(typeName) - 1] = '\0';
        }
    } else {
        /* No colon: lowercase the entire string (handles field names) */
        int j;
        for(j=0; rid[j] && j < (int)(sizeof(typeName)-1); j++)
            typeName[j] = (rid[j]>='A'&&rid[j]<='Z') ? rid[j]+32 : rid[j];
        typeName[j] = '\0';
    }

    const char* genTypes[] = {
        "combustion_generator", "gas_turbine", "steam_turbine",
        "plasma_generator", "thermal_generator", "semi_fluid_generator",
        "supercritical_steam_turbine", "rocket_engine", "large_boiler",
        "steam_boiler",
        "naquadah_reactor", "hyper_reactor", "advanced_hyper_reactor",
        "large_naquadah_reactor", "mana_garden", "annihilate_generator",
        "fuel_cell_energy_absorption",
        "fuel_cell_energy_transfer", "fuel_cell_energy_release",
        "space_elevator",
        NULL
    };
    for (int i = 0; genTypes[i] != NULL; i++) {
        if (strcmp(typeName, genTypes[i]) == 0) return 1;
    }
    /* Fallback for colon-less input: substring match (handles field names like "LARGE_BOILER_RECIPES") */
    if (!colon) {
        for (int i = 0; genTypes[i] != NULL; i++) {
            if (strstr(typeName, genTypes[i])) return 1;
        }
    }
    return 0;
}
/* Strategy C: scan all static RecipeType fields in GTORecipeTypes.
 * For each type, probe field & methods to extract recipes directly.
 * Bypasses GTRegistries and RecipeBuilder entirely. */
static jobject collectAllViaTypeFields(JNIEnv *e) {
    L("[NATIVE] trying Strategy C: scan GTORecipeTypes static fields...");
    jclass rtc = (*e)->FindClass(e, "com/gtocore/common/data/GTORecipeTypes");
    if (!rtc) { (*e)->ExceptionClear(e); L("[NATIVE] GTORecipeTypes not found"); return NULL; }

    jclass clsC = (*e)->FindClass(e, "java/lang/Class");
    jobjectArray fieldArr = (jobjectArray)(*e)->CallObjectMethod(e, rtc,
        (*e)->GetMethodID(e, clsC, "getDeclaredFields", "()[Ljava/lang/reflect/Field;"));
    if (!fieldArr) { L("[NATIVE] no fields"); return NULL; }

    jint fLen = (*e)->GetArrayLength(e, fieldArr);
    jclass fCls = (*e)->FindClass(e, "java/lang/reflect/Field");
    jclass rtCls = (*e)->FindClass(e, "com/gtolib/api/recipe/RecipeType");
    jclass collCls = (*e)->FindClass(e, "java/util/Collection");
    jclass mapCls = (*e)->FindClass(e, "java/util/Map");

    jclass alC = (*e)->FindClass(e, "java/util/ArrayList");
    jobject allRecipes = (*e)->NewObject(e, alC,
        (*e)->GetMethodID(e, alC, "<init>", "()V"));
    jmethodID alAddAll = (*e)->GetMethodID(e, alC, "addAll", "(Ljava/util/Collection;)Z");

    const char* rtMethods[] = {"getRecipes","getRecipeMap","recipeMap",NULL};
    const char* rtSigs[] = {"()Ljava/util/Collection;","()Ljava/util/Map;","()Ljava/util/Map;",NULL};

    int totalDefs = 0, typeHits = 0;
    for (jint i = 0; i < fLen; i++) {
        jobject fObj = (*e)->GetObjectArrayElement(e, fieldArr, i);
        if (!fObj) continue;
        jint mods = (*e)->CallIntMethod(e, fObj,
            (*e)->GetMethodID(e, fCls, "getModifiers", "()I"));
        if (!(mods & 0x0008)) continue;
        jobject typeObj = (*e)->CallObjectMethod(e, fObj,
            (*e)->GetMethodID(e, fCls, "getType", "()Ljava/lang/Class;"));
        if (!typeObj) continue;
        jboolean assignable = (*e)->CallBooleanMethod(e, rtCls,
            (*e)->GetMethodID(e, clsC, "isAssignableFrom", "(Ljava/lang/Class;)Z"), typeObj);
        if (!assignable) continue;
        (*e)->CallVoidMethod(e, fObj,
            (*e)->GetMethodID(e, fCls, "setAccessible", "(Z)V"), JNI_TRUE);
        jobject rt = (*e)->CallObjectMethod(e, fObj,
            (*e)->GetMethodID(e, fCls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;"), NULL);
        if (!rt) continue;

        /* Check exclusion list */
        jstring fnStr = (jstring)(*e)->CallObjectMethod(e, fObj,
            (*e)->GetMethodID(e, fCls, "getName", "()Ljava/lang/String;"));
        const char* fn = (*e)->GetStringUTFChars(e, fnStr, NULL);
        if (isExcludedType(fn)) {
            char xb[256];
            snprintf(xb, sizeof(xb), "[NATIVE] EXCLUDED field: %s", fn);
            L(xb);
            (*e)->ReleaseStringUTFChars(e, fnStr, fn);
            continue;
        }
        (*e)->ReleaseStringUTFChars(e, fnStr, fn);

        jobject values = NULL;

        /* Strategy C1: recipes as Set (0.5.1) */
        jfieldID rfSet = (*e)->GetFieldID(e, rtCls, "recipes", "Ljava/util/Set;");
        if (rfSet) {
            values = (*e)->GetObjectField(e, rt, rfSet);
        } else { (*e)->ExceptionClear(e); }

        /* Strategy C2: recipes as Map (0.5.0) -> values() */
        if (!values) {
            jfieldID rfMap = (*e)->GetFieldID(e, rtCls, "recipes", "Ljava/util/Map;");
            if (rfMap) {
                jobject map = (*e)->GetObjectField(e, rt, rfMap);
                if (map) {
                    values = (*e)->CallObjectMethod(e, map,
                        (*e)->GetMethodID(e, mapCls, "values", "()Ljava/util/Collection;"));
                }
            } else { (*e)->ExceptionClear(e); }
        }

        if (!values) {
            for (int m = 0; rtMethods[m] != NULL; m++) {
                jmethodID mid = (*e)->GetMethodID(e, rtCls, rtMethods[m], rtSigs[m]);
                if (!mid) { (*e)->ExceptionClear(e); continue; }
                jobject result = (*e)->CallObjectMethod(e, rt, mid);
                if (!result) continue;
                if ((*e)->IsInstanceOf(e, result, mapCls)) {
                    values = (*e)->CallObjectMethod(e, result,
                        (*e)->GetMethodID(e, mapCls, "values", "()Ljava/util/Collection;"));
                } else if ((*e)->IsInstanceOf(e, result, collCls)) {
                    values = result;
                }
                if (values) break;
            }
        }

        if (values) {
            typeHits++;
            (*e)->CallBooleanMethod(e, allRecipes, alAddAll, values);
            jint sz = (*e)->CallIntMethod(e, values,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, values), "size", "()I"));
            totalDefs += sz;
        }
    }

    char buf[256];
    snprintf(buf, sizeof(buf), "[NATIVE] Strategy C: %d types hit, %d total recipes",
        typeHits, totalDefs);
    L(buf);
    if (totalDefs == 0) return NULL;
    return allRecipes;
}

/* ======================== JNI exports ======================== */

JNIEXPORT jobject JNICALL Java_com_gtocutcorners_GTOCutCorners_getRecipeCollection
    (JNIEnv *e, jclass cls) {
    lf = fopen("gtocutcorners_native.log", "a");
    L("[NATIVE] getRecipeCollection via GTRegistries...");
    jobject coll = collectAllRecipes(e);
    if (coll != NULL) {
        jclass colC = (*e)->GetObjectClass(e, coll);
        jint sz = (*e)->CallIntMethod(e, coll, (*e)->GetMethodID(e, colC, "size", "()I"));
        if (sz < 1000) {
            char buf[128];
            snprintf(buf, sizeof(buf), "[NATIVE] WARNING: only %d recipes (suspicious), trying fallback", sz);
            L(buf);
            coll = fallbackCollectRecipes(e);
        }
    } else {
        L("[NATIVE] collectAllRecipes failed, trying fallback");
        coll = fallbackCollectRecipes(e);
    }
    if (coll == NULL) { L("[NATIVE] ERR: paths A+B failed, trying Strategy C"); 
        coll = collectAllViaTypeFields(e); }
    if (coll == NULL) { L("[NATIVE] ERR: all paths failed"); fclose(lf); return NULL; }
    fclose(lf);
    return coll;
}

JNIEXPORT jint JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeSetIntField
    (JNIEnv *e, jclass cls, jobject obj, jstring fieldName, jint val) {
    const char* fn = (*e)->GetStringUTFChars(e, fieldName, NULL);
    jclass oc = (*e)->GetObjectClass(e, obj);
    jfieldID fid = (*e)->GetFieldID(e, oc, fn, "I");
    jint old = 0;
    if (fid) { old = (*e)->GetIntField(e, obj, fid); (*e)->SetIntField(e, obj, fid, val); }
    (*e)->ReleaseStringUTFChars(e, fieldName, fn);
    return old;
}

JNIEXPORT jint JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeMassPatch
    (JNIEnv *e, jclass cls, jboolean clearConditions) {
    lf = fopen("gtocutcorners_native.log", "a");
    L("[NATIVE] Mass patch via GTRegistries...");
    jobject coll = collectAllRecipes(e);
    if (coll != NULL) {
        jclass colC = (*e)->GetObjectClass(e, coll);
        jint sz = (*e)->CallIntMethod(e, coll, (*e)->GetMethodID(e, colC, "size", "()I"));
        if (sz < 1000) {
            char buf[128];
            snprintf(buf, sizeof(buf), "[NATIVE] WARNING: only %d recipes, trying fallback", sz);
            L(buf);
            coll = fallbackCollectRecipes(e);
        }
    } else {
        L("[NATIVE] collectAllRecipes failed, trying fallback");
        coll = fallbackCollectRecipes(e);
    }
    if (coll == NULL) { L("[NATIVE] ERR: paths A+B failed, trying Strategy C");
        coll = collectAllViaTypeFields(e); }
    if (coll == NULL) { L("[NATIVE] ERR: all paths failed"); fclose(lf); return -1; }

    jclass colC = (*e)->GetObjectClass(e, coll);
    jint sz = (*e)->CallIntMethod(e, coll, (*e)->GetMethodID(e, colC, "size", "()I"));
    char buf[128];
    snprintf(buf, sizeof(buf), "[NATIVE] collection size=%d", sz);
    L(buf);

    jobjectArray ca = NULL;
    jclass apcC = NULL;
    if (clearConditions) {
        apcC = (*e)->FindClass(e, "com/gtocutcorners/AlwaysPassCondition");
        if (apcC) {
            jobject apc = (*e)->GetStaticObjectField(e, apcC,
                (*e)->GetStaticFieldID(e, apcC, "INSTANCE",
                    "Lcom/gtocutcorners/AlwaysPassCondition;"));
            jclass condC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/RecipeCondition");
            if (condC) ca = (*e)->NewObjectArray(e, 1, condC, apc);
        }
        if (!ca) { (*e)->ExceptionClear(e); L("[NATIVE] conditions class not found, skipping clear"); }
    }
    if (!clearConditions) L("[NATIVE] clearConditions=false, keeping original conditions");
    jclass defC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/GTRecipeDefinition");
    if (!defC) (*e)->ExceptionClear(e);
    if (!defC) (*e)->ExceptionClear(e); /* 0.5.1: class removed */
    jclass recC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/GTRecipe");
    if (!recC) (*e)->ExceptionClear(e);
    jfieldID durF = NULL;
    jfieldID condF = NULL;
    /* 0.5.1: duration on GTRecipe; 0.5.0: on GTRecipeDefinition */
    if (defC) {
        durF = (*e)->GetFieldID(e, defC, "duration", "I");
        condF = (*e)->GetFieldID(e, defC, "conditions",
            "[Lcom/gregtechceu/gtceu/api/recipe/RecipeCondition;");
        if (durF) L("[NATIVE] duration on GTRecipeDefinition");
    }
    if (!durF && recC) {
        durF = (*e)->GetFieldID(e, recC, "duration", "I");
        if (durF) L("[NATIVE] duration on GTRecipe");
    }

    if ((*e)->PushLocalFrame(e, sz + 512) < 0) {
        L("[NATIVE] ERR: PushLocalFrame"); fclose(lf); return -1;
    }
    jobject arr = (*e)->CallObjectMethod(e, coll,
        (*e)->GetMethodID(e, colC, "toArray", "()[Ljava/lang/Object;"));
    jint len = (*e)->GetArrayLength(e, arr);
    int dC = 0, cC = 0, genSkip = 0;
    /* Attach JNI-capable way to get recipe class (bypasses module restrictions) */
    jclass objC = NULL;
    jmethodID getOutputEUt = NULL;
    /* Try getOutputEUt detection on first recipe */
    int eutChecked = 0;
    for (int i = 0; i < len; i++) {
        jobject d = (*e)->GetObjectArrayElement(e, arr, i);
        if (!d) continue;

        /* Lazy init: get getOutputEUt from the actual recipe object class */
        if (!eutChecked) {
            eutChecked = 1;
            objC = (*e)->GetObjectClass(e, d);
            getOutputEUt = (*e)->GetMethodID(e, objC, "getOutputEUt", "()J");
            if (!getOutputEUt) {
                (*e)->ExceptionClear(e);
                L("[NATIVE] getOutputEUt not found, fallback to name exclusion");
            } else {
                L("[NATIVE] getOutputEUt found, auto-detecting generators");
            }
        }

        if (getOutputEUt) {
            jlong eut = (*e)->CallLongMethod(e, d, getOutputEUt);
            if (eut > 0) { genSkip++; continue; }
            /* Non-generator with getOutputEUt: fall through to ID-based exclusion */
        }
        {
            /* Try recipeType.registryName to auto-detect generators
             * via the recipe type's IO direction (field-based, no names) */
            jclass objC = (*e)->GetObjectClass(e, d);
            jobject rl = NULL;

            /* Try reading recipeType.registryName.toString() */
            jfieldID rtFld = (*e)->GetFieldID(e, objC, "recipeType", "Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;");
            if (rtFld) {
                jobject rt = (*e)->GetObjectField(e, d, rtFld);
                if (rt) {
                    jclass rtC = (*e)->GetObjectClass(e, rt);
                    jfieldID rnFld = (*e)->GetFieldID(e, rtC, "registryName", "Lnet/minecraft/resources/ResourceLocation;");
                    if (rnFld) {
                        jobject rn = (*e)->GetObjectField(e, rt, rnFld);
                        if (rn) rl = rn;
                    } else { (*e)->ExceptionClear(e); }
                }
            } else { (*e)->ExceptionClear(e); }

            /* Fallback: read 'id' field (GTRecipeDefinition) or 'getId()' (Recipe) */
            if (!rl) {
                jfieldID idFld = (*e)->GetFieldID(e, objC, "id", "Lnet/minecraft/resources/ResourceLocation;");
                if (idFld) rl = (*e)->GetObjectField(e, d, idFld);
                else {
                    (*e)->ExceptionClear(e);
                    jmethodID gid = (*e)->GetMethodID(e, objC, "getId", "()Lnet/minecraft/resources/ResourceLocation;");
                    if (gid) rl = (*e)->CallObjectMethod(e, d, gid);
                    else (*e)->ExceptionClear(e);
                }
            }
            if (rl) {
                jstring rs = (jstring)(*e)->CallObjectMethod(e, rl,
                    (*e)->GetMethodID(e, (*e)->GetObjectClass(e, rl), "toString", "()Ljava/lang/String;"));
                if (rs) {
                    const char* rls = (*e)->GetStringUTFChars(e, rs, NULL);
                    int excluded = isExcludedType(rls);
                    if (genSkip < 10) {
                        /* Extract type for debug */
                        const char* c2 = strchr(rls, ':');
                        const char* ts = c2 ? c2 + 1 : rls;
                    const char* sl = strchr(ts, '/');
                    char tbuf[64];
                    if (sl) { size_t tl = sl - ts; if(tl>63)tl=63; memcpy(tbuf, ts, tl); tbuf[tl]=0; }
                    else strcpy(tbuf, ts);
                    char dbg[512];
                    snprintf(dbg, sizeof(dbg), "[NATIVE] DBG type='%s' id='%s' excluded=%d", tbuf, rls, excluded);
                    L(dbg);
                    }
                    if (excluded) { genSkip++; (*e)->ReleaseStringUTFChars(e, rs, rls); continue; }
                    (*e)->ReleaseStringUTFChars(e, rs, rls);
                }
            } else if (genSkip < 5) {
                L("[NATIVE] cannot read recipe id (no field or method)");
            }
        }
        if (durF) {
            jint dd = (*e)->GetIntField(e, d, durF);
            if (dd != 1) { (*e)->SetIntField(e, d, durF, 1); dC++; }
        }
        if (condF && ca) { (*e)->SetObjectField(e, d, condF, ca); cC++; }
        if (i % 20000 == 0) {
            snprintf(buf, sizeof(buf), "[NATIVE] %d/%d d=%d c=%d", i, len, dC, cC);
            L(buf);
        }
    }
    (*e)->PopLocalFrame(e, NULL);
    snprintf(buf, sizeof(buf), "[NATIVE] DONE: %d d=%d c=%d skip=%d", len, dC, cC, genSkip);
    L(buf);
    fclose(lf);
    return cC;
}


/* =================================================================
 * Watchdog v2: lightweight linked-list based.
 * RecipeLogicMixin calls nativeRegisterRecipeLogic at construction
 * and nativeUnregisterRecipeLogic at unload. The watchdog thread
 * ================================================================= */

// =================================================================
// HeaterMachine diagnostic: dump instance state via JNI
// =================================================================
JNIEXPORT void JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeDiagnoseHeater
    (JNIEnv *e, jclass cls) {
    char buf[512];
    lf = fopen("gtocutcorners_native.log", "a");
    L("[DIAG] === HeaterMachine native diagnosis ===");

    /* Get server */
    jclass slh = (*e)->FindClass(e, "net/minecraftforge/server/ServerLifecycleHooks");
    if (!slh) { (*e)->ExceptionClear(e); L("[DIAG] ServerLifecycleHooks not found"); fclose(lf); return; }
    jmethodID gcs = (*e)->GetStaticMethodID(e, slh, "getCurrentServer",
        "()Lnet/minecraft/server/MinecraftServer;");
    if (!gcs) { (*e)->ExceptionClear(e); L("[DIAG] getCurrentServer not found"); fclose(lf); return; }
    jobject server = (*e)->CallStaticObjectMethod(e, slh, gcs);
    if (!server) { L("[DIAG] server null"); fclose(lf); return; }

    /* Get levels field */
    jclass sc = (*e)->GetObjectClass(e, server);
    jfieldID lfld = NULL;
    jclass cc = sc;
    while (cc && !lfld) {
        lfld = (*e)->GetFieldID(e, cc, "levels", "Ljava/util/Map;");
        if (!lfld) { (*e)->ExceptionClear(e); cc = (*e)->GetSuperclass(e, cc); }
    }
    if (!lfld) { L("[DIAG] levels field not found"); fclose(lf); return; }
    jobject levelMap = (*e)->GetObjectField(e, server, lfld);
    if (!levelMap) { L("[DIAG] levelMap null"); fclose(lf); return; }

    jclass mapC = (*e)->GetObjectClass(e, levelMap);
    jobject values = (*e)->CallObjectMethod(e, levelMap,
        (*e)->GetMethodID(e, mapC, "values", "()Ljava/util/Collection;"));
    jobjectArray va = (jobjectArray)(*e)->CallObjectMethod(e, values,
        (*e)->GetMethodID(e, (*e)->GetObjectClass(e, values), "toArray", "()[Ljava/lang/Object;"));
    jint vlen = (*e)->GetArrayLength(e, va);

    jclass heaterCls = NULL;
    int found = 0;

    for (jint li = 0; li < vlen; li++) {
        jobject lvl = (*e)->GetObjectArrayElement(e, va, li);
        if (!lvl) continue;

        jobject cs = (*e)->CallObjectMethod(e, lvl,
            (*e)->GetMethodID(e, (*e)->GetObjectClass(e, lvl), "getChunkSource",
            "()Lnet/minecraft/server/level/ChunkMap;"));
        if (!cs) continue;

        jobject chunks = (*e)->CallObjectMethod(e, cs,
            (*e)->GetMethodID(e, (*e)->GetObjectClass(e, cs), "getChunks",
            "()Ljava/lang/Iterable;"));
        if (!chunks) continue;

        jobject iter = (*e)->CallObjectMethod(e, chunks,
            (*e)->GetMethodID(e, (*e)->GetObjectClass(e, chunks), "iterator",
            "()Ljava/util/Iterator;"));
        jmethodID hasNext = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, iter), "hasNext", "()Z");
        jmethodID next = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, iter), "next", "()Ljava/lang/Object;");

        while ((*e)->CallBooleanMethod(e, iter, hasNext)) {
            jobject chunk = (*e)->CallObjectMethod(e, iter, next);
            if (!chunk) continue;

            jobject beMap = (*e)->CallObjectMethod(e, chunk,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, chunk), "getBlockEntities",
                "()Ljava/util/Map;"));
            if (!beMap) continue;

            jobject beValues = (*e)->CallObjectMethod(e, beMap,
                (*e)->GetMethodID(e, mapC, "values", "()Ljava/util/Collection;"));
            jobjectArray bea = (jobjectArray)(*e)->CallObjectMethod(e, beValues,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, beValues), "toArray", "()[Ljava/lang/Object;"));
            jint blen = (*e)->GetArrayLength(e, bea);

            for (jint bi = 0; bi < blen; bi++) {
                jobject be = (*e)->GetObjectArrayElement(e, bea, bi);
                if (!be) continue;

                /* Check if this is a HeaterMachine */
                jclass bec = (*e)->GetObjectClass(e, be);
                jmethodID toString = (*e)->GetMethodID(e, bec, "toString", "()Ljava/lang/String;");
                jstring ts = (jstring)(*e)->CallObjectMethod(e, be, toString);
                const char* cts = ts ? (*e)->GetStringUTFChars(e, ts, NULL) : NULL;
                int isHeater = cts && strstr(cts, "Heater") != NULL;
                if (cts) (*e)->ReleaseStringUTFChars(e, ts, cts);
                if (!isHeater) continue;

                found++;
                char buf[512];
                snprintf(buf, sizeof(buf), "[DIAG] HEATER[%d] BE class=%s", found,
                    (*e)->GetObjectClass(e, be) ? "ok" : "null");
                L(buf);

                /* Get MetaMachine */
                jobject mm = NULL;
                jmethodID gmm = (*e)->GetMethodID(e, bec, "getMetaMachine",
                    "()Lcom/gregtechceu/gtceu/api/machine/MetaMachine;");
                if (gmm) mm = (*e)->CallObjectMethod(e, be, gmm);
                else (*e)->ExceptionClear(e);

                if (mm) {
                    jclass mmc = (*e)->GetObjectClass(e, mm);
                    /* RecipeType */
                    jobject rt = NULL;
                    jmethodID grt = (*e)->GetMethodID(e, mmc, "getRecipeType",
                        "()Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;");
                    if (grt) rt = (*e)->CallObjectMethod(e, mm, grt);
                    else (*e)->ExceptionClear(e);

                    /* RecipeLogic */
                    jobject rl = NULL;
                    jmethodID grl = (*e)->GetMethodID(e, mmc, "getRecipeLogic",
                        "()Lcom/gregtechceu/gtceu/api/machine/trait/RecipeLogic;");
                    if (grl) rl = (*e)->CallObjectMethod(e, mm, grl);
                    else (*e)->ExceptionClear(e);

                    /* Dump RecipeType */
                    if (rt) {
                        jmethodID rtTs = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, rt), "toString",
                            "()Ljava/lang/String;");
                        jstring rts = (jstring)(*e)->CallObjectMethod(e, rt, rtTs);
                        const char* crt = (*e)->GetStringUTFChars(e, rts, NULL);
                        snprintf(buf, sizeof(buf), "[DIAG]   RecipeType=%s", crt ? crt : "null");
                        L(buf);
                        if (crt) (*e)->ReleaseStringUTFChars(e, rts, crt);

                        /* Recipe count */
                        jmethodID gr = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, rt), "getRecipes",
                            "()Ljava/util/Map;");
                        if (gr) {
                            jobject recipes = (*e)->CallObjectMethod(e, rt, gr);
                            if (recipes) {
                                jint sz = (*e)->CallIntMethod(e, recipes,
                                    (*e)->GetMethodID(e, (*e)->GetObjectClass(e, recipes), "size", "()I"));
                                snprintf(buf, sizeof(buf), "[DIAG]   Recipes count=%d", sz);
                                L(buf);
                            }
                        } else (*e)->ExceptionClear(e);
                    }

                    /* Dump RecipeLogic state */
                    if (rl) {
                        jclass rlc = (*e)->GetObjectClass(e, rl);
                        /* duration */
                        jfieldID durF = NULL;
                        jclass cc2 = rlc;
                        while (cc2 && !durF) {
                            durF = (*e)->GetFieldID(e, cc2, "duration", "I");
                            if (!durF) { (*e)->ExceptionClear(e); cc2 = (*e)->GetSuperclass(e, cc2); }
                        }
                        jint dur = durF ? (*e)->GetIntField(e, rl, durF) : -1;

                        /* progress */
                        jfieldID progF = NULL;
                        cc2 = rlc;
                        while (cc2 && !progF) {
                            progF = (*e)->GetFieldID(e, cc2, "progress", "I");
                            if (!progF) { (*e)->ExceptionClear(e); cc2 = (*e)->GetSuperclass(e, cc2); }
                        }
                        jint prog = progF ? (*e)->GetIntField(e, rl, progF) : -1;

                        /* isWorking */
                        jmethodID iw = (*e)->GetMethodID(e, rlc, "isWorking", "()Z");
                        jboolean working = iw ? (*e)->CallBooleanMethod(e, rl, iw) : JNI_FALSE;
                        if (!iw) (*e)->ExceptionClear(e);

                        /* lastRecipe */
                        jobject lr = NULL;
                        jmethodID glr = (*e)->GetMethodID(e, rlc, "getLastRecipe",
                            "()Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;");
                        if (glr) lr = (*e)->CallObjectMethod(e, rl, glr);
                        else (*e)->ExceptionClear(e);

                        snprintf(buf, sizeof(buf), "[DIAG]   RL: dur=%d prog=%d working=%d lastRecipe=%s",
                            dur, prog, working, lr ? "present" : "null");
                        L(buf);

                        /* Dump import items */
                        jmethodID gii = (*e)->GetMethodID(e, mmc, "getImportItems",
                            "()Lcom/gregtechceu/gtceu/api/machine/trait/NotifiableItemStackHandler;");
                        if (gii) {
                            jobject ih = (*e)->CallObjectMethod(e, mm, gii);
                            if (ih) {
                                jclass ihc = (*e)->GetObjectClass(e, ih);
                                jmethodID gs = (*e)->GetMethodID(e, ihc, "getSlots", "()I");
                                jmethodID gsis = (*e)->GetMethodID(e, ihc, "getStackInSlot",
                                    "(I)Lnet/minecraft/world/item/ItemStack;");
                                jint slots = gs ? (*e)->CallIntMethod(e, ih, gs) : 0;
                                snprintf(buf, sizeof(buf), "[DIAG]   Import slots=%d", slots);
                                L(buf);
                                for (jint si = 0; si < slots && si < 8; si++) {
                                    jobject stack = gsis ? (*e)->CallObjectMethod(e, ih, gsis, si) : NULL;
                                    if (stack) {
                                        jmethodID gcount = (*e)->GetMethodID(e,
                                            (*e)->GetObjectClass(e, stack), "getCount", "()I");
                                        jint count = gcount ? (*e)->CallIntMethod(e, stack, gcount) : 0;
                                        if (count > 0) {
                                            jstring sts = (jstring)(*e)->CallObjectMethod(e, stack,
                                                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, stack),
                                                "toString", "()Ljava/lang/String;"));
                                            const char* cst = (*e)->GetStringUTFChars(e, sts, NULL);
                                            snprintf(buf, sizeof(buf), "[DIAG]   slot[%d]=%s x%d",
                                                si, cst ? cst : "?", count);
                                            L(buf);
                                            if (cst) (*e)->ReleaseStringUTFChars(e, sts, cst);
                                        }
                                    }
                                }
                            } else (*e)->ExceptionClear(e);
                        } else (*e)->ExceptionClear(e);
                    }
                }
            }
        }
    }
    snprintf(buf, sizeof(buf), "[DIAG] Total HeaterMachine found: %d", found);
    L(buf);
    fclose(lf);
}

/* =================================================================
 * only iterates registered instances -- no world/chunk scanning.
 * ================================================================= */

/* ---- Lock-free singly-linked list node ---- */
typedef struct rl_node {
    jobject global_ref;          /* JNI global reference to RecipeLogic */
    volatile struct rl_node* next;
} rl_node_t;

static rl_node_t* g_rl_head = NULL;
static jfieldID g_rl_dur_fid = NULL;

/* Simple spinlock for list operations */
#ifdef _WIN32
static volatile LONG g_rl_lock = 0;
#define RL_LOCK()   while(InterlockedExchange(&g_rl_lock, 1)) { Sleep(0); }
#define RL_UNLOCK() InterlockedExchange(&g_rl_lock, 0)
#else
static volatile int g_rl_lock = 0;
#define RL_LOCK()   while(__sync_lock_test_and_set(&g_rl_lock, 1)) { usleep(0); }
#define RL_UNLOCK() __sync_lock_release(&g_rl_lock)
#endif

JNIEXPORT void JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeRegisterRecipeLogic
    (JNIEnv *e, jclass cls, jobject logic) {
    if (!logic) return;
    rl_node_t* node = (rl_node_t*)malloc(sizeof(rl_node_t));
    if (!node) return;
    node->global_ref = (*e)->NewGlobalRef(e, logic);
    if (!node->global_ref) { free(node); return; }

    /* Cache duration field ID once */
    if (!g_rl_dur_fid) {
        jclass rlCls = (*e)->GetObjectClass(e, logic);
        g_rl_dur_fid = (*e)->GetFieldID(e, rlCls, "duration", "I");
        if (!g_rl_dur_fid) (*e)->ExceptionClear(e);
    }

    RL_LOCK();
    node->next = g_rl_head;
    g_rl_head = node;
    RL_UNLOCK();
}

JNIEXPORT void JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeUnregisterRecipeLogic
    (JNIEnv *e, jclass cls, jobject logic) {
    if (!logic || !g_rl_head) return;
    RL_LOCK();
    rl_node_t** prev = &g_rl_head;
    rl_node_t* curr = g_rl_head;
    while (curr) {
        if ((*e)->IsSameObject(e, curr->global_ref, logic)) {
            *prev = (rl_node_t*)curr->next;
            (*e)->DeleteGlobalRef(e, curr->global_ref);
            free(curr);
            break;
        }
        prev = (rl_node_t**)&curr->next;
        curr = (rl_node_t*)curr->next;
    }
    RL_UNLOCK();
}


/* Bootstrap: full world scan to populate the linked list when empty.
 * Only called once when list is cold. */
static int watchdogBootstrap(JNIEnv* e) {
    L("[BOOTSTRAP] starting world scan...");
    jclass hooksCls = (*e)->FindClass(e, "net/minecraftforge/server/ServerLifecycleHooks");
    if (!hooksCls) {
        L("[BOOTSTRAP] ERR: ServerLifecycleHooks class not found");
        (*e)->ExceptionClear(e); return 0;
    }
    L("[BOOTSTRAP] ServerLifecycleHooks found");
    jmethodID getCurrent = (*e)->GetStaticMethodID(e, hooksCls, "getCurrentServer",
        "()Lnet/minecraft/server/MinecraftServer;");
    if (!getCurrent) {
        L("[BOOTSTRAP] ERR: getCurrentServer method not found");
        (*e)->ExceptionClear(e); return 0;
    }
    L("[BOOTSTRAP] getCurrentServer method found");
    jobject server = (*e)->CallStaticObjectMethod(e, hooksCls, getCurrent);
    if (!server) {
        L("[BOOTSTRAP] server is null (world not loaded?)");
        return 0;
    }
    L("[BOOTSTRAP] server obtained OK");

    jclass mcCls = (*e)->GetObjectClass(e, server);
    jobject levels = NULL;
    jmethodID getLevels = NULL;

    /* Try multiple method names for getting server levels (MC 1.20.1) */
    const char* levelMethods[] = {
        "getAllLevels", "getLevels", "getWorldArray", "getAllLevelsNoPrecipitation",
        NULL
    };
    const char* levelSigs[] = {
        "()Ljava/lang/Iterable;",
        "()Ljava/lang/Iterable;",
        "()[Lnet/minecraft/server/level/ServerLevel;",
        "()Ljava/lang/Iterable;",
        NULL
    };
    for (int m = 0; levelMethods[m] != NULL; m++) {
        getLevels = (*e)->GetMethodID(e, mcCls, levelMethods[m], levelSigs[m]);
        if (getLevels) {
            char buf[128];
            snprintf(buf, sizeof(buf), "[BOOTSTRAP] found: %s %s", levelMethods[m], levelSigs[m]);
            L(buf);
            break;
        }
        (*e)->ExceptionClear(e);
    }

    if (getLevels) {
        levels = (*e)->CallObjectMethod(e, server, getLevels);
    }

    /* Fallback: try overworld() directly */
    if (!levels) {
        L("[BOOTSTRAP] trying overworld() directly...");
        jmethodID ow = (*e)->GetMethodID(e, mcCls, "overworld",
            "()Lnet/minecraft/server/level/ServerLevel;");
        if (ow) {
            jobject owLevel = (*e)->CallObjectMethod(e, server, ow);
            if (owLevel) {
                /* Wrap in a single-element list */
                jclass alCls = (*e)->FindClass(e, "java/util/ArrayList");
                jobject al = (*e)->NewObject(e, alCls,
                    (*e)->GetMethodID(e, alCls, "<init>", "()V"));
                (*e)->CallBooleanMethod(e, al,
                    (*e)->GetMethodID(e, alCls, "add", "(Ljava/lang/Object;)Z"), owLevel);
                levels = al;
                L("[BOOTSTRAP] overworld obtained, wrapped in list");
            }
        } else {
            (*e)->ExceptionClear(e);
        }
    }

    if (!levels) {
        L("[BOOTSTRAP] ERR: cannot get any levels");
        return 0;
    }
    L("[BOOTSTRAP] levels obtained OK");

    /* levels might be Iterable or Object[] — handle both */
    int isArray = 0;
    jobjectArray levelArr = NULL;
    jint levelCount = 0;
    jobject it = NULL;
    jmethodID hasNext = NULL, next = NULL;

    jclass levelsCls = (*e)->GetObjectClass(e, levels);
    if ((*e)->IsInstanceOf(e, levels, (*e)->FindClass(e, "java/util/Iterable"))) {
        it = (*e)->CallObjectMethod(e, levels,
            (*e)->GetMethodID(e, levelsCls, "iterator", "()Ljava/util/Iterator;"));
        if (it) {
            jclass itCls = (*e)->GetObjectClass(e, it);
            hasNext = (*e)->GetMethodID(e, itCls, "hasNext", "()Z");
            next = (*e)->GetMethodID(e, itCls, "next", "()Ljava/lang/Object;");
        }
    }
    if (!it) {
        (*e)->ExceptionClear(e);
        /* Try as Object[] */
        if ((*e)->IsInstanceOf(e, levels, (*e)->FindClass(e, "[Ljava/lang/Object;"))) {
            levelArr = (jobjectArray)levels;
            levelCount = (*e)->GetArrayLength(e, levelArr);
            isArray = 1;
            L("[BOOTSTRAP] levels is Object[], count using array index");
        }
    }
    if (!it && !isArray) {
        L("[BOOTSTRAP] ERR: cannot iterate levels");
        return 0;
    }

    jclass mmbeCls = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/blockentity/MetaMachineBlockEntity");
    jclass rlmCls = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine");
    {
        char _dbg[256];
        snprintf(_dbg, sizeof(_dbg), "[BOOTSTRAP] MMBe=%p IRLM=%p", (void*)mmbeCls, (void*)rlmCls);
        L(_dbg);
    }
    if (!mmbeCls) {
        L("[BOOTSTRAP] ERR: MetaMachineBlockEntity class not found!");
        (*e)->ExceptionClear(e);
    }
    if (!rlmCls) {
        L("[BOOTSTRAP] ERR: IRecipeLogicMachine class not found!");
        (*e)->ExceptionClear(e);
    }
    if (!mmbeCls || !rlmCls) return 0;

    int registered = 0;
    int levelIdx = 0;
    while (isArray ? (levelIdx < levelCount) : (*e)->CallBooleanMethod(e, it, hasNext)) {
        jobject level = isArray ? (*e)->GetObjectArrayElement(e, levelArr, levelIdx++) : (*e)->CallObjectMethod(e, it, next);
        if (!level) continue;
        jclass slCls = (*e)->GetObjectClass(e, level);
        jmethodID getCS = (*e)->GetMethodID(e, slCls, "getChunkSource",
            "()Lnet/minecraft/server/level/ServerChunkCache;");
        if (!getCS) { (*e)->ExceptionClear(e); continue; }
        jobject cs = (*e)->CallObjectMethod(e, level, getCS);
        if (!cs) continue;
        jmethodID getChunks = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, cs),
            "getChunks", "()Ljava/lang/Iterable;");
        if (!getChunks) { (*e)->ExceptionClear(e); continue; }
        jobject chunks = (*e)->CallObjectMethod(e, cs, getChunks);
        if (!chunks) continue;
        jobject chunkIt = (*e)->CallObjectMethod(e, chunks,
            (*e)->GetMethodID(e, (*e)->GetObjectClass(e, chunks), "iterator", "()Ljava/util/Iterator;"));
        if (!chunkIt) continue;
        jclass citc = (*e)->GetObjectClass(e, chunkIt);
        jmethodID chn = (*e)->GetMethodID(e, citc, "hasNext", "()Z");
        jmethodID cnx = (*e)->GetMethodID(e, citc, "next", "()Ljava/lang/Object;");
        while ((*e)->CallBooleanMethod(e, chunkIt, chn)) {
            jobject chunk = (*e)->CallObjectMethod(e, chunkIt, cnx);
            if (!chunk) continue;
            jmethodID getBE = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, chunk),
                "getBlockEntities", "()Ljava/util/Map;");
            if (!getBE) { (*e)->ExceptionClear(e); continue; }
            jobject beMap = (*e)->CallObjectMethod(e, chunk, getBE);
            if (!beMap) continue;
            jobject vals = (*e)->CallObjectMethod(e, beMap,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, beMap), "values", "()Ljava/util/Collection;"));
            if (!vals) continue;
            jobject beIt = (*e)->CallObjectMethod(e, vals,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, vals), "iterator", "()Ljava/util/Iterator;"));
            if (!beIt) continue;
            jclass bitc = (*e)->GetObjectClass(e, beIt);
            jmethodID bhn = (*e)->GetMethodID(e, bitc, "hasNext", "()Z");
            jmethodID bnx = (*e)->GetMethodID(e, bitc, "next", "()Ljava/lang/Object;");
            while ((*e)->CallBooleanMethod(e, beIt, bhn)) {
                jobject be = (*e)->CallObjectMethod(e, beIt, bnx);
                if (!be || !(*e)->IsInstanceOf(e, be, mmbeCls)) continue;
                jmethodID gm = (*e)->GetMethodID(e, mmbeCls, "getMetaMachine",
                    "()Lcom/gregtechceu/gtceu/api/machine/MetaMachine;");
                if (!gm) { (*e)->ExceptionClear(e); continue; }
                jobject machine = (*e)->CallObjectMethod(e, be, gm);
                if (!machine || !(*e)->IsInstanceOf(e, machine, rlmCls)) continue;
                jmethodID grl = (*e)->GetMethodID(e, rlmCls, "getRecipeLogic",
                    "()Lcom/gregtechceu/gtceu/api/machine/trait/RecipeLogic;");
                if (!grl) { (*e)->ExceptionClear(e); continue; }
                jobject logic = (*e)->CallObjectMethod(e, machine, grl);
                if (!logic) continue;
                /* Found one — register into linked list */
                rl_node_t* node = (rl_node_t*)malloc(sizeof(rl_node_t));
                if (!node) continue;
                node->global_ref = (*e)->NewGlobalRef(e, logic);
                if (!node->global_ref) { free(node); continue; }
                if (!g_rl_dur_fid) {
                    jclass rlCls = (*e)->GetObjectClass(e, logic);
                    g_rl_dur_fid = (*e)->GetFieldID(e, rlCls, "duration", "I");
                    if (!g_rl_dur_fid) (*e)->ExceptionClear(e);
                }
                /* Check duplicate — skip if already in list */
                {
                    int dup = 0;
                    RL_LOCK();
                    rl_node_t* p = g_rl_head;
                    while (p) {
                        if ((*e)->IsSameObject(e, p->global_ref, logic)) { dup = 1; break; }
                        p = (rl_node_t*)p->next;
                    }
                    if (!dup) {
                        node->next = g_rl_head;
                        g_rl_head = node;
                    } else {
                        (*e)->DeleteGlobalRef(e, node->global_ref);
                        free(node);
                    }
                    RL_UNLOCK();
                    if (dup) continue;
                }
                registered++;
            }
        }
    }
    {
        char _dbg[128];
        snprintf(_dbg, sizeof(_dbg), "[BOOTSTRAP] done, registered=%d", registered);
        L(_dbg);
    }
    return registered;
}

#ifdef _WIN32
static DWORD WINAPI watchdog_thread(LPVOID arg) {
#else
static void* watchdog_thread(void* arg) {
#endif
    lf = fopen("gtocutcorners_watchdog.log", "a");
    L("[NATIVE WATCHDOG] ======== thread entered ========");
    {
        char _dbg[128];
        snprintf(_dbg, sizeof(_dbg), "[NATIVE WATCHDOG] g_jvm=%p", (void*)g_jvm);
        L(_dbg);
    }
    if (!g_jvm) { L("[NATIVE WATCHDOG] FATAL: g_jvm is NULL, exiting"); fclose(lf); return 0; }
    L("[NATIVE WATCHDOG] thread started (linked-list mode)");
    g_watchdog_running = 1;
    L("[NATIVE WATCHDOG] g_watchdog_running set to 1");
    printf("[NATIVE WATCHDOG] entering main loop, g_jvm=%p\n", (void*)g_jvm); fflush(stdout);
    int cycle = 0;

    while (g_watchdog_running) {
#ifdef _WIN32
        Sleep(3000);
#else
        sleep(3);
#endif
        cycle++;
        if (cycle <= 3) {
            char _dbg[64];
            snprintf(_dbg, sizeof(_dbg), "[NATIVE WATCHDOG] woke up, cycle=%d", cycle);
            L(_dbg);
        }
        if (!g_watchdog_running) continue;

        if (!g_jvm) {
            L("[NATIVE WATCHDOG] FATAL: g_jvm NULL, cannot attach");
#ifdef _WIN32
            Sleep(10000);
#else
            sleep(10);
#endif
            continue;
        }
        JNIEnv* e = NULL;
        printf("[NATIVE WATCHDOG] calling GetEnv...\n"); fflush(stdout);
        jint rs = (*g_jvm)->GetEnv(g_jvm, (void**)&e, JNI_VERSION_1_8);
        int need_detach = 0;
        if (rs == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&e, NULL) != JNI_OK) continue;
            need_detach = 1;
        } else if (rs != JNI_OK) continue;

        /* Hybrid: full scan every 10 cycles to catch new machines;
         * between scans, only iterate the linked list. */
        int alive = 0, patched = 0, dead = 0;

        if (cycle % 10 == 1 || !g_rl_head) {
            L("[NATIVE WATCHDOG] running full scan (bootstrap)...");
            int synced = watchdogBootstrap(e);
            if (synced > 0 && cycle > 1) {
                char bbuf[128];
                snprintf(bbuf, sizeof(bbuf), "[NATIVE WATCHDOG] sync: added %d new RecipeLogic", synced);
                L(bbuf);
            } else if (synced > 0) {
                char bbuf[128];
                snprintf(bbuf, sizeof(bbuf), "[NATIVE WATCHDOG] bootstrap: %d RecipeLogic", synced);
                L(bbuf);
            }
            if (!g_rl_head) {
                if (need_detach) { (*g_jvm)->DetachCurrentThread(g_jvm); }
                continue;
            }
        }
        rl_node_t* prev = NULL;
        rl_node_t* curr = NULL;

        RL_LOCK();
        curr = g_rl_head;
        RL_UNLOCK();

        while (curr) {
            rl_node_t* next_node = (rl_node_t*)curr->next;
            alive++;

            /* Check if object still alive by reading its duration field */
            if (g_rl_dur_fid) {
                jint dur = 0;
                int valid = 1;
                /* Use exception check pattern for safety */
                dur = (*e)->GetIntField(e, curr->global_ref, g_rl_dur_fid);
                if ((*e)->ExceptionCheck(e)) {
                    (*e)->ExceptionClear(e);
                    valid = 0;
                }
                if (!valid) {
                    /* Object died, remove from list */
                    RL_LOCK();
                    if (prev) {
                        prev->next = next_node;
                    } else {
                        g_rl_head = next_node;
                    }
                    RL_UNLOCK();
                    (*e)->DeleteGlobalRef(e, curr->global_ref);
                    free(curr);
                    dead++;
                } else if (dur > 1) {
                    (*e)->SetIntField(e, curr->global_ref, g_rl_dur_fid, 1);
                    patched++;
                    prev = curr;
                } else {
                    prev = curr;
                }
            }
            curr = next_node;
        }

        /* Verbose: log first 3 cycles always, then every 20 or when patched */
        if (cycle <= 3 || cycle % 20 == 0 || patched > 0) {
            char buf[200];
            snprintf(buf, sizeof(buf),
                "[NATIVE WATCHDOG] cycle=%d alive=%d patched=%d dead=%d list_empty=%d",
                cycle, alive, patched, dead, (g_rl_head == NULL));
            L(buf);
        }
        if (need_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    L("[NATIVE WATCHDOG] stopped");
#ifdef _WIN32
    return 0;
#else
    return NULL;
#endif
}


/* ======================== Patch MAX_PROGRESS constants ======================== */


/* ======================== Bytecode-level "SQL injection" ========================
 * findMethodBytecode: locate the raw bytecodes of a method in JVM memory.
 * jmethodID in JDK 21 HotSpot is a Method* pointer. We scan from it to find
 * void* which contains the actual bytecodes.
 *
 * Then searchAndPatchBytecode replaces bipush 20 (0x10 0x14) with bipush 1 (0x10 0x01).
 */

static int findAndPatchDurationBytecode(JNIEnv* e, const char* className,
                                         const char* methodName, const char* methodSig) {
    char buf[256];

    jclass cls = (*e)->FindClass(e, className);
    if (!cls) {
        snprintf(buf, sizeof(buf), "[BYTECODE] class not found: %s", className);
        L(buf); (*e)->ExceptionClear(e); return 0;
    }

    jmethodID mid = (*e)->GetMethodID(e, cls, methodName, methodSig);
    if (!mid) {
        snprintf(buf, sizeof(buf), "[BYTECODE] method not found: %s", methodName);
        L(buf); (*e)->ExceptionClear(e); return 0;
    }

    /* jmethodID == Method* in JDK 21.
     * Method layout: [vtable_ptr:8] [ConstMethod*:8] [...]
     * ConstMethod* is at offset 8. Read it. */
    char* methodPtr = (char*)mid;
    snprintf(buf, sizeof(buf), "[BYTECODE] methodPtr=%p", (void*)methodPtr);
    L(buf);

    /* Safety: first check vtable is non-null (basic validity) */
    void* vtable = *(void**)methodPtr;
    snprintf(buf, sizeof(buf), "[BYTECODE] vtable=%p", vtable);
    L(buf);
    if (!vtable) { L("[BYTECODE] NULL vtable, abort"); fflush(lf); return 0; }

    /* Read ConstMethod* at offset 8 */
    char* cm = *(char**)(methodPtr + 8);
    snprintf(buf, sizeof(buf), "[BYTECODE] ConstMethod*=%p", (void*)cm);
    L(buf);
    if (!cm) { L("[BYTECODE] NULL ConstMethod*, abort"); fflush(lf); return 0; }

    /* Validate address range — Metaspace on Win x64 is typically 0x7ff... or 0x8... */
    unsigned long long cmAddr = (unsigned long long)cm;
    if (cmAddr < 0x1000000ULL) {
        snprintf(buf, sizeof(buf), "[BYTECODE] ConstMethod* looks invalid: %llx", cmAddr);
        L(buf); fflush(lf); return 0;
    }

    /* Scan ConstMethod memory for bipush 20 (0x10 0x14).
     * ConstMethod starts with header (~64 bytes), then bytecodes follow. */
    int patched = 0;
    for (int pos = 32; pos < 4096 - 1; pos++) {
        /* Use __try for Windows SEH to catch access violations */
        unsigned char* bc = (unsigned char*)(cm + pos);
        /* Quick validity check before reading */
        unsigned long long bcAddr = (unsigned long long)bc;
        if (bcAddr < 0x1000000ULL || bcAddr > 0x800000000000ULL) break;

        if (bc[0] == 0x10 && bc[1] == 0x14) {
            snprintf(buf, sizeof(buf), "[BYTECODE] FOUND bipush 20 at pos=%d -> patching", pos);
            L(buf); fflush(lf);
            bc[1] = 0x01;
            patched++;
            break;
        }
    }

    if (patched == 0) L("[BYTECODE] bipush 20 not found in bytecode range 32-4096"); fflush(lf);
    return patched;
}
static int patchMaxProgressConstants(JNIEnv* e) {
    int anyChanged = 0;
    const char* targets[][2] = {
        {"com/gtocore/common/machine/trait/INFFluidDrillLogic", "MAX_PROGRESS"},
        {"com/gtocore/common/machine/trait/AdvancedInfiniteDrillLogic", "MAX_PROGRESS"},
        {NULL, NULL}
    };

    for (int i = 0; targets[i][0] != NULL; i++) {
        jclass cls = (*e)->FindClass(e, targets[i][0]);
        if (!cls) {
            (*e)->ExceptionClear(e);
            char buf[256];
            snprintf(buf, sizeof(buf), "[NATIVE] MAX_PROGRESS: class not found: %s", targets[i][0]);
            L(buf);
            continue;
        }

        jfieldID fid = (*e)->GetStaticFieldID(e, cls, targets[i][1], "I");
        if (!fid) {
            (*e)->ExceptionClear(e);
            char buf[256];
            snprintf(buf, sizeof(buf), "[NATIVE] MAX_PROGRESS: field not found in %s", targets[i][0]);
            L(buf);
            continue;
        }

        jint oldVal = (*e)->GetStaticIntField(e, cls, fid);
        if (oldVal != 1) {
            (*e)->SetStaticIntField(e, cls, fid, 1);
            char buf[256];
            snprintf(buf, sizeof(buf), "[NATIVE] MAX_PROGRESS: %s %d -> 1 (patched)", targets[i][0], oldVal);
            anyChanged = 1;
            L(buf);
        } else {
            char buf[256];
            snprintf(buf, sizeof(buf), "[NATIVE] MAX_PROGRESS: %s already 1 (stable)", targets[i][0]);
            L(buf);
        }
    }
    if (anyChanged) L("[MAX_PROGRESS] patch done (changes applied)");
    else L("[MAX_PROGRESS] patch done (all stable)");
    return anyChanged;
}


/* ======================== Watchdog Tick (called from Java timer) ======================== */

static int g_wd_cycle = 0;

JNIEXPORT jint JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeWatchdogTick
    (JNIEnv *e, jclass cls) {
    lf = fopen("gtocutcorners_watchdog.log", "a");
    g_wd_cycle++;

    /* First cycle: patch bytecode + MAX_PROGRESS + verify */
    if (g_wd_cycle == 1) {
        L("[WD_TICK] patching bytecode (SQL injection)...");
        findAndPatchDurationBytecode(e,
            "com/gtocore/common/machine/trait/INFFluidDrillLogic",
            "getFluidDrillRecipe", "()Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;");
        findAndPatchDurationBytecode(e,
            "com/gtocore/common/machine/trait/AdvancedInfiniteDrillLogic",
            "getFluidDrillRecipe", "()Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;");
        L("[WD_TICK] patching MAX_PROGRESS constants...");
        patchMaxProgressConstants(e);
        const char* verifyClasses[] = {
            "com/gtocore/common/machine/trait/INFFluidDrillLogic",
            "com/gtocore/common/machine/trait/AdvancedInfiniteDrillLogic",
            NULL
        };
        for (int v = 0; verifyClasses[v] != NULL; v++) {
            jclass vcls = (*e)->FindClass(e, verifyClasses[v]);
            if (vcls) {
                jfieldID vfid = (*e)->GetStaticFieldID(e, vcls, "MAX_PROGRESS", "I");
                if (vfid) {
                    jint vval = (*e)->GetStaticIntField(e, vcls, vfid);
                    char vbuf[256];
                    snprintf(vbuf, sizeof(vbuf), "[VERIFY] %s.MAX_PROGRESS = %d (expect 1)", verifyClasses[v], vval);
                    L(vbuf);
                } else {
                    L("[VERIFY] MAX_PROGRESS field not found");
                    (*e)->ExceptionClear(e);
                }
            } else {
                L("[VERIFY] class not found");
                (*e)->ExceptionClear(e);
            }
        }
    }
    
    static int stableCount = 0;

    /* Re-apply MAX_PROGRESS — stop after 10 consecutive stable cycles */
    if (stableCount < 10) {
        int changed = patchMaxProgressConstants(e);
        if (changed) stableCount = 0;
        else stableCount++;
    }

    /* MAX_PROGRESS already patched at source — no world scan needed. */
    if (g_wd_cycle % 30 == 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "[WD_TICK] cycle=%d keep-alive OK", g_wd_cycle);
        L(buf);
    }
    fclose(lf);
    return g_wd_cycle;
}

JNIEXPORT void JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeStartWatchdog
    (JNIEnv *e, jclass cls) {
    lf = fopen("gtocutcorners_watchdog.log", "a");
    L("[WATCHDOG] ======== nativeStartWatchdog called ========");
    {
        char _dbg[128];
        snprintf(_dbg, sizeof(_dbg), "[WATCHDOG] g_jvm=%p g_watchdog_running=%d", (void*)g_jvm, g_watchdog_running);
        L(_dbg);
    }
    if (g_watchdog_running) { L("[WATCHDOG] already running, skip"); fclose(lf); return; }
    L("[WATCHDOG] step1: patching MAX_PROGRESS...");
    patchMaxProgressConstants(e);
    {
        char _dbg[128];
        snprintf(_dbg, sizeof(_dbg), "[WATCHDOG] step2: g_jvm=%p starting daemon...", (void*)g_jvm);
        L(_dbg);
    }
#ifdef _WIN32
    HANDLE h = CreateThread(NULL, 0, watchdog_thread, NULL, 0, NULL);
    {
        char _dbg[128];
        snprintf(_dbg, sizeof(_dbg), "[WATCHDOG] step3: CreateThread returned %p", (void*)h);
        L(_dbg);
    }
    if (h) { CloseHandle(h); L("[WATCHDOG] thread handle closed, thread running"); }
    else L("[WATCHDOG] ERROR: CreateThread failed!");
#else
    pthread_t tid;
    int rc = pthread_create(&tid, NULL, watchdog_thread, NULL);
    {
        char _dbg[128];
        snprintf(_dbg, sizeof(_dbg), "[WATCHDOG] step3: pthread_create rc=%d tid=%lu", rc, (unsigned long)tid);
        L(_dbg);
    }
    if (rc == 0) { pthread_detach(tid); L("[WATCHDOG] thread detached"); }
    else L("[WATCHDOG] ERROR: pthread_create failed!");
#endif
    L("[WATCHDOG] ======== nativeStartWatchdog done ========");
    fclose(lf);
}

JNIEXPORT void JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeStopWatchdog
    (JNIEnv *e, jclass cls) {
    g_watchdog_running = 0;
    L("[WATCHDOG] stop signal sent");
}

/* JVMTI bytecode injection and diagnostics */
#include "jvmti_patch.c"
