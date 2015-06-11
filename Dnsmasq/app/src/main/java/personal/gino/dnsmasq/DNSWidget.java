package personal.gino.dnsmasq;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;


/**
 * Implementation of App Widget functionality.
 */
public class DNSWidget extends AppWidgetProvider {
    private static final String TAG = "dnsmasq widget";
    // Environment.getExternalStorageDirectory().getPath() can't use,
    // because some devices return /storage/emulated/0
    private static final String CONF = "/sdcard/dnsmasq.conf";
    private static final String DNSMASQ_ACTION = "personal.gino.dnsmasq.DNSMASQ_ACTION";
    private static String PID = "/sdcard/dnsmasq.pid";
    private static String s_ipaddress;
    private static String s_gateway;
    private static int s_netmask;

    private static Object getField(Object obj, String name)
            throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        Field f = obj.getClass().getField(name);
        Object out = f.get(obj);
        return out;
    }

    private static Object getDeclaredField(Object obj, String name)
            throws SecurityException, NoSuchFieldException,
            IllegalArgumentException, IllegalAccessException {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        Object out = f.get(obj);
        return out;
    }

    private static void setEnumField(Object obj, String value, String name)
            throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        Field f = obj.getClass().getField(name);
        f.set(obj, Enum.valueOf((Class<Enum>) f.getType(), value));
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // There may be multiple widgets active, so update all of them
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onEnabled(Context context) {
        // Enter relevant functionality for when the first widget is created
        File dnsconf = new File(CONF);
        if (!dnsconf.exists()) {
            Log.i(TAG, "copy conf file");
            AssetManager assetManager = context.getAssets();
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assetManager.open("dnsmasq.conf");
                out = new FileOutputStream(dnsconf);
                copyFile(in, out);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        // NOOP
                    }
                }
                if (out != null) {
                    try {
                        out.flush();
                        out.close();
                    } catch (IOException e) {
                        // NOOP
                    }
                }
            }

        }
    }

    @Override
    public void onDisabled(Context context) {
        // Enter relevant functionality for when the last widget is disabled
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        Log.d(TAG, intent.getAction());
        if (intent.getAction() != null && intent.getAction().equals(DNSMASQ_ACTION)) {
            if (!isConnectWIFI(context)) {
                Toast.makeText(context, R.string.wifi_close, Toast.LENGTH_LONG).show();
                Log.w(TAG, "wifi is not open");
                return;
            }
            // check dnsmasq whether is running
            if (check()) {
                stop();
                setDhcp(context);
            } else {
                start();
                setStatic(context);
            }

            //app widget update
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName thisAppWidget = new ComponentName(context.getPackageName(), DNSWidget.class.getName());
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget);

            onUpdate(context, appWidgetManager, appWidgetIds);
        }
    }

    private void updateAppWidget(Context context, AppWidgetManager appWidgetManager,
                                 int appWidgetId) {

        // Create an Intent to broadcast action
        Intent intent = new Intent(context, DNSWidget.class);
        intent.setAction(DNSMASQ_ACTION);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, 0);

        // Construct the RemoteViews object
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.dnswidget);
        views.setOnClickPendingIntent(R.id.widget_button, pendingIntent);

        if (check()) {
            views.setImageViewResource(R.id.widget_button, R.mipmap.open);
            Toast.makeText(context, R.string.start, Toast.LENGTH_LONG).show();
        } else {
            views.setImageViewResource(R.id.widget_button, R.mipmap.close);
            Toast.makeText(context, R.string.stop, Toast.LENGTH_LONG).show();
        }

        // Instruct the widget manager to update the widget
        // Tell the AppWidgetManager to perform an update on the current app widget
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private void setIpAssignment(String assign, WifiConfiguration wifiConf)
            throws SecurityException, IllegalArgumentException, NoSuchFieldException,
            IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Object ipConfiguration = wifiConf.getClass().getMethod("getIpConfiguration").invoke(wifiConf);
            setEnumField(ipConfiguration, assign, "ipAssignment");
        } else {
            setEnumField(wifiConf, assign, "ipAssignment");
        }
    }

    private void setDNS(InetAddress dns, WifiConfiguration wifiConf)
            throws SecurityException, IllegalArgumentException, NoSuchFieldException,
            IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // support android 5.x
            Object ipConfiguration = wifiConf.getClass().getMethod("getIpConfiguration").invoke(wifiConf);
            Object staticIpConfiguration = ipConfiguration.getClass().getMethod("getStaticIpConfiguration").invoke(ipConfiguration);
            Field ipAddress = null;
            Field gateway = null;
            // maybe is null, so you need to initial it manually
            if (staticIpConfiguration == null) {
                Log.d(TAG, "staticIpConfiguration is null");
                try {
                    // create staticIpConfiguration
                    staticIpConfiguration = Class.forName("android.net.StaticIpConfiguration").newInstance();

                    // create static info
                    @SuppressWarnings("unchecked")
                    ArrayList<InetAddress> dnsServers = (ArrayList<InetAddress>) getDeclaredField(staticIpConfiguration, "dnsServers");
                    dnsServers.add(dns);
                    LinkAddress obj = (LinkAddress) Class.forName("android.net.LinkAddress").getConstructor(InetAddress.class, int.class).newInstance(InetAddress.getByName(s_ipaddress), s_netmask);
                    ipAddress = staticIpConfiguration.getClass().getField("ipAddress");
                    ipAddress.set(staticIpConfiguration, obj);
                    gateway = staticIpConfiguration.getClass().getField("gateway");
                    gateway.set(staticIpConfiguration, InetAddress.getByName(s_gateway));

                    // get Enum object
                    @SuppressWarnings("unchecked")
                    Class<Enum> ipencl = (Class<Enum>) Class.forName("android.net.IpConfiguration$IpAssignment");
                    @SuppressWarnings("unchecked")
                    Class<Enum> encl = (Class<Enum>) Class.forName("android.net.IpConfiguration$ProxySettings");

                    // initial staticipconfiguration
                    Method method = ipConfiguration.getClass().getDeclaredMethod("init", ipencl, encl,
                            Class.forName("android.net.StaticIpConfiguration"), Class.forName("android.net.ProxyInfo"));
                    method.setAccessible(true);
                    method.invoke(ipConfiguration, Enum.valueOf(ipencl, "STATIC"), Enum.valueOf(encl, "NONE"), staticIpConfiguration, null);
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            } else {
                try {
                    @SuppressWarnings("unchecked")
                    ArrayList<InetAddress> dnsServers = (ArrayList<InetAddress>) getDeclaredField(staticIpConfiguration, "dnsServers");
                    dnsServers.clear();
                    dnsServers.add(dns);
                    LinkAddress obj = (LinkAddress) Class.forName("android.net.LinkAddress").getConstructor(InetAddress.class, int.class).newInstance(InetAddress.getByName(s_ipaddress), s_netmask);
                    ipAddress = staticIpConfiguration.getClass().getField("ipAddress");
                    ipAddress.set(staticIpConfiguration, obj);
                    gateway = staticIpConfiguration.getClass().getField("gateway");
                    gateway.set(staticIpConfiguration, InetAddress.getByName(s_gateway));
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
            }
        } else {
            // support android 3.X~4.X
            Object linkProperties = getField(wifiConf, "linkProperties");
            if (linkProperties == null) return;
            ArrayList<InetAddress> mDnses = (ArrayList<InetAddress>) getDeclaredField(linkProperties, "mDnses");
            mDnses.clear(); //or add a new dns address , here I just want to replace DNS1
            mDnses.add(dns);
        }
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    // check dnsmasq service status
    private boolean check() {
        boolean result = false;
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec("ps dnsmasq");
            if (process.waitFor() == 0) {
                BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String msg;
                while ((msg = in.readLine()) != null) {
                    result = msg.contains("dnsmasq");
                    Log.d(TAG, "ps result: " + msg + "and result: " + Boolean.toString(result));
                }
                in.close();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            Log.e(TAG, "check dnsmasq process error");
        }
        return result;
    }

    // start dnsmasq
    private void start() {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec("su");
            DataOutputStream dos = new DataOutputStream(process.getOutputStream());
            String cmd = "dnsmasq -C " + CONF + " -x " + PID + "\n";
//            String cmd = "dnsmasq -C /sdcard/dnsmasq.conf -x /sdcard/dnsmasq.pid\n";
            Log.d(TAG, "start cmd:" + cmd);
            dos.writeBytes(cmd);
            dos.flush();
            // wait 0.5s for dnsmasq startup
            SystemClock.sleep(500);
            dos.close();
        } catch (IOException e) {
            Log.e(TAG, "dnsmasq start error");
        }
    }

    // stop dnsmasq
    private void stop() {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec("su");
            DataOutputStream dos = new DataOutputStream(process.getOutputStream());
            String cmd = "kill $(cat " + PID + ")\n";
//            String cmd = "kill $(cat /sdcard/dnsmasq.pid)\n";
            Log.d(TAG, "stop cmd:" + cmd);
            dos.writeBytes(cmd);
            dos.writeBytes("rm " + PID + "\n");
            dos.flush();
            SystemClock.sleep(500);
            dos.close();
        } catch (IOException e) {
            Log.e(TAG, "dnsmasq stop error");
        }
    }

    // set wifi to static ip
    private void setStatic(Context context) {
        WifiConfiguration wifiConf = null;
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
        getStaticDefualt(dhcpInfo);
        WifiInfo connectionInfo = wifiManager.getConnectionInfo();
        List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
        for (WifiConfiguration conf : configuredNetworks) {
            if (conf.networkId == connectionInfo.getNetworkId()) {
                wifiConf = conf;
                break;
            }
        }
        try {
            setIpAssignment("STATIC", wifiConf); //or "DHCP" for dynamic setting
            wifiManager.updateNetwork(wifiConf);
            setDNS(InetAddress.getByName("127.0.0.1"), wifiConf);
            wifiManager.updateNetwork(wifiConf);
            wifiManager.disconnect();
            wifiManager.reconnect();
            Log.d(TAG, "set static");
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    // set wifi to dhcp
    private void setDhcp(Context context) {
        WifiConfiguration wifiConf = null;
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo connectionInfo = wifiManager.getConnectionInfo();
        List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
        for (WifiConfiguration conf : configuredNetworks) {
            if (conf.networkId == connectionInfo.getNetworkId()) {
                wifiConf = conf;
                break;
            }
        }
        try {
            setIpAssignment("DHCP", wifiConf); //or "DHCP" for dynamic setting
            wifiManager.updateNetwork(wifiConf);
            wifiManager.disconnect();
            wifiManager.reconnect();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    // check wifi status
    private boolean isConnectWIFI(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return info != null && info.isConnected();
    }

    // get dhcp for static default
    private void getStaticDefualt(DhcpInfo dhcpInfo) {
        int ip = dhcpInfo.ipAddress;
        s_ipaddress = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + (ip >> 24 & 0xFF);
        ip = dhcpInfo.netmask;
        String temp = Integer.toString(ip, 2);
        s_netmask = temp.length() - temp.replace("1", "").length();
        ip = dhcpInfo.gateway;
        s_gateway = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + (ip >> 24 & 0xFF);
        Log.d(TAG, "newtork info:" + s_ipaddress + " " + s_netmask + " " + s_gateway);
    }
}

