package com.medvault.app
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import java.util.concurrent.atomic.AtomicBoolean
internal class DockBackdropCapture(private val sourceWindow:Window,private val onBitmapReady:(Bitmap)->Unit){
 private val inFlight=AtomicBoolean(false);@Volatile private var closed=false;@Volatile private var pendingRect:Rect?=null
 fun capture(sourceRect:Rect){
  if(closed||Build.VERSION.SDK_INT<Build.VERSION_CODES.O||sourceRect.width()<=0||sourceRect.height()<=0)return
  if(!inFlight.compareAndSet(false,true)){pendingRect=Rect(sourceRect);return}
  val bitmap=try{Bitmap.createBitmap(sourceRect.width(),sourceRect.height(),Bitmap.Config.ARGB_8888)}catch(_:OutOfMemoryError){inFlight.set(false);return}
  try{PixelCopy.request(sourceWindow,Rect(sourceRect),bitmap,{result->
   inFlight.set(false);if(closed)bitmap.recycle() else if(result==PixelCopy.SUCCESS)onBitmapReady(bitmap) else bitmap.recycle()
   val next=pendingRect;pendingRect=null;if(!closed&&next!=null&&next!=sourceRect)capture(next)
  },Handler(Looper.getMainLooper()))}catch(_:RuntimeException){inFlight.set(false);bitmap.recycle();val next=pendingRect;pendingRect=null;if(!closed&&next!=null)capture(next)}
 }
 fun close(){closed=true;pendingRect=null}
}
internal tailrec fun Context.findActivity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.findActivity();else->null}
