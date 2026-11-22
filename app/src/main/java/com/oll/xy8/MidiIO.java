package com.oll.xy8;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceInputPort;
import android.media.midi.MidiDeviceOutputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;

final class MidiIO {
    interface Listener { void incomingCC(int channel,int cc,int value); void notice(String message); }
    static final class Endpoint {
        final MidiDeviceInfo info;final int port;
        final String name;
        Endpoint(MidiDeviceInfo i,int p,String n){info=i;port=p;name=n;}
    }
    private final MidiManager manager;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MidiDevice outDevice,inDevice;
    private MidiDeviceInputPort outPort;
    private MidiDeviceOutputPort inPort;
    private String outputName="None",inputName="None";
    MidiIO(Context context,Listener listener){
        this.listener=listener;
        manager=(MidiManager)context.getSystemService(Context.MIDI_SERVICE);
    }
    ArrayList<Endpoint> outputs(){return list(MidiDeviceInfo.PortInfo.TYPE_INPUT);}
    ArrayList<Endpoint> inputs(){return list(MidiDeviceInfo.PortInfo.TYPE_OUTPUT);}
    private ArrayList<Endpoint> list(int portType){
        ArrayList<Endpoint> result=new ArrayList<>();
        if(manager==null)return result;
        for(MidiDeviceInfo info:manager.getDevices()){
            for(MidiDeviceInfo.PortInfo port:info.getPorts()){
                if(port.getType()==portType){
                    String n=info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
                    if(n==null||n.trim().isEmpty()) n="MIDI Device "+info.getId();
                    result.add(new Endpoint(info,port.getPortNumber(),n+" / port "+(port.getPortNumber()+1)));
                }
            }
        }
        return result;
    }
    void selectOutput(Endpoint endpoint){
        closeOutput();
        if(endpoint==null){listener.notice("MIDI output: None");return;}
        manager.openDevice(endpoint.info,device->{
            if(device==null){listener.notice("Could not open MIDI output");return;}
            MidiDeviceInputPort port=device.openInputPort(endpoint.port);
            if(port==null){try{device.close();}catch(IOException ignored){}listener.notice("MIDI output port unavailable");return;}
            synchronized(this){outDevice=device;outPort=port;outputName=endpoint.name;}
            listener.notice("MIDI OUT: "+endpoint.name);
        },handler);
    }
    void selectInput(Endpoint endpoint){
        closeInput();
        if(endpoint==null){listener.notice("MIDI input: None");return;}
        manager.openDevice(endpoint.info,device->{
            if(device==null){listener.notice("Could not open MIDI input");return;}
            MidiDeviceOutputPort port=device.openOutputPort(endpoint.port);
            if(port==null){try{device.close();}catch(IOException ignored){}listener.notice("MIDI input port unavailable");return;}
            port.connect(receiver);
            synchronized(this){inDevice=device;inPort=port;inputName=endpoint.name;}
            listener.notice("MIDI IN: "+endpoint.name);
        },handler);
    }
    private final MidiReceiver receiver=new MidiReceiver(){
        int runningStatus=0;
        @Override public void onSend(byte[] data,int offset,int count,long timestamp){
            for(int i=offset;i<offset+count;){
                int b=data[i]&255;
                if(b>=248){i++;continue;}
                if(b>=128){
                    if(b>=240){runningStatus=0;i++;continue;}
                    runningStatus=b;i++;
                }
                if(runningStatus==0){i++;continue;}
                int cmd=runningStatus&240;
                int need=(cmd==192||cmd==208)?1:2;
                if(i+need>offset+count)break;
                int a=data[i++]&127;
                int v=need==2?data[i++]&127:0;
                if(cmd==176)listener.incomingCC(runningStatus&15,a,v);
            }
        }
    };
    synchronized void send(int status,int data1,int data2){
        if(outPort==null)return;
        byte[] b={(byte)(status&255),(byte)(data1&127),(byte)(data2&127)};
        try{outPort.send(b,0,b.length);}catch(IOException e){listener.notice("MIDI send failed: "+e.getMessage());}
    }
    void cc(int channel,int controller,int value){send(176|(channel&15),controller,value);}
    void on(int channel,int note,int velocity){send(144|(channel&15),note,velocity);}
    void off(int channel,int note){send(128|(channel&15),note,0);}
    synchronized String outputName(){return outputName;}
    synchronized String inputName(){return inputName;}
    synchronized void closeOutput(){
        try{if(outPort!=null)outPort.close();}catch(IOException ignored){}
        try{if(outDevice!=null)outDevice.close();}catch(IOException ignored){}
        outPort=null;outDevice=null;outputName="None";
    }
    synchronized void closeInput(){
        try{if(inPort!=null)inPort.close();}catch(IOException ignored){}
        try{if(inDevice!=null)inDevice.close();}catch(IOException ignored){}
        inPort=null;inDevice=null;inputName="None";
    }
    void close(){closeInput();closeOutput();}
}
