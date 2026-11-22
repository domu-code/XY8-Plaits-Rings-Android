package com.oll.xy8;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

final class Model {
    static final int CC=0, NOTE=1;
    static final String[] SCALES = {"Chromatic","Major","Minor","Dorian","Phrygian","Lydian","Mixolydian","Pentatonic","Minor Pent.","Blues","Harmonic Minor","Whole Tone"};
    static final int[][] INTERVALS = {
        {0,1,2,3,4,5,6,7,8,9,10,11},{0,2,4,5,7,9,11},{0,2,3,5,7,8,10},
        {0,2,3,5,7,9,10},{0,1,3,5,7,8,10},{0,2,4,6,7,9,11},
        {0,2,4,5,7,9,10},{0,2,4,7,9},{0,3,5,7,10},
        {0,3,5,6,7,10},{0,2,3,5,7,8,11},{0,2,4,6,8,10}
    };
    static final String[] PROGRAMS={"LEAD","BASS","CHORD","DRUM"};
    static final String[] Y_MODES={"FILTER","VELOCITY","DECAY","VIBRATO","RESONANCE","CHORD","TIMBRE","MORPH","HARMONICS","DAMPING","POSITION","BRIGHTNESS"};
    static final String[] SYNTHS={"PLAITS","RINGS"};
    static final String[] PLAITS_MODELS={"VA VCF","PHASE DISTORT","6-OP FM A","6-OP FM B","6-OP FM C","WAVE TERRAIN","STRING MACHINE","CHIPTUNE",
        "VIRTUAL ANALOG","WAVESHAPER","2-OP FM","GRANULAR","ADDITIVE","WAVETABLE","CHORDS","SPEECH","SWARM","NOISE","PARTICLES","STRING","MODAL","BASS DRUM","SNARE","HI-HAT"};
    static final String[] RINGS_MODELS={"MODAL","SYMPATHETIC","STRING","FM VOICE","QUANTIZED STRINGS","STRING + REVERB"};
    static final String[] WAVES={"SAW","SINE","SQUARE","TRIANGLE"};
    static final int[] COLORS={0xffff5656,0xffffc64e,0xff56db89,0xff60b9ff};
    static float clamp(float x,float lo,float hi){return Math.max(lo,Math.min(hi,x));}
    static int clamp(int x,int lo,int hi){return Math.max(lo,Math.min(hi,x));}
    static JSONArray array(int[] numbers){JSONArray a=new JSONArray();for(int i:numbers)a.put(i);return a;}

    static class Point {
        float x,y;
        Point(float x,float y){this.x=clamp(x,0,1);this.y=clamp(y,0,1);}
        JSONArray json(){JSONArray a=new JSONArray();a.put(x);a.put(y);return a;}
    }
    static class Event {
        static final int OFF=0,ON=1,Y=2;
        long ms;
        int type,note,value;
        Event(long ms,int type,int note,int value){this.ms=ms;this.type=type;this.note=note;this.value=value;}
        JSONArray json(){JSONArray a=new JSONArray();a.put(ms);a.put(type);a.put(note);a.put(value);return a;}
    }
    static class Track {
        final int bank,index;
        final ArrayList<Point> points=new ArrayList<>();
        final ArrayList<Event> events=new ArrayList<>();
        boolean playing=true, muted=false, solo=false, armed=false, overdub=false, syncCC=false, gateArp=false;
        int ccX,ccY,channel,rangeXMin=0,rangeXMax=127,rangeYMin=0,rangeYMax=127;
        int speed=2, bars=2, root=60, octaves=2, scale=1, program=0, yMode=0, wave=0;
        int gateDivision=4, gatePercent=55, lastCCStep=-1, lastNote=-1;
        int synthEngine=0, plaitsModel=8, ringsModel=0;
        float harmonics=0.5f,timbre=0.5f,morph=0.5f,decay=0.5f,lpgColour=0.5f;
        float structure=0.5f,brightness=0.6f,damping=0.6f,position=0.3f;
        float synthGain=0.7f,auxMix=0.15f;
        long lastNotePhase=-1, nextGateAt=-1, gateOffAt=-1, lastCCRecordAt=0;
        float cursorX=-1,cursorY=-1;
        boolean touching=false,hasBeenEdited=false;
        int liveNote=-1;
        int[] activeChord=new int[0];
        Track(int bank,int index){
            this.bank=bank;this.index=index;
            ccX=44+index*2;ccY=45+index*2;
            channel=(bank==CC)?0:(index==3?9:index+1);
            program=index;
            wave=(index==1?2:0);
            synthEngine=(index==2?1:0);plaitsModel=new int[]{8,10,19,21}[index];
            yMode=(index==2?5:(index==3?1:0));
            if(index==3)octaves=1;
        }
        String label(){return (bank==CC?"CC":"N")+(index+1);}
        JSONObject save() throws JSONException{
            JSONObject o=new JSONObject();
            o.put("bank",bank);o.put("index",index);o.put("playing",playing);o.put("muted",muted);o.put("solo",solo);
            o.put("armed",false);o.put("overdub",overdub);o.put("syncCC",syncCC);o.put("gateArp",gateArp);
            o.put("ccX",ccX);o.put("ccY",ccY);o.put("channel",channel);
            o.put("rangeXMin",rangeXMin);o.put("rangeXMax",rangeXMax);o.put("rangeYMin",rangeYMin);o.put("rangeYMax",rangeYMax);
            o.put("speed",speed);o.put("bars",bars);o.put("root",root);o.put("octaves",octaves);
            o.put("scale",scale);o.put("program",program);o.put("yMode",yMode);o.put("wave",wave);
            o.put("gateDivision",gateDivision);o.put("gatePercent",gatePercent);
            o.put("synthEngine",synthEngine);o.put("plaitsModel",plaitsModel);o.put("ringsModel",ringsModel);
            o.put("harmonics",harmonics);o.put("timbre",timbre);o.put("morph",morph);o.put("decay",decay);o.put("lpgColour",lpgColour);
            o.put("structure",structure);o.put("brightness",brightness);o.put("damping",damping);o.put("position",position);
            o.put("synthGain",synthGain);o.put("auxMix",auxMix);
            JSONArray p=new JSONArray();for(Point v:points)p.put(v.json());o.put("points",p);
            JSONArray e=new JSONArray();for(Event v:events)e.put(v.json());o.put("events",e);
            return o;
        }
        void load(JSONObject o){
            playing=o.optBoolean("playing",true);muted=o.optBoolean("muted",false);solo=o.optBoolean("solo",false);
            armed=false;overdub=o.optBoolean("overdub",false);syncCC=o.optBoolean("syncCC",false);gateArp=o.optBoolean("gateArp",false);
            ccX=clamp(o.optInt("ccX",ccX),0,127);ccY=clamp(o.optInt("ccY",ccY),0,127);
            channel=clamp(o.optInt("channel",channel),0,15);
            rangeXMin=clamp(o.optInt("rangeXMin",rangeXMin),0,127);rangeXMax=clamp(o.optInt("rangeXMax",rangeXMax),0,127);
            rangeYMin=clamp(o.optInt("rangeYMin",rangeYMin),0,127);rangeYMax=clamp(o.optInt("rangeYMax",rangeYMax),0,127);
            speed=clamp(o.optInt("speed",speed),1,64);bars=clamp(o.optInt("bars",bars),1,8);
            root=clamp(o.optInt("root",root),0,127);octaves=clamp(o.optInt("octaves",octaves),1,6);
            scale=clamp(o.optInt("scale",scale),0,SCALES.length-1);
            program=clamp(o.optInt("program",program),0,3);
            yMode=clamp(o.optInt("yMode",yMode),0,Y_MODES.length-1);wave=clamp(o.optInt("wave",wave),0,3);
            gateDivision=clamp(o.optInt("gateDivision",gateDivision),1,16);
            gatePercent=clamp(o.optInt("gatePercent",gatePercent),5,100);
            synthEngine=clamp(o.optInt("synthEngine",synthEngine),0,1);
            plaitsModel=clamp(o.optInt("plaitsModel",plaitsModel),0,23);
            ringsModel=clamp(o.optInt("ringsModel",ringsModel),0,5);
            harmonics=clamp((float)o.optDouble("harmonics",harmonics),0,1);
            timbre=clamp((float)o.optDouble("timbre",timbre),0,1);
            morph=clamp((float)o.optDouble("morph",morph),0,1);
            decay=clamp((float)o.optDouble("decay",decay),0,1);
            lpgColour=clamp((float)o.optDouble("lpgColour",lpgColour),0,1);
            structure=clamp((float)o.optDouble("structure",structure),0,1);
            brightness=clamp((float)o.optDouble("brightness",brightness),0,1);
            damping=clamp((float)o.optDouble("damping",damping),0,1);
            position=clamp((float)o.optDouble("position",position),0,1);
            synthGain=clamp((float)o.optDouble("synthGain",synthGain),0,1);
            auxMix=clamp((float)o.optDouble("auxMix",auxMix),0,1);
            points.clear();events.clear();
            JSONArray ps=o.optJSONArray("points");
            if(ps!=null)for(int i=0;i<ps.length();i++){
                JSONArray a=ps.optJSONArray(i);if(a!=null&&a.length()>=2)points.add(new Point((float)a.optDouble(0),(float)a.optDouble(1)));
            }
            JSONArray ev=o.optJSONArray("events");
            if(ev!=null)for(int i=0;i<ev.length();i++){
                JSONArray a=ev.optJSONArray(i);if(a!=null&&a.length()>=4)events.add(new Event(a.optLong(0),a.optInt(1),a.optInt(2),a.optInt(3)));
            }
            sortEvents();resetPlayback();
        }
        void sortEvents(){Collections.sort(events,Comparator.comparingLong(a->a.ms));}
        void resetPlayback(){lastCCStep=-1;lastNotePhase=-1;nextGateAt=-1;gateOffAt=-1;touching=false;liveNote=-1;activeChord=new int[0];cursorX=-1;cursorY=-1;}
    }
}
