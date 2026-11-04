#include <mgba/core/core.h>
#include <stdio.h>
#include <mgba/core/log.h>
#include <stdlib.h>
#include <string.h>
static struct mCore*c;
static void quiet(struct mLogger*l,int c,enum mLogLevel v,const char*f,va_list a){}
static struct mLogger logger={quiet,0};
static void run(int n){while(n--)c->runFrame(c);}
static void press(int k){c->setKeys(c,k);run(20);c->setKeys(c,0);run(20);}
static unsigned album;
static int matches(int index){unsigned offset=c->busRead32(c,album+16+index*36);for(unsigned i=0;i<38400;i++)if(c->busRead16(c,0x06000000+i*2)!=c->busRead16(c,album+offset+i*2))return 0;return 1;}
static unsigned hash(void){unsigned h=2166136261;for(unsigned a=0x06000000;a<0x06012c00;a+=2)h=(h^c->busRead16(c,a))*16777619;return h;}
static void ppm(const char*name){FILE*f=fopen(name,"wb");fprintf(f,"P6\n240 160\n255\n");for(unsigned a=0x06000000;a<0x06012c00;a+=2){unsigned v=c->busRead16(c,a);unsigned char p[3]={(v&31)*255/31,((v>>5)&31)*255/31,((v>>10)&31)*255/31};fwrite(p,1,3,f);}fclose(f);}
int main(int argc,char**argv){if(argc<2)return 2;mLogSetDefaultLogger(&logger);c=mCoreFind(argv[1]);if(!c||!c->init(c))return 3;mCoreInitConfig(c,"packer-test");color_t*video=calloc(240*160,sizeof(color_t));c->setVideoBuffer(c,video,240);if(!mCoreLoadFile(c,argv[1]))return 4;c->reset(c);run(120);for(unsigned a=0x080000c0;a<0x08010000;a+=4)if(c->busRead32(c,a)==0x50494353){album=a;break;}if(!album)return 5;unsigned help=hash();ppm("gba-help.ppm");press(1);unsigned menu=hash();ppm("gba-menu.ppm");press(1);unsigned photo=hash();int first_ok=matches(0);ppm("gba-photo.ppm");press(256);unsigned next=hash();int next_ok=matches(1);c->setKeys(c,2);run(3);c->setKeys(c,258);run(3);c->setKeys(c,2);run(3);c->setKeys(c,0);run(20);int b_skip_ok=matches(2);c->setKeys(c,1);run(3);c->setKeys(c,513);run(3);c->setKeys(c,0);run(20);int a_skip_ok=matches(1);press(2);unsigned back=hash();press(8);unsigned show=hash();run(310);unsigned later=hash();int slideshow_ok=matches(2);press(1);unsigned pause=hash();run(310);unsigned paused=hash();int ok=first_ok&&next_ok&&slideshow_ok&&b_skip_ok&&a_skip_ok&&help!=menu&&menu!=photo&&photo!=next&&back!=next&&show!=later&&pause==paused;printf("{\"pixel_exact_first\":%s,\"pixel_exact_next\":%s,\"pixel_exact_slideshow\":%s,\"skip_100\":%s,\"skip_10\":%s,\"pause\":%s,\"all_passed\":%s}\n",first_ok?"true":"false",next_ok?"true":"false",slideshow_ok?"true":"false",b_skip_ok?"true":"false",a_skip_ok?"true":"false",pause==paused?"true":"false",ok?"true":"false");c->deinit(c);free(video);return ok?0:1;}
