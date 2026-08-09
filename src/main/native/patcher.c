#include <jni.h>
#include <jvmti.h>
#include <string.h>
#include <stdlib.h>
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
static int init_jvmti(JavaVM* vm);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *r) {
    g_jvm = vm;
    /* Log to stdout first, then try file */
    printf("[NATIVE] JNI_OnLoad: vm=%p\n", (void*)vm);
    lf = fopen("gtocutcorners_watchdog.log", "a");
    L("[NATIVE] ======== JNI_OnLoad: vm saved, g_jvm set ========");
    int jvmti_ok = init_jvmti(vm);
    { char _b[128]; snprintf(_b,sizeof(_b),"[NATIVE] init_jvmti returned: %d (0=fail, 1=success)", jvmti_ok); L(_b); }
    printf("[NATIVE] init_jvmti: %s\n", jvmti_ok ? "SUCCESS" : "FAILED");
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
        "scanner", "world_data_scanner", "radiation_hatch", "space_probe_surface_reception",
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
    lf = fopen("gtocutcorners_native.log", "w");
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
    (JNIEnv *e, jclass cls) {
    lf = fopen("gtocutcorners_native.log", "w");
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

    jclass defC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/GTRecipeDefinition");
    if (!defC) (*e)->ExceptionClear(e);
    if (!defC) (*e)->ExceptionClear(e); /* 0.5.1: class removed */
    jclass recC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/GTRecipe");
    if (!recC) (*e)->ExceptionClear(e);
    jfieldID durF = NULL;
    /* 0.5.1: duration on GTRecipe; 0.5.0: on GTRecipeDefinition */
    if (defC) {
        durF = (*e)->GetFieldID(e, defC, "duration", "I");
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
    int dC = 0, genSkip = 0;
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
        if (i % 20000 == 0) {
            snprintf(buf, sizeof(buf), "[NATIVE] %d/%d d=%d", i, len, dC);
            L(buf);
        }
    }
    (*e)->PopLocalFrame(e, NULL);
    snprintf(buf, sizeof(buf), "[NATIVE] DONE: %d d=%d skip=%d", len, dC, genSkip);
    L(buf);
    fclose(lf);
    return dC;
}

#include "jvmti_patch.c"
