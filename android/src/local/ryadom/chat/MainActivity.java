package local.ryadom.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.LinkAddress;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native LAN-only shell. No JavaScript bridge, analytics, cloud services or storage permissions. */
public final class MainActivity extends Activity {
 private static final int PICK_FILE=41, SAVE_FILE=42;
 private static final int BG=0xfffafbf8, INK=0xff354b37, MUTED=0xff8b9c81, GREEN=0xff176b56;
 private LinearLayout root;
 private WebView web;
 private EditText serverInput;
 private TextView errorText;
 private Button connectButton;
 private SharedPreferences prefs;
 private String base="", downloadUrl="", downloadCookie="";
 private DirectProxy proxy;
 private boolean hosting=false;
 private int screenGeneration=0;
 private ValueCallback<Uri[]> fileCallback;
 private final ExecutorService io=Executors.newFixedThreadPool(2);

 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  prefs=getSharedPreferences("ryadom",MODE_PRIVATE);
  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
  setContentView(root);
  root.setOnApplyWindowInsetsListener((v,insets)->{
   int top=insets.getSystemWindowInsetTop(),bottom=insets.getSystemWindowInsetBottom();
   if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());top=bars.top;bottom=Math.max(bars.bottom,keyboard.bottom);}
   root.setPadding(insets.getSystemWindowInsetLeft(),top,insets.getSystemWindowInsetRight(),bottom);return WindowInsets.CONSUMED;
  });
  showConnect("");
 }
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private GradientDrawable box(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
 private LinearLayout.LayoutParams params(int w,int h){return new LinearLayout.LayoutParams(w,h);}
 private void space(LinearLayout layout,int amount){View v=new View(this);layout.addView(v,params(1,dp(amount)));}
 private void showConnect(String error) {
  screenGeneration++;
  if(web!=null){web.stopLoading();web.loadUrl("about:blank");web.destroy();web=null;}
  if(proxy!=null){proxy.close();proxy=null;}hosting=false;
  root.removeAllViews();
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,params(-1,-1));
  LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setGravity(Gravity.CENTER);outer.setPadding(dp(28),dp(30),dp(28),dp(30));scroll.addView(outer,new ScrollView.LayoutParams(-1,-1));
  LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(27),dp(32),dp(27),dp(28));card.setBackground(box(Color.WHITE,22));outer.addView(card,params(-1,-2));
  TextView logo=text("р.",31,Color.WHITE);logo.setTypeface(Typeface.DEFAULT,Typeface.BOLD);logo.setGravity(Gravity.CENTER);logo.setBackground(box(GREEN,16));card.addView(logo,params(dp(56),dp(56)));space(card,32);
  TextView eyebrow=text("ЛИЧНЫЙ ЧАТ В ВАШЕЙ СЕТИ",10,MUTED);eyebrow.setLetterSpacing(.11f);card.addView(eyebrow);space(card,12);
  TextView title=text("Кто сегодня главный?",27,INK);title.setLetterSpacing(-.035f);card.addView(title);space(card,12);
  card.addView(text("Создайте чат на телефоне или подключитесь к другому устройству. Провод и Wi-Fi работают в одной домашней сети.",13,MUTED));space(card,20);
  Button hostButton=new Button(this);hostButton.setText(HostService.active!=null?"Открыть чат на этом телефоне":"Создать чат на этом телефоне");hostButton.setAllCaps(false);hostButton.setTextSize(13);hostButton.setTextColor(Color.WHITE);hostButton.setBackground(box(GREEN,10));card.addView(hostButton,params(-1,dp(50)));hostButton.setOnClickListener(v->startHosting());space(card,18);
  Button findButton=new Button(this);findButton.setText("Найти главное устройство в сети");findButton.setAllCaps(false);findButton.setTextSize(12);findButton.setTextColor(GREEN);card.addView(findButton,params(-1,dp(46)));findButton.setOnClickListener(v->findChats(findButton));space(card,14);
  card.addView(text("Или введите его домашний адрес",12,INK));space(card,9);
  serverInput=new EditText(this);serverInput.setSingleLine(true);serverInput.setTextSize(15);serverInput.setTextColor(INK);serverInput.setHintTextColor(0xffb0bda7);serverInput.setHint("192.168.1.10:4280");serverInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);serverInput.setPadding(dp(13),dp(8),dp(13),dp(8));serverInput.setBackground(box(0xfff2f6ed,9));serverInput.setText(prefs.getString("server",""));card.addView(serverInput,params(-1,dp(52)));space(card,10);
  card.addView(text("Адрес указан в разделе «Подключить устройство». ПК может быть подключён кабелем, телефон — по Wi-Fi. Нужна одна подсеть без изоляции клиентов.",11,MUTED));
  errorText=text(error,12,0xffa65042);space(card,12);card.addView(errorText);space(card,12);
  connectButton=new Button(this);connectButton.setText("Подключиться  →");connectButton.setTextSize(14);connectButton.setAllCaps(false);connectButton.setTextColor(Color.WHITE);connectButton.setBackground(box(GREEN,10));card.addView(connectButton,params(-1,dp(49)));connectButton.setOnClickListener(v->connect());
  space(card,20);card.addView(text("Happ может оставаться включённым. История хранится на выбранном главном устройстве и не переносится автоматически.",10,MUTED));space(outer,25);TextView caption=text("Рядом 0.1  ·  Домашняя сеть напрямую",11,MUTED);caption.setGravity(Gravity.CENTER);outer.addView(caption);
 }
 /** Reject DNS names, public addresses, credentials, paths and ambiguous IPv4 forms. */
 static String validateServer(String value) throws Exception {
  String input=value.trim();if(!input.startsWith("http://"))input="http://"+input;
  URL u=new URL(input);if(!u.getProtocol().equals("http")||u.getUserInfo()!=null||u.getQuery()!=null||u.getRef()!=null||(!u.getPath().isEmpty()&&!u.getPath().equals("/")))throw new Exception("Укажите только локальный адрес компьютера и порт.");
  String host=u.getHost();String[] parts=host.split("\\.");if(parts.length!=4)throw new Exception("Нужен локальный IPv4-адрес, например 192.168.1.10:4280.");
  int[] n=new int[4];for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new Exception("Проверьте адрес компьютера.");n[i]=Integer.parseInt(parts[i]);if(n[i]>255)throw new Exception("Проверьте адрес компьютера.");}
  boolean local=n[0]==10||(n[0]==192&&n[1]==168)||(n[0]==172&&n[1]>=16&&n[1]<=31)||(n[0]==169&&n[1]==254);
  if(!local)throw new Exception("Рядом подключается только к устройствам в локальной сети.");
  int port=u.getPort()==-1?4280:u.getPort();if(port<1||port>65535)throw new Exception("Проверьте номер порта.");
  return "http://"+host+":"+port;
 }
 private void connect(){
  if(HostService.active!=null){new AlertDialog.Builder(this).setTitle("Остановить чат на телефоне?").setMessage("Другие устройства отключатся. История сохранится на телефоне. Затем вы подключитесь к другому главному устройству.").setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(dialog,which)->{startService(new Intent(this,HostService.class).setAction("stop"));connectClient();}).show();return;}
  connectClient();
 }
 private void connectClient(){
  final String target;try{target=validateServer(serverInput.getText().toString());}catch(Exception e){errorText.setText(e.getMessage());return;}
  final int generation=screenGeneration;connectButton.setEnabled(false);connectButton.setText("Подключаемся…");errorText.setText("");
  io.execute(()->{String problem="";HttpURLConnection conn=null;DirectProxy local=null;try{URL remote=new URL(target);LanNetwork network=LanNetwork.select(this,remote.getHost());conn=network.connection(new URL(target+"/api/health"));conn.setConnectTimeout(4500);conn.setReadTimeout(4500);conn.setInstanceFollowRedirects(false);if(conn.getResponseCode()!=200)throw new Exception();ByteArrayOutputStream health=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] b=new byte[512];int n;while((n=in.read(b))!=-1){if(health.size()+n>4096)throw new Exception("Некорректный ответ устройства");health.write(b,0,n);}}String result=new String(health.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);if(!result.contains("\"app\":\"ryadom\""))throw new Exception();local=new DirectProxy(target,(host,port)->LanNetwork.select(this,host).connect(host,port));}catch(Exception e){problem="Нет прямой связи. Проверьте адрес и домашнюю Wi-Fi сеть. На главном устройстве разрешите «Рядом» входящие TCP 4280 и UDP 42800 (брандмауэр, в том числе сторонний: ESET, Kaspersky). Если Happ блокирует обход, добавьте «Рядом» в приложения Direct в Happ.\n"+(e.getMessage()==null?"":e.getMessage());}finally{if(conn!=null)conn.disconnect();}final String failure=problem;final DirectProxy ready=local;runOnUiThread(()->{if(isFinishing()||screenGeneration!=generation){if(ready!=null)ready.close();return;}if(failure.isEmpty()){if(proxy!=null)proxy.close();proxy=ready;base=proxy.base();hosting=false;prefs.edit().putString("server",target).apply();showChat();}else{connectButton.setEnabled(true);connectButton.setText("Подключиться  →");errorText.setText(failure);}});});
 }
 private void findChats(Button button){button.setEnabled(false);button.setText("Ищем в домашней сети…");final int generation=screenGeneration;io.execute(()->{List<Discovery.Found> found=new ArrayList<>();String problem="";WifiManager.MulticastLock lock=null;try{LanNetwork network=LanNetwork.select(this,null);WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);lock=wifi.createMulticastLock("Ryadom search");lock.acquire();List<Integer> prefixes=new ArrayList<>();for(LinkAddress a:network.addresses)prefixes.add(a.getPrefixLength());found=Discovery.find(network.ips(),prefixes,network.network::bindSocket);}catch(Exception e){problem=e.getMessage()==null?"Не удалось найти устройства":e.getMessage();}finally{if(lock!=null&&lock.isHeld())lock.release();}final List<Discovery.Found> results=found;final String error=problem;runOnUiThread(()->{if(isFinishing()||screenGeneration!=generation)return;button.setEnabled(true);button.setText("Найти главное устройство в сети");if(results.isEmpty()){errorText.setText(error.isEmpty()?"Чаты не найдены. Запустите чат на ПК или телефоне, либо введите адрес вручную. Проверьте, что сеть не гостевая и изоляция клиентов отключена.":error);return;}String[] labels=new String[results.size()];for(int i=0;i<labels.length;i++)labels[i]=results.get(i).toString();new AlertDialog.Builder(this).setTitle("Главное устройство").setItems(labels,(dialog,index)->{Discovery.Found f=results.get(index);serverInput.setText(f.address+":"+f.port);connect();}).setNegativeButton("Отмена",null).show();});});}
 private void startHosting(){
  if(HostService.active!=null&&HostService.active.host!=null){openHost();return;}
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},43);
  HostService.lastError="";startForegroundService(new Intent(this,HostService.class));connectButton.setEnabled(false);errorText.setText("Запускаем чат на телефоне…");waitForHost(screenGeneration,0);
 }
 private void waitForHost(int generation,int attempt){new Handler(getMainLooper()).postDelayed(()->{if(isFinishing()||screenGeneration!=generation)return;if(HostService.active!=null&&HostService.active.host!=null){openHost();return;}if(!HostService.lastError.isEmpty()||attempt>50){connectButton.setEnabled(true);errorText.setText(HostService.lastError.isEmpty()?"Не удалось запустить чат. Повторите попытку.":HostService.lastError);return;}waitForHost(generation,attempt+1);},150);}
 private void openHost(){if(proxy!=null){proxy.close();proxy=null;}hosting=true;base="http://127.0.0.1:4280";showChat();}
 private void changeRole(){new AlertDialog.Builder(this).setTitle("Выбрать главное устройство заново?").setMessage(hosting?"Чат на телефоне остановится. Переписка сохранится здесь. Неотправленный текст сохраните перед выходом.":"Вы выйдете из текущего чата. Неотправленный текст сохраните перед выходом.").setNegativeButton("Остаться",null).setPositiveButton("Выбрать",(d,w)->{if(hosting)startService(new Intent(this,HostService.class).setAction("stop"));showConnect("");}).show();}
 private boolean sameServer(String url){try{URL a=new URL(url),b=new URL(base);return a.getProtocol().equals("http")&&a.getHost().equals(b.getHost())&&a.getPort()==b.getPort()&&a.getUserInfo()==null;}catch(Exception e){return false;}}
 private void showChat(){
  screenGeneration++;root.removeAllViews();
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(18),0,dp(10),0);bar.setBackgroundColor(0xffeef3e9);root.addView(bar,params(-1,dp(36)));
  TextView label=text(hosting?"●  Этот телефон главный":"●  Домашняя сеть · напрямую",10,0xff739164);bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button change=new Button(this);change.setText("Выбрать главное");change.setTextSize(10);change.setAllCaps(false);change.setTextColor(0xff6a825e);change.setBackgroundColor(Color.TRANSPARENT);bar.addView(change,params(-2,-1));change.setOnClickListener(v->changeRole());
  web=new WebView(this);web.setBackgroundColor(BG);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
  WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setMediaPlaybackRequiresUserGesture(true);s.setBuiltInZoomControls(false);s.setTextZoom(100);
  CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !sameServer(request.getUrl().toString());}
   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){String url=request.getUrl().toString();if(!sameServer(url)&&!url.startsWith("blob:")&&!url.equals("about:blank"))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));return null;}
   @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())runOnUiThread(()->{if(web==view)showConnect("Связь с главным устройством потеряна. Проверьте домашнюю сеть и попробуйте подключиться снова.");});}
   @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame()&&response.getStatusCode()>=500)runOnUiThread(()->{if(web==view)showConnect("Главное устройство не отвечает. Проверьте домашнюю сеть и выберите устройство заново.");});}
   @Override public void onPageFinished(WebView view,String url){CookieManager.getInstance().flush();}
  });
  web.setWebChromeClient(new WebChromeClient(){
   @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams p){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);try{startActivityForResult(Intent.createChooser(i,"Фото, видео или файлы"),PICK_FILE);}catch(Exception e){fileCallback.onReceiveValue(null);fileCallback=null;Toast.makeText(MainActivity.this,"Не найдено приложение для выбора файлов",Toast.LENGTH_LONG).show();}return true;}
  });
  web.setDownloadListener((url,userAgent,disposition,mime,length)->{
   if(!sameServer(url))return;if(!downloadUrl.isEmpty()){Toast.makeText(this,"Сначала завершите сохранение предыдущего файла",Toast.LENGTH_SHORT).show();return;}
   downloadUrl=url;downloadCookie=CookieManager.getInstance().getCookie(url);String name=android.webkit.URLUtil.guessFileName(url,disposition,mime);
   if(disposition!=null&&disposition.contains("filename*=UTF-8''")){try{name=URLDecoder.decode(disposition.substring(disposition.indexOf("filename*=UTF-8''")+17),"UTF-8");}catch(Exception ignored){}}
   Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.addCategory(Intent.CATEGORY_OPENABLE);save.setType(mime==null?"application/octet-stream":mime);save.putExtra(Intent.EXTRA_TITLE,name);
   try{startActivityForResult(save,SAVE_FILE);}catch(Exception e){downloadUrl="";Toast.makeText(this,"Не удалось открыть сохранение файла",Toast.LENGTH_LONG).show();}
  });
  web.loadUrl(hosting&&HostService.active!=null&&HostService.active.host!=null?HostService.active.host.localUrl():base+"/");
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(request==PICK_FILE&&fileCallback!=null){Uri[] uris=null;if(result==RESULT_OK&&data!=null){ClipData clips=data.getClipData();if(clips!=null){uris=new Uri[clips.getItemCount()];for(int i=0;i<uris.length;i++)uris[i]=clips.getItemAt(i).getUri();}else if(data.getData()!=null)uris=new Uri[]{data.getData()};}fileCallback.onReceiveValue(uris);fileCallback=null;}
  if(request==SAVE_FILE){if(result!=RESULT_OK||data==null||data.getData()==null){downloadUrl="";return;}final Uri uri=data.getData();final String url=downloadUrl,cookie=downloadCookie;Toast.makeText(this,"Сохраняем файл…",Toast.LENGTH_SHORT).show();io.execute(()->{String notice="Файл сохранён";HttpURLConnection conn=null;try{conn=(HttpURLConnection)new URL(url).openConnection(Proxy.NO_PROXY);conn.setConnectTimeout(5000);conn.setReadTimeout(60000);conn.setInstanceFollowRedirects(false);if(cookie!=null)conn.setRequestProperty("Cookie",cookie);if(conn.getResponseCode()!=200)throw new Exception();long expected=conn.getContentLengthLong(),count=0;try(InputStream in=conn.getInputStream();OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new Exception();byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);count+=n;}if(expected>=0&&count!=expected)throw new Exception();}}catch(Exception e){notice="Не удалось сохранить файл. Проверьте соединение и свободное место.";try{android.provider.DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception ignored){}}finally{if(conn!=null)conn.disconnect();}final String message=notice;runOnUiThread(()->{downloadUrl="";Toast.makeText(this,message,Toast.LENGTH_LONG).show();});});}
 }
 @Override protected void onPause(){CookieManager.getInstance().flush();super.onPause();}
 @Override public void onBackPressed(){if(web!=null){web.evaluateJavascript("(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return true;}return false;})()",value->{if(!"true".equals(value))moveTaskToBack(true);});}else super.onBackPressed();}
 @Override protected void onDestroy(){if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}if(web!=null){web.destroy();web=null;}if(proxy!=null){proxy.close();proxy=null;}io.shutdown();super.onDestroy();}
}
