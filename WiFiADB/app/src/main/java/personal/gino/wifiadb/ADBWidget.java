package personal.gino.wifiadb;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.RemoteViews;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;


/**
 * Implementation of App Widget functionality.
 */
public class ADBWidget extends AppWidgetProvider {
    private static final String TAG = "adb widget";
    private static final String WIRELESS_ADB_ACTION =
            "personal.gino.wifiadb.WIRELESS_ADB_ACTION";
    private NotificationCompat.Builder mBuilder;
    private int mId = 0;

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
//        mBuilder = new NotificationCompat.Builder(context);
//        mBuilder.setSmallIcon(R.drawable.notification_template_icon_bg)
//                .setContentTitle("Notice")
//                .setContentText("This app need root access.");
//        NotificationManager mNotificationManager =
//                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
//        mNotificationManager.notify(mId, mBuilder.build());
    }

    @Override
    public void onDisabled(Context context) {
        // Enter relevant functionality for when the last widget is disabled
    }

    void updateAppWidget(Context context, AppWidgetManager appWidgetManager,
                         int appWidgetId) {

        // Create an Intent to broadcast action
        Intent intent = new Intent(context, ADBWidget.class);
        intent.setAction(WIRELESS_ADB_ACTION);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, 0);


        // Construct the RemoteViews object
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.adbwidget);
        views.setOnClickPendingIntent(R.id.widget_button, pendingIntent);

        //get status to set widget
        if (check()) {
            views.setImageViewResource(R.id.widget_button, R.mipmap.on);
        } else {
            views.setImageViewResource(R.id.widget_button, R.mipmap.off);
        }
        // Instruct the widget manager to update the widget
        // Tell the AppWidgetManager to perform an update on the current app widget
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        super.onReceive(context, intent);
        // why can get action without intent filter?
        Log.d(TAG, intent.getAction());
        // create notification
        mBuilder = new NotificationCompat.Builder(context);

        if (intent.getAction() != null && intent.getAction().equals(WIRELESS_ADB_ACTION)) {
            // check adb port and close or open
            if (check()) {
                close();
                mBuilder.setSmallIcon(R.drawable.notification_template_icon_bg)
                        .setContentTitle("Notice")
                        .setContentText(context.getString(R.string.close));
//                Toast.makeText(context, R.string.close, Toast.LENGTH_LONG).show();
            } else {
                if (!isConnectWIFI(context)) {
                    mBuilder.setSmallIcon(R.drawable.notification_template_icon_bg)
                            .setContentTitle("Notice")
                            .setContentText(context.getString(R.string.no_wifi));
//                    Toast.makeText(context, R.string.no_wifi, Toast.LENGTH_LONG).show();
                    Log.w(TAG, "wifi is not open");
                    //connected wifi and open port
                } else if (isConnectWIFI(context)) {
                    if (open()) {
                        String ipInfo = getWIFIIP(context);
                        mBuilder.setSmallIcon(R.drawable.notification_template_icon_bg)
                                .setContentTitle(context.getString(R.string.open))
                                .setContentText("Address:" + ipInfo);
//                        Toast.makeText(context, context.getString(R.string.open) + ipInfo, Toast.LENGTH_LONG).show();
                    } else {
                        mBuilder.setSmallIcon(R.drawable.notification_template_icon_bg)
                                .setContentTitle("Notice")
                                .setContentText(context.getString(R.string.no_root));
//                        Toast.makeText(context, R.string.no_root, Toast.LENGTH_LONG).show();
                    }
                }
            }

            NotificationManager mNotificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(mId, mBuilder.build());

            //app widget update
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName thisAppWidget = new ComponentName(context.getPackageName(), ADBWidget.class.getName());
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget);

            onUpdate(context, appWidgetManager, appWidgetIds);
        }
    }

    // open adb port
    private boolean open() {
        Runtime runtime = Runtime.getRuntime();
        Process pro;
        try {
            pro = runtime.exec("su");
            DataOutputStream dos = new DataOutputStream(pro.getOutputStream());
            dos.writeBytes("setprop service.adb.tcp.port 5555\n");
            dos.writeBytes("stop adbd\n");
            dos.writeBytes("start adbd\n");
            dos.flush();
            dos.close();
            return pro.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // close adb port
    private boolean close() {
        Runtime runtime = Runtime.getRuntime();
        Process pro;
        try {
            pro = runtime.exec("su");
            DataOutputStream dos = new DataOutputStream(pro.getOutputStream());
            dos.writeBytes("setprop service.adb.tcp.port -1\n");
            dos.writeBytes("stop adbd\n");
            dos.writeBytes("start adbd\n");
            dos.flush();
            dos.close();
            return pro.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // check adb tcp port
    private boolean check() {
        boolean result = false;
        Runtime runtime = Runtime.getRuntime();
        try {
            Process pro = runtime.exec("getprop service.adb.tcp.port");
            if (pro.waitFor() == 0) {
                BufferedReader in = new BufferedReader(new InputStreamReader(pro.getInputStream()));
                String msg = in.readLine();
                in.close();
                result = msg.contains("5555");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            Log.e(TAG, "check prop port error");
        }
        return result;
    }

    // check wifi status
    private boolean isConnectWIFI(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return info != null && info.isConnected();
    }

    // get wifi ip
    private String getWIFIIP(Context context) {
        WifiManager wifiManger = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManger.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();
        return " IP:" + (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + (ip >> 24 & 0xFF);
    }
}

