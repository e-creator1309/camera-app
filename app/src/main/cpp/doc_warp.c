/*
    * doc_warp.c — full-resolution perspective warp for document scan crop.
    * Kotlin computes inverse homography via Matrix.invert()+getValues() and
    * passes the 9-float array here; for each dest pixel we invert to find the
    * source coords then bilinear-interpolate the result.
    */
    #ifndef DOC_WARP_C
    #define DOC_WARP_C
    #include <jni.h>
    #include <android/bitmap.h>
    #include <android/log.h>
    #include <stdint.h>
    #define LOG_TAG "CamImg"
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

    /* Android Matrix.getValues() row-major layout -> map (dx,dy) to (sx,sy). */
    static void dw_map(const jfloat *m,float dx,float dy,float *sx,float *sy){
      float w=m[6]*dx+m[7]*dy+m[8]; if(w==0.0f)w=1.0f;
      *sx=(m[0]*dx+m[1]*dy+m[2])/w; *sy=(m[3]*dx+m[4]*dy+m[5])/w;
    }
    /* Bilinear sample — treat all 4 bytes identically to avoid endian issues. */
    static uint32_t dw_sample(const uint32_t *p,int W,int H,float sx,float sy){
      if(sx<0.f)sx=0.f; if(sy<0.f)sy=0.f;
      if(sx>(float)(W-1))sx=(float)(W-1); if(sy>(float)(H-1))sy=(float)(H-1);
      int x0=(int)sx,y0=(int)sy,x1=x0+1<W?x0+1:x0,y1=y0+1<H?y0+1:y0;
      float fx=sx-(float)x0,fy=sy-(float)y0;
      const uint8_t *p00=(const uint8_t*)&p[y0*W+x0],*p10=(const uint8_t*)&p[y0*W+x1],
                    *p01=(const uint8_t*)&p[y1*W+x0],*p11=(const uint8_t*)&p[y1*W+x1];
      uint32_t out; uint8_t *o=(uint8_t*)&out;
      for(int c=0;c<4;c++){
          float v=p00[c]*(1.f-fx)*(1.f-fy)+p10[c]*fx*(1.f-fy)
                 +p01[c]*(1.f-fx)*fy      +p11[c]*fx*fy;
          o[c]=(uint8_t)(v+.5f);
      }
      return out;
    }
    JNIEXPORT jboolean JNICALL
    Java_com_replit_cameraapp_NativeImaging_warpDocumentNative(
          JNIEnv *env,jobject thiz,jobject src_bmp,jobject dst_bmp,jfloatArray inv_mat){
      AndroidBitmapInfo si,di; void *sp=NULL,*dp=NULL; jboolean ok=JNI_FALSE;
      if(AndroidBitmap_getInfo(env,src_bmp,&si)<0) return JNI_FALSE;
      if(AndroidBitmap_getInfo(env,dst_bmp,&di)<0) return JNI_FALSE;
      if(si.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||di.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
      if(AndroidBitmap_lockPixels(env,src_bmp,&sp)<0) return JNI_FALSE;
      if(AndroidBitmap_lockPixels(env,dst_bmp,&dp)<0){AndroidBitmap_unlockPixels(env,src_bmp);return JNI_FALSE;}
      jfloat *m=(*env)->GetFloatArrayElements(env,inv_mat,NULL);
      if(m){
          const uint32_t *src=(const uint32_t*)sp; uint32_t *dst=(uint32_t*)dp;
          int SW=(int)si.width,SH=(int)si.height,DW=(int)di.width,DH=(int)di.height;
          for(int dy=0;dy<DH;dy++) for(int dx=0;dx<DW;dx++){
              float sx,sy; dw_map(m,(float)dx,(float)dy,&sx,&sy);
              dst[dy*DW+dx]=dw_sample(src,SW,SH,sx,sy);
          }
          (*env)->ReleaseFloatArrayElements(env,inv_mat,m,JNI_ABORT); ok=JNI_TRUE;
      }
      AndroidBitmap_unlockPixels(env,dst_bmp); AndroidBitmap_unlockPixels(env,src_bmp);
      return ok;
    }
    #endif
    