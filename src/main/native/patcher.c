#include <jni.h>
#include <string.h>
#include <stdio.h>

static FILE* lf=NULL;
static void L(const char*m){printf("%s\n",m);if(lf){fprintf(lf,"%s\n",m);fflush(lf);}}

/* 获取全局配方Collection */
JNIEXPORT jobject JNICALL Java_com_gtocutcorners_GTOCutCorners_getRecipeCollection
    (JNIEnv *e, jclass cls) {
    lf=fopen("gtocutcorners_native.log","w");
    L("[NATIVE] Getting collection...");

    jclass rtc=(*e)->FindClass(e,"com/gtocore/common/data/GTORecipeTypes");
    if(!rtc){(*e)->ExceptionClear(e);L("ERR");fclose(lf);return NULL;}

    jfieldID fld=(*e)->GetStaticFieldID(e,rtc,"GAS_COMPRESSOR_RECIPES","Lcom/gtolib/api/recipe/RecipeType;");
    jobject rt=(*e)->GetStaticObjectField(e,rtc,fld);
    jclass rtC=(*e)->GetObjectClass(e,rt);
    jobject bld=(*e)->CallObjectMethod(e,rt,(*e)->GetMethodID(e,rtC,"recipeBuilder","(Ljava/lang/String;)Lcom/gtolib/api/recipe/RecipeBuilder;"),(*e)->NewStringUTF(e,"x"));
    jclass bC=(*e)->GetObjectClass(e,bld);
    jclass clsC=(*e)->FindClass(e,"java/lang/Class");
    jobject vm=(*e)->CallObjectMethod(e,bC,(*e)->GetMethodID(e,clsC,"getMethod","(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"),(*e)->NewStringUTF(e,"values"),NULL);
    jobject coll=(*e)->CallObjectMethod(e,vm,(*e)->GetMethodID(e,(*e)->FindClass(e,"java/lang/reflect/Method"),"invoke","(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),bld,NULL);
    L("[NATIVE] Done");
    fclose(lf);
    return coll;
}

/* SetIntField - 纯JNI写, Java调一次写一次 */
JNIEXPORT jint JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeSetIntField
    (JNIEnv *e, jclass cls, jobject obj, jstring fieldName, jint val) {
    const char*fn=(*e)->GetStringUTFChars(e,fieldName,NULL);
    jclass oc=(*e)->GetObjectClass(e,obj);
    jfieldID fid=(*e)->GetFieldID(e,oc,fn,"I");
    jint old=0;
    if(fid){ old=(*e)->GetIntField(e,obj,fid); (*e)->SetIntField(e,obj,fid,val); }
    (*e)->ReleaseStringUTFChars(e,fieldName,fn);
    return old;
}
