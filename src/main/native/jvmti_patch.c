/*
 * jvmti_patch.c - JVMTI patches
 *
 * 1. Drill classes: bipush 20 (MAX_PROGRESS) -> bipush 1
 * 2. ICustomRecipeLogicHolder machines: duration(N) -> duration(1)
 *    (sipush 400/600/6000, bipush 20 in createCustomRecipe -> sipush/bipush 1)
 * 3. RecipeLogic.setupRecipe injection: REMOVED (harmed standard machines)
 * 4. Diag dump
 */

#include <jvmti.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <time.h>

typedef unsigned char  ju1;
typedef unsigned short ju2;
typedef unsigned int   ju4;

static jvmtiEnv* g_jvmti = NULL;
static int g_jvmti_ready = 0;
static FILE* jvmti_lf = NULL;
static ju1* g_cached_patched = NULL;
static jint g_cached_patched_len = 0;
static int g_hook_fired = 0;
static int g_overclock_patch_active = 1;  /* 1=patch speed to 0.0 (1-tick), 0=leave speed at 1.0 (normal) */

static void jvmti_log(const char* fmt, ...) {
    char buf[2048];va_list a;va_start(a,fmt);vsnprintf(buf,sizeof(buf),fmt,a);va_end(a);
    printf("[JVMTI] %s\n",buf);
    if(!jvmti_lf)jvmti_lf=fopen("gtocutcorners_jvmti.log","a");
    if(jvmti_lf){fprintf(jvmti_lf,"[JVMTI] %s\n",buf);fflush(jvmti_lf);}
}
static ju2 rd_u2(const ju1* p){return((ju2)p[0]<<8)|p[1];}
static ju4 rd_u4(const ju1* p){return((ju4)p[0]<<24)|((ju4)p[1]<<16)|((ju4)p[2]<<8)|p[3];}
static void wr_u2(ju1* p,ju2 v){p[0]=(ju1)(v>>8);p[1]=(ju1)v;}
static void wr_u4(ju1* p,ju4 v){p[0]=(ju1)(v>>24);p[1]=(ju1)(v>>16);p[2]=(ju1)(v>>8);p[3]=(ju1)v;}

static int op_operand_len(ju1 op,const ju1* code,int code_len,int pos){switch(op){
case 0xaa:{int pad=(4-((pos+1)%4))%4;const ju1*p=code+pos+1+pad;if(pos+1+pad+12>code_len)return-1;int low=(int)rd_u4(p+4),high=(int)rd_u4(p+8);return pad+12+(high-low+1)*4;}
case 0xab:{int pad=(4-((pos+1)%4))%4;const ju1*p=code+pos+1+pad;if(pos+1+pad+8>code_len)return-1;int npairs=(int)rd_u4(p+4);return pad+8+npairs*8;}
case 0xc4:{if(pos+1>=code_len)return-1;if(code[pos+1]==0x84)return 5;return 3;}
case 0x10:case 0x12:case 0xbc:case 0xa9:case 0x15:case 0x16:case 0x17:case 0x18:case 0x19:case 0x36:case 0x37:case 0x38:case 0x39:case 0x3a:return 1;
case 0x11:case 0x13:case 0x14:case 0x84:case 0x99:case 0x9a:case 0x9b:case 0x9c:case 0x9d:case 0x9e:case 0x9f:case 0xa0:case 0xa1:case 0xa2:case 0xa3:case 0xa4:case 0xa5:case 0xa6:case 0xa7:case 0xa8:case 0xb2:case 0xb3:case 0xb4:case 0xb5:case 0xb6:case 0xb7:case 0xb8:case 0xbb:case 0xbd:case 0xc0:case 0xc1:case 0xc6:case 0xc7:return 2;
case 0xc5:return 3;case 0xc8:case 0xc9:case 0xb9:case 0xba:return 4;default:return 0;}}

static int cp_utf8(const ju1* data,jint len,ju2 idx,char* buf,int bsz){ju2 cc=rd_u2(data+8);if(idx==0||idx>=cc)return-1;int o=10;
for(ju2 i=1;i<idx;i++){if(o>=len)return-1;ju1 t=data[o];o++;switch(t){case 1:{ju2 sl=rd_u2(data+o);o+=2+sl;break;}case 3:case 4:o+=4;break;case 5:case 6:o+=8;i++;break;case 7:case 8:case 16:case 19:case 20:o+=2;break;case 9:case 10:case 11:case 12:case 17:case 18:o+=4;break;case 15:o+=3;break;default:return-1;}}
if(o>=len||data[o]!=1)return-1;ju2 sl=rd_u2(data+o+1);if(o+3+sl>len||sl>=bsz)return-1;memcpy(buf,data+o+3,sl);buf[sl]=0;return 0;}

static ju1 cp_tag_off(const ju1* data,jint len,ju2 idx,int* oo){ju2 cc=rd_u2(data+8);if(idx==0||idx>=cc)return 0;int o=10;
for(ju2 i=1;i<idx;i++){if(o>=len)return 0;ju1 t=data[o];o++;switch(t){case 1:{ju2 sl=rd_u2(data+o);o+=2+sl;break;}case 3:case 4:o+=4;break;case 5:case 6:o+=8;i++;break;case 7:case 8:case 16:case 19:case 20:o+=2;break;case 9:case 10:case 11:case 12:case 17:case 18:o+=4;break;case 15:o+=3;break;default:return 0;}}
if(o>=len)return 0;*oo=o;return data[o];}

static ju2 cp_find_fieldref(const ju1* data,jint len,const char*tc,const char*tn,const char*td){ju2 cc=rd_u2(data+8);int o=10;
for(ju2 i=1;i<cc;i++){if(o>=len)break;ju1 t=data[o];int es;switch(t){case 1:{ju2 sl=rd_u2(data+o+1);es=1+2+sl;break;}case 3:case 4:es=5;break;case 5:case 6:es=9;i++;break;case 7:case 8:case 16:case 19:case 20:es=3;break;case 9:case 10:case 11:case 12:case 17:case 18:es=5;break;case 15:es=4;break;default:return 0;}
if(t==9){ju2 ci=rd_u2(data+o+1),ni=rd_u2(data+o+3);int co;if(cp_tag_off(data,len,ci,&co)==7){ju2 nidx=rd_u2(data+co+1);char cn[512];if(cp_utf8(data,len,nidx,cn,sizeof(cn))==0&&strcmp(cn,tc)==0){int no;if(cp_tag_off(data,len,ni,&no)==12){ju2 fni=rd_u2(data+no+1),fdi=rd_u2(data+no+3);char fn[256],fd[64];if(cp_utf8(data,len,fni,fn,sizeof(fn))==0&&cp_utf8(data,len,fdi,fd,sizeof(fd))==0&&strcmp(fn,tn)==0&&strcmp(fd,td)==0)return i;}}}}o+=es;}return 0;}

static int calc_shift(int pos,const int* returns,int nr){int s=0;for(int i=0;i<nr;i++)if(returns[i]<=pos)s+=5;return s;}

static int vti_size(const ju1* p){if(p[0]==7||p[0]==8)return 3;return 1;}

static int fix_smt(const ju1* data,int dlen,const int* returns,int nr,ju1** ob){if(dlen<2)return 0;ju2 ne=rd_u2(data);int cap=dlen+ne*4+16;ju1*b=(ju1*)malloc(cap);if(!b)return 0;int bp=0;wr_u2(b+bp,ne);bp+=2;int poa=0,pna=0;const ju1*p=data+2;
for(int i=0;i<ne;i++){ju1 ft=p[0];int od;const ju1*vd=NULL;int vt=0,fto;if(ft<=63){od=ft;fto=1;}else if(ft<=127){od=ft-64;vd=p+1;vt=vti_size(vd);fto=1+vt;}else if(ft==247){od=rd_u2(p+1);vd=p+3;vt=vti_size(vd);fto=3+vt;}else if(ft>=248&&ft<=250){od=rd_u2(p+1);fto=3;}else if(ft==251){od=rd_u2(p+1);fto=3;}else if(ft>=252&&ft<=254){od=rd_u2(p+1);int vc=ft-251;vd=p+3;vt=0;for(int v=0;v<vc;v++)vt+=vti_size(vd+vt);fto=3+vt;}else if(ft==255){od=rd_u2(p+1);ju2 nl=rd_u2(p+3);const ju1*lp=p+5;int ls=0;for(int v=0;v<nl;v++)ls+=vti_size(lp+ls);ju2 ns=rd_u2(lp+ls);const ju1*sp=lp+ls+2;int ss=0;for(int v=0;v<ns;v++)ss+=vti_size(sp+ss);vd=p+3;vt=2+ls+2+ss;fto=3+vt;}else{free(b);return 0;}
int oa=(i==0)?od:poa+od+1;int sh=calc_shift(oa,returns,nr);int na=oa+sh;int nd=(i==0)?na:na-pna-1;
if(ft<=63){if(nd<=63)b[bp++]=(ju1)nd;else{b[bp++]=251;wr_u2(b+bp,(ju2)nd);bp+=2;}}else if(ft<=127){if(nd<=63){b[bp++]=(ju1)(64+nd);if(vt){memcpy(b+bp,vd,vt);bp+=vt;}}else{b[bp++]=247;wr_u2(b+bp,(ju2)nd);bp+=2;if(vt){memcpy(b+bp,vd,vt);bp+=vt;}}}else if(ft==247){b[bp++]=247;wr_u2(b+bp,(ju2)nd);bp+=2;if(vt){memcpy(b+bp,vd,vt);bp+=vt;}}else if(ft>=248&&ft<=254){b[bp++]=ft;wr_u2(b+bp,(ju2)nd);bp+=2;if(vt){memcpy(b+bp,vd,vt);bp+=vt;}}else{b[bp++]=255;wr_u2(b+bp,(ju2)nd);bp+=2;if(vt){memcpy(b+bp,vd,vt);bp+=vt;}}
poa=oa;pna=na;p+=fto;}
*ob=b;return bp;}

/* ========== RecipeLogic.setupRecipe patcher ========== */
static jint patch_setup_recipe(const ju1* class_data,jint class_data_len,ju1** out_data){
    ju2 fri=cp_find_fieldref(class_data,class_data_len,"com/gregtechceu/gtceu/api/machine/trait/RecipeLogic","duration","I");
    if(!fri)return 0;
    ju2 cc=rd_u2(class_data+8);int o=10;for(ju2 i=1;i<cc;i++){ju1 t=class_data[o];o++;switch(t){case 1:{ju2 sl=rd_u2(class_data+o);o+=2+sl;break;}case 3:case 4:o+=4;break;case 5:case 6:o+=8;i++;break;case 7:case 8:case 16:case 19:case 20:o+=2;break;case 9:case 10:case 11:case 12:case 17:case 18:o+=4;break;case 15:o+=3;break;}}
    o+=6;ju2 ifc=rd_u2(class_data+o);o+=2+ifc*2;ju2 fc=rd_u2(class_data+o);o+=2;for(ju2 f=0;f<fc;f++){o+=6;ju2 fa=rd_u2(class_data+o);o+=2;for(ju2 a=0;a<fa;a++){o+=2;ju4 al=rd_u4(class_data+o);o+=4+al;}}
    ju2 mc=rd_u2(class_data+o);o+=2;int tm=-1,te=-1;
    for(ju2 m=0;m<mc;m++){int ms=o;ju2 nidx=rd_u2(class_data+o+2);char mn[256];cp_utf8(class_data,class_data_len,nidx,mn,256);o+=6;ju2 ma=rd_u2(class_data+o);o+=2;for(ju2 a=0;a<ma;a++){o+=2;ju4 al=rd_u4(class_data+o);o+=4+al;}if(strcmp(mn,"setupRecipe")==0){tm=ms;te=o;}}
    if(tm<0)return 0;
    ju2 ma_cnt=rd_u2(class_data+tm+6);int ao=tm+8;int cao=-1;
    for(ju2 a=0;a<ma_cnt;a++){ju2 ani=rd_u2(class_data+ao);ju4 al=rd_u4(class_data+ao+2);char an[256];cp_utf8(class_data,class_data_len,ani,an,256);if(strcmp(an,"Code")==0)cao=ao;ao+=6+al;}
    if(cao<0)return 0;
    int coff=cao+6;ju2 ms2=rd_u2(class_data+coff);ju2 ml=rd_u2(class_data+coff+2);ju4 cl=rd_u4(class_data+coff+4);int cs=coff+8;const ju1* cd=class_data+cs;
    int returns[64],nr=0,po=0;while(po<(int)cl){ju1 op=cd[po];if(op==0xB1&&nr<64)returns[nr++]=po;int ol=op_operand_len(op,cd,(int)cl,po);if(ol<0)return 0;po+=1+ol;}
    if(!nr)return 0;
    int INJ=5;ju4 ncl=cl+nr*INJ;ju1*nc=(ju1*)malloc(ncl);int np=0,ri=0;
    for(int op_pos=0;op_pos<(int)cl;){ju1 op=cd[op_pos];int ol=op_operand_len(op,cd,(int)cl,op_pos);int il=1+ol;
        if(ri<nr&&op_pos==returns[ri]&&op==0xB1){nc[np++]=0x2A;nc[np++]=0x04;nc[np++]=0xB5;wr_u2(nc+np,fri);np+=2;ri++;}
        int br2=(op>=0x99&&op<=0xA8)||op==0xC6||op==0xC7,br4=op==0xC8||op==0xC9;
        if(br2){nc[np++]=op;int16_t oo=(int16_t)((cd[op_pos+1]<<8)|cd[op_pos+2]);int tg=op_pos+oo,sh=calc_shift(tg,returns,nr)-calc_shift(op_pos,returns,nr);oo+=sh;nc[np++]=(oo>>8)&0xFF;nc[np++]=oo&0xFF;}
        else if(br4){nc[np++]=op;int32_t oo=(int32_t)rd_u4(cd+op_pos+1);int tg=op_pos+oo,sh=calc_shift(tg,returns,nr)-calc_shift(op_pos,returns,nr);wr_u4(nc+np,(ju4)(oo+sh));np+=4;}
        else{memcpy(nc+np,cd+op_pos,il);np+=il;}op_pos+=il;}
    int es=cs+cl;ju2 el=rd_u2(class_data+es);int nes=2+el*8;ju1*ne=(ju1*)malloc(nes);wr_u2(ne,el);
    for(ju2 e=0;e<el;e++){const ju1*src=class_data+es+2+e*8;ju2 sp=rd_u2(src),ep=rd_u2(src+2),hp=rd_u2(src+4),ct=rd_u2(src+6);wr_u2(ne+2+e*8,sp+calc_shift(sp,returns,nr));wr_u2(ne+2+e*8+2,ep+calc_shift(ep,returns,nr));wr_u2(ne+2+e*8+4,hp+calc_shift(hp,returns,nr));wr_u2(ne+2+e*8+6,ct);}
    int so=es+2+el*8;ju2 sc=rd_u2(class_data+so);ju1*sb=(ju1*)malloc(512);int slen=0,scap=512;int sao=so+2;
    for(ju2 a=0;a<sc;a++){ju2 sni=rd_u2(class_data+sao);ju4 sal=rd_u4(class_data+sao+2);char sn[256];cp_utf8(class_data,class_data_len,sni,sn,256);
        if(strcmp(sn,"LineNumberTable")==0){ju2 lc=rd_u2(class_data+sao+6);int need=8+lc*4;while(slen+need>scap){scap*=2;sb=realloc(sb,scap);}wr_u2(sb+slen,sni);slen+=2;wr_u4(sb+slen,sal);slen+=4;wr_u2(sb+slen,lc);slen+=2;for(ju2 l=0;l<lc;l++){ju2 spc=rd_u2(class_data+sao+8+l*4),ln=rd_u2(class_data+sao+8+l*4+2);wr_u2(sb+slen,spc+calc_shift(spc,returns,nr));slen+=2;wr_u2(sb+slen,ln);slen+=2;}}
        else if(strcmp(sn,"StackMapTable")==0){ju1*smt=NULL;int smtl=fix_smt(class_data+sao+6,sal,returns,nr,&smt);if(smt&&smtl>0){int need=6+smtl;while(slen+need>scap){scap*=2;sb=realloc(sb,scap);}wr_u2(sb+slen,sni);slen+=2;wr_u4(sb+slen,smtl);slen+=4;memcpy(sb+slen,smt,smtl);slen+=smtl;free(smt);}else{int need=6+sal;while(slen+need>scap){scap*=2;sb=realloc(sb,scap);}memcpy(sb+slen,class_data+sao,need);slen+=need;}}
        else{int need=6+sal;while(slen+need>scap){scap*=2;sb=realloc(sb,scap);}memcpy(sb+slen,class_data+sao,need);slen+=need;}sao+=6+sal;}
    ju2 nms=ms2<2?2:ms2;ju4 ncal=2+2+4+ncl+nes+2+slen;int ncat=6+ncal;ju1*nca=(ju1*)malloc(ncat);int cp=0;
    wr_u2(nca+cp,rd_u2(class_data+cao));cp+=2;wr_u4(nca+cp,ncal);cp+=4;wr_u2(nca+cp,nms);cp+=2;wr_u2(nca+cp,ml);cp+=2;wr_u4(nca+cp,ncl);cp+=4;
    memcpy(nca+cp,nc,ncl);cp+=ncl;memcpy(nca+cp,ne,nes);cp+=nes;wr_u2(nca+cp,sc);cp+=2;if(slen)memcpy(nca+cp,sb,slen);cp+=slen;
    int bc=cao-tm-8,ocat=6+(int)rd_u4(class_data+cao+2),acs=cao+ocat,aess=te-acs;
    int nmsz=8+bc+ncat+aess;ju1*nm=(ju1*)malloc(nmsz);int mp=0;
    memcpy(nm,class_data+tm,8);mp+=8;if(bc>0){memcpy(nm+mp,class_data+tm+8,bc);mp+=bc;}memcpy(nm+mp,nca,ncat);mp+=ncat;if(aess>0){memcpy(nm+mp,class_data+acs,aess);mp+=aess;}
    int before=tm,after=class_data_len-te,total=before+nmsz+after;ju1*res=(ju1*)malloc(total);int rp=0;
    memcpy(res,class_data,before);rp+=before;memcpy(res+rp,nm,nmsz);rp+=nmsz;memcpy(res+rp,class_data+te,after);rp+=after;
    free(nc);free(ne);free(sb);free(nca);free(nm);*out_data=res;return rp;
}

/* ========== ClassFileLoadHook ========== */
static void JNICALL jvmti_class_file_load_hook(
        jvmtiEnv* jt_env,JNIEnv* jni_env,jclass cr,jobject ld,
        const char* name,jobject pd,jint dlen,const unsigned char* data,
        jint* ndlen,unsigned char** ndata){
    if(!name)return;

    /* 1. Drill classes: bipush 20 -> bipush 1 */
    const char* drills[]={"com/gtocore/common/machine/trait/INFFluidDrillLogic",
                          "com/gtocore/common/machine/trait/AdvancedInfiniteDrillLogic",
                          "com/gregtechceu/gtceu/common/machine/trait/FluidDrillLogic",NULL};
    for(const char**d=drills;*d;d++)if(strcmp(name,*d)==0){
        jvmti_log("=== %s: bipush 20->1 ===",*d);
        int rp=0;ju1*mod=(ju1*)malloc(dlen);memcpy(mod,data,dlen);
        for(int i=0;i<dlen-1;i++)if(mod[i]==0x10&&mod[i+1]==0x14){mod[i+1]=0x01;rp++;}
        if(rp>0){ju1*jb;jvmtiError e=(*jt_env)->Allocate(jt_env,dlen,&jb);if(e==JVMTI_ERROR_NONE){memcpy(jb,mod,dlen);*ndlen=dlen;*ndata=jb;}}
        free(mod);return;
    }

    /* 1b. ICustomRecipeLogicHolder machines: patch createCustomRecipe duration constants -> 1 */
    {
        const char* dur20[]={
            "com/gtocore/common/machine/multiblock/electric/ChiselMachine",
            "com/gtocore/common/machine/multiblock/electric/FishingGroundMachine",
            "com/gtocore/common/machine/multiblock/electric/VirtualCoinMiner",
            "com/gtocore/common/machine/multiblock/electric/voidseries/StarcoreMinerMachine",
            "com/gtocore/common/machine/multiblock/electric/space/DysonSphereReceivingStationMcahine",
        NULL};
        const char* dur400[]={
            "com/gtocore/common/machine/multiblock/electric/BlockConversionRoomMachine",
        NULL};
        const char* dur600[]={
            "com/gtocore/common/machine/multiblock/electric/adventure/SlaughterhouseMachine",
        NULL};
        const char* dur6000[]={
            "com/gtocore/common/machine/multiblock/electric/space/SatelliteControlCenterMachine",
        NULL};

        int matched=0, rp=0;
        ju1*mod=(ju1*)malloc(dlen);
        memcpy(mod,data,dlen);

        /* bipush 20 -> bipush 1 (same as drills) */
        for(const char**t=dur20;*t;t++)if(strcmp(name,*t)==0){matched=1;break;}
        if(matched){
            jvmti_log("=== %s: bipush 20->1 ===",name);
            for(int i=0;i<dlen-1;i++)if(mod[i]==0x10&&mod[i+1]==0x14){mod[i+1]=0x01;rp++;}
        }
        /* sipush 400 -> sipush 1 */
        if(!matched){for(const char**t=dur400;*t;t++)if(strcmp(name,*t)==0){matched=2;break;}}
        if(matched==2){
            jvmti_log("=== %s: sipush 400->1 ===",name);
            for(int i=0;i<dlen-2;i++)if(mod[i]==0x11&&mod[i+1]==0x01&&mod[i+2]==0x90){mod[i+1]=0x00;mod[i+2]=0x01;rp++;}
        }
        /* sipush 600 -> sipush 1 */
        if(!matched){for(const char**t=dur600;*t;t++)if(strcmp(name,*t)==0){matched=3;break;}}
        if(matched==3){
            jvmti_log("=== %s: sipush 600->1 ===",name);
            for(int i=0;i<dlen-2;i++)if(mod[i]==0x11&&mod[i+1]==0x02&&mod[i+2]==0x58){mod[i+1]=0x00;mod[i+2]=0x01;rp++;}
        }
        /* sipush 6000 -> sipush 1 */
        if(!matched){for(const char**t=dur6000;*t;t++)if(strcmp(name,*t)==0){matched=4;break;}}
        if(matched==4){
            jvmti_log("=== %s: sipush 6000->1 ===",name);
            for(int i=0;i<dlen-2;i++)if(mod[i]==0x11&&mod[i+1]==0x17&&mod[i+2]==0x70){mod[i+1]=0x00;mod[i+2]=0x01;rp++;}
        }

        if(matched&&rp>0){
            ju1*jb;jvmtiError e=(*jt_env)->Allocate(jt_env,dlen,&jb);
            if(e==JVMTI_ERROR_NONE){memcpy(jb,mod,dlen);*ndlen=dlen;*ndata=jb;}
        }
        free(mod);
        if(matched)return;
    }

    /* 1c. RecipeModifier OVERCLOCKING: change speed=1.0 to speed=0.0
     *   In the 3-arg overclocking method (public API entry):
     *     dconst_1 (speed=1.0) at code[5] -> dconst_0 (speed=0.0)
     *   This makes duration = recipe.duration * 0.0 = 0 in the inner
     *   10-arg method, triggering the else-branch -> recipe.duration = 1.
     *   Single-byte change. No StackMapTable/attribute fixes needed.
     */
    if(strcmp(name,"com/gregtechceu/gtceu/api/recipe/modifier/RecipeModifier")==0){
        if(!g_overclock_patch_active){
            jvmti_log("=== RecipeModifier: hook fired but g_overclock_patch_active=0, skipping ===");
            goto rm_skip;
        }
        jvmti_log("=== RecipeModifier: hook fired ===");
        ju2 cc=rd_u2(data+8);int o=10;
        jvmti_log("RM: cp_count=%d dlen=%d",(int)cc,dlen);
        /* walk constant pool */
        for(ju2 i=1;i<cc;i++){
            if(o>=dlen){jvmti_log("RM: CP overflow at idx %d",i);goto rm_done;}
            ju1 t=data[o];
            switch(t){
                case 1:{ju2 sl=rd_u2(data+o+1);o+=1+2+sl;break;}
                case 3:case 4:o+=5;break;
                case 5:case 6:o+=9;i++;break;
                case 7:case 8:case 16:case 19:case 20:o+=3;break;
                case 9:case 10:case 11:case 12:case 17:case 18:o+=5;break;
                case 15:o+=4;break;
                default:jvmti_log("RM: bad CP tag %d at idx %d",t,i);goto rm_done;
            }
        }
        /* skip access_flags+this_class+super_class+interfaces */
        if(o+8>dlen){jvmti_log("RM: class header past end");goto rm_done;}
        o+=2+2+2; ju2 ic=rd_u2(data+o); o+=2+ic*2;
        /* skip fields */
        if(o+2>dlen){jvmti_log("RM: fields header past end");goto rm_done;}
        ju2 fc=rd_u2(data+o);o+=2;
        for(ju2 f=0;f<fc;f++){o+=6;ju2 fa=rd_u2(data+o);o+=2;for(ju2 a=0;a<fa;a++){ju4 al=rd_u4(data+o+2);o+=6+al;}}
        /* methods */
        if(o+2>dlen){jvmti_log("RM: methods header past end");goto rm_done;}
        ju2 mc=rd_u2(data+o);o+=2;
        jvmti_log("RM: scanning %d methods",(int)mc);
        int rm_found=0;
        for(ju2 m=0;m<mc&&!rm_found;m++){
            int ms=o;
            ju2 ma_flags=rd_u2(data+o);o+=2;
            ju2 ni=rd_u2(data+o);o+=2;
            ju2 di=rd_u2(data+o);o+=2;
            ju2 ac=rd_u2(data+o);o+=2;
            char mn[256],md[512];
            cp_utf8(data,dlen,ni,mn,sizeof(mn));
            cp_utf8(data,dlen,di,md,sizeof(md));
            if(strcmp(mn,"overclocking")==0){
                int is_static=(ma_flags&0x0008)!=0;
                jvmti_log("RM: method[%d] overclocking static=%d desc=%s",(int)m,is_static,md);
                if(is_static && strstr(md,"IRecipeHandlerHolder") && strstr(md,"RecipeHandlerUnit")
                    && strstr(md,"GTRecipe") && !strstr(md,"ZDDD")){
                    jvmti_log("RM: *** TARGET: public 3-arg overclocking found! ***");
                    int ao=o;
                    for(ju2 a=0;a<ac;a++){
                        ju2 ani=rd_u2(data+ao);ju4 al=rd_u4(data+ao+2);
                        char an[256];cp_utf8(data,dlen,ani,an,sizeof(an));
                        if(strcmp(an,"Code")==0){
                            int c_off=ao+6;
                            ju2 mx_stk=rd_u2(data+c_off);
                            ju2 mx_loc=rd_u2(data+c_off+2);
                            ju4 cl=rd_u4(data+c_off+4);
                            int cs=c_off+8;
                            jvmti_log("RM: Code len=%d stack=%d locals=%d",(int)cl,(int)mx_stk,(int)mx_loc);
                            jvmti_log("RM: code[0..12]=%02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                                data[cs],data[cs+1],data[cs+2],data[cs+3],data[cs+4],data[cs+5],
                                data[cs+6],data[cs+7],data[cs+8],data[cs+9],data[cs+10],data[cs+11],data[cs+12]);
                            if(cl>=13){
                                jvmti_log("RM: code[3]=0x%02X code[4]=0x%02X code[5]=0x%02X code[6]=0x%02X",
                                    data[cs+3],data[cs+4],data[cs+5],data[cs+6]);
                                if(data[cs+5]==0x0F){
                                    jvmti_log("RM: code[5]==0x0F (dconst_1=speed=1.0) -> patching to 0x0E (dconst_0=speed=0.0)");
                                    ju1*mod=(ju1*)malloc(dlen);memcpy(mod,data,dlen);
                                    mod[cs+5]=0x0E;
                                    ju1*jb;jvmtiError err=(*jt_env)->Allocate(jt_env,dlen,&jb);
                                    if(err==JVMTI_ERROR_NONE){memcpy(jb,mod,dlen);*ndlen=dlen;*ndata=jb;
                                        jvmti_log("RM: *** PATCH APPLIED speed=1.0->0.0 (duration will be forced to 1) ***");
                                        rm_found=1;
                                    }else{jvmti_log("RM: Allocate failed err=%d",(int)err);}
                                    free(mod);
                                }else{jvmti_log("RM: code[5]=0x%02X != 0x0F, not dconst_1? skip",data[cs+5]);}
                            }else{jvmti_log("RM: Code too short: %d < 13",(int)cl);}
                            break;
                        }
                        ao+=6+al;
                    }
                }
            }
            /* skip method attributes */
            for(ju2 a=0;a<ac;a++){ju4 al=rd_u4(data+o+2);o+=6+al;}
        }
        if(!rm_found)jvmti_log("RM: 3-arg overclocking NOT patched (not found / already modified / wrong layout)");
        else jvmti_log("RM: patch OK, returning modified bytecode");
        if(rm_found)return;
    }
rm_skip:
    jvmti_log("RM: overclock patch disabled, class passed through unchanged");
rm_done:
    if(strcmp(name,"com/gregtechceu/gtceu/api/recipe/modifier/RecipeModifier")==0)
        jvmti_log("RM: parse aborted (class format unexpected)");

    /* 2. RecipeLogic.setupRecipe injection - REMOVED:
 * This global injection harmed standard machines (boilers, heaters)
 * while being ineffective for ICustomRecipeLogicHolder machines.
 * All duration patching is handled per-class in sections 1 & 1b.
 */
    return;
}

/* ========== JNI: toggle overclock patch ========== */
JNIEXPORT void JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeSetOverclockPatchEnabled
    (JNIEnv* env, jclass cls, jboolean enabled){
    g_overclock_patch_active = enabled ? 1 : 0;
    jvmti_log("nativeSetOverclockPatchEnabled: %s", enabled ? "ON (speed->0.0, 1-tick)" : "OFF (speed=1.0, normal)");
}

/* ========== JVMTI init ========== */
static int init_jvmti(JavaVM*vm){
    jvmti_log("=== init_jvmti ===");jvmtiEnv*jt=NULL;
    if((*vm)->GetEnv(vm,(void**)&jt,JVMTI_VERSION_1_0)!=JNI_OK||!jt)return 0;
    jvmtiCapabilities cp;memset(&cp,0,sizeof(cp));cp.can_redefine_classes=1;cp.can_retransform_classes=1;cp.can_generate_all_class_hook_events=1;cp.can_get_bytecodes=1;
    if((*jt)->AddCapabilities(jt,&cp)!=JVMTI_ERROR_NONE)return 0;
    jvmtiEventCallbacks cb;memset(&cb,0,sizeof(cb));cb.ClassFileLoadHook=&jvmti_class_file_load_hook;
    if((*jt)->SetEventCallbacks(jt,&cb,sizeof(cb))!=JVMTI_ERROR_NONE)return 0;
    if((*jt)->SetEventNotificationMode(jt,JVMTI_ENABLE,JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,NULL)!=JVMTI_ERROR_NONE)return 0;
    g_jvmti=jt;g_jvmti_ready=1;return 1;
}

/* ========== Redefine loaded ========== */
static void redefine_loaded_classes(JNIEnv*env){
    if(!g_jvmti_ready)return;
    /* RecipeLogic retransform removed */
    const char* drills[]={"com/gtocore/common/machine/trait/INFFluidDrillLogic","com/gtocore/common/machine/trait/AdvancedInfiniteDrillLogic","com/gregtechceu/gtceu/common/machine/trait/FluidDrillLogic",NULL};
    for(const char**d=drills;*d;d++){jclass c=(*env)->FindClass(env,*d);if(c){jvmtiError e=(*g_jvmti)->RetransformClasses(g_jvmti,1,&c);jvmti_log("Drill %s: %s",*d,e==JVMTI_ERROR_NONE?"OK":"FAIL");(*env)->DeleteLocalRef(env,c);}else(*env)->ExceptionClear(env);}
    const char* dur_targets[]={
        "com/gtocore/common/machine/multiblock/electric/ChiselMachine",
        "com/gtocore/common/machine/multiblock/electric/FishingGroundMachine",
        "com/gtocore/common/machine/multiblock/electric/VirtualCoinMiner",
        "com/gtocore/common/machine/multiblock/electric/voidseries/StarcoreMinerMachine",
        "com/gtocore/common/machine/multiblock/electric/space/DysonSphereReceivingStationMcahine",
        "com/gtocore/common/machine/multiblock/electric/BlockConversionRoomMachine",
        "com/gtocore/common/machine/multiblock/electric/adventure/SlaughterhouseMachine",
        "com/gtocore/common/machine/multiblock/electric/space/SatelliteControlCenterMachine",
    NULL};
    for(const char**t=dur_targets;*t;t++){jclass c=(*env)->FindClass(env,*t);if(c){jvmtiError e=(*g_jvmti)->RetransformClasses(g_jvmti,1,&c);jvmti_log("Duration %s: %s",*t,e==JVMTI_ERROR_NONE?"OK":"FAIL");(*env)->DeleteLocalRef(env,c);}else(*env)->ExceptionClear(env);}
    /* 1c. RecipeModifier retransform for overclocking patch */
    {
        const char* rm="com/gregtechceu/gtceu/api/recipe/modifier/RecipeModifier";
        jclass c=(*env)->FindClass(env,rm);
        if(c){jvmtiError e=(*g_jvmti)->RetransformClasses(g_jvmti,1,&c);
            jvmti_log("RecipeModifier retransform: %s",e==JVMTI_ERROR_NONE?"OK":"FAIL");
            (*env)->DeleteLocalRef(env,c);
        }else{(*env)->ExceptionClear(env);jvmti_log("RecipeModifier: FindClass failed (may not be loaded yet)");}
    }
}

/* ========== JNI ========== */
JNIEXPORT void JNICALL Java_com_gtocutcorners_GTOCutCorners_nativeInitJVMTI(JNIEnv*env,jclass c){redefine_loaded_classes(env);}
