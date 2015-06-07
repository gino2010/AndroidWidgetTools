package personal.gino.dnsmasq;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Environment;
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


/**
 * Implementation of App Widget functionality.
 */
public class DNSWidget extends AppWidgetProvider {
    private static final String TAG = "dnsmasq widget";
    private static final String CONF = Environment.getExternalStorageDirectory().getAbsolutePath() + "/dnsmasq.conf";
    private static String PID = Environment.getExternalStorageDirectory().getAbsolutePath() + "/dnsmasq.pid";
    private static final String DNSMASQ_ACTION = "personal.gino.dnsmasq.DNSMASQ_ACTION";

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
        File dir = Environment.getExternalStorageDirectory();
        Log.d(TAG, dir.getAbsolutePath());
        File dnsconf = new File(CONF);
        if (!dnsconf.exists()) {
            Log.d(TAG, "copy conf file");
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
            // check dnsmasq whether is running
            if (check()) {
                stop();
//                if (stop()) {
//                    Toast.makeText(context, R.string.stop, Toast.LENGTH_LONG).show();
//                } else {
//                    Toast.makeText(context, R.string.stop_error, Toast.LENGTH_LONG).show();
//                }
            } else {
                start();
//                if (start()) {
//                    Toast.makeText(context, R.string.start, Toast.LENGTH_LONG).show();
//                } else {
//                    Toast.makeText(context, R.string.start_error, Toast.LENGTH_LONG).show();
//                }
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

    private void start() {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec("su");
            DataOutputStream dos = new DataOutputStream(process.getOutputStream());
//            String cmd = "dnsmasq -C " + CONF + " -x " + PID + "\n";
            String cmd = "dnsmasq -C /sdcard/dnsmasq.conf -x /sdcard/dnsmasq.pid\n";
            Log.d(TAG, "start cmd:" + cmd);
            dos.writeBytes(cmd);
            dos.flush();
            dos.close();
//            boolean tempcheck = check();
//            Log.d(TAG, "tempcheck: " + Boolean.toString(tempcheck));
//            return tempcheck;
            SystemClock.sleep(500);
        } catch (IOException e) {
//            return false;
            Log.e(TAG, "dnsmasq start error");
        }
    }

    private void stop() {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec("su");
            DataOutputStream dos = new DataOutputStream(process.getOutputStream());
//            String cmd = "kill $(cat " + PID + ")\n";
            String cmd = "kill $(cat /sdcard/dnsmasq.pid)\n";
            Log.d(TAG, "stop cmd:" + cmd);
            dos.writeBytes(cmd);
            dos.writeBytes("rm " + PID);
            dos.flush();
            dos.close();
//            return process.waitFor() == 0;
            Log.d(TAG, "return code: " + process.waitFor());
        } catch (IOException | InterruptedException e) {
//            return false;
            Log.e(TAG, "dnsmasq stop error");
        }
    }
}

