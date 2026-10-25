package com.forcemind.chatgpt.profiles;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small monochrome vector icons with a consistent 24-unit grid. */
final class StudioIcon extends Drawable {
    private final String name;private final Path shape=new Path();private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    StudioIcon(String name){this.name=name;paint.setColor(Color.rgb(23,23,23));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    private void path(Canvas c,float...points){Path p=shape;p.reset();p.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)p.lineTo(points[i],points[i+1]);c.drawPath(p,paint);}
    @Override public void draw(Canvas canvas){canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        switch(name){
            case "chevron"->path(canvas,7,10,12,15,17,10);case "back"->path(canvas,14,5,7,12,14,19);
            case "plus"->{path(canvas,12,5,12,19);path(canvas,5,12,19,12);}
            case "check"->path(canvas,5,12,9,16,19,6);
            case "chat"->{path(canvas,3,4,21,4,21,17,9,17,4,21,4,17,3,17,3,4);path(canvas,7,9,17,9);}
            case "folder"->path(canvas,3,5,10,5,12,8,21,8,21,20,3,20,3,5);
            case "file"->{path(canvas,5,3,14,3,19,8,19,21,5,21,5,3);path(canvas,14,3,14,9,19,9);path(canvas,8,14,16,14);}
            case "edit"->path(canvas,15,3,21,9,9,21,3,21,3,15,15,3);
            case "spark"->path(canvas,12,3,15,9,21,12,15,15,12,21,9,15,3,12,9,9,12,3);
            case "account"->{canvas.drawCircle(12,8,4,paint);canvas.drawArc(4,13,20,27,180,180,false,paint);}
            case "close"->{path(canvas,7,7,17,17);path(canvas,7,17,17,7);}
            case "search"->{canvas.drawCircle(10,10,6,paint);path(canvas,15,15,20,20);}
            case "history"->{canvas.drawArc(3,3,21,21,210,310,false,paint);path(canvas,3,4,3,11,10,11);path(canvas,12,7,12,12,15,14);}
            case "assets"->{path(canvas,3,7,12,3,21,7,21,19,12,22,3,19,3,7,12,11,21,7);path(canvas,12,11,12,22);}
            case "pin"->{path(canvas,8,4,16,4,15,11,18,14,6,14,9,11,8,4);path(canvas,12,14,12,21);}
            default->{paint.setStyle(Paint.Style.FILL);canvas.drawCircle(5,12,1.6f,paint);canvas.drawCircle(12,12,1.6f,paint);canvas.drawCircle(19,12,1.6f,paint);paint.setStyle(Paint.Style.STROKE);}
        }canvas.restore();}
    @Override public int getIntrinsicWidth(){return 24;}@Override public int getIntrinsicHeight(){return 24;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
