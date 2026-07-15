/*
    * portrait_blur.c — 3-pass separable box blur (~Gaussian) and native pixel blend.
    * stackBlurNative : blurs RGBA_8888 Bitmap in-place at full photo resolution.
    * blendPixelsNative: composites sharp+blurred Java-ARGB arrays via confidence weights.
    */
    #ifndef PORTRAIT_BLUR_C
    #define PORTRAIT_BLUR_C
    #include <jni.h>
    #include <android/bitmap.h>
    #include <android/log.h>
    #include <stdint.h>
    #include <stdlib.h>
    #define LOG_TAG "CamImg"
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

    static void pb_box_h(const uint32_t *src,uint32_t *dst,int W,int H,int r){
      int ks=2*r+1;
      for(int y=0;y<H;y++){
          int s[4]={0,0,0,0};
          for(int x=-r;x<=r;x++){int cx=x<0?0:(x>=W?W-1:x);const uint8_t*p=(const uint8_t*)&src[y*W+cx];for(int c=0;c<4;c++)s[c]+=p[c];}
          for(int x=0;x<W;x++){
              uint8_t*o=(uint8_t*)&dst[y*W+x]; for(int c=0;c<4;c++)o[c]=(uint8_t)(s[c]/ks);
              int ox=x-r<0?0:x-r,ix=x+r+1>=W?W-1:x+r+1;
              const uint8_t*po=(const uint8_t*)&src[y*W+ox],*pi=(const uint8_t*)&src[y*W+ix];
              for(int c=0;c<4;c++)s[c]+=pi[c]-po[c];
          }
      }
    }
    static void pb_box_v(const uint32_t *src,uint32_t *dst,int W,int H,int r){
      int ks=2*r+1;
      for(int x=0;x<W;x++){
          int s[4]={0,0,0,0};
          for(int y=-r;y<=r;y++){int cy=y<0?0:(y>=H?H-1:y);const uint8_t*p=(const uint8_t*)&src[cy*W+x];for(int c=0;c<4;c++)s[c]+=p[c];}
          for(int y=0;y<H;y++){
              uint8_t*o=(uint8_t*)&dst[y*W+x]; for(int c=0;c<4;c++)o[c]=(uint8_t)(s[c]/ks);
              int oy=y-r<0?0:y-r,iy=y+r+1>=H?H-1:y+r+1;
              const uint8_t*po=(const uint8_t*)&src[oy*W+x],*pi=(const uint8_t*)&src[iy*W+x];
              for(int c=0;c<4;c++)s[c]+=pi[c]-po[c];
          }
      }
    }
    JNIEXPORT void JNICALL
    Java_com_replit_cameraapp_NativeImaging_stackBlurNative(JNIEnv *env,jobject thiz,jobject bmp,jint radius){
      AndroidBitmapInfo info; void *pixels=NULL;
      if(AndroidBitmap_getInfo(env,bmp,&info)<0) return;
      if(info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) return;
      if(AndroidBitmap_lockPixels(env,bmp,&pixels)<0) return;
      int W=(int)info.width,H=(int)info.height,r=(int)radius;
      if(r<1)r=1; if(r>250)r=250;
      uint32_t *buf=(uint32_t*)malloc((size_t)W*H*4);
      if(buf){uint32_t *pix=(uint32_t*)pixels; for(int p=0;p<3;p++){pb_box_h(pix,buf,W,H,r);pb_box_v(buf,pix,W,H,r);} free(buf);}
      AndroidBitmap_unlockPixels(env,bmp);
    }
    JNIEXPORT void JNICALL
    Java_com_replit_cameraapp_NativeImaging_blendPixelsNative(
          JNIEnv *env,jobject thiz,
          jintArray sa,jintArray ba,jfloatArray ca,jintArray oa,jint count){
      jint *sh=(*env)->GetIntArrayElements(env,sa,NULL),*bl=(*env)->GetIntArrayElements(env,ba,NULL);
      jfloat *co=(*env)->GetFloatArrayElements(env,ca,NULL);
      jint *out=(*env)->GetIntArrayElements(env,oa,NULL);
      if(sh&&bl&&co&&out){
          for(int i=0;i<(int)count;i++){
              float c=co[i]; if(c<0.f)c=0.f; else if(c>1.f)c=1.f; float bc=1.f-c;
              jint s=sh[i],b=bl[i];
              int a=(s>>24)&0xFF,r=(int)(((s>>16)&0xFF)*c+((b>>16)&0xFF)*bc+.5f),
                  g=(int)(((s>>8)&0xFF)*c+((b>>8)&0xFF)*bc+.5f),bv=(int)((s&0xFF)*c+(b&0xFF)*bc+.5f);
              if(r>255)r=255; if(g>255)g=255; if(bv>255)bv=255;
              out[i]=(a<<24)|(r<<16)|(g<<8)|bv;
          }
      }
      if(out) (*env)->ReleaseIntArrayElements(env,oa,out,0);
      if(co)  (*env)->ReleaseFloatArrayElements(env,ca,co,JNI_ABORT);
      if(bl)  (*env)->ReleaseIntArrayElements(env,ba,bl,JNI_ABORT);
      if(sh)  (*env)->ReleaseIntArrayElements(env,sa,sh,JNI_ABORT);
    }
    #endif
    