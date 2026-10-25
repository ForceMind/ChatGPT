#include <CoreGraphics/CoreGraphics.h>
#include <CoreFoundation/CoreFoundation.h>
#include <stdio.h>
#include <stdint.h>

/* Hosted CI only: allow real native windows to reach the tested desktop sizes. */
int main(int argc,char **argv) {
    CGDirectDisplayID display=CGMainDisplayID();
    if(argc>1){CGDisplayModeRef current=CGDisplayCopyDisplayMode(display);if(!current)return 1;size_t width=CGDisplayModeGetWidth(current),height=CGDisplayModeGetHeight(current);printf("CI session display: %zux%zu\n",width,height);CFRelease(current);return width>=1600&&height>=1080?0:1;}
    CFArrayRef modes=CGDisplayCopyAllDisplayModes(display,NULL);
    if(!modes){fputs("No CI display modes available\n",stderr);return 1;}
    CGDisplayModeRef chosen=NULL;uint64_t area=UINT64_MAX;
    for(CFIndex i=0;i<CFArrayGetCount(modes);i++){
        CGDisplayModeRef mode=(CGDisplayModeRef)CFArrayGetValueAtIndex(modes,i);
        size_t width=CGDisplayModeGetWidth(mode),height=CGDisplayModeGetHeight(mode);
        if(width>=1600&&height>=1080&&(uint64_t)width*height<area){chosen=mode;area=(uint64_t)width*height;}
    }
    if(!chosen){fputs("CI display cannot provide a 1600x1080 desktop; available modes:\n",stderr);for(CFIndex i=0;i<CFArrayGetCount(modes);i++){CGDisplayModeRef mode=(CGDisplayModeRef)CFArrayGetValueAtIndex(modes,i);fprintf(stderr,"%zux%zu\n",CGDisplayModeGetWidth(mode),CGDisplayModeGetHeight(mode));}CFRelease(modes);return 1;}
    /* Session scope survives this helper's exit on the disposable runner. */
    CGDisplayConfigRef configuration;
    CGError result=CGBeginDisplayConfiguration(&configuration);
    if(result==kCGErrorSuccess){result=CGConfigureDisplayWithDisplayMode(configuration,display,chosen,NULL);if(result==kCGErrorSuccess)result=CGCompleteDisplayConfiguration(configuration,kCGConfigureForSession);else CGCancelDisplayConfiguration(configuration);}
    printf("CI display: %zux%zu, result=%d\n",CGDisplayModeGetWidth(chosen),CGDisplayModeGetHeight(chosen),result);
    CFRelease(modes);return result==kCGErrorSuccess?0:1;
}
