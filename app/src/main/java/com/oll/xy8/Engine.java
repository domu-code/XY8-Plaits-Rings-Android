package com.oll.xy8;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

final class Engine {
    final Model.Track[][] tracks=new Model.Track[2][4];
    final MidiIO midi;
    final Synth synth;
    private final Context context;
    int bank=Model.CC,selectedCC=0,selectedNote=0,bpm=120;
    boolean playing=false,loop=true,pauseOthersDuringRecord=true;
    boolean internalSound=true;
    float tapeMix=0.0f,tapeDrive=0.3f,tapeWow=0.10f,tapeFlutter=0.12f,tapeHiss=0.0f,tapeWear=0.0f,tapeDelay=0.0f;
    long startedAt=0;
    private long lastUiNotify=0;
    Engine(Context c,MidiIO midi,Synth synth){
        context=c;this.midi=midi;this.synth=synth;
        for(int b=0;b<2;b++)for(int i=0;i<4;i++)tracks[b][i]=new Model.Track(b,i);
        tracks[Model.NOTE][1].root=36;
        tracks[Model.NOTE][2].root=48;
        syncPatches();syncTape();
    }
    Model.Track selected(){return tracks[bank][bank==Model.CC?selectedCC:selectedNote];}
    Model.Track track(int b,int i){return tracks[b][i];}
    int selection(){return bank==Model.CC?selectedCC:selectedNote;}
    void chooseBank(int b){bank=b;}
    void select(int i){if(bank==Model.CC)selectedCC=i;else selectedNote=i;}
    void syncPatches(){for(int i=0;i<4;i++)syncPatch(i);}
    void syncPatch(int i){
        Model.Track t=tracks[Model.NOTE][i];
        synth.patch(i,t.synthEngine,t.plaitsModel,t.ringsModel,t.harmonics,t.timbre,t.morph,
            t.decay,t.lpgColour,t.structure,t.brightness,t.damping,t.position,t.synthGain,t.auxMix);
    }
    void syncTape(){synth.tape(tapeMix,tapeDrive,tapeWow,tapeFlutter,tapeHiss,tapeWear,tapeDelay);}
    long now(){return SystemClock.elapsedRealtime();}
    long barMs(){return Math.max(1,Math.round(240000.0/bpm));}
    long noteLoopMs(Model.Track t){return barMs()*t.bars;}
    long phase(Model.Track t,long now){return Math.floorMod(now-startedAt,noteLoopMs(t));}
    boolean hasSolo(){for(int b=0;b<2;b++)for(int i=0;i<4;i++)if(tracks[b][i].solo)return true;return false;}
    boolean audible(Model.Track t){return !t.muted&&t.playing&&(!hasSolo()||t.solo);}
    void start(){
        if(playing)return;
        startedAt=now();playing=true;
        for(int b=0;b<2;b++)for(int i=0;i<4;i++)tracks[b][i].resetPlayback();
    }
    void stop(){
        playing=false;
        for(int b=0;b<2;b++)for(int i=0;i<4;i++){
            Model.Track t=tracks[b][i];
            t.touching=false;t.lastCCStep=-1;t.lastNotePhase=-1;t.armed=false;t.activeChord=new int[0];
        }
        panic();
    }
    void panic(){
        synth.silence();
        for(int c=0;c<16;c++)midi.cc(c,123,0);
    }
    void toggleSound(){internalSound=!internalSound;synth.enabled(internalSound);}
    void toggleMute(){Model.Track t=selected();t.muted=!t.muted;if(t.bank==Model.NOTE)quiet(t);}
    void toggleSolo(){Model.Track t=selected();t.solo=!t.solo;panic();}
    void toggleLayerPlay(){Model.Track t=selected();t.playing=!t.playing;if(!t.playing)quiet(t);t.resetPlayback();}
    void toggleRecord(){Model.Track t=selected();t.armed=!t.armed;t.hasBeenEdited=false;}
    void clearSelected(){Model.Track t=selected();quiet(t);t.points.clear();t.events.clear();t.resetPlayback();t.armed=false;}
    void resetCcCenter(boolean x,boolean y){
        Model.Track t=selected();if(t.bank!=Model.CC)return;
        if(x)midi.cc(t.channel,t.ccX,64);
        if(y)midi.cc(t.channel,t.ccY,64);
    }
    void touchDown(float x,float y){
        Model.Track t=selected();
        if(t.armed&&!playing)start();
        t.lastCCRecordAt=0;
        t.touching=true;t.cursorX=Model.clamp(x,0,1);t.cursorY=Model.clamp(y,0,1);
        if(t.armed&&!t.hasBeenEdited){
            if(!t.overdub){quiet(t);t.points.clear();t.events.clear();t.resetPlayback();}
            t.hasBeenEdited=true;
        }
        if(t.bank==Model.CC){
            t.lastCCRecordAt=0;
            liveCc(t,now());
        } else {
            t.nextGateAt=-1;t.gateOffAt=-1;
            if(!t.gateArp)performNote(t,now(),true);
        }
    }
    void touchMove(float x,float y){
        Model.Track t=selected();
        if(!t.touching)return;
        t.cursorX=Model.clamp(x,0,1);t.cursorY=Model.clamp(y,0,1);
        if(t.bank==Model.CC){liveCc(t,now());}
        else {
            if(!t.gateArp)performNote(t,now(),false);
            sendExpression(t,now());
        }
    }
    void touchUp(){
        Model.Track t=selected();
        if(!t.touching)return;
        long time=now();
        if(t.bank==Model.CC){liveCc(t,time);}
        else releaseChord(t,time);
        t.touching=false;t.gateOffAt=-1;t.nextGateAt=-1;
    }
    private void liveCc(Model.Track t,long time){
        if(!t.muted){
            midi.cc(t.channel,t.ccX,ccScale(t.cursorX,t.rangeXMin,t.rangeXMax));
            midi.cc(t.channel,t.ccY,ccScale(t.cursorY,t.rangeYMin,t.rangeYMax));
        }
        if(t.armed&&(t.lastCCRecordAt==0||time-t.lastCCRecordAt>=16)){
            t.points.add(new Model.Point(t.cursorX,t.cursorY));t.lastCCRecordAt=time;
            if(t.points.size()>30000)t.points.remove(0);
        }
    }
    static int ccScale(float fraction,int min,int max){return Model.clamp(Math.round(min+(max-min)*fraction),0,127);}
    private int noteForX(Model.Track t,float x){
        if(t.program==3){int[] drums={36,38,42,46,41,43,49,51};return drums[Model.clamp((int)(x*8),0,7)];}
        int[] scale=Model.INTERVALS[t.scale];
        int total=Math.max(1,t.octaves*scale.length);
        int step=Model.clamp((int)(x*total),0,total-1);
        return Model.clamp(t.root+12*(step/scale.length)+scale[step%scale.length],0,127);
    }
    private int[] chordNotes(Model.Track t,int note,float y){
        if(t.program!=2)return new int[]{note};
        // Major, minor, suspended, dominant seventh, minor seventh, major seventh.
        int[][] intervals={{0,4,7},{0,3,7},{0,5,7},{0,4,7,10},{0,3,7,10},{0,4,7,11}};
        int k=Model.clamp((int)(y*intervals.length),0,intervals.length-1);
        int[] a=new int[intervals[k].length];
        for(int i=0;i<a.length;i++)a[i]=Model.clamp(note+intervals[k][i],0,127);
        return a;
    }
    private int velocity(Model.Track t){
        return t.yMode==1||t.program==3?Model.clamp(Math.round(30+97*t.cursorY),1,127):105;
    }
    private void performNote(Model.Track t,long time,boolean first){
        int note=noteForX(t,t.cursorX);
        int[] newChord=chordNotes(t,note,t.cursorY);
        boolean diff=first||t.activeChord.length!=newChord.length;
        if(!diff)for(int i=0;i<newChord.length;i++)if(newChord[i]!=t.activeChord[i]){diff=true;break;}
        if(diff){releaseChord(t,time);startChord(t,newChord,time);}
        t.liveNote=note;
    }
    private void startChord(Model.Track t,int[] chord,long time){
        if(t.muted){t.activeChord=new int[0];return;}
        int v=velocity(t);
        for(int n:chord){
            midi.on(t.channel,n,v);
            syncPatch(t.index);synth.on(t.index,n,t.yMode,v,t.cursorY);
            recordEvent(t,time,Model.Event.ON,n,v);
        }
        t.activeChord=chord;
        sendExpression(t,time);
    }
    private void releaseChord(Model.Track t,long time){
        for(int n:t.activeChord){
            midi.off(t.channel,n);synth.off(t.index,n);
            recordEvent(t,time,Model.Event.OFF,n,0);
        }
        t.activeChord=new int[0];t.liveNote=-1;
    }
    private void quiet(Model.Track t){
        for(int n:t.activeChord)midi.off(t.channel,n);
        t.activeChord=new int[0];t.liveNote=-1;
        if(t.bank==Model.NOTE){midi.cc(t.channel,123,0);synth.allOff(t.index);}
    }
    private void sendExpression(Model.Track t,long time){
        int val=Model.clamp(Math.round(t.cursorY*127),0,127);
        int cc=(t.yMode==3?1:(t.yMode==1?11:74));
        if(!t.muted){midi.cc(t.channel,cc,val);synth.expression(t.index,t.cursorY);}
        if(t.armed&&(t.lastCCRecordAt==0||time-t.lastCCRecordAt>=40)){
            recordEvent(t,time,Model.Event.Y,cc,val);t.lastCCRecordAt=time;
        }
    }
    private void recordEvent(Model.Track t,long time,int type,int note,int value){
        if(!t.armed)return;
        long position=playing?phase(t,time):0;
        t.events.add(new Model.Event(position,type,note,value));
        if(t.events.size()>12000)t.events.remove(0);
        t.sortEvents();
    }
    private void emitNoteEvent(Model.Track t,Model.Event e){
        if(e.type==Model.Event.ON){
            midi.on(t.channel,e.note,e.value);
            syncPatch(t.index);synth.on(t.index,e.note,t.yMode,e.value,t.cursorY>=0?t.cursorY:0.65f);
        }else if(e.type==Model.Event.OFF){
            midi.off(t.channel,e.note);synth.off(t.index,e.note);
        }else if(e.type==Model.Event.Y){
            midi.cc(t.channel,e.note,e.value);
            synth.expression(t.index,e.value/127f);
        }
    }
    synchronized void tick(){
        if(!playing)return;
        long current=now();
        boolean anyRecording=false;
        for(int b=0;b<2;b++)for(int i=0;i<4;i++)if(tracks[b][i].touching&&tracks[b][i].armed)anyRecording=true;
        for(int i=0;i<4;i++){
            Model.Track t=tracks[Model.CC][i];
            if(!audible(t)||t.points.isEmpty()||(pauseOthersDuringRecord&&anyRecording&&!t.touching))continue;
            long span=t.syncCC?noteLoopMs(t):t.points.size()*16L*t.speed;
            if(span<=0)continue;
            long pos=Math.floorMod(current-startedAt,span);
            if(!loop&&current-startedAt>=span)continue;
            int step=(int)Math.min(t.points.size()-1,(pos*t.points.size())/span);
            if(step!=t.lastCCStep){
                Model.Point p=t.points.get(step);
                if(!t.touching){
                    midi.cc(t.channel,t.ccX,ccScale(p.x,t.rangeXMin,t.rangeXMax));
                    midi.cc(t.channel,t.ccY,ccScale(p.y,t.rangeYMin,t.rangeYMax));
                    t.cursorX=p.x;t.cursorY=p.y;
                }
                t.lastCCStep=step;
            }
        }
        for(int i=0;i<4;i++){
            Model.Track t=tracks[Model.NOTE][i];
            if(!audible(t)){t.lastNotePhase=-1;continue;}
            long len=noteLoopMs(t),pos=phase(t,current);
            if(!loop&&current-startedAt>=len)continue;
            if(t.lastNotePhase>=0&&pos<t.lastNotePhase){
                // Break long notes at the loop boundary to prevent stuck voices.
                midi.cc(t.channel,123,0);synth.allOff(t.index);
            }
            if(!(pauseOthersDuringRecord&&anyRecording&&!t.touching)&&!t.events.isEmpty()&&!t.touching){
                if(t.lastNotePhase<0){
                    for(Model.Event e:t.events)if(e.ms<=pos)emitNoteEvent(t,e);
                }else if(pos>=t.lastNotePhase){
                    for(Model.Event e:t.events)if(e.ms>t.lastNotePhase&&e.ms<=pos)emitNoteEvent(t,e);
                }else{
                    for(Model.Event e:t.events)if(e.ms>t.lastNotePhase||e.ms<=pos)emitNoteEvent(t,e);
                }
            }
            t.lastNotePhase=pos;
            if(t.touching&&t.gateArp){
                long duration=Math.max(25,Math.round(60000.0/bpm/t.gateDivision));
                if(t.gateOffAt>=0&&current>=t.gateOffAt){releaseChord(t,current);t.gateOffAt=-1;}
                if(t.nextGateAt<0)t.nextGateAt=current;
                if(current>=t.nextGateAt){
                    releaseChord(t,current);
                    startChord(t,chordNotes(t,noteForX(t,t.cursorX),t.cursorY),current);
                    t.gateOffAt=current+Math.round(duration*t.gatePercent/100.0);
                    t.nextGateAt=current+duration;
                }
            }
        }
    }
    synchronized void incomingCC(int channel,int cc,int value){
        if(cc>=20&&cc<=35){
            int n=(cc-20)/4,field=(cc-20)%4;
            Model.Track t=tracks[Model.CC][n];
            switch(field){case 0:t.rangeXMin=value;break;case 1:t.rangeXMax=value;break;
                case 2:t.rangeYMin=value;break;default:t.rangeYMax=value;}
        }else if(cc>=60&&cc<=63){
            tracks[Model.CC][cc-60].muted=value<=63;
        }
    }
    synchronized JSONObject save() throws Exception{
        JSONObject o=new JSONObject();o.put("format","XY8-v1");o.put("bpm",bpm);o.put("loop",loop);
        o.put("pauseOthersDuringRecord",pauseOthersDuringRecord);
        o.put("internalSound",internalSound);
        o.put("tapeMix",tapeMix);o.put("tapeDrive",tapeDrive);o.put("tapeWow",tapeWow);o.put("tapeFlutter",tapeFlutter);
        o.put("tapeHiss",tapeHiss);o.put("tapeWear",tapeWear);o.put("tapeDelay",tapeDelay);
        JSONArray a=new JSONArray();for(int b=0;b<2;b++)for(int i=0;i<4;i++)a.put(tracks[b][i].save());
        o.put("tracks",a);return o;
    }
    synchronized void load(JSONObject o){
        panic();
        bpm=Model.clamp(o.optInt("bpm",120),30,300);loop=o.optBoolean("loop",true);
        pauseOthersDuringRecord=o.optBoolean("pauseOthersDuringRecord",false);
        internalSound=o.optBoolean("internalSound",true);synth.enabled(internalSound);
        tapeMix=Model.clamp((float)o.optDouble("tapeMix",tapeMix),0,1);
        tapeDrive=Model.clamp((float)o.optDouble("tapeDrive",tapeDrive),0,1);
        tapeWow=Model.clamp((float)o.optDouble("tapeWow",tapeWow),0,1);
        tapeFlutter=Model.clamp((float)o.optDouble("tapeFlutter",tapeFlutter),0,1);
        tapeHiss=Model.clamp((float)o.optDouble("tapeHiss",tapeHiss),0,1);
        tapeWear=Model.clamp((float)o.optDouble("tapeWear",tapeWear),0,1);
        tapeDelay=Model.clamp((float)o.optDouble("tapeDelay",tapeDelay),0,1);
        JSONArray a=o.optJSONArray("tracks");
        if(a!=null)for(int i=0;i<Math.min(8,a.length());i++){
            JSONObject p=a.optJSONObject(i);if(p!=null)tracks[i/4][i%4].load(p);
        }
        playing=false;syncPatches();syncTape();
    }
    private JSONObject asset(String filename)throws Exception{
        try(InputStream stream=context.getAssets().open(filename);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;
            while((n=stream.read(b))!=-1)out.write(b,0,n);
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
    }
    synchronized boolean loadLegacyData(int slot){
        try{
            JSONArray a=asset("metro_xy_automation_presets.json").getJSONArray("slots");
            JSONObject wrap=a.getJSONObject(slot);
            if(!wrap.optBoolean("exists",false))return false;
            JSONObject data=wrap.getJSONObject("data");
            for(int i=0;i<4;i++){
                Model.Track t=tracks[Model.CC][i];
                JSONArray xs=data.optJSONArray("recordedX"+(i+1));
                JSONArray ys=data.optJSONArray("recordedY"+(i+1));
                t.points.clear();t.speed=Model.clamp(data.optInt("speed"+(i+1),2),1,64);
                if(xs!=null&&ys!=null)for(int j=0;j<Math.min(xs.length(),ys.length());j++){
                    t.points.add(new Model.Point((float)(xs.optDouble(j)-180)/400f,(float)(ys.optDouble(j)-180)/400f));
                }
                t.resetPlayback();
            }
            return true;
        }catch(Exception ignored){return false;}
    }
    synchronized boolean loadLegacyRouting(int slot){
        try{
            JSONArray a=asset("metro_xy_routing_presets.json").getJSONArray("slots");
            JSONObject wrap=a.getJSONObject(slot);
            if(!wrap.optBoolean("exists",false))return false;
            return applyRouting(wrap.getJSONObject("data"));
        }catch(Exception ignored){return false;}
    }
    synchronized boolean loadLegacyTemporaryRouting(){
        try{return applyRouting(asset("metro_xy_routing_options_temp.json"));}catch(Exception ignored){return false;}
    }
    private boolean applyRouting(JSONObject o){
        JSONArray xs=o.optJSONArray("channelXCC"),ys=o.optJSONArray("channelYCC");
        if(xs==null||ys==null)return false;
        for(int i=0;i<4;i++){
            Model.Track t=tracks[Model.CC][i];
            t.ccX=Model.clamp(xs.optInt(i,t.ccX),0,127);
            t.ccY=Model.clamp(ys.optInt(i,t.ccY),0,127);
        }
        return true;
    }
}
