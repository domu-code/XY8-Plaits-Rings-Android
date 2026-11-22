package com.oll.xy8;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/** Original Mutable Instruments Plaits + Rings DSP via Android NDK/JNI. */
final class Synth {
    private static final int RATE=48000, CHUNK=480;
    static {System.loadLibrary("xy8dsp");}

    private static native void nativeInit();
    private static native void nativeDestroy();
    private static native void nativeOn(int layer,int note,int velocity,int yMode,float y);
    private static native void nativeOff(int layer,int note);
    private static native void nativeExpression(int layer,float y);
    private static native void nativeAllOff(int layer);
    private static native void nativePatch(int layer,int engine,int plaitsModel,int ringsModel,
        float harmonics,float timbre,float morph,float decay,float lpgColour,
        float structure,float brightness,float damping,float position,float gain,float auxMix);
    private static native void nativeTape(float mix,float drive,float wow,float flutter,float hiss,float wear,float delay);
    private static native void nativeRender(short[] out,int frames);

    private volatile boolean active=true,enabled=true;
    private final Thread thread;
    Synth(){
        nativeInit();
        thread=new Thread(this::audioLoop,"XY8-Mutable-audio");
        thread.setDaemon(true);thread.start();
    }
    void on(int layer,int note,int yMode,int velocity,float y){nativeOn(layer,note,velocity,yMode,y);}
    void off(int layer,int note){nativeOff(layer,note);}
    void expression(int layer,float y){nativeExpression(layer,y);}
    void allOff(int layer){nativeAllOff(layer);}
    void silence(){nativeAllOff(-1);}
    void enabled(boolean value){enabled=value;if(!value)silence();}
    boolean enabled(){return enabled;}
    void patch(int layer,int synthEngine,int plaitsModel,int ringsModel,
        float harmonics,float timbre,float morph,float decay,float lpgColour,
        float structure,float brightness,float damping,float position,float gain,float auxMix){
        nativePatch(layer,synthEngine,plaitsModel,ringsModel,harmonics,timbre,morph,decay,lpgColour,
            structure,brightness,damping,position,gain,auxMix);
    }
    void tape(float mix,float drive,float wow,float flutter,float hiss,float wear,float delay){
        nativeTape(mix,drive,wow,flutter,hiss,wear,delay);
    }
    void destroy(){
        active=false;thread.interrupt();
        try{thread.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        nativeDestroy();
    }
    private void audioLoop(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        int min=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)return;
        AudioTrack audio=null;
        try{
            audio=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                Math.max(min,CHUNK*8),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            audio.play();
            short[] samples=new short[CHUNK];
            while(active){
                nativeRender(samples,CHUNK);
                if(!enabled)java.util.Arrays.fill(samples,(short)0);
                int offset=0;
                while(active&&offset<CHUNK){
                    int n=audio.write(samples,offset,CHUNK-offset,AudioTrack.WRITE_BLOCKING);
                    if(n<0)break;
                    offset+=n;
                }
            }
        }catch(Exception e){android.util.Log.e("XY8","Audio thread stopped",e);}
        finally{if(audio!=null){try{audio.stop();audio.flush();audio.release();}catch(Exception ignored){}}}
    }
}
