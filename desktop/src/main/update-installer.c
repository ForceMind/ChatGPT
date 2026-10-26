// macOS-only helper. The running app checkpoints first; this process waits for
// its exit, validates the staged bundle, then atomically exchanges directories.
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
#include <sys/stat.h>
#include <sys/stdio.h>
#include <fcntl.h>
#include <signal.h>
#include <spawn.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern char **environ;
static int real_directory(const char *p) {
 struct stat s; char real[PATH_MAX];
 return lstat(p,&s)==0&&S_ISDIR(s.st_mode)&&!S_ISLNK(s.st_mode)&&realpath(p,real)&&strcmp(p,real)==0;
}
static int same_parent(const char *a,const char *b) {
 const char *x=strrchr(a,'/'),*y=strrchr(b,'/');
 return x&&y&&x-a==y-b&&strncmp(a,b,(size_t)(x-a))==0;
}
static int bundle_valid(const char *p,const char *version) {
 int ok=0;CFURLRef url=CFURLCreateFromFileSystemRepresentation(NULL,(const UInt8*)p,(CFIndex)strlen(p),true);
 if(!url)return 0;CFDictionaryRef info=CFBundleCopyInfoDictionaryForURL(url);
 CFStringRef wanted=CFStringCreateWithCString(NULL,version,kCFStringEncodingUTF8);
 if(info&&wanted){CFTypeRef id=CFDictionaryGetValue(info,CFSTR("CFBundleIdentifier")),v=CFDictionaryGetValue(info,CFSTR("CFBundleShortVersionString"));
  if(id&&v&&CFEqual(id,CFSTR("com.forcemind.chatgpt.profiles"))&&CFEqual(v,wanted)){
   SecStaticCodeRef code=NULL;
   if(SecStaticCodeCreateWithPath(url,kSecCSDefaultFlags,&code)==errSecSuccess){ok=SecStaticCodeCheckValidity(code,kSecCSCheckAllArchitectures|kSecCSCheckNestedCode|kSecCSStrictValidate,NULL)==errSecSuccess;CFRelease(code);}
  }
 }
 if(wanted)CFRelease(wanted);if(info)CFRelease(info);CFRelease(url);return ok;
}
static void result(const char *file,int ok) {
 char temp[PATH_MAX];if(snprintf(temp,sizeof(temp),"%s.%d.tmp",file,getpid())>=(int)sizeof(temp))return;
 int fd=open(temp,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(fd<0)return;
 const char *body=ok?"{\"ok\":true}\n":"{\"ok\":false}\n";size_t n=strlen(body);
 if(write(fd,body,n)==(ssize_t)n&&fsync(fd)==0){close(fd);rename(temp,file);}else{close(fd);unlink(temp);}
}
static int open_bundle(const char *target,const char *mode) {
 pid_t child;char *normal[]={"/usr/bin/open",(char*)target,NULL};char *quiet[]={"/usr/bin/open","-g","-j",(char*)target,"--args","--profiles-background-update",NULL};char **args=strcmp(mode,"background")==0?quiet:normal;return posix_spawn(&child,args[0],NULL,NULL,args,environ);
}
static int migrate_name(int argc,char **argv) {
 if(argc!=8)return 2;
 char *end=NULL;long pid=strtol(argv[2],&end,10);if(!end||*end||pid<=1||pid>INT_MAX)return 2;
 const char *source=argv[3],*target=argv[4],*version=argv[5],*output=argv[6],*mode=argv[7];
 const char *a=strrchr(source,'/'),*b=strrchr(target,'/');struct stat s;int code=3;
 int can_result=lstat(output,&s)!=0&&errno==ENOENT;
 int ended=0;for(int n=0;n<600;n++){if(kill((pid_t)pid,0)!=0&&errno==ESRCH){ended=1;break;}usleep(200000);}
 if(!ended){code=4;goto failed;}
 if(!can_result||!a||!b||strcmp(a,"/ChatGPT Profiles.app")||strcmp(b,"/ChatGPT Studio.app")||!same_parent(source,target)||!real_directory(source)||!bundle_valid(source,version))goto failed;
 if(lstat(target,&s)==0||errno!=ENOENT)goto failed;
 // Never replace a second installation, including a symlink or a racing move.
 if(renameatx_np(AT_FDCWD,source,AT_FDCWD,target,RENAME_EXCL)!=0){code=5;goto failed;}
 if(!bundle_valid(target,version)){renameatx_np(AT_FDCWD,target,AT_FDCWD,source,RENAME_EXCL);code=6;goto failed;}
 result(output,1);
 if(strcmp(mode,"no")!=0&&open_bundle(target,mode)!=0)return 7;
 return 0;
failed:
 if(can_result)result(output,0);
 // Keep the old location usable if a permission error or competing install won.
 if(ended&&strcmp(mode,"no")!=0&&real_directory(source)&&bundle_valid(source,version))open_bundle(source,mode);
 return code;
}
int main(int argc,char **argv) {
 if(argc>1&&strcmp(argv[1],"--rename-bundle")==0)return migrate_name(argc,argv);
 if(argc!=8)return 2;
 char *end=NULL;long pid=strtol(argv[1],&end,10);if(!end||*end||pid<=1||pid>INT_MAX)return 2;
 const char *target=argv[2],*candidate=argv[3],*output=argv[4],*version=argv[5];int background=strcmp(argv[6],"background")==0;int restart=background||strcmp(argv[6],"yes")==0;
 // Do not allow a symlink result destination or candidates on another volume.
 struct stat old,next,out;int valid=lstat(output,&out)!=0&&errno==ENOENT;
 if(!valid||!real_directory(target)||!real_directory(candidate)||!same_parent(target,candidate)||lstat(target,&old)||lstat(candidate,&next)||old.st_dev!=next.st_dev){result(output,0);return 3;}
 int ended=0;for(int n=0;n<600;n++){if(kill((pid_t)pid,0)!=0&&errno==ESRCH){ended=1;break;}usleep(200000);}
 if(!ended||!real_directory(target)||!real_directory(candidate)||!bundle_valid(candidate,version)||!bundle_valid(target,argv[7])){result(output,0);return 4;}
 // RENAME_SWAP leaves either the whole old app or whole new app at target,
 // even if this helper is killed. The previous app is retained at candidate.
 if(renameatx_np(AT_FDCWD,target,AT_FDCWD,candidate,RENAME_SWAP)!=0){result(output,0);return 5;}
 if(!bundle_valid(target,version)){renameatx_np(AT_FDCWD,target,AT_FDCWD,candidate,RENAME_SWAP);result(output,0);return 6;}
 result(output,1);
 if(restart){pid_t child;char *normal[]={"/usr/bin/open",(char*)target,NULL};char *quiet[]={"/usr/bin/open","-g","-j",(char*)target,"--args","--profiles-background-update",NULL};char **args=background?quiet:normal;if(posix_spawn(&child,args[0],NULL,NULL,args,environ)!=0)return 7;}
 return 0;
}
