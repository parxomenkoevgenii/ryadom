package local.ryadom.chat;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.util.*;

public final class HostService extends Service {
 static volatile HostService active;static volatile String lastError="";
 volatile ChatHost host;private LanNetwork network;private Discovery discovery;private WifiManager.MulticastLock multicast;private PowerManager.WakeLock wake;
 private ConnectivityManager.NetworkCallback callback;
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null&&"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(host!=null)return START_NOT_STICKY;active=this;lastError="";
  NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);notifications.createNotificationChannel(new NotificationChannel("ryadom-host","Главное устройство Рядом",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,HostService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification notification=new Notification.Builder(this,"ryadom-host").setSmallIcon(local.ryadom.chat.R.drawable.ic_launcher).setContentTitle("Рядом · этот телефон главный").setContentText("Чат работает в домашней сети, в том числе для ПК по кабелю").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Выключить чат",stop).build()).build();
  startForeground(4280,notification);
  try{
   network=LanNetwork.select(this,null);network.bindProcess();
   WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);multicast=wifi.createMulticastLock("Ryadom discovery");multicast.setReferenceCounted(false);multicast.acquire();
   wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Ryadom:host");wake.acquire();
   host=new ChatHost(new File(getFilesDir(),"host"),network.ips(),4280,name->{try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){MiniHttp.copy(in,out,-1);return out.toByteArray();}},()->new Handler(getMainLooper()).post(this::stopSelf));
   List<Integer> prefixes=new ArrayList<>();for(LinkAddress address:network.addresses)prefixes.add(address.getPrefixLength());
   try{discovery=new Discovery(network.ips(),prefixes,Build.MODEL,network.network::bindSocket);}catch(Exception ignored){}
   callback=new ConnectivityManager.NetworkCallback(){@Override public void onLost(Network lost){if(lost.equals(network.network)){lastError="Домашняя сеть отключилась. Запустите чат заново после подключения к Wi-Fi.";new Handler(getMainLooper()).post(()->stopSelf());}}};
   network.manager.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback);
  }catch(Exception e){lastError=e.getMessage()==null?"Не удалось запустить чат":e.getMessage();stopSelf();}
  return START_NOT_STICKY;
 }
 @Override public void onDestroy(){if(callback!=null&&network!=null)try{network.manager.unregisterNetworkCallback(callback);}catch(Exception ignored){}if(discovery!=null)discovery.close();if(host!=null){host.close();host=null;}if(multicast!=null&&multicast.isHeld())multicast.release();if(wake!=null&&wake.isHeld())wake.release();if(network!=null)network.manager.bindProcessToNetwork(null);if(active==this)active=null;super.onDestroy();}
}
