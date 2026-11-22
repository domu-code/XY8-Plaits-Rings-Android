#include <jni.h>
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <memory>
#include <array>
#include <cstring>
#include "stmlib/utils/buffer_allocator.h"
#include "plaits/dsp/voice.h"
#include "rings/dsp/part.h"

namespace {
constexpr int kRate=48000;
constexpr int kVoices=16;
constexpr int kBlock=12;
constexpr float kPi=3.14159265358979323846f;
std::mutex audioMutex;
float clip(float x){return std::max(0.0f,std::min(1.0f,x));}

struct Patch {
    int engine=0,plaitsModel=8,ringsModel=0;
    float harmonics=0.5f,timbre=0.5f,morph=0.5f,decay=0.5f,lpg=0.5f;
    float structure=0.5f,brightness=0.6f,damping=0.6f,position=0.3f;
    float gain=0.7f,aux=0.15f;
};
struct NoteVoice {
    bool used=false,gate=false,first=true;
    int layer=0,note=60,yMode=0;
    float velocity=0.8f,expression=0.5f,release=1.0f,vibrato=0.0f,lowpass=0.0f;
    int offSamples=0;
    std::array<char,16384> workspace{};
    std::array<uint16_t,32768> reverb{};
    plaits::Voice plaitsVoice;
    rings::Part ringsPart;
    plaits::Voice::Frame frame[kBlock]{};
    float in[kBlock]{},out[kBlock]{},aux[kBlock]{};
    void init(){
        stmlib::BufferAllocator allocator(workspace.data(),workspace.size());
        plaitsVoice.Init(&allocator);
        ringsPart.Init(reverb.data());
        ringsPart.set_polyphony(1);
    }
};
std::array<Patch,4> patches;
std::array<std::unique_ptr<NoteVoice>,kVoices> voices;
struct Tape {
    float mix=0.0f,drive=0.3f,wow=0.1f,flutter=0.12f,hiss=0.0f,wear=0.0f,delay=0.0f;
    std::array<float,65536> line{};
    unsigned int phase=0,random=0x71c629af;
    float wowPhase=0.0f,flutterPhase=0.0f,drop=1.0f,age=0.0f;
    float noise(){random^=random<<13;random^=random>>17;random^=random<<5;return (float)(random&65535)/32767.5f-1.0f;}
    float apply(float input){
        if(mix<=0.00001f)return input;
        wowPhase+=2*kPi*0.38f/kRate;flutterPhase+=2*kPi*5.4f/kRate;
        if(wowPhase>2*kPi)wowPhase-=2*kPi;
        if(flutterPhase>2*kPi)flutterPhase-=2*kPi;
        float wowDelay=180.0f+wow*340.0f*std::sin(wowPhase)+flutter*29.0f*std::sin(flutterPhase);
        wowDelay=std::max(2.0f,wowDelay);
        float read=(float)phase-wowDelay;
        while(read<0)read+=line.size();
        int a=(int)read&65535,b=(a+1)&65535;
        float delayed=line[a]+(line[b]-line[a])*(read-(int)read);
        float tap=line[(phase+65536-12000)&65535];
        float saturated=std::tanh((input+delayed*0.19f+delay*tap*0.37f)*(1.0f+drive*6.0f));
        if((phase&511)==0&&wear>0){float r=noise();if(r>0.98f-wear*0.09f)drop=0.08f;}
        drop+=(1.0f-drop)*(0.0006f+wear*0.002f);
        age+=0.016f*((saturated-age));
        float degraded=(saturated*(1.0f-wear*0.35f)+age*wear*0.35f)*drop+hiss*0.027f*noise();
        line[phase&65535]=input+tap*delay*0.65f;
        phase=(phase+1)&65535;
        return input*(1-mix)+degraded*mix;
    }
} tape;

void renderVoice(NoteVoice& v,const Patch& patch,float* accumulator,int frames){
    if(patch.engine==0){
        plaits::Patch pp{};
        pp.note=0;pp.engine=patch.plaitsModel;pp.harmonics=patch.harmonics;
        pp.timbre=patch.timbre;pp.morph=patch.morph;
        pp.decay=v.yMode==2?v.expression:patch.decay;pp.lpg_colour=patch.lpg;
        if(v.yMode==4)pp.harmonics=v.expression;
        plaits::Modulations m{};
        m.note=(float)v.note;m.trigger=v.gate?1.0f:0.0f;
        m.trigger_patched=true;
        m.level=v.velocity;
        m.level_patched=true;
        if(v.yMode==6){m.timbre_patched=true;m.timbre=v.expression*2.0f-1.0f;}
        if(v.yMode==7){m.morph_patched=true;m.morph=v.expression*2.0f-1.0f;}
        if(v.yMode==8)m.harmonics=v.expression*2.0f-1.0f;
        if(v.yMode==3){
            m.note+=std::sin(v.vibrato)*v.expression*1.7f;
            v.vibrato+=2*kPi*5.0f*frames/kRate;
            if(v.vibrato>2*kPi)v.vibrato-=2*kPi;
        }
        v.plaitsVoice.Render(pp,m,v.frame,frames);
        for(int j=0;j<frames;j++){
            float signal=(v.frame[j].out*(1.0f-patch.aux)+v.frame[j].aux*patch.aux)/32768.0f;
            if(v.yMode==0){
                float cutoff=100.0f+v.expression*v.expression*14500.0f;
                float alpha=std::min(0.95f,2.0f*kPi*cutoff/kRate);
                v.lowpass+=alpha*(signal-v.lowpass);signal=v.lowpass;
            }
            accumulator[j]+=signal*patch.gain*0.36f*v.release;
        }
    }else{
        rings::Patch rp{};
        rp.structure=patch.structure;
        rp.brightness=(v.yMode==11||v.yMode==0?v.expression:patch.brightness);
        rp.damping=(v.yMode==9||v.yMode==2?v.expression:patch.damping);
        rp.position=(v.yMode==10?v.expression:patch.position);
        rings::PerformanceState state{};
        state.strum=v.first;state.internal_exciter=true;state.internal_strum=true;state.internal_note=true;
        state.note=(float)v.note;state.tonic=(float)v.note;state.fm=0;state.chord=0;
        v.ringsPart.set_model(static_cast<rings::ResonatorModel>(patch.ringsModel));
        std::memset(v.in,0,sizeof(v.in));
        v.ringsPart.Process(state,rp,v.in,v.out,v.aux,frames);
        for(int j=0;j<frames;j++)accumulator[j]+=
            (v.out[j]*(1.0f-patch.aux)+v.aux[j]*patch.aux)*patch.gain*0.55f*v.release;
    }
    v.first=false;
    if(!v.gate){
        v.offSamples+=frames;
        if(patch.engine==0)v.release*=0.96f;
        else if(v.offSamples>kRate*2)v.release*=0.985f;
        if(v.release<0.001f||v.offSamples>kRate*8)v.used=false;
    }
}
void reset(){
    for(auto& v:voices){v.reset(new NoteVoice());v->init();}
    for(auto& p:patches)p=Patch{};
    tape=Tape{};
}
}

extern "C" {
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeInit(JNIEnv*,jclass){std::lock_guard<std::mutex> guard(audioMutex);reset();}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeDestroy(JNIEnv*,jclass){
    std::lock_guard<std::mutex> guard(audioMutex);
    for(auto& v:voices)v.reset();
}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativePatch(JNIEnv*,jclass,jint layer,jint engine,jint pModel,jint rModel,
    jfloat harmonics,jfloat timbre,jfloat morph,jfloat decay,jfloat lpg,
    jfloat structure,jfloat brightness,jfloat damping,jfloat position,jfloat gain,jfloat aux){
    std::lock_guard<std::mutex> guard(audioMutex);
    if(layer<0||layer>=4)return;
    Patch& p=patches[layer];p.engine=engine==1?1:0;
    p.plaitsModel=std::max(0,std::min(23,(int)pModel));
    p.ringsModel=std::max(0,std::min(5,(int)rModel));
    p.harmonics=clip(harmonics);p.timbre=clip(timbre);p.morph=clip(morph);
    p.decay=clip(decay);p.lpg=clip(lpg);p.structure=clip(structure);
    p.brightness=clip(brightness);p.damping=clip(damping);p.position=clip(position);
    p.gain=clip(gain);p.aux=clip(aux);
}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeTape(JNIEnv*,jclass,jfloat mix,jfloat drive,jfloat wow,
    jfloat flutter,jfloat hiss,jfloat wear,jfloat delay){
    std::lock_guard<std::mutex> guard(audioMutex);
    tape.mix=clip(mix);tape.drive=clip(drive);tape.wow=clip(wow);
    tape.flutter=clip(flutter);tape.hiss=clip(hiss);tape.wear=clip(wear);tape.delay=clip(delay);
}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeOn(JNIEnv*,jclass,jint layer,jint note,jint velocity,jint yMode,jfloat y){
    std::lock_guard<std::mutex> guard(audioMutex);
    if(layer<0||layer>=4)return;
    NoteVoice* candidate=nullptr;
    for(auto& ptr:voices){
        if(!ptr)continue;
        if(ptr->used&&ptr->layer==layer&&ptr->note==note){candidate=ptr.get();break;}
        if(!ptr->used&&!candidate)candidate=ptr.get();
    }
    if(!candidate)candidate=voices.front().get();
    if(!candidate)return;
    candidate->init();
    candidate->used=true;candidate->gate=true;candidate->first=true;
    candidate->layer=layer;candidate->note=std::max(0,std::min(127,(int)note));
    candidate->velocity=std::max(0.01f,std::min(1.f,velocity/127.f));
    candidate->yMode=yMode;candidate->expression=clip(y);
    candidate->release=1.0f;candidate->offSamples=0;candidate->vibrato=0.0f;candidate->lowpass=0.0f;
}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeOff(JNIEnv*,jclass,jint layer,jint note){
    std::lock_guard<std::mutex> guard(audioMutex);
    for(auto& v:voices)if(v&&v->used&&v->layer==layer&&v->note==note)v->gate=false;
}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeExpression(JNIEnv*,jclass,jint layer,jfloat y){
    std::lock_guard<std::mutex> guard(audioMutex);
    for(auto& v:voices)if(v&&v->used&&v->layer==layer)v->expression=clip(y);
}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeAllOff(JNIEnv*,jclass,jint layer){
    std::lock_guard<std::mutex> guard(audioMutex);
    for(auto& v:voices)if(v&&(layer<0||v->layer==layer)){v->used=false;v->gate=false;}
}
JNIEXPORT void JNICALL Java_com_oll_xy8_Synth_nativeRender(JNIEnv* env,jclass,jshortArray output,jint frames){
    std::lock_guard<std::mutex> guard(audioMutex);
    if(!output)return;
    int length=env->GetArrayLength(output);
    frames=std::max(0,std::min(length,(int)frames));
    jshort* dst=env->GetShortArrayElements(output,nullptr);
    if(!dst)return;
    for(int offset=0;offset<frames;offset+=kBlock){
        int n=std::min(kBlock,frames-offset);
        float mix[kBlock]{};
        for(auto& voice:voices)if(voice&&voice->used)renderVoice(*voice,patches[voice->layer],mix,n);
        for(int i=0;i<n;i++){
            float s=tape.apply(mix[i]);
            s=std::tanh(s*1.2f);
            dst[offset+i]=static_cast<jshort>(std::max(-1.f,std::min(1.f,s))*32767.f);
        }
    }
    env->ReleaseShortArrayElements(output,dst,0);
}
}
