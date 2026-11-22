package com.oll.xy8;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.provider.DocumentsContract;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements MidiIO.Listener {
    private static final int EXPORT_CODE=6001,IMPORT_CODE=6002;
    private MidiIO midi;
    private Synth synth;
    private Engine engine;
    private PerformanceView view;
    private ScheduledExecutorService timer;
    private SharedPreferences prefs;
    private long lastAutosave=0;
    // Android 11+ uses WindowInsetsController for immersive mode. Keep the
    // legacy system UI flags only on older devices (minimum supported API 26).
    private void applyImmersiveLandscape(){
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        }else{
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus)applyImmersiveLandscape();
    }
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        applyImmersiveLandscape();
        prefs=getSharedPreferences("xy8",MODE_PRIVATE);
        synth=new Synth();
        midi=new MidiIO(this,this);
        engine=new Engine(this,midi,synth);
        try{String state=prefs.getString("current",null);if(state!=null)engine.load(new JSONObject(state));}
        catch(Exception e){toast("Could not restore session");}
        view=new PerformanceView(this,engine);
        setContentView(view);
        timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"XY8-clock");t.setDaemon(true);return t;});
        timer.scheduleAtFixedRate(()->{
            synchronized(engine){engine.tick();}
            if(view!=null)view.postInvalidate();
        },0,10,TimeUnit.MILLISECONDS);
    }
    @Override public void incomingCC(int channel,int cc,int value){
        synchronized(engine){engine.incomingCC(channel,cc,value);}
        if(view!=null)view.postInvalidate();
    }
    @Override public void notice(String m){runOnUiThread(()->toast(m));}
    void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    void autosave(){
        if(engine==null)return;
        try{
            String json;
            synchronized(engine){json=engine.save().toString();}
            prefs.edit().putString("current",json).apply();
        }catch(Exception e){toast("Save error: "+e.getMessage());}
    }
    @Override protected void onPause(){super.onPause();synchronized(engine){engine.stop();}autosave();}
    @Override protected void onDestroy(){
        if(timer!=null)timer.shutdownNow();
        if(engine!=null){synchronized(engine){engine.stop();}}
        if(midi!=null)midi.close();
        if(synth!=null)synth.destroy();
        super.onDestroy();
    }
    void command(String action){
        if(action.startsWith("layer")&&action.length()==6){
            synchronized(engine){engine.touchUp();engine.select(Integer.parseInt(action.substring(5)));}
            view.invalidate();return;
        }
        switch(action){
            case "bankcc":synchronized(engine){engine.touchUp();engine.chooseBank(Model.CC);}break;
            case "banknote":synchronized(engine){engine.touchUp();engine.chooseBank(Model.NOTE);}break;
            case "play":synchronized(engine){engine.start();}break;
            case "stop":synchronized(engine){engine.stop();}break;
            case "rec":synchronized(engine){engine.toggleRecord();}break;
            case "mute":synchronized(engine){engine.toggleMute();}break;
            case "solo":synchronized(engine){engine.toggleSolo();}break;
            case "loop":synchronized(engine){engine.loop=!engine.loop;}break;
            case "layerplay":synchronized(engine){engine.toggleLayerPlay();}break;
            case "bpmminus":synchronized(engine){engine.bpm=Model.clamp(engine.bpm-1,30,300);}break;
            case "bpmplus":synchronized(engine){engine.bpm=Model.clamp(engine.bpm+1,30,300);}break;
            case "clear":confirmClear();return;
            case "midi":midiMenu();return;
            case "presets":presetMenu();return;
            case "settings":settingsMenu();return;
            case "center":synchronized(engine){engine.resetCcCenter(true,true);}break;
            case "xcc":case "ycc":case "xmin":case "xmax":case "ymin":case "ymax":
                editCC(action);return;
            case "speed":speedMenu();return;
            case "ccsync":ccSyncMenu();return;
            case "program":chooseProgram();return;
            case "sound":soundMenu();return;
            case "scale":chooseScale();return;
            case "root":editNumber("Root MIDI note",0,127,engine.selected().root,
                value->engine.selected().root=value);return;
            case "octaves":chooseOctaves();return;
            case "ymode":chooseYMode();return;
            case "arp":synchronized(engine){engine.selected().gateArp=!engine.selected().gateArp;}break;
            case "gate":gateMenu();return;
            case "bars":chooseBars();return;
        }
        autosave();view.invalidate();
    }
    interface NumberSetter{void set(int n);}
    private void editNumber(String title,int min,int max,int current,NumberSetter setter){
        EditText e=new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setSingleLine(true);e.setText(String.valueOf(current));e.selectAll();
        LinearLayout box=new LinearLayout(this);box.setPadding(35,10,35,0);box.addView(e);
        new AlertDialog.Builder(this).setTitle(title+" ("+min+" to "+max+")").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{
                try{
                    int value=Model.clamp(Integer.parseInt(e.getText().toString().trim()),min,max);
                    synchronized(engine){setter.set(value);}
                    autosave();view.invalidate();
                }catch(Exception ex){toast("Enter an integer");}
            }).show();
    }
    private void choice(String title,String[] labels,int chosen,java.util.function.IntConsumer onPick){
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,chosen,(d,i)->{
            synchronized(engine){onPick.accept(i);}
            d.dismiss();autosave();view.invalidate();
        }).setNegativeButton("Cancel",null).show();
    }
    private void editCC(String field){
        Model.Track t=engine.selected();int v;
        switch(field){
            case "xcc":v=t.ccX;break;case "ycc":v=t.ccY;break;
            case "xmin":v=t.rangeXMin;break;case "xmax":v=t.rangeXMax;break;
            case "ymin":v=t.rangeYMin;break;default:v=t.rangeYMax;
        }
        editNumber(field.toUpperCase(),0,127,v,n->{
            Model.Track a=engine.selected();
            switch(field){
                case "xcc":a.ccX=n;break;case "ycc":a.ccY=n;break;
                case "xmin":a.rangeXMin=n;break;case "xmax":a.rangeXMax=n;break;
                case "ymin":a.rangeYMin=n;break;default:a.rangeYMax=n;
            }
        });
    }
    private void speedMenu(){
        String[] names={"1 (fast)","2 (original default)","4","8","16","32","64"};
        int[] speeds={1,2,4,8,16,32,64};
        int selected=0;for(int i=0;i<speeds.length;i++)if(engine.selected().speed==speeds[i])selected=i;
        choice("CC playback sample divisor",names,selected,i->engine.selected().speed=speeds[i]);
    }
    private void ccSyncMenu(){
        Model.Track t=engine.selected();
        String[] names={"Free-running (original scan speed)","Synchronise to master tempo: "+t.bars+" bars","Choose synced loop bars"};
        choice("CC loop timing",names,t.syncCC?1:0,i->{
            if(i==0)t.syncCC=false;
            if(i==1)t.syncCC=true;
            if(i==2){t.syncCC=true;runOnUiThread(this::chooseBars);}
        });
    }
    private void chooseProgram(){
        choice("Note generator style",Model.PROGRAMS,engine.selected().program,i->{
            Model.Track t=engine.selected();t.program=i;
            if(i==3){t.channel=9;t.yMode=1;t.octaves=1;}
            else if(t.channel==9)t.channel=t.index+1;
            if(i==2)t.yMode=5;
        });
    }

    // Android-native touch editors provide large targets for all synthesis and effect controls.
    private void soundMenu(){
        Model.Track t=engine.selected();
        String[] options={"Engine: "+Model.SYNTHS[t.synthEngine],
            "Plaits model: "+Model.PLAITS_MODELS[t.plaitsModel],
            "Rings model: "+Model.RINGS_MODELS[t.ringsModel],
            "Plaits controls", "Rings controls", "Tape / lo-fi effects", "Synthesis mix / aux", "Reset tape effects"};
        new AlertDialog.Builder(this).setTitle("Sound editor / "+t.label()).setItems(options,(d,i)->{
            switch(i){
                case 0:choice("Synthesis engine",Model.SYNTHS,t.synthEngine,j->{t.synthEngine=j;engine.syncPatch(t.index);});break;
                case 1:choice("Original Plaits model",Model.PLAITS_MODELS,t.plaitsModel,j->{t.plaitsModel=j;engine.syncPatch(t.index);});break;
                case 2:choice("Rings resonator model",Model.RINGS_MODELS,t.ringsModel,j->{t.ringsModel=j;engine.syncPatch(t.index);});break;
                case 3:paramPage("Plaits / "+t.label(),new String[]{"Harmonics","Timbre","Morph","Decay","LPG colour"},
                        new float[]{t.harmonics,t.timbre,t.morph,t.decay,t.lpgColour},values->{
                            t.harmonics=values[0];t.timbre=values[1];t.morph=values[2];t.decay=values[3];t.lpgColour=values[4];engine.syncPatch(t.index);
                        });break;
                case 4:paramPage("Rings / "+t.label(),new String[]{"Structure","Brightness","Damping","Position"},
                        new float[]{t.structure,t.brightness,t.damping,t.position},values->{
                            t.structure=values[0];t.brightness=values[1];t.damping=values[2];t.position=values[3];engine.syncPatch(t.index);
                        });break;
                case 5:tapeMenu();break;
                case 6:paramPage("Synthesis mixer / "+t.label(),new String[]{"Voice gain","Auxiliary output mix"},
                        new float[]{t.synthGain,t.auxMix},values->{t.synthGain=values[0];t.auxMix=values[1];engine.syncPatch(t.index);});break;
                case 7:synchronized(engine){engine.tapeMix=0;engine.tapeDrive=0.3f;engine.tapeWow=0.1f;engine.tapeFlutter=0.12f;engine.tapeHiss=0;engine.tapeWear=0;engine.tapeDelay=0;engine.syncTape();}autosave();break;
            }
        }).show();
    }
    private void tapeMenu(){
        paramPage("Master tape emulator",new String[]{"Wet/dry","Saturation","Wow","Flutter","Hiss","Dropouts / wear","Echo feedback"},
            new float[]{engine.tapeMix,engine.tapeDrive,engine.tapeWow,engine.tapeFlutter,engine.tapeHiss,engine.tapeWear,engine.tapeDelay},values->{
                engine.tapeMix=values[0];engine.tapeDrive=values[1];engine.tapeWow=values[2];engine.tapeFlutter=values[3];
                engine.tapeHiss=values[4];engine.tapeWear=values[5];engine.tapeDelay=values[6];engine.syncTape();
            });
    }
    interface ParameterSink { void apply(float[] values); }
    private void paramPage(String title,String[] names,float[] values,ParameterSink onChange){
        ScrollView scroll=new ScrollView(this);
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(14*getResources().getDisplayMetrics().density);
        rows.setPadding(pad,pad,pad,pad);
        scroll.addView(rows);
        for(int i=0;i<names.length;i++){
            final int k=i;
            TextView label=new TextView(this);label.setText(names[i]+"  "+Math.round(values[i]*100)+"%");label.setTextSize(16);
            rows.addView(label);
            SeekBar bar=new SeekBar(this);bar.setMax(1000);bar.setProgress(Math.round(values[i]*1000));
            bar.setPadding(0,5,0,12);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                @Override public void onProgressChanged(SeekBar sb,int progress,boolean fromUser){
                    if(!fromUser)return;
                    values[k]=progress/1000f;label.setText(names[k]+"  "+Math.round(values[k]*100)+"%");
                    synchronized(engine){onChange.apply(values);}
                    view.invalidate();
                }
                @Override public void onStartTrackingTouch(SeekBar sb){}
                @Override public void onStopTrackingTouch(SeekBar sb){autosave();}
            });
            rows.addView(bar);
        }
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Done",(d,w)->{autosave();view.invalidate();}).show();
    }
    private void chooseWave(){choice("Internal synth waveform",Model.WAVES,engine.selected().wave,i->engine.selected().wave=i);}
    private void chooseScale(){choice("Scale",Model.SCALES,engine.selected().scale,i->engine.selected().scale=i);}
    private void chooseYMode(){choice("Y-axis sound parameter",Model.Y_MODES,engine.selected().yMode,i->engine.selected().yMode=i);}
    private void chooseOctaves(){
        String[] options={"1 octave","2 octaves","3 octaves","4 octaves","5 octaves","6 octaves"};
        choice("X-axis note range",options,engine.selected().octaves-1,i->engine.selected().octaves=i+1);
    }
    private void chooseBars(){
        String[] names={"1 bar","2 bars","3 bars","4 bars","8 bars"};int[] bars={1,2,3,4,8};
        int selected=0;for(int i=0;i<bars.length;i++)if(engine.selected().bars==bars[i])selected=i;
        choice("Track loop length",names,selected,i->engine.selected().bars=bars[i]);
    }
    private void gateMenu(){
        String[] names={"Gate 20%","Gate 35%","Gate 50%","Gate 65%","Gate 80%","Gate 100%","Speed: 1/4","Speed: 1/8","Speed: 1/16","Speed: 1/32"};
        int[] gates={20,35,50,65,80,100};
        new AlertDialog.Builder(this).setTitle("Gate arpeggiator").setItems(names,(d,i)->{
            synchronized(engine){
                Model.Track t=engine.selected();
                if(i<6)t.gatePercent=gates[i];else t.gateDivision=new int[]{1,2,4,8}[i-6];
                t.gateArp=true;
            }
            autosave();view.invalidate();
        }).show();
    }
    private void confirmClear(){
        new AlertDialog.Builder(this).setTitle("Clear "+engine.selected().label()+"?")
            .setMessage("Erase this layer's recorded path or MIDI events? This cannot be undone.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{
                synchronized(engine){engine.clearSelected();}autosave();view.invalidate();
            }).show();
    }
    private void midiMenu(){
        String[] options={"Choose MIDI output (USB/Bluetooth/virtual)","Choose MIDI input (CC controls)",
            "Change this layer's MIDI channel","Internal synth: "+(engine.internalSound?"ON":"OFF"),"All notes OFF (panic)"};
        new AlertDialog.Builder(this).setTitle("MIDI connections / audio").setItems(options,(d,i)->{
            switch(i){
                case 0:midiDeviceMenu(false);break;case 1:midiDeviceMenu(true);break;
                case 2:
                    editNumber("MIDI channel (1 to 16)",1,16,engine.selected().channel+1,
                        n->engine.selected().channel=n-1);break;
                case 3:synchronized(engine){engine.toggleSound();}autosave();break;
                case 4:synchronized(engine){engine.panic();}break;
            }
        }).show();
    }
    private void midiDeviceMenu(boolean input){
        ArrayList<MidiIO.Endpoint> eps=input?midi.inputs():midi.outputs();
        String[] labels=new String[eps.size()+1];labels[0]="None (disconnected)";
        for(int i=0;i<eps.size();i++)labels[i+1]=eps.get(i).name;
        new AlertDialog.Builder(this).setTitle(input?"MIDI IN":"MIDI OUT").setItems(labels,(d,i)->{
            MidiIO.Endpoint endpoint=i==0?null:eps.get(i-1);
            if(input)midi.selectInput(endpoint);else midi.selectOutput(endpoint);
        }).setNegativeButton("Cancel",null).show();
    }
    private void settingsMenu(){
        String[] options={"Presets and legacy import","Set master BPM","Pause other tracks while recording: "+(engine.pauseOthersDuringRecord?"ON":"OFF"),
            "Overdub selected track: "+(engine.selected().overdub?"ON":"OFF"),"All notes OFF", "Export project JSON","Import project JSON","Help"};
        new AlertDialog.Builder(this).setTitle("XY8 settings").setItems(options,(d,i)->{
            switch(i){
                case 0:presetMenu();return;
                case 1:editNumber("Master BPM",30,300,engine.bpm,n->engine.bpm=n);return;
                case 2:synchronized(engine){engine.pauseOthersDuringRecord=!engine.pauseOthersDuringRecord;}break;
                case 3:synchronized(engine){engine.selected().overdub=!engine.selected().overdub;engine.selected().hasBeenEdited=false;}break;
                case 4:synchronized(engine){engine.panic();}break;
                case 5:exportJson();return;
                case 6:importJson();return;
                case 7:showHelp();return;
            }
            autosave();view.invalidate();
        }).show();
    }
    private void presetMenu(){
        String[] options={"Save entire 8-layer project to slot 1","Save entire 8-layer project to slot 2",
            "Save entire 8-layer project to slot 3","Save entire 8-layer project to slot 4",
            "Load project slot 1","Load project slot 2","Load project slot 3","Load project slot 4",
            "Import original CC data banks (1-4)","Import original routing banks (1-4)",
            "Import most recent desktop routing"};
        new AlertDialog.Builder(this).setTitle("Project banks / legacy presets").setItems(options,(d,i)->{
            if(i<4){
                try{
                    synchronized(engine){prefs.edit().putString("slot"+i,engine.save().toString()).apply();}
                    toast("Saved project slot "+(i+1));
                }catch(Exception ex){toast("Save failed");}
            }else if(i<8){
                String stored=prefs.getString("slot"+(i-4),null);
                if(stored==null){toast("Empty project slot");return;}
                try{synchronized(engine){engine.load(new JSONObject(stored));}toast("Loaded slot "+(i-3));}
                catch(Exception ex){toast("Preset read failed");}
            }else if(i==8)legacyMenu(false);
            else if(i==9)legacyMenu(true);
            else {
                boolean ok; synchronized(engine){ok=engine.loadLegacyTemporaryRouting();}
                toast(ok?"Desktop routing imported":"Routing file not found");
            }
            autosave();view.invalidate();
        }).show();
    }
    private void legacyMenu(boolean routing){
        String[] names={"Legacy slot 1","Legacy slot 2","Legacy slot 3","Legacy slot 4"};
        new AlertDialog.Builder(this).setTitle(routing?"Import CC routing":"Import CC automation")
            .setItems(names,(d,i)->{
                boolean ok;
                synchronized(engine){ok=routing?engine.loadLegacyRouting(i):engine.loadLegacyData(i);}
                toast(ok?"Imported "+names[i]:"Legacy slot empty or invalid");
                autosave();view.invalidate();
            }).show();
    }
    private void exportJson(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,"XY8-project.json");startActivityForResult(intent,EXPORT_CODE);
    }
    private void importJson(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");
        startActivityForResult(intent,IMPORT_CODE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            if(request==EXPORT_CODE){
                String s; synchronized(engine){s=engine.save().toString(2);}
                try(OutputStream os=getContentResolver().openOutputStream(uri)){
                    if(os==null)throw new Exception("No write stream");
                    os.write(s.getBytes(StandardCharsets.UTF_8));
                }
                toast("Project JSON exported");
            }else if(request==IMPORT_CODE){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream is=getContentResolver().openInputStream(uri)){
                    if(is==null)throw new Exception("No read stream");
                    byte[] block=new byte[4096];int n;
                    while((n=is.read(block))>=0){bytes.write(block,0,n);if(bytes.size()>5_000_000)throw new Exception("Project too large");}
                }
                JSONObject loaded=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                if(!"XY8-v1".equals(loaded.optString("format")))throw new Exception("Not an XY8 project");
                synchronized(engine){engine.load(loaded);}
                autosave();toast("Project imported");
            }
        }catch(Exception ex){toast("Project I/O: "+ex.getMessage());}
        view.invalidate();
    }
    private void showHelp(){
        new AlertDialog.Builder(this).setTitle("XY8 touch controls")
            .setMessage("BANK CC / NOTE: select four automation or four note layers.\n\n"
                +"1–4: select a layer. The selected layer is shown on the XY pad; all other active layers keep running.\n\n"
                +"PLAY / STOP: global transport. REC: arm recording for selected layer, then draw on the pad. Tap REC again to disarm.\n\n"
                +"MUTE / SOLO: isolate tracks. CLEAR: erase selected layer with confirmation. LOOP: repeat or play once.\n\n"
                +"CC: X and Y have separate MIDI CC numbers and MIN/MAX ranges. SPEED chooses original sample-divisor timing; SYNC stretches loop to measures.\n\n"
                +"NOTE: X follows the selected key, scale and octave range. Y controls patch expression; CHORD mode picks chord quality. ARP retriggers notes at a rhythmic gate rate.\n\n"
                +"BANKS: four complete-project slots and imports for the four original Processing CC data/routing banks. SET has overdub, JSON import/export and pause-others options.\n\n"
                +"SOUND on a note layer opens Plaits/Rings engine and model selection, synthesis parameter pages, and the shared tape emulator. Plaits has 24 original engine models. Rings offers modal, string and resonator variants.\n\n"
                +"MIDI: choose an Android MIDI endpoint. Internal audio plays even without external MIDI hardware.")
            .setPositiveButton("Close",null).show();
    }
}
