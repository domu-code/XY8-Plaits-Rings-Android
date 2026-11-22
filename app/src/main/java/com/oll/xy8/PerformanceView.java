package com.oll.xy8;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.DisplayCutout;
import android.os.Build;
import java.util.ArrayList;

final class PerformanceView extends View {
    private static final int BG=0xff0d1119, PANEL=0xff171d27, BORDER=0xff384556;
    private static final int TEXT=0xffe5ecf6, DIM=0xff8390a4, ON=0xff283f52;
    private final MainActivity activity;
    private final Engine engine;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Hit> hits=new ArrayList<>();
    private final float density;
    private float ww,hh,padX,padY,padW,padH,panelX,panelW,footer;
    private int safeLeft=0,safeTop=0,safeRight=0,safeBottom=0;
    private boolean onPad=false;
    private int finger=-1;
    private static final class Hit {
        final RectF r;final String command;
        Hit(float x,float y,float w,float h,String command){r=new RectF(x,y,x+w,y+h);this.command=command;}
    }
    PerformanceView(Context ctx,Engine engine){
        super(ctx);activity=(MainActivity)ctx;this.engine=engine;
        density=getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P){
            setOnApplyWindowInsetsListener((v,insets)->{
                DisplayCutout cutout=insets.getDisplayCutout();
                int l=cutout==null?0:cutout.getSafeInsetLeft();
                int t=cutout==null?0:cutout.getSafeInsetTop();
                int r=cutout==null?0:cutout.getSafeInsetRight();
                int b=cutout==null?0:cutout.getSafeInsetBottom();
                if(safeLeft!=l||safeTop!=t||safeRight!=r||safeBottom!=b){
                    safeLeft=l;safeTop=t;safeRight=r;safeBottom=b;
                    invalidate();
                }
                return insets;
            });
        }
    }
    @Override protected void onDraw(Canvas raw){
        super.onDraw(raw);
        raw.drawColor(BG);
        // Do not place controls under a camera cutout in landscape.
        // Translation is in pixels; the remaining layout is in dp units.
        raw.save();raw.translate(safeLeft,safeTop);raw.scale(density,density);
        ww=Math.max(1,getWidth()-safeLeft-safeRight)/density;
        hh=Math.max(1,getHeight()-safeTop-safeBottom)/density;
        hits.clear();
        footer=Math.max(51,Math.min(58,hh*0.15f));
        float header=51;
        panelW=Math.min(285,Math.max(205,ww*0.30f));
        panelX=ww-panelW-8;
        padX=8;padY=header+5;padW=panelX-padX-7;padH=hh-footer-padY-7;
        synchronized(engine){
            header(raw,header);
            drawPad(raw);
            drawPanel(raw);
            drawFooter(raw);
        }
        raw.restore();
    }
    private void rect(Canvas c,float x,float y,float w,float h,int color,float r){
        p.setColor(color);p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(x,y,x+w,y+h,r,r,p);
    }
    private void stroke(Canvas c,float x,float y,float w,float h,int color,float radius){
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f);
        c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);p.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){
        p.setColor(color);p.setTextSize(size);p.setTypeface(Typeface.create("monospace",bold?Typeface.BOLD:Typeface.NORMAL));
        p.setStyle(Paint.Style.FILL);c.drawText(s,x,y,p);
    }
    private void centered(Canvas c,String s,float x,float y,float w,float h,float size,int color){
        p.setColor(color);p.setTextSize(size);p.setTypeface(Typeface.create("monospace",Typeface.BOLD));
        p.setStyle(Paint.Style.FILL);
        Paint.FontMetrics f=p.getFontMetrics();
        float bx=x+(w-p.measureText(s))*0.5f;
        float by=y+h*0.5f-(f.ascent+f.descent)*0.5f;
        c.drawText(s,bx,by,p);
    }
    private void button(Canvas c,String command,String label,float x,float y,float w,float h,boolean selected,int accent){
        rect(c,x,y,w,h,selected?accent:PANEL,7);
        stroke(c,x,y,w,h,selected?accent:BORDER,7);
        centered(c,label,x,y,w,h,label.length()>11?10:11,selected?BG:TEXT);
        hits.add(new Hit(x,y,w,h,command));
    }
    private void header(Canvas c,float h){
        rect(c,0,0,ww,h,0xff111a23,0);
        button(c,"bankcc","CC",8,5,48,40,engine.bank==Model.CC,0xff55b9f9);
        button(c,"banknote","NOTE",60,5,58,40,engine.bank==Model.NOTE,0xffedbf5b);
        int active=engine.selection();
        float bw=Math.min(51,Math.max(40,(ww-350)/8));
        for(int i=0;i<4;i++){
            Model.Track t=engine.track(engine.bank,i);
            float x=124+i*(bw+4);
            int col=Model.COLORS[i];
            button(c,"layer"+i,""+(i+1),x,5,bw,40,i==active,col);
            if(t.muted){rect(c,x+7,40,bw-14,2,0xfff27b80,0);}
            else if(t.armed){rect(c,x+7,40,bw-14,2,0xfffcf8a0,0);}
        }
        if(ww>640){
            String status=engine.playing?"RUN":"STOP";
            text(c,"XY8  "+status+"   "+engine.bpm+" BPM",124+4*(bw+4)+11,29,11,DIM,true);
        }
        button(c,"presets","BANKS",ww-194,5,57,40,false,0);
        button(c,"midi","MIDI",ww-131,5,56,40,false,0);
        button(c,"settings","SET",ww-69,5,61,40,false,0);
    }
    private void drawPad(Canvas c){
        Model.Track t=engine.selected();
        rect(c,padX,padY,padW,padH,0xff101825,8);
        c.save();c.clipRect(padX,padY,padX+padW,padY+padH);
        p.setStrokeWidth(1);p.setColor(0xff283448);
        for(int i=1;i<12;i++){
            float x=padX+i*padW/12f;
            c.drawLine(x,padY,x,padY+padH,p);
        }
        for(int i=1;i<6;i++){
            float y=padY+i*padH/6f;
            c.drawLine(padX,y,padX+padW,y,p);
        }
        int accent=Model.COLORS[t.index];
        if(t.bank==Model.NOTE){
            int[] intervals=Model.INTERVALS[t.scale];int n=Math.max(1,Math.min(60,t.octaves*intervals.length));
            for(int i=1;i<n;i++){
                float x=padX+i*padW/n;
                p.setColor((i%intervals.length==0)?0x5069b7f5:0x242f93c7);
                c.drawLine(x,padY,x,padY+padH,p);
            }
        }
        if(t.bank==Model.CC){
            if(t.points.size()>0){
                Path path=new Path();
                for(int i=0;i<t.points.size();i++){
                    Model.Point xy=t.points.get(i);
                    float x=padX+xy.x*padW,y=padY+xy.y*padH;
                    if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
                }
                p.setColor(accent);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.2f);
                c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
            }
        }else if(!t.events.isEmpty()){
            for(Model.Event e:t.events){
                if(e.type!=Model.Event.ON)continue;
                float fraction=(e.ms/(float)engine.noteLoopMs(t));
                float x=padX+fraction*padW;
                float y=padY+padH*(1-Model.clamp((e.note-t.root)/(12f*Math.max(1,t.octaves)),0,1));
                p.setColor(accent);c.drawCircle(x,y,3.5f,p);
            }
        }
        if(t.cursorX>=0){
            float x=padX+t.cursorX*padW,y=padY+(t.bank==Model.CC?t.cursorY:1-t.cursorY)*padH;
            p.setColor(accent);p.setStrokeWidth(1.8f);
            c.drawLine(x,padY,x,padY+padH,p);
            c.drawLine(padX,y,padX+padW,y,p);
            p.setStyle(Paint.Style.FILL);p.setColor(TEXT);c.drawCircle(x,y,7,p);
            p.setColor(accent);c.drawCircle(x,y,4.3f,p);
        }
        if(engine.playing){
            long now=engine.now();
            long span=t.bank==Model.CC?(t.syncCC?engine.noteLoopMs(t):t.points.size()*16L*t.speed):engine.noteLoopMs(t);
            if(span>0){
                float ph=(Math.floorMod(now-engine.startedAt,span))/(float)span;
                float x=padX+padW*ph;
                p.setColor(0xffbed6e0);p.setStrokeWidth(1);
                c.drawLine(x,padY,x,padY+padH,p);
            }
        }
        String sub=t.bank==Model.CC?"X: CC "+t.ccX+"    Y: CC "+t.ccY:
            "X: NOTE / "+Model.SCALES[t.scale]+"     Y: "+Model.Y_MODES[t.yMode];
        rect(c,padX+6,padY+6,Math.min(padW-12,350),22,0xc0111924,5);
        text(c,sub,padX+14,padY+21,10,TEXT,true);
        if(t.points.isEmpty()&&t.events.isEmpty()){
            centered(c,t.armed?"DRAW TO RECORD":"TOUCH TO PLAY  /  ARM TO RECORD",
                padX,padY+padH/2-14,padW,28,Math.min(14,padW/28),0xff566577);
        }
        c.restore();stroke(c,padX,padY,padW,padH,0xff4a5c72,8);
    }
    private void drawPanel(Canvas c){
        Model.Track t=engine.selected();
        float x=panelX,y=padY,w=panelW,h=padH;
        rect(c,x,y,w,h,0xff141c28,8);stroke(c,x,y,w,h,BORDER,8);
        text(c,t.label()+"   "+(t.bank==Model.CC?"AUTOMATION":Model.PROGRAMS[t.program]),x+11,y+20,13,Model.COLORS[t.index],true);
        String mode=t.muted?"MUTED":(!t.playing?"PAUSED":t.armed?"REC ARMED":engine.playing?"PLAYING":"READY");
        text(c,mode+"  CH "+(t.channel+1),x+11,y+38,10,DIM,false);
        float rowY=y+47;
        float rh=Math.max(31,Math.min(42,(h-51)/5));
        float bw=(w-21)/2;
        if(t.bank==Model.CC){
            panelPair(c,x,rowY,bw,rh,"xcc","X CC "+t.ccX,"ycc","Y CC "+t.ccY);
            panelPair(c,x,rowY+rh,bw,rh,"xmin","X MIN "+t.rangeXMin,"xmax","X MAX "+t.rangeXMax);
            panelPair(c,x,rowY+2*rh,bw,rh,"ymin","Y MIN "+t.rangeYMin,"ymax","Y MAX "+t.rangeYMax);
            panelPair(c,x,rowY+3*rh,bw,rh,"speed","SPEED /"+t.speed,"ccsync",t.syncCC?"SYNC "+t.bars+" BAR":"FREE LOOP");
            panelPair(c,x,rowY+4*rh,bw,rh,"layerplay",t.playing?"PAUSE LAYER":"PLAY LAYER","center","CC -> 64");
        }else{
            panelPair(c,x,rowY,bw,rh,"program","PATCH "+Model.PROGRAMS[t.program],"sound","SOUND "+Model.SYNTHS[t.synthEngine]);
            panelPair(c,x,rowY+rh,bw,rh,"scale",Model.SCALES[t.scale],"root","KEY "+noteName(t.root));
            panelPair(c,x,rowY+2*rh,bw,rh,"octaves","RANGE "+t.octaves+" OCT","ymode","Y "+Model.Y_MODES[t.yMode]);
            panelPair(c,x,rowY+3*rh,bw,rh,"arp",t.gateArp?"ARP ON":"ARP OFF","gate","GATE "+t.gatePercent+"%");
            panelPair(c,x,rowY+4*rh,bw,rh,"bars",t.bars+" BARS","layerplay",t.playing?"PAUSE LAYER":"PLAY LAYER");
        }
    }
    private void panelPair(Canvas c,float x,float y,float bw,float h,String a,String al,String b,String bl){
        button(c,a,al,x+5,y,bw-2,h-4,false,0);
        button(c,b,bl,x+bw+10,y,bw-3,h-4,false,0);
    }
    private String noteName(int note){
        String[] n={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        return n[note%12]+(note/12-1);
    }
    private void drawFooter(Canvas c){
        float y=hh-footer;
        rect(c,0,y,ww,footer,0xff111a23,0);
        float x=6,space=4,bw=(ww-12-8*space)/9f;
        Model.Track t=engine.selected();
        String[] ids={"play","stop","rec","mute","solo","clear","loop","bpmminus","bpmplus"};
        String[] names={"PLAY","STOP",t.armed?"REC ON":"REC",t.muted?"UNMUTE":"MUTE",t.solo?"SOLO ON":"SOLO","CLEAR",engine.loop?"LOOP ON":"1-SHOT","BPM -","BPM +"};
        boolean[] states={engine.playing,false,t.armed,t.muted,t.solo,false,engine.loop,false,false};
        int[] colors={0xff56d9a8,0xffdd746b,0xffe69b7f,0xffcf737a,0xffedc46e,0xff637287,0xff70a4d7,0xff718da8,0xff718da8};
        for(int i=0;i<9;i++){
            button(c,ids[i],names[i],x,y+5,bw,footer-10,states[i],colors[i]);
            x+=bw+space;
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        int act=e.getActionMasked();
        if(act==MotionEvent.ACTION_DOWN){
            finger=e.getPointerId(0);
            float x=(e.getX()-safeLeft)/density,y=(e.getY()-safeTop)/density;
            for(int i=hits.size()-1;i>=0;i--){
                Hit hit=hits.get(i);
                if(hit.r.contains(x,y)){
                    activity.command(hit.command);invalidate();return true;
                }
            }
            if(x>=padX&&x<=padX+padW&&y>=padY&&y<=padY+padH){
                onPad=true;
                synchronized(engine){engine.touchDown((x-padX)/padW,engine.bank==Model.CC?(y-padY)/padH:1-(y-padY)/padH);}
                invalidate();return true;
            }
            return true;
        }
        if(act==MotionEvent.ACTION_MOVE&&onPad){
            int idx=e.findPointerIndex(finger);if(idx<0)idx=0;
            float x=(e.getX(idx)-safeLeft)/density,y=(e.getY(idx)-safeTop)/density;
            synchronized(engine){engine.touchMove((x-padX)/padW,engine.bank==Model.CC?(y-padY)/padH:1-(y-padY)/padH);}
            invalidate();return true;
        }
        if((act==MotionEvent.ACTION_UP||act==MotionEvent.ACTION_CANCEL)&&onPad){
            synchronized(engine){engine.touchUp();}onPad=false;finger=-1;
            activity.autosave();invalidate();return true;
        }
        return true;
    }
}
