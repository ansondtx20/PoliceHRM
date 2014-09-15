package com.arm.sensinode.gateway;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.text.format.Formatter;
import android.util.Base64;
import android.widget.Toast;

// Resources
import com.arm.sensinode.gateway.resources.BatteryResource;
import com.arm.sensinode.gateway.resources.HRMResource;
import com.arm.sensinode.gateway.resources.LocationResource;
import com.arm.sensinode.gateway.resources.ManufacturerResource;
import com.arm.sensinode.gateway.resources.ModelResource;

// Sensinode imports
import com.sensinode.coap.BlockOption.BlockSize;
import com.sensinode.coap.CoapConstants;
import com.sensinode.coap.server.CoapServer;
import com.sensinode.coap.server.EndPointRegistrator;
import com.sensinode.coap.server.EndPointRegistrator.RegistrationState;
import com.sensinode.coap.utils.Callback;
import com.sensinode.coap.utils.SimpleCoapResource;
import com.sensinode.coap.utils.SyncCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;


























import java.net.URL;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
// SLF4J ported to Android - replaces Log4J with a bridge
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SensinodeService class provides the backend for connecting to the Sensinode/NSP instance.
 *
 * It makes use of the NSP Java client libraries, adapted for Android use.
 * Please note that you will need to provide an external Log4J library also,
 * as the client library uses it internally.
 *
 * I have used an Android port of SLF4J, in combination with a SLF4J Log4J bridge to satisfy
 * this dependency, built with Maven from the git project page.
 *
 * Uses properties configured from the GUI, with the help of the MainActivity class.
 *
 * @author Dragos Donici <Dragos.Donici@arm.com>
 * @see com.arm.sensinode.gateway.NSPConfigActivity
 *
 * @see http://www.slf4j.org/android/
 * @see https://github.com/twwwt/slf4j
 *
 */
public class SensinodeService extends Service {
    public static HRMResource m_hrm = null;
    
    // HACK for using HTTP to directly set NSP resource changes without waiting on notifyChange()
    private String nsp_authentication = "app2:secret";
    private String nsp_resource_url_template = "http://HOST:PORT/DOMAIN/endpoints/ENDPOINT_NAMERESOURCESYNCCACHE";
    
    private final IBinder mBinder = new ServiceBinder();

    // Thread used to start the NSP server, network operations can't run on the main UI thread on Android
    private Thread init_thread = null;

    // Get a logging handler
    private final static Logger LOGGER = LoggerFactory.getLogger(SensinodeService.class);

    // Connection details
    private String serverAddress = null;
    private int serverPort = CoapConstants.DEFAULT_PORT;
    private InetSocketAddress nspAddress;
    private CoapServer server = null;
    private EndPointRegistrator registrator = null;
    private String endPointHostName = "nsp-btn-gw";
    private String endPointDomain = "domain";
    private final String endPointType = "button gateway";

    // Resources
    private BatteryResource battery_resource;
    private HRMResource hrm_resource;
    private ModelResource model_resource;
    private ManufacturerResource manufacturer_resource;
    private LocationResource location_resource;

    // Get preferences
    private SharedPreferences preferences;

    // Used to display Toasts from a non-UI thread
    private Handler handler;

    /**
     * Initialize the handler and the preferences when the service is created.
     *
     */
    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(getApplicationContext().getMainLooper());
        preferences = getSharedPreferences(getPackageName(), Context.MODE_PRIVATE);

        serverAddress = preferences.getString("server_address", "192.168.1.220");
        serverPort = preferences.getInt("server_port", CoapConstants.DEFAULT_PORT);
        endPointHostName = preferences.getString("endpoint_id", "nsp-btn-gw");
        endPointDomain = preferences.getString("server_domain", "domain");
    }

    /**
     * This is the entry-point for the service.
     *
     * It will start the service in the foreground (to prevent Android from killing it),
     * as well as initialize the NSP server thread.
     *
     * It uses a boolean intent extra named "enabled", which holds true or false,
     * whether to start the service or stop it.
     *
     *
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The intent to launch when the user clicks the expanded notification
        Intent service_intent = new Intent(this, NSPConfigActivity.class);
        service_intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending_intent = PendingIntent.getActivity(this, 0, intent, 0);

        // Create the notification for the foreground service
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setTicker("Started NSP service.").setContentTitle("NSP").setContentText("Service is running.")
                .setWhen(System.currentTimeMillis()).setAutoCancel(false)
                .setOngoing(true).setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pending_intent);
        Notification notification = builder.build();

        notification.flags |= Notification.FLAG_NO_CLEAR;

        // Determine what the requested operation is, start or stop, based on the intent extra
        boolean enabled = intent.getBooleanExtra("enabled", true);
        if (enabled) {
            LOGGER.info("Service start command received");
            if (preferences.getBoolean("service_enabled", true) && preferences.getBoolean("service_running", false)) {
            	LOGGER.info("Service already enabled and running.");
            }
            else {
                // Fire up the service
                startForeground(1, notification);
                startThread();
                preferences.edit().putBoolean("service_enabled", true).apply();
            }
        }
        // Disable command received
        else {
            LOGGER.info("Service stop command received");
            if (!preferences.getBoolean("service_enabled", true) && !preferences.getBoolean("service_running", false)) LOGGER.info("Service already disabled and stopped.");
            else {
                stopThread();
                preferences.edit().putBoolean("service_enabled", false).apply();
                stopForeground(true);
                stopSelf();
            }
        }
        return Service.START_REDELIVER_INTENT;
    }

    /**
     * Called when the service instance is destroyed.
     * Attempts to quit cleanly, trying to stop the thread before it dies.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopThread();
    }

    public class ServiceBinder extends Binder {
        /**
         * Gets a current instance of the service.
         *
         * @return SensinodeService
         *          The current instance of the service.
         */
        public SensinodeService getService() {
            return SensinodeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void startThread() {
        init_thread = new Thread(new Runnable() {
            @Override
            public void run() {
                init();
            }
        });
        init_thread.start();
    }

    private void stopThread() {
        if (preferences.getBoolean("service_running", false)) {
            LOGGER.info("Stopping NSP thread");
            if (init_thread != null) {
                init_thread.interrupt();
                // Wait until the thread exits
                try {
                    init_thread.join();
                } catch (InterruptedException ex) {
                    // Unexpected interruption
                    ex.printStackTrace();
                }

                LOGGER.info("NSP Thread stopped");
                init_thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                       stop();
                   }
                });
                init_thread.start();
            }
        }
        else LOGGER.warn("Tried to stop service, but it was not running.");
    }

    private void init() {
        try {
            URI uri = URI.create("coap://" + serverAddress);
            nspAddress = new InetSocketAddress(uri.getHost(), CoapConstants.DEFAULT_PORT);
            start();
            preferences.edit().putBoolean("service_running", true).apply();
        }
        catch(IllegalArgumentException exc) {
            showToast("Invalid server IP address or port.", Toast.LENGTH_LONG);
            preferences.edit().putBoolean("service_running", false).apply();
        }
    }
    
    // get our endpoint ID value
    public String getEndpoint() { return this.endPointHostName; }
    
    // Create the Endpoint Resource URL
    public String buildResourceURL(String endpoint,String resource) {
    	String url = this.nsp_resource_url_template
    					.replace("HOST", serverAddress)
    					.replace("PORT", "8080")
    					.replace("DOMAIN", "domain")
    					.replace("ENDPOINT_NAME",endpoint)
    					.replace("RESOURCE", resource)
    					.replace("SYNC","")
    					.replace("CACHE","");
    	
    	LOGGER.debug("NSP Resource URL: " + url);
    	return url;
    }
    
    // HACK: use HTTP to put a resource value directly
    public boolean putResourceValue(String urlstr,String value) {
    	boolean success = false;
    	
    	try {
	    	// use Apache HTTP
	    	DefaultHttpClient httpClient = new DefaultHttpClient();
	    	
	    	URL url = new URL(urlstr);
	    	HttpPut putRequest = new HttpPut(url.toURI());
	
	    	StringEntity input = new StringEntity(value);
	    	input.setContentType("text/plain");
	    	
	    	putRequest.setHeader("Authorization", "Basic " + Base64.encodeToString(this.nsp_authentication.getBytes(), Base64.NO_WRAP));
	
	    	putRequest.setEntity(input);
	    	HttpResponse response = httpClient.execute(putRequest);
	    	
	    	LOGGER.debug("putResourceValue response code: " + response.getStatusLine().getStatusCode());
	    	success = true;
    	}
    	catch (Exception ex) {
    		showToast("Caught Exception in putResourceValue: " + ex.getMessage(),Toast.LENGTH_LONG);
    		LOGGER.debug("Caught Exception in putResourceValue: " + ex.getMessage());
    		success = false;
    	}
    	
    	// return our status
    	return success;
    }
    
    // HACK: use HTTP to get a resource value directly
    public String getResourceValue(String urlstr) {
    	String value = null;
    	
	    try {
	    	// use Apache HTTP
	    	DefaultHttpClient httpClient = new DefaultHttpClient();
	    	
	    	URL url = new URL(urlstr);
	    	HttpGet getRequest = new HttpGet(url.toURI());
	    	
	    	getRequest.setHeader("Authorization", "Basic " + Base64.encodeToString(this.nsp_authentication.getBytes(), Base64.NO_WRAP));
	
	    	HttpResponse response = httpClient.execute(getRequest);
	    	
	    	LOGGER.debug("putResourceValue response code: " + response.getStatusLine().getStatusCode());

	    	// Get the response
	    	BufferedReader rd = new BufferedReader
	    	  (new InputStreamReader(response.getEntity().getContent()));
	    	    
	    	String line = "";
	    	while ((line = rd.readLine()) != null) {
	    	  value += line;
	    	} 
		}
		catch (Exception ex) {
			showToast("Caught Exception in getResourceValue: " + ex.getMessage(),Toast.LENGTH_LONG);
    		LOGGER.debug("Caught Exception in getResourceValue: " + ex.getMessage());
			value = null;
		}
    	
    	// return the response
    	return value;
    }

    private void showToast(final String msg, final int length) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), msg, length).show();
            }
        });
    }

    private void start() {
        // Create the server
        try {
            server = CoapServer.create(serverPort);
            server.setBlockSize(BlockSize.S_1024);
            server.start();
        } catch (IOException ex) {
            LOGGER.error("Unable to start NSP server: " + ex.getMessage());
            showToast("Unable to start NSP server, please verify logcat.", Toast.LENGTH_LONG);
            return;
        }
        
        // IP Address
        SimpleCoapResource ipaddr = new SimpleCoapResource(this.getLocalIPAddress(), "", 0);
        ipaddr.getLink().setInterfaceDescription("ns:v6addr");
        server.addRequestHandler("/nw/ipaddr", ipaddr);
        
        // MAC Address
        SimpleCoapResource macaddr = new SimpleCoapResource(this.getLocalMACAddress(), "", 0);
        macaddr.getLink().setInterfaceDescription("ns:macaddr");
        server.addRequestHandler("/nw/macaddr", macaddr);
        
        // GPS Fix
        SimpleCoapResource gpsfix = new SimpleCoapResource("1", "", 0);
        gpsfix.getLink().setInterfaceDescription("ns:gpsfix");
        server.addRequestHandler("/gps/fix", gpsfix);
        
        // GPS Initialized
        SimpleCoapResource gpsint = new SimpleCoapResource("60", "ucum:s", 0);
        gpsint.getLink().setInterfaceDescription("ns:gpsint");
        server.addRequestHandler("/gps/int", gpsfix);
        
        // Location Resource
        SimpleCoapResource location = new SimpleCoapResource("Santa Clara CA", "", 0);
        location.getLink().setInterfaceDescription("ns:location");
        server.addRequestHandler("/dev/location", location);

        // Add Manufacturer resource
        manufacturer_resource = new ManufacturerResource();
        server.addRequestHandler("/dev/mfg", manufacturer_resource);

        // Add Model resource
        model_resource = new ModelResource();
        server.addRequestHandler("/dev/mdl", model_resource);

        // Add battery info resource
        battery_resource = new BatteryResource(getApplicationContext(), server);
        server.addRequestHandler("/dev/bat", battery_resource);
        
        // Add HRM info resource
        SensinodeService.m_hrm = new HRMResource(getApplicationContext(), server);
        SensinodeService.m_hrm.setService(this);
        server.addRequestHandler("/lt/0/on", SensinodeService.m_hrm);

        // Add Location resource
        location_resource = new LocationResource(getApplicationContext(), server);
        server.addRequestHandler("/gps/loc", location_resource);
        
        // Add the handler for /.well-known/core
        server.addRequestHandler(CoapConstants.WELL_KNOWN_CORE, server.getResourceLinkResource());

        // Register with NSP end-point
        if (nspAddress != null) {
        	LOGGER.info("Registering Endpoint: NSP: " + nspAddress + " Endpoint hostName: " + endPointHostName + " Domain: " + endPointDomain);
            registrator = new EndPointRegistrator(server, nspAddress, endPointHostName);
            if (endPointDomain != null) {
                registrator.setDomain(endPointDomain);
                registrator.setType(endPointType);
            }
            
            registrator.setLifeTime(1200);
            registrator.register(new Callback<EndPointRegistrator.RegistrationState>() {

                @Override
                public void callException(Exception excptn) {
                    LOGGER.warn("Could not register: " + excptn);
                    showToast("Unable to register with Sensinode, please verify logcat.", Toast.LENGTH_LONG);
                }

                @Override
                public void call(RegistrationState registrationState) {
                    LOGGER.info("Registation state: " + registrationState);
                }
            });
        }
    }
    
    private String getLocalIPAddress() {
      WifiManager wifiMgr = (WifiManager) getSystemService(WIFI_SERVICE);
      WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
      int ip = wifiInfo.getIpAddress();
      return  Formatter.formatIpAddress(ip);
    }
    
    private String getLocalMACAddress() {
      WifiManager wifiMgr = (WifiManager) getSystemService(WIFI_SERVICE);
      WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
      return wifiInfo.getMacAddress();
    }

    private void stop() {
        if (registrator != null && registrator.getState() == EndPointRegistrator.RegistrationState.REGISTERED) {
            // Unregister
            LOGGER.debug("Removing NSP registration");
            SyncCallback<RegistrationState> callback = new SyncCallback<RegistrationState>();
            registrator.unregister(callback);

            try {
                callback.getResponse();
                LOGGER.info("Registration state: " + registrator.getState());
            } catch (Exception ex) {
            }
        }
        
        try {
	        // Stop the server
	        if (server.isRunning())
	            server.stop();
	
	        // Disable the battery receiver
	        if (battery_resource.isReceiverRunning())
	            battery_resource.stopBatteryReceiver();
	
	        // Disable the HRM sensor
	        if (hrm_resource.isReceiverRunning())
	            hrm_resource.stopHRMReceiver();
        }
        catch (Exception ex) {
        	LOGGER.warn("Exception caught during service disablement: " + ex.getMessage());
        	
        }

        preferences.edit().putBoolean("service_running", false).apply();
    }
}