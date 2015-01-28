package ba.ctrl.ctrltest1.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.json.JSONException;
import org.json.JSONObject;

import ba.ctrl.ctrltest1.CommonStuff;
import ba.ctrl.ctrltest1.R;
import ba.ctrl.ctrltest1.database.DataSource;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class CtrlService extends Service implements NetworkStateReceiverCallbacks {
    private static String TAG = "CtrlBaService";

    // Google Cloud Messaging PROJECT ID
    public static final String GCM_SENDER_ID = "982449092255";

    // for receiving broadcasts FROM activities
    public static final String BC_SERVICE_TASKS = "ba.ctrl.ctrltest1.intent.action.BC_SERVICE_TASKS";
    public static final String BC_SERVICE_TASKS_KEY = BC_SERVICE_TASKS + "_KEY";
    public static final String BC_SERVICE_TASKS_SEND_DATA = BC_SERVICE_TASKS + "_SEND_DATA";
    public static final String BC_SERVICE_TASKS_REQUEST_STATUS = BC_SERVICE_TASKS + "_REQUEST_STATUS";
    public static final String BC_SERVICE_TASKS_OPEN_CONNECTION = BC_SERVICE_TASKS + "_OPEN_CONNECTION";
    public static final String BC_SERVICE_TASKS_RESTART_CONNECTION = BC_SERVICE_TASKS + "_RESTART_CONNECTION";
    public static final String BC_SERVICE_TASKS_CLOSE_CONNECTION = BC_SERVICE_TASKS + "_CLOSE_CONNECTION";
    public static final String BC_SERVICE_TASKS_GCM_REREG = BC_SERVICE_TASKS + "_GCM_REREG";

    // for sending broadcasts of task completion back TO activities
    public static final String BC_SERVICE_TASKS_COMPLETION = "ba.ctrl.ctrltest1.intent.action.BC_SERVICE_TASKS_COMPLETION";

    // for sending broadcasts of new data arrival TO activities
    public static final String BC_NEW_DATA = "ba.ctrl.ctrltest1.intent.action.BC_NEW_DATA";
    public static final String BC_NEW_DATA_BASE_ID_KEY = BC_NEW_DATA + "_BASE_ID_KEY";

    // for sending broadcasts of base status TO activities
    public static final String BC_BASE_STATUS = "ba.ctrl.ctrltest1.intent.action.BC_BASE_STATUS";
    public static final String BC_BASE_STATUS_BASE_ID_KEY = BC_BASE_STATUS + "_BASE_ID_KEY";
    public static final String BC_BASE_STATUS_CONNECTED_KEY = BC_BASE_STATUS + "_CONNECTED_KEY";

    // for sending service status broadcasts TO activities
    public static final String BC_SERVICE_STATUS = "ba.ctrl.ctrltest1.intent.action.BC_SERVICE_STATUS";
    // System status
    public static final String BC_CONNECTION_STATUS_KEY = BC_SERVICE_STATUS + "_CONN_KEY";
    public static final String BC_CONNECTION_STATUS_IDLE = BC_CONNECTION_STATUS_KEY + "_CONN_IDLE";
    public static final String BC_CONNECTION_STATUS_RUNNING = BC_CONNECTION_STATUS_KEY + "_CONN_RUNNING";
    public static final String BC_CONNECTION_STATUS_ERROR = BC_CONNECTION_STATUS_KEY + "_CONN_ERROR";
    // CTRL connection error
    public static final String BC_CTRL_STATUS_KEY = BC_SERVICE_STATUS + "_CTRL_KEY";
    public static final String BC_CTRL_STATUS_NONE = BC_SERVICE_STATUS + "_CTRL_NONE";
    public static final String BC_CTRL_STATUS_WRONG_AUTH = BC_SERVICE_STATUS + "_CTRL_WRONG_AUTH";
    public static final String BC_CTRL_STATUS_TOO_MANY = BC_SERVICE_STATUS + "_CTRL_TOO_MANY";

    // need this for debugging, to be able to send Toasts to UI
    private Handler mHandler;

    private DataSource dataSource = null;
    private Context context;
    // used to receive commands from Activities
    private ServiceTasksReceiver serviceTasksReceiver;
    private NetworkStateReceiver networkStateReceiver;
    private SSLContext sslContext = null;

    // Errors during authentication can be: Wrong Auth Token or Too Many Auth
    // Attempts
    private enum CtrlErrors {
        NONE, WRONG_AUTH_TOKEN, TOO_MANY_AUTH_ATTEMPTS
    }

    private CtrlErrors ctrlError = CtrlErrors.NONE;

    // Available commands/tasks for socket threads
    private enum ThreadTasks {
        NONE, OPEN, CLOSE, RESTART
    }

    // States in which socket threads can be found
    private enum SocketThreadStates {
        IDLE, RUNNING, ERROR
    }

    // Thread that keeps lookout on socket connection and makes sure they are
    // always connected (when app is running in foreground or until last
    // client2server item from DB queue is sent)
    private Thread ctrlConnMaintainer = null;

    private ThreadTasks ctrlThreadTask = ThreadTasks.NONE;
    private Socket ctrlSocket = null;
    private Thread ctrlSocketThread = null;
    private SocketThreadStates ctrlSocketThreadState = SocketThreadStates.IDLE;

    // private boolean ignoredFirstNetworkStateReceiverBroadcast = false;
    private boolean networkConnected;

    // When we want to write to socket, we write to this mBufferOut
    private PrintWriter ctrlSocketBufferOut;

    // CTRL stack related
    private boolean ctrlAuthenticated = false;
    private int TXserver = 0;
    private int outOfSyncCnt = 0;

    private boolean ctrlSenderTimerRunning = false;
    private Timer ctrlSenderTimer = null;

    // For how long can service run after no activity has pinged (and when
    // service has nothing else to do)?
    private final int pingIdleTime = 4000;
    private long pingStamp = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        // android.os.Debug.waitForDebugger();

        // "Open" the database
        dataSource = DataSource.getInstance(this);

        mHandler = new Handler();

        context = getApplicationContext();

        // Add our self-signed certificate CA we prepared earlier in
        // ctrlba_keystore.bks file to Android TrustManager. I created the BKS
        // file using portecle-1.7.zip found on the Internet.
        try {
            KeyStore store = KeyStore.getInstance("BKS");
            InputStream truststore = context.getResources().openRawResource(R.raw.ctrlba_keystore);

            File customCertDir = new File(CommonStuff.getCustomSslCertDir(context));
            if (customCertDir.mkdirs() || customCertDir.isDirectory()) {
                File sslFile = new File(CommonStuff.getCustomSslCertDir(context), "customssl.bks");
                if (sslFile.isFile()) {
                    truststore = new FileInputStream(sslFile);
                }
            }

            store.load(truststore, "ctrlba".toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        }
        catch (KeyStoreException e) {
            e.printStackTrace();
        }
        catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        catch (CertificateException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        catch (KeyManagementException e) {
            e.printStackTrace();
        }

        // Register a receiver to receive commands from Activities
        IntentFilter filter = new IntentFilter(BC_SERVICE_TASKS);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        serviceTasksReceiver = new ServiceTasksReceiver();
        registerReceiver(serviceTasksReceiver, filter);

        // onStartCommand will be called with after onCreate, so this might not
        // be required. Lets leave it just in case.
        pingStamp = System.currentTimeMillis();

        // Create Service worker thread now
        ctrlConnMaintainer = new Thread(new CtrlConnMaintainer());
        ctrlConnMaintainer.start();

        // If there is an Internet connection available - connect, else don't
        // connect until we receive the broadcast from NetworkStateReceiver
        // class
        if (CommonStuff.getNetConnectivityStatus(context) != CommonStuff.NET_NOT_CONNECTED) {
            networkConnected = true;
            Log.i(TAG, "Startup, there is internet, opening socket...");
            openCtrlSocket();
        }
        else {
            networkConnected = false;
            Log.i(TAG, "Note: Service will not connect, there is no Internet connection...");
            closeCtrlSocket();
        }

        // Register a receiver to receive notifications about Internet
        // Connectivity. This has to be done AFTER we first initialize the
        // networkConnected boolean, because this broadcast is sticky on *some
        // devices*.
        IntentFilter filterNet = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        filterNet.addCategory(Intent.CATEGORY_DEFAULT);
        networkStateReceiver = new NetworkStateReceiver(this);
        registerReceiver(networkStateReceiver, filterNet);

        // mHandler.post(new ToastRunnable("SERVICE onCreate()"));
        Log.i(TAG, "SERVICE onCreate()");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // This is actually where we receive "pings" from activities to start us
        // and keep us running
        pingStamp = System.currentTimeMillis();

        Log.i(TAG, "SERVICE onStartCommand()");

        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        this.unregisterReceiver(networkStateReceiver);
        this.unregisterReceiver(serviceTasksReceiver);

        // mHandler.post(new ToastRunnable("SERVICE onDestroy()"));
        Log.i(TAG, "SERVICE onDestroy()");

        super.onDestroy();
    }

    // Toasting to UI
    private class ToastRunnable implements Runnable {
        String mText;

        public ToastRunnable(String text) {
            mText = text;
        }

        @Override
        public void run() {
            Toast.makeText(getApplicationContext(), mText, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Starting the Queued Items Sender timer.
     */
    private void startQueuedItemsSender() {
        if (ctrlSenderTimerRunning)
            return;

        ctrlSenderTimer = new Timer(true);
        ctrlSenderTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                JSONObject item = dataSource.getNextTxClient2Server();
                Log.i(TAG, "Client2Server sender executed, assembling JSON and sending to Server...");

                try {
                    // no more? stop timer
                    if (!item.has("moreInQueue") || !item.getBoolean("moreInQueue")) {
                        stopQueuedItemsSender();
                    }

                    if (!item.getBoolean("fetched")) {
                        Log.w(TAG, "  ...warning, nothing fetched from BD!");
                        stopQueuedItemsSender();
                        return;
                    }

                    CtrlMessage msg = new CtrlMessage(item.getString("jsonPackage"));
                    msg.setTXsender(item.getInt("TXclient"));

                    sendSocket(msg.buildMessage());
                }
                catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, 15, 85);
        ctrlSenderTimerRunning = true;
    }

    /**
     * Stopping the Queued Items Sender timer.
     */
    private void stopQueuedItemsSender() {
        if (ctrlSenderTimerRunning) {
            ctrlSenderTimer.cancel();
        }

        ctrlSenderTimerRunning = false;
    }

    /**
     * Main Service thread which keeps track on socket connection status - restarts when required or shuts them down when it is time to die.
     * It also measures elapsed time from last ping we received from some Activity, in order to stop us if we don't receive next ping in ~3 seconds and have nothing pending to do. 
     */
    public class CtrlConnMaintainer implements Runnable {
        @Override
        public void run() {
            boolean running = true;

            Log.i(TAG, "CtrlConnMaintainer thread created!");

            while (running) {
                // ***** SOCKET ERROR HANDLING
                if (ctrlSocketThreadState == SocketThreadStates.ERROR) {
                    Log.i(TAG, "CtrlConnMaintainer detected ERROR, will RESTART in 1sec unless stopped or lost Internet.");

                    try {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // Cancel re-connecting if we lost Internet in the
                    // meantime
                    if (networkConnected)
                        ctrlThreadTask = ThreadTasks.RESTART;
                    else {
                        ctrlThreadTask = ThreadTasks.NONE;
                        // Also clear the Error flag so we don't enter here
                        // again. Once we restore the Internet connection
                        // broadcast receiver will re-open the socket.
                        ctrlSocketThreadState = SocketThreadStates.IDLE;
                        broadcastConnectionStatus();
                    }
                }

                // ***** AUTOMATIC SERVICE SHUTDOWN
                // Note: Need to have this code above "SOCKET THREAD TASKS"
                if (!ctrlSenderTimerRunning && (System.currentTimeMillis() - pingStamp) > pingIdleTime) {
                    ctrlThreadTask = ThreadTasks.CLOSE;

                    // don't loop here anymore, exit completelly
                    running = false;
                }

                // ***** SOCKET THREAD TASKS
                if (ctrlThreadTask == ThreadTasks.OPEN) {
                    if (ctrlSocketThread == null || !ctrlSocketThread.isAlive()) {
                        ctrlThreadTask = ThreadTasks.NONE;

                        ctrlSocketThread = new Thread(new CtrlSocketRunnable());
                        ctrlSocketThread.start();
                    }
                    // If OPEN is called on currently opened socket, we will
                    // restart it
                    else {
                        ctrlThreadTask = ThreadTasks.RESTART;
                    }
                }
                else if (ctrlThreadTask == ThreadTasks.CLOSE || ctrlThreadTask == ThreadTasks.RESTART) {
                    // ctrlSocketThread thread is now waiting in .readLine() so
                    // we will do socket.close() which will release the
                    // readLine() wait and finally end the socket thread
                    try {
                        // we need to change this so thread exits while loop
                        ctrlSocketThreadState = SocketThreadStates.IDLE;

                        if (ctrlSocket != null && !ctrlSocket.isClosed()) {
                            ctrlSocket.close();
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }

                    // Wait for socket Thread to complete so we can re-create it
                    // ...or not.
                    while (ctrlSocketThread != null && ctrlSocketThread.isAlive()) {
                        Log.i(TAG, "CtrlConnMaintainer waiting for thread to complete, current state = " + ctrlSocketThreadState);

                        try {
                            Thread.sleep(50);
                        }
                        catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    Log.i(TAG, "CtrlConnMaintainer closed the socket connection.");

                    // This was a Restart task?
                    if (ctrlThreadTask == ThreadTasks.RESTART) {
                        Log.i(TAG, "CtrlConnMaintainer will re-open the socket connection.");

                        ctrlThreadTask = ThreadTasks.OPEN;
                    }
                    else {
                        ctrlThreadTask = ThreadTasks.NONE;
                    }
                }
            }

            Log.i(TAG, "CtrlConnMaintainer stopping service completely with stopSelf()!");

            // bye bye...
            stopSelf();
        }
    }

    /**
     * Open the socket connection with CTRL Server.
     */
    private void openCtrlSocket() {
        ctrlThreadTask = ThreadTasks.OPEN;
    }

    /**
     * Restart the socket connection with CTRL Server.
     */
    private void restartCtrlSocket() {
        ctrlThreadTask = ThreadTasks.RESTART;
    }

    /**
     * Closing socket connection with CTRL Server. 
     */
    private void closeCtrlSocket() {
        ctrlThreadTask = ThreadTasks.CLOSE;
    }

    /**
     * ctrlSocket receiver function. When socket receives message from Server, this function is called.
     * 
     * @param mServerMessage
     */
    private void recvSocket(String mServerMessage) {
        if (mServerMessage != null) {
            CtrlMessage msg = new CtrlMessage(mServerMessage);
            if (msg.getIsExtracted()) {
                if (ctrlAuthenticated) {
                    if (msg.getIsAck()) {
                        Log.i(TAG, "Processing ACK to our TXsender: " + msg.getTXsender());

                        dataSource.ackTxClient2Server(msg.getTXsender());
                        Log.i(TAG, "  ...acked in DB.");

                        if (msg.getIsOutOfSync()) {
                            Log.i(TAG, "  ...ACKed but server told me OUT-OF-SYNC!");
                            if (outOfSyncCnt >= 5) {
                                Log.i(TAG, "  ...will flush queue and re-connect the socket!");

                                stopQueuedItemsSender();

                                dataSource.flushTxClient2Server();

                                // close the socket, and ctrlConnMaintainer will
                                // re-connect ASAP
                                restartCtrlSocket();
                            }
                            else {
                                outOfSyncCnt++;
                                Log.i(TAG, "  ...will re-send unacknowledged queue items. Increased flush-counter to: " + outOfSyncCnt + "/" + 5 + "!");

                                stopQueuedItemsSender();

                                Log.i(TAG, "  ...marking all unacknowledged items as unsent...");
                                dataSource.unsendAllUnackedTxClient2Server();

                                Log.i(TAG, "  ...starting queued items sender of all unacknowledged items...");
                                startQueuedItemsSender();
                            }
                        }
                    }
                    else {
                        Log.i(TAG, "Processing Server's data...");

                        // acknowledge immediatelly (but only if
                        // client is authorized and if this is not a
                        // notification)
                        CtrlMessage msgAck = new CtrlMessage();
                        msgAck.setIsAck(true);
                        msgAck.setTXsender(msg.getTXsender());

                        if (!msg.getIsNotification()) {
                            if (msg.getTXsender() <= TXserver) {
                                msgAck.setIsProcessed(false);
                                Log.w(TAG, "  ...Warning: re-transmitted command, not processed!");
                            }
                            else if (msg.getTXsender() > (TXserver + 1)) {
                                // SYNC PROBLEM! Server sent higher
                                // than we expected! This means we
                                // missed some previous Message!
                                // This part should be handled on
                                // Servers side.
                                // Server should flush all data (NOT
                                // A VERY SMART IDEA) and
                                // re-connect. Re-sync should
                                // naturally occur
                                // then in auth procedure as there
                                // would be nothing pending in queue
                                // to send to Client.

                                msgAck.setIsOutOfSync(true);
                                msgAck.setIsProcessed(false);
                                Log.e(TAG, "  ...Error: Client sent out-of-sync data! Expected: " + (TXserver + 1) + ", but I got: " + msg.getTXsender());
                            }
                            else {
                                msgAck.setIsProcessed(true);
                                // next package we will receive
                                // should be +1 of current value, so
                                // lets ++
                                TXserver++;

                                dataSource.savePubVar("TXserver", String.valueOf(TXserver));
                            }

                            sendSocket(msgAck.buildMessage());
                            Log.i(TAG, "  ...ACK sent back for TXsender: " + msg.getTXsender());
                        }
                        else {
                            // we need this for bellow code to
                            // execute
                            msgAck.setIsProcessed(true);
                            Log.i(TAG, "  ...didn't ACK because this was a notification.");
                        }

                        if (msgAck.getIsProcessed()) {
                            if (msg.getIsSystemMessage()) {
                                Log.i(TAG, "  ...system message received, parsing...");

                                // we must parse and extract stuff
                                // from msg.data object
                                JSONObject data = (JSONObject) msg.getData();

                                try {
                                    if (data.has("type") && data.getString("type").equals("base_connection_status") && data.has("connected") && data.has("baseid")) {
                                        // this is a Base status
                                        // notification, we get this for
                                        // each Base associated to our
                                        // Client account

                                        Log.i(TAG, "  ...got Base Status Notification. BaseID=" + data.getString("baseid") + ", connected=" + data.getBoolean("connected"));

                                        // update base's last activity only if
                                        // the status has changed since last
                                        // update
                                        boolean lastConnectedState = dataSource.getBaseStatus(data.getString("baseid"));
                                        if (lastConnectedState != data.getBoolean("connected")) {
                                            dataSource.updateBaseLastActivity(data.getString("baseid"));
                                        }

                                        dataSource.saveBaseConnectedStatus(data.getString("baseid"), data.getBoolean("connected"));

                                        broadcastBaseStatus(data.getString("baseid"), data.getBoolean("connected"));
                                    }
                                }
                                catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                            else {
                                Log.i(TAG, "  ...fresh data from Base " + msg.getBaseIds());
                                Log.i(TAG, "  ...Data: " + msg.getData());

                                dataSource.saveBaseData(msg.getBaseIds().get(0), msg.getData().toString());
                                dataSource.updateBaseLastActivity(msg.getBaseIds().get(0));

                                // lets notify any listening activity that some
                                // data arrived, so it can show to user
                                broadcastNewDataArrival(msg.getBaseIds().get(0));
                            }
                        }
                    }
                }
                else {
                    // if not authenticated, whatever we receive
                    // should be response to our auth request, so
                    // lets parse it
                    try {
                        // we must parse and extract stuff from
                        // msg.data object
                        if (msg.getData() instanceof JSONObject) {
                            JSONObject data = (JSONObject) msg.getData();

                            if (data.has("type") && data.get("type") != null && data.getString("type").equals("authentication_response") && data.has("result") && data.get("result") != null) {
                                // authorized?
                                if (data.getInt("result") == 0) {
                                    ctrlAuthenticated = true;

                                    if (msg.getIsSync()) {
                                        TXserver = 0;
                                    }
                                    else {
                                        String sTXserver = dataSource.getPubVar("TXserver");
                                        try {
                                            TXserver = Integer.parseInt(sTXserver);
                                        }
                                        catch (NumberFormatException e) {
                                            TXserver = 0;
                                        }
                                    }

                                    // update our GCM regID to Server
                                    manageRegID(false);

                                    // we have pending items in DB to send to
                                    // Server?
                                    if (dataSource.countUnackedTxClient2Server() > 0) {
                                        Log.i(TAG, "WE HAVE UNACKED ITEMS TO SEND NOW");
                                        startQueuedItemsSender();
                                    }
                                    else {
                                        Log.i(TAG, "NO UNACKED ITEMS TO SEND NOW");
                                    }

                                    ctrlError = CtrlErrors.NONE;
                                    broadcastCtrlStatus();
                                }
                                else if (data.getInt("result") == 1) {
                                    ctrlError = CtrlErrors.WRONG_AUTH_TOKEN;
                                    broadcastCtrlStatus();
                                    closeCtrlSocket();
                                }
                                else {
                                    ctrlError = CtrlErrors.TOO_MANY_AUTH_ATTEMPTS;
                                    broadcastCtrlStatus();
                                    closeCtrlSocket();
                                }
                            }
                        }
                        else {
                            Log.e(TAG, "Response to AUTH should have been JSON Object! Ignored.");
                        }
                    }
                    catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * Checks to see if app version has changed, and if there is no previous regID.
     * If any of these is correct, it will re-register to GCM, save new regID to local DB
     * and send it to CTRL Server.
     */
    private void manageRegID(boolean force) {
        String appVer = dataSource.getPubVar("appVer");
        int currAppVer = CommonStuff.getAppVersion(getApplicationContext());

        String regID = dataSource.getPubVar("regID");

        // clearing this will force re-reg
        if (force)
            regID = ""; // clear this so we always register to GCM

        // Time to register for new regID with Google Play Services
        if (!String.valueOf(currAppVer).equals(appVer) || regID.equals("")) {

            GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(this);
            String newRegID = null;
            try {
                newRegID = gcm.register(GCM_SENDER_ID);
            }
            catch (IOException e) {
                Log.e(TAG, "manageRegID(), GCM Registration IOException: " + e.getMessage());

                // If we failed we don't do anything. We will try later when we
                // re-connect to Server
                return;
            }

            // If we actually succeeded...
            if (newRegID != null && !newRegID.equals("")) {
                dataSource.savePubVar("appVer", String.valueOf(currAppVer));
                dataSource.savePubVar("regID", newRegID);

                Log.i(TAG, "GCM Registered, new regID: " + newRegID);

                // Send regID to Server
                CtrlMessage msg = new CtrlMessage();
                msg.setIsNotification(true);
                msg.setIsSystemMessage(true);

                JSONObject data = new JSONObject();
                try {
                    data.put("type", "ext_android_gcm_myregid");
                    data.put("regid", newRegID);
                }
                catch (JSONException e) {
                    e.printStackTrace();
                }
                msg.setData(data);

                // send to socket
                sendSocket(msg.buildMessage());
            }
        }
    }

    /**
     * Thread that waits for data from CTRL Server on Socket connection and calls recvSocket().
     */
    public class CtrlSocketRunnable implements Runnable {
        @Override
        public void run() {
            try {
                ctrlAuthenticated = false;
                ctrlSocketThreadState = SocketThreadStates.RUNNING;

                String ctrlServer = dataSource.getPubVar("ctrl_server");
                if (ctrlServer.equals(""))
                    ctrlServer = CommonStuff.CTRL_SERVER;

                int ctrlServerPort;
                if (dataSource.getPubVar("ctrl_server_port").equals("")) {
                    ctrlServerPort = CommonStuff.CTRL_VERSION;
                }
                else {
                    ctrlServerPort = Integer.parseInt(dataSource.getPubVar("ctrl_server_port"));
                }

                ctrlSocket = sslContext.getSocketFactory().createSocket(InetAddress.getByName(ctrlServer), ctrlServerPort);
                ctrlSocket.setKeepAlive(true);
                ctrlSocket.setSoTimeout(0);

                ctrlSocketBufferOut = new PrintWriter(new BufferedWriter(new OutputStreamWriter(ctrlSocket.getOutputStream(), "US-ASCII")), true);
                BufferedReader mBufferIn = new BufferedReader(new InputStreamReader(ctrlSocket.getInputStream(), "US-ASCII"));

                broadcastConnectionStatus();

                // authorize to CTRL server!
                ctrlAuthorize();

                Log.i(TAG, "STARTED LISTENING ON SOCKET.");
                while (ctrlSocketThreadState == SocketThreadStates.RUNNING) {
                    // will block here until line with \n is received!
                    String mServerMessage = mBufferIn.readLine();
                    recvSocket(mServerMessage);
                }
                Log.i(TAG, "ENDED LISTENING ON SOCKET.");

                if (ctrlSocket != null && !ctrlSocket.isClosed()) {
                    ctrlSocket.close();
                }
            }
            catch (Exception e) {
                ctrlSocketThreadState = SocketThreadStates.ERROR;
                broadcastConnectionStatus();

                Log.e(TAG, "Exception in CtrlSocketRunnable:", e);
                e.printStackTrace();

                // The finally block should execute even with this return here!
                return;
            }
            finally {
                if (ctrlSocketBufferOut != null) {
                    ctrlSocketBufferOut.flush();
                    ctrlSocketBufferOut.close();
                }
            }

            // This will not execute on exception above :)
            ctrlSocketThreadState = SocketThreadStates.IDLE;
            broadcastConnectionStatus();
        }
    }

    public void ctrlAuthorize() {
        stopQueuedItemsSender();

        dataSource.flushAckedTxClient2Server();
        dataSource.unsendAllTxClient2Server();

        CtrlMessage msg = new CtrlMessage();
        msg.setIsNotification(true);
        msg.setIsSystemMessage(true);

        if (dataSource.countUnackedTxClient2Server() == 0) {
            msg.setIsSync(true); // server should sync
        }

        JSONObject data = new JSONObject();
        try {
            data.put("auth_token", dataSource.getPubVar("auth_token"));
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
        msg.setData(data);

        // send to socket
        sendSocket(msg.buildMessage());
        Log.i(TAG, "AUTH REQUEST SENT.");
    }

    private void sendSocket(String data) {
        if (ctrlSocketBufferOut != null && !ctrlSocketBufferOut.checkError() && ctrlSocketThreadState == SocketThreadStates.RUNNING) {
            ctrlSocketBufferOut.println(data);
            ctrlSocketBufferOut.flush();
        }

        if (ctrlSocketBufferOut != null && ctrlSocketBufferOut.checkError()) {
            // conn manager thread will re-connect us
            ctrlSocketThreadState = SocketThreadStates.ERROR;
            broadcastConnectionStatus();
        }
    }

    private void broadcastNewDataArrival(String baseId) {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(BC_NEW_DATA);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);
        broadcastIntent.putExtra(BC_NEW_DATA_BASE_ID_KEY, baseId);
        try {
            sendBroadcast(broadcastIntent);
        }
        catch (Exception e) {
            Log.e(TAG, "broadcastNewDataArrival() Error: " + e.getMessage());
        }
    }

    private void broadcastBaseStatus(String baseId, boolean connected) {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(BC_BASE_STATUS);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);
        broadcastIntent.putExtra(BC_BASE_STATUS_BASE_ID_KEY, baseId);
        broadcastIntent.putExtra(BC_BASE_STATUS_CONNECTED_KEY, connected);
        try {
            sendBroadcast(broadcastIntent);
        }
        catch (Exception e) {
            Log.e(TAG, "broadcastBaseStatus() Error: " + e.getMessage());
        }
    }

    private void broadcastConnectionStatus() {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(BC_SERVICE_STATUS);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);

        String systemKeyValue = BC_CONNECTION_STATUS_IDLE;
        if (ctrlSocketThreadState == SocketThreadStates.RUNNING)
            systemKeyValue = BC_CONNECTION_STATUS_RUNNING;
        else if (ctrlSocketThreadState == SocketThreadStates.ERROR)
            systemKeyValue = BC_CONNECTION_STATUS_ERROR;
        broadcastIntent.putExtra(BC_CONNECTION_STATUS_KEY, systemKeyValue);

        try {
            sendBroadcast(broadcastIntent);
        }
        catch (Exception e) {
            Log.e(TAG, "broadcastServiceStatus() Error: " + e.getMessage());
        }
    }

    private void broadcastCtrlStatus() {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(BC_SERVICE_STATUS);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);

        String ctrlErrorKeyValue = BC_CTRL_STATUS_NONE;
        if (ctrlError == CtrlErrors.TOO_MANY_AUTH_ATTEMPTS)
            ctrlErrorKeyValue = BC_CTRL_STATUS_TOO_MANY;
        else if (ctrlError == CtrlErrors.WRONG_AUTH_TOKEN)
            ctrlErrorKeyValue = BC_CTRL_STATUS_WRONG_AUTH;
        broadcastIntent.putExtra(BC_CTRL_STATUS_KEY, ctrlErrorKeyValue);

        try {
            sendBroadcast(broadcastIntent);
        }
        catch (Exception e) {
            Log.e(TAG, "broadcastCtrlStatus() Error: " + e.getMessage());
        }
    }

    private void broadcastServiceTaskCompletion(Bundle bundle) {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(BC_SERVICE_TASKS_COMPLETION);
        broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);

        broadcastIntent.putExtras(bundle);

        try {
            sendBroadcast(broadcastIntent);
        }
        catch (Exception e) {
            Log.e(TAG, "broadcastServiceTaskCompletion() Error: " + e.getMessage());
        }
    }

    // Receiving tasks from Activities
    public class ServiceTasksReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.hasExtra(BC_SERVICE_TASKS_KEY) || intent.getStringExtra(BC_SERVICE_TASKS_KEY) == null || intent.getStringExtra(BC_SERVICE_TASKS_KEY).equals("")) {
                return;
            }

            if (intent.getStringExtra(BC_SERVICE_TASKS_KEY).equals(BC_SERVICE_TASKS_REQUEST_STATUS)) {
                broadcastConnectionStatus();
            }
            else if (intent.getStringExtra(BC_SERVICE_TASKS_KEY).equals(BC_SERVICE_TASKS_OPEN_CONNECTION)) {
                openCtrlSocket();
            }
            else if (intent.getStringExtra(BC_SERVICE_TASKS_KEY).equals(BC_SERVICE_TASKS_RESTART_CONNECTION)) {
                restartCtrlSocket();
            }
            else if (intent.getStringExtra(BC_SERVICE_TASKS_KEY).equals(BC_SERVICE_TASKS_CLOSE_CONNECTION)) {
                closeCtrlSocket();
            }
            else if (intent.getStringExtra(BC_SERVICE_TASKS_KEY).equals(BC_SERVICE_TASKS_GCM_REREG)) {
                Thread sender = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        manageRegID(true);
                    }
                });
                sender.start();
            }
            else if (intent.getStringExtra(BC_SERVICE_TASKS_KEY).equals(BC_SERVICE_TASKS_SEND_DATA)) {
                final CtrlMessage msg = new CtrlMessage();
                msg.setIsNotification(intent.hasExtra("isNotification") && intent.getBooleanExtra("isNotification", false));
                msg.setData(intent.getStringExtra("sendData"));

                if (intent.hasExtra("baseIds")) {
                    ArrayList<String> baseIds = intent.getStringArrayListExtra("baseIds");
                    msg.setBaseIds(baseIds);
                }

                // send notifications right now
                if (msg.getIsNotification()) {
                    Log.i(TAG, "Sending notification right away...");

                    Thread sender = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            sendSocket(msg.buildMessage());
                        }
                    });
                    sender.start();
                }
                // normal data first goes into database queue
                else {
                    Log.i(TAG, "Adding data to queue and starting sender...");

                    dataSource.addTxClient2Server(msg.buildMessage());
                    startQueuedItemsSender();
                }

                // send task completition broadcast back along with whatever we
                // received here
                broadcastServiceTaskCompletion(intent.getExtras());
            }
        }
    }

    @Override
    public void networkStateChanged(boolean connected) {
        if (!connected && networkConnected) {
            networkConnected = false;
            Log.i(TAG, "networkStateChanged, closing socket...");
            closeCtrlSocket();
        }
        else if (connected && !networkConnected) {
            networkConnected = true;
            Log.i(TAG, "networkStateChanged, (re)opening socket...");
            openCtrlSocket();
        }
    }

}
